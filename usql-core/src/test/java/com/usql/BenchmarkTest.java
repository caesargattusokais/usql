package com.usql;

import com.usql.analyzer.SemanticAnalyzer;
import com.usql.ast.USqlAst;
import com.usql.backend.*;
import com.usql.capability.CapabilityChecker;
import com.usql.capability.PolyfillEngine;
import com.usql.catalog.FunctionCatalog;
import com.usql.catalog.TypeCatalog;
import com.usql.dialect.Dialect;
import com.usql.ir.SemanticIR;
import com.usql.optimizer.IROptimizer;
import com.usql.parser.AstBuilder;
import com.usql.SchemaProvider;

/**
 * Compilation performance benchmark with phase breakdown.
 * Run: mvn exec:java -pl usql-core -Dexec.mainClass=com.usql.BenchmarkTest -Dexec.classpathScope=test
 */
public class BenchmarkTest {

    static final int WARMUP = 50;
    static final int ITERATIONS = 500;

    static String[] QUERIES = {
        "SELECT name, age FROM users WHERE age > 18",
        "SELECT d.name, COUNT(*) AS cnt, AVG(e.salary) AS avg_sal FROM departments d JOIN employees e ON d.id = e.dept_id GROUP BY d.name",
        "SELECT name, salary, ROW_NUMBER() OVER (PARTITION BY dept_id ORDER BY salary DESC) AS rn FROM employees",
        "WITH RECURSIVE nums AS (SELECT 1 AS n UNION ALL SELECT n+1 FROM nums WHERE n<10) SELECT n FROM nums",
        "INSERT INTO t (name, score) VALUES ('A',10),('B',20),('C',30),('D',40),('E',50)",
        "BEGIN",
    };

    static Dialect[] DIALECTS = {Dialect.MYSQL, Dialect.POSTGRESQL, Dialect.ORACLE, Dialect.CLICKHOUSE};

    public static void main(String[] args) {
        System.out.println("=== USQL Phase Breakdown (μs) ===\n");

        FunctionCatalog fc = new FunctionCatalog();
        TypeCatalog tc = new TypeCatalog();

        for (Dialect d : DIALECTS) {
            System.out.println("── " + d.displayName() + " ──");
            System.out.printf("  %-45s %8s %8s %8s %8s%n", "Query", "Parse", "Analyze", "Optimize", "Generate");

            for (String q : QUERIES) {
                String label = q.substring(0, Math.min(40, q.length()));

                // Warmup
                for (int i = 0; i < WARMUP; i++) compile(q, d, fc, tc);

                // Measure phases
                long parseNs = 0, analyzeNs = 0, optNs = 0, genNs = 0;

                for (int i = 0; i < ITERATIONS; i++) {
                    // Phase 1: Parse
                    long t0 = System.nanoTime();
                    USqlAst.Statement ast = AstBuilder.buildSingle(q);
                    long t1 = System.nanoTime();
                    parseNs += (t1 - t0);

                    // Phase 2: Semantic Analysis
                    SemanticAnalyzer analyzer = new SemanticAnalyzer(SchemaProvider.EMPTY, fc, tc);
                    var analysis = analyzer.analyze(ast);
                    long t2 = System.nanoTime();
                    analyzeNs += (t2 - t1);

                    // Phase 3: IR Optimization
                    SemanticIR ir = analysis.ir();
                    ir = IROptimizer.optimize(ir, 3);
                    long t3 = System.nanoTime();
                    optNs += (t3 - t2);

                    // Phase 4: Backend generation
                    DialectBackend backend = getBackend(d);
                    String sql = backend.generate(ir.rootStatement(), GenerateOptions.DEFAULTS);
                    long t4 = System.nanoTime();
                    genNs += (t4 - t3);
                }

                System.out.printf("  %-45s %7.0f %8.0f %8.0f %8.0f%n",
                    label,
                    parseNs / 1000.0 / ITERATIONS,
                    analyzeNs / 1000.0 / ITERATIONS,
                    optNs / 1000.0 / ITERATIONS,
                    genNs / 1000.0 / ITERATIONS);
            }
        }

        System.out.println("\n✔ Profiling complete");
    }

    static CompilationResult compile(String usql, Dialect d, FunctionCatalog fc, TypeCatalog tc) {
        USqlAst.Statement ast = AstBuilder.buildSingle(usql);
        SemanticAnalyzer analyzer = new SemanticAnalyzer(SchemaProvider.EMPTY, fc, tc);
        var analysis = analyzer.analyze(ast);
        SemanticIR ir = IROptimizer.optimize(analysis.ir(), 3);
        DialectBackend backend = getBackend(d);
        String sql = backend.generate(ir.rootStatement(), GenerateOptions.DEFAULTS);
        return CompilationResult.success(sql);
    }

    static DialectBackend getBackend(Dialect d) {
        return switch (d) {
            case MYSQL -> new MySqlBackend();
            case POSTGRESQL -> new PgBackend();
            case ORACLE -> new OracleBackend();
            case CLICKHOUSE -> new ClickHouseBackend();
            default -> new MySqlBackend();
        };
    }
}
