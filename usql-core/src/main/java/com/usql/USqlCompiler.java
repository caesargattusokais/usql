package com.usql;

import com.usql.analyzer.SemanticAnalyzer;
import com.usql.ast.USqlAst.Statement;
import com.usql.backend.*;
import com.usql.capability.CapabilityChecker;
import com.usql.capability.PolyfillEngine;
import com.usql.catalog.FunctionCatalog;
import com.usql.catalog.TypeCatalog;
import com.usql.dialect.Dialect;
import com.usql.ir.*;
import com.usql.verify.SemanticVerifier;

import java.util.*;

/**
 * Main entry point for the USQL compiler.
 *
 * <pre>
 *   USqlCompiler compiler = USqlCompiler.builder()
 *       .withSchema(mySchema)
 *       .withVerify(true)
 *       .build();
 *
 *   // From U-SQL text (requires antlr4-generated parser)
 *   CompilationResult result = compiler.compile(
 *       "SELECT name, COUNT(*) AS cnt FROM users GROUP BY name LIMIT 10",
 *       Dialect.ORACLE
 *   );
 *
 *   // From AST (always works)
 *   CompilationResult result = compiler.compileFromAst(astNode, Dialect.ORACLE);
 * </pre>
 */
public class USqlCompiler {

    private final SchemaProvider schemaProvider;
    private final FunctionCatalog functionCatalog;
    private final TypeCatalog typeCatalog;
    private final boolean verify;
    private final int optimizeLevel;
    private final Dialect defaultDialect;

    private final Map<Dialect, DialectBackend> backends;
    private final CapabilityChecker capabilityChecker;
    private final PolyfillEngine polyfillEngine;
    private final SemanticVerifier verifier;

    /** Compiled plan cache — keyed by SQL text, stores analyzed IR. */
    private final Map<String, SemanticIR> planCache;
    private final int maxCacheSize;
    private final boolean cacheEnabled;

    private USqlCompiler(Builder builder) {
        this.schemaProvider = builder.schemaProvider;
        this.functionCatalog = builder.functionCatalog != null
            ? builder.functionCatalog : new FunctionCatalog();
        this.typeCatalog = builder.typeCatalog != null
            ? builder.typeCatalog : new TypeCatalog();
        this.verify = builder.verify;
        this.optimizeLevel = builder.optimizeLevel;
        this.defaultDialect = builder.defaultDialect;

        // Initialize backends
        this.backends = new EnumMap<>(Dialect.class);
        this.backends.put(Dialect.MYSQL,      new MySqlBackend());
        this.backends.put(Dialect.POSTGRESQL, new PgBackend());
        this.backends.put(Dialect.ORACLE,     new OracleBackend());
        this.backends.put(Dialect.DM,         new DmBackend());
        this.backends.put(Dialect.SQLSERVER,  new com.usql.backend.SqlServerBackend());
        this.backends.put(Dialect.MARIADB,    new MariaDbBackend()); // MySQL + IF NOT EXISTS
        this.backends.put(Dialect.TIDB,       new com.usql.backend.TiDbBackend()); // MySQL protocol, native CREATE INDEX IF NOT EXISTS
        this.backends.put(Dialect.SQLITE,     new com.usql.backend.SqliteBackend());
        this.backends.put(Dialect.DUCKDB,     new com.usql.backend.DuckDbBackend());
        this.backends.put(Dialect.OCEANBASE,  new MySqlBackend());
        this.backends.put(Dialect.CLICKHOUSE,  new com.usql.backend.ClickHouseBackend());
        this.backends.put(Dialect.H2,         new MySqlBackend()); // H2 ≈ MySQL for now

        // Inject function catalog for name translation
        for (var backend : this.backends.values()) {
            backend.setFunctionCatalog(this.functionCatalog);
        }

        this.capabilityChecker = new CapabilityChecker();
        this.polyfillEngine = new PolyfillEngine();
        this.verifier = new SemanticVerifier();
        this.cacheEnabled = builder.cacheEnabled;
        this.maxCacheSize = builder.maxCacheSize;
        this.planCache = cacheEnabled ? new LinkedHashMap<>() {
            @Override protected boolean removeEldestEntry(Map.Entry<String, SemanticIR> e) {
                return size() > maxCacheSize;
            }
        } : null;
    }

    // ══════════════════════════════════════════════════
    //  Public API — from U-SQL text
    // ══════════════════════════════════════════════════

    /**
     * Compile U-SQL text to the target dialect.
     * Requires antlr4-generated parser classes (run 'mvn generate-sources' first).
     */
    public CompilationResult compile(String usql, Dialect target) {
        return compile(usql, target, GenerateOptions.DEFAULTS);
    }

    /**
     * Compile U-SQL text with custom generation options.
     *
     * Full pipeline: Text → Lexer → Parser → AST → Semantic Analysis → IR → Backend → SQL
     */
    public CompilationResult compile(String usql, Dialect target, GenerateOptions genOpts) {
        // Check compiled plan cache (stores the *optimized* analyzed IR)
        SemanticIR cachedIR = null;
        if (cacheEnabled) {
            synchronized (planCache) {
                cachedIR = planCache.get(usql);
            }
        }

        if (cachedIR != null) {
            // Cache hit: skip Parse + Semantic Analysis, reuse the optimized IR
            return compileFromIR(cachedIR, target, genOpts);
        }

        // Phase 1-2: Lex + Parse
        List<Statement> astNodes;
        try {
            astNodes = com.usql.parser.AstBuilder.build(usql);
        } catch (com.usql.parser.AstBuilder.ParseException e) {
            return CompilationResult.failed(List.of(
                CompilationResult.Error.of(0, 0, "Parse error: " + e.getMessage())
            ));
        }

        if (astNodes.isEmpty()) {
            return CompilationResult.failed(List.of(
                CompilationResult.Error.of(0, 0, "Empty input — no statements found")
            ));
        }

        // Phase 4: Semantic analysis (AST → IR)
        SemanticAnalyzer analyzer = new SemanticAnalyzer(schemaProvider, functionCatalog, typeCatalog);
        SemanticAnalyzer.AnalysisResult analysis = analyzer.analyze(astNodes.get(0));
        if (!analysis.errors().isEmpty()) {
            return CompilationResult.failed(analysis.errors());
        }

        // Phase 5: IR optimization — done once here so the optimized IR can be cached
        // and reused, keeping cache-hit and cache-miss results identical.
        SemanticIR optimizedIR = analysis.ir();
        if (optimizeLevel > 0) {
            optimizedIR = com.usql.optimizer.IROptimizer.optimize(optimizedIR, optimizeLevel);
        }

        // Store the OPTIMIZED IR in cache (only if analysis succeeded)
        if (cacheEnabled) {
            synchronized (planCache) {
                planCache.put(usql, optimizedIR);
            }
        }

        // Phase 6-8: capability → polyfill → generate (via the IR path)
        CompilationResult result = compileFromIR(optimizedIR, target, genOpts);

        // Forward semantic warnings (compileFromIR only surfaces capability findings)
        if (!analysis.warnings().isEmpty() && result.isSuccess()) {
            var allWarnings = new ArrayList<>(analysis.warnings());
            allWarnings.addAll(result.getWarnings());
            return CompilationResult.success(result.getSql(), result.getReferenceSql(), allWarnings);
        }

        return result;
    }

    /**
     * Compile with the default dialect.
     */
    public CompilationResult compile(String usql) {
        return compile(usql, defaultDialect);
    }

    // ══════════════════════════════════════════════════
    //  Public API — from AST (always works, no parser needed)
    // ══════════════════════════════════════════════════

    /**
     * Compile from a manually constructed AST node.
     */
    public CompilationResult compileFromAst(Statement ast, Dialect target) {
        return compileFromAst(ast, target, GenerateOptions.DEFAULTS);
    }

    /**
     * Compile from AST with custom generation options.
     */
    public CompilationResult compileFromAst(Statement ast, Dialect target, GenerateOptions genOpts) {
        List<CompilationResult.Warning> allWarnings = new ArrayList<>();

        // Phase 4: Semantic analysis (AST → IR)
        SemanticAnalyzer analyzer = new SemanticAnalyzer(schemaProvider, functionCatalog, typeCatalog);
        SemanticAnalyzer.AnalysisResult analysis = analyzer.analyze(ast);

        if (!analysis.errors().isEmpty()) {
            return CompilationResult.failed(analysis.errors());
        }
        allWarnings.addAll(analysis.warnings());

        SemanticIR ir = analysis.ir();

        // Phase 5: IR optimization
        if (optimizeLevel > 0) {
            ir = com.usql.optimizer.IROptimizer.optimize(ir, optimizeLevel);
        }

        // Phase 6: Capability check + polyfill
        CapabilityChecker.CapabilityReport capReport = capabilityChecker.check(
            ir.rootStatement(), target);

        if (capReport.hasFatal()) {
            List<CompilationResult.Error> fatalErrors = capReport.findings().stream()
                .filter(f -> f.severity() == CapabilityChecker.Severity.ERROR)
                .map(f -> CompilationResult.Error.of(0, 0, f.message()))
                .toList();
            return CompilationResult.failed(fatalErrors);
        }

        if (capReport.hasMissing()) {
            ir = new SemanticIR(
                polyfillEngine.apply(ir.rootStatement(), capReport, target)
            );
            for (var finding : capReport.findings()) {
                allWarnings.add(CompilationResult.Warning.of(0, 0,
                    "[" + target.displayName() + "] " + finding.message()));
            }
        }

        // Phase 7: Backend generation
        DialectBackend backend = backends.get(target);
        if (backend == null) {
            return CompilationResult.failed(List.of(
                CompilationResult.Error.of(0, 0, "No backend registered for dialect: " + target)
            ));
        }
        String sql = backend.generate(ir.rootStatement(), genOpts);

        // Phase 8: Verification (optional — generates H2 reference SQL)
        String refSql = null;
        if (verify) {
            DialectBackend refBackend = backends.get(Dialect.H2);
            refSql = refBackend.generate(ir.rootStatement(), GenerateOptions.MINIMAL);
        }

        return refSql != null
            ? CompilationResult.success(sql, refSql, allWarnings)
            : CompilationResult.success(sql, allWarnings);
    }

    /**
     * Compile from IR directly (skip parse + analyze — used by cache hit path).
     */
    public CompilationResult compileFromIR(SemanticIR ir, Dialect target, GenerateOptions genOpts) {
        return compileFromIR(ir.rootStatement(), target, genOpts);
    }

    /**
     * Compile from IR statement directly (skip AST step — for testing/debugging).
     */
    public CompilationResult compileFromIR(IRStatement ir, Dialect target) {
        return compileFromIR(ir, target, GenerateOptions.DEFAULTS);
    }

    /**
     * Compile from IR statement with custom options.
     *
     * Note: IR optimization is NOT applied here — the caller is responsible
     * for optimizing before calling this method. This avoids double-optimization
     * when called from {@link #compile} or {@link #compileFromAst}.
     */
    public CompilationResult compileFromIR(IRStatement ir, Dialect target, GenerateOptions genOpts) {
        // Phase 6: Capability check + polyfill
        CapabilityChecker.CapabilityReport capReport = capabilityChecker.check(ir, target);
        if (capReport.hasFatal()) {
            List<CompilationResult.Error> fatalErrors = capReport.findings().stream()
                .filter(f -> f.severity() == CapabilityChecker.Severity.ERROR)
                .map(f -> CompilationResult.Error.of(0, 0, f.message()))
                .toList();
            return CompilationResult.failed(fatalErrors);
        }

        if (capReport.hasMissing()) {
            ir = polyfillEngine.apply(ir, capReport, target);
        }

        // Phase 7: Generate
        DialectBackend backend = backends.get(target);
        if (backend == null) {
            return CompilationResult.failed(List.of(
                CompilationResult.Error.of(0, 0, "No backend for: " + target)
            ));
        }
        String sql = backend.generate(ir, genOpts);

        // Phase 8: Verification (optional — generates H2 reference SQL)
        String refSql = null;
        if (verify) {
            DialectBackend refBackend = backends.get(Dialect.H2);
            refSql = refBackend.generate(ir, GenerateOptions.MINIMAL);
        }

        List<CompilationResult.Warning> warnings = new ArrayList<>();
        for (var finding : capReport.findings()) {
            warnings.add(CompilationResult.Warning.of(0, 0,
                "[" + target.displayName() + "] " + finding.message()));
        }

        return refSql != null
            ? CompilationResult.success(sql, refSql, warnings)
            : CompilationResult.success(sql, warnings);
    }

    // ══════════════════════════════════════════════════
    //  Convenience: transpile SQL between dialects
    // ══════════════════════════════════════════════════

    /**
     * Quick transpile: translate SQL text from one dialect to another.
     * This is a convenience shortcut — the full pipeline is parse→IR→generate.
     */
    public static String transpile(String sql, Dialect from, Dialect to) {
        // This will parse the SQL in the 'from' dialect, build IR, generate for 'to'.
        // Requires dialect-specific parsers (future work).
        throw new UnsupportedOperationException(
            "Direct SQL-to-SQL transpilation not yet implemented. " +
            "Write U-SQL and use compile() instead."
        );
    }

    /** Clear the compiled plan cache. */
    public void clearCache() {
        if (planCache != null) synchronized (planCache) { planCache.clear(); }
    }

    /** Number of cached plans. */
    public int cacheSize() {
        if (planCache != null) synchronized (planCache) { return planCache.size(); }
        return 0;
    }

    // ══════════════════════════════════════════════════
    //  Schema registration shortcut
    // ══════════════════════════════════════════════════

    /**
     * Build a SchemaProvider from a list of table definitions.
     */
    public static SchemaProvider schemaOf(SchemaProvider.TableDef... tables) {
        return new SchemaProvider() {
            private final Map<String, SchemaProvider.TableDef> map =
                java.util.Arrays.stream(tables).collect(
                    java.util.stream.Collectors.toMap(
                        t -> t.name(), t -> t,
                        (a, b) -> a, java.util.LinkedHashMap::new)
                );

            @Override
            public Optional<SchemaProvider.TableDef> getTable(String name) {
                return Optional.ofNullable(map.get(name));
            }

            @Override
            public List<String> tableNames() {
                return List.copyOf(map.keySet());
            }
        };
    }

    // ══════════════════════════════════════════════════
    //  Builder
    // ══════════════════════════════════════════════════

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private SchemaProvider schemaProvider = SchemaProvider.EMPTY;
        private FunctionCatalog functionCatalog;
        private TypeCatalog typeCatalog;
        private boolean verify = false;
        private int optimizeLevel = 1;
        private Dialect defaultDialect = Dialect.MYSQL;
        private boolean cacheEnabled = true;
        private int maxCacheSize = 256;

        public Builder withSchema(SchemaProvider provider) {
            this.schemaProvider = provider;
            return this;
        }

        public Builder withFunctionCatalog(FunctionCatalog catalog) {
            this.functionCatalog = catalog;
            return this;
        }

        public Builder withTypeCatalog(TypeCatalog catalog) {
            this.typeCatalog = catalog;
            return this;
        }

        public Builder withVerify(boolean verify) {
            this.verify = verify;
            return this;
        }

        public Builder withOptimizeLevel(int level) {
            this.optimizeLevel = level;
            return this;
        }

        public Builder withDefaultDialect(Dialect dialect) {
            this.defaultDialect = dialect;
            return this;
        }

        public Builder withCache(boolean enabled) {
            this.cacheEnabled = enabled;
            return this;
        }

        public Builder withCacheSize(int size) {
            this.maxCacheSize = size;
            return this;
        }

        public USqlCompiler build() {
            return new USqlCompiler(this);
        }
    }
}
