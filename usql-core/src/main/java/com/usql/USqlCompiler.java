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
        this.backends.put(Dialect.H2,         new MySqlBackend()); // H2 ≈ MySQL for now

        this.capabilityChecker = new CapabilityChecker();
        this.polyfillEngine = new PolyfillEngine();
        this.verifier = new SemanticVerifier();
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
     */
    public CompilationResult compile(String usql, Dialect target, GenerateOptions genOpts) {
        List<CompilationResult.Error> allErrors = new ArrayList<>();

        // Phase 1-2: Lex + Parse (requires antlr4)
        List<Statement> astNodes;
        try {
            astNodes = com.usql.parser.AstBuilder.build(usql);
        } catch (UnsupportedOperationException e) {
            return CompilationResult.failed(List.of(
                CompilationResult.Error.of(0, 0,
                    "Parser not yet available — run 'mvn generate-sources' to generate antlr4 classes. " +
                    "Use compileFromAst() to compile manually constructed AST nodes.")
            ));
        }

        if (astNodes.isEmpty()) {
            return CompilationResult.failed(List.of(
                CompilationResult.Error.of(0, 0, "Empty input — no statements found")
            ));
        }

        return compileFromAst(astNodes.get(0), target, genOpts);
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

        // Phase 8: Verification (optional)
        if (verify) {
            DialectBackend refBackend = backends.get(Dialect.H2);
            String refSql = refBackend.generate(ir.rootStatement(), GenerateOptions.MINIMAL);

            // Verification needs live DB connections — skip for now, log intent
            allWarnings.add(CompilationResult.Warning.of(0, 0,
                "Verification enabled but requires live DB connections (Phase 8 — not yet automated)"));
        }

        return CompilationResult.success(sql, allWarnings);
    }

    /**
     * Compile from IR directly (skip AST step — for testing/debugging).
     */
    public CompilationResult compileFromIR(IRStatement ir, Dialect target) {
        return compileFromIR(ir, target, GenerateOptions.DEFAULTS);
    }

    /**
     * Compile from IR directly with custom options.
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

        List<CompilationResult.Warning> warnings = new ArrayList<>();
        for (var finding : capReport.findings()) {
            warnings.add(CompilationResult.Warning.of(0, 0,
                "[" + target.displayName() + "] " + finding.message()));
        }

        return CompilationResult.success(sql, warnings);
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

    // ══════════════════════════════════════════════════
    //  Schema registration shortcut
    // ══════════════════════════════════════════════════

    /**
     * Build a SchemaProvider from a list of table definitions.
     */
    public static SchemaProvider schemaOf(SchemaProvider.TableDef... tables) {
        return new SchemaProvider() {
            private final Map<String, SchemaProvider.TableDef> map = Map.of(
                java.util.Arrays.stream(tables).collect(
                    java.util.stream.Collectors.toMap(t -> t.name(), t -> t)
                )
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

        public USqlCompiler build() {
            return new USqlCompiler(this);
        }
    }
}
