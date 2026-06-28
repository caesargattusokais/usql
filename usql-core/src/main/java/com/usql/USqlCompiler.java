package com.usql;

import com.usql.backend.DialectBackend;
import com.usql.backend.GenerateOptions;
import com.usql.capability.CapabilityChecker;
import com.usql.capability.PolyfillEngine;
import com.usql.dialect.Dialect;

import java.util.List;

/**
 * Main entry point for the USQL compiler.
 *
 * <pre>
 *   USqlCompiler compiler = USqlCompiler.builder()
 *       .withSchema(mySchema)
 *       .withVerify(true)
 *       .build();
 *
 *   CompilationResult result = compiler.compile(
 *       "SELECT name, COUNT(*) AS cnt FROM users GROUP BY name LIMIT 10",
 *       Dialect.ORACLE
 *   );
 *
 *   System.out.println(result.getSql());  // Oracle SQL with ROWNUM
 * </pre>
 */
public class USqlCompiler {

    private final SchemaProvider schemaProvider;
    private final boolean verify;
    private final int optimizeLevel;
    private final Dialect defaultDialect;

    private USqlCompiler(Builder builder) {
        this.schemaProvider = builder.schemaProvider;
        this.verify = builder.verify;
        this.optimizeLevel = builder.optimizeLevel;
        this.defaultDialect = builder.defaultDialect;
    }

    // ══════════════════════════════════════════════════
    //  Public API
    // ══════════════════════════════════════════════════

    /**
     * Compile U-SQL to the target dialect.
     */
    public CompilationResult compile(String usql, Dialect target) {
        return compile(usql, target, GenerateOptions.DEFAULTS);
    }

    /**
     * Compile U-SQL with custom generation options.
     */
    public CompilationResult compile(String usql, Dialect target, GenerateOptions genOpts) {
        // TODO: Phase 1-2 — Lex + Parse
        // Phase 3 — AST build
        // Phase 4 — Semantic analysis
        // Phase 5 — IR optimization
        // Phase 6 — Capability check + polyfill
        // Phase 7 — Backend generation
        // Phase 8 — Verification (if enabled)

        // For now, return a placeholder
        return CompilationResult.failed(List.of(
            CompilationResult.Error.of(1, 1, "Compiler pipeline not yet implemented")
        ));
    }

    /**
     * Compile with the default dialect.
     */
    public CompilationResult compile(String usql) {
        return compile(usql, defaultDialect);
    }

    // ══════════════════════════════════════════════════
    //  Builder
    // ══════════════════════════════════════════════════

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private SchemaProvider schemaProvider = SchemaProvider.EMPTY;
        private boolean verify = false;
        private int optimizeLevel = 1;
        private Dialect defaultDialect = Dialect.MYSQL;

        public Builder withSchema(SchemaProvider provider) {
            this.schemaProvider = provider;
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
