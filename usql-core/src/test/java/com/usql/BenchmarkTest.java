package com.usql;

import com.usql.dialect.Dialect;

/**
 * Compilation performance benchmark.
 * Run: mvn exec:java -pl usql-core -Dexec.mainClass=com.usql.BenchmarkTest -Dexec.classpathScope=test
 */
public class BenchmarkTest {

    static final int WARMUP = 50;
    static final int ITERATIONS = 500;

    static String[] QUERIES = {
        // Simple SELECT
        "SELECT name, age FROM users WHERE age > 18",
        // JOIN + aggregate
        "SELECT d.name, COUNT(*) AS cnt, AVG(e.salary) AS avg_sal FROM departments d JOIN employees e ON d.id = e.dept_id GROUP BY d.name HAVING COUNT(*) > 2",
        // Window function
        "SELECT name, salary, ROW_NUMBER() OVER (PARTITION BY dept_id ORDER BY salary DESC) AS rn FROM employees",
        // CTE
        "WITH hi AS (SELECT name, salary FROM employees WHERE salary > 70000) SELECT name FROM hi",
        // Recursive CTE
        "WITH RECURSIVE nums AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM nums WHERE n < 10) SELECT n FROM nums",
        // Complex with subquery + KEEP
        "SELECT dept_id, MAX(salary) KEEP (DENSE_RANK LAST ORDER BY hire_date) FROM (SELECT * FROM employees WHERE active = TRUE) e GROUP BY dept_id",
        // MERGE
        "MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN UPDATE SET t.name = s.name WHEN NOT MATCHED THEN INSERT (id, name) VALUES (s.id, s.name)",
        // CREATE TABLE DDL
        "CREATE TABLE t (id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(100) NOT NULL, score DECIMAL(10,2) DEFAULT 0, active BOOLEAN DEFAULT TRUE, created DATE, updated DATETIME, bio TEXT)",
        // INSERT multi-row
        "INSERT INTO t (name, score) VALUES ('A', 10), ('B', 20), ('C', 30), ('D', 40), ('E', 50)",
        // TCL
        "BEGIN",
    };

    static Dialect[] DIALECTS = {Dialect.MYSQL, Dialect.POSTGRESQL, Dialect.ORACLE, Dialect.SQLSERVER, Dialect.SQLITE};

    public static void main(String[] args) {
        USqlCompiler compiler = USqlCompiler.builder().build();

        System.out.println("=== USQL Compilation Benchmark ===\n");
        System.out.printf("%-50s %10s %10s %10s %10s %10s%n", "Query", "MySQL", "PG", "Oracle", "SQLSrv", "SQLite");
        System.out.println("─".repeat(110));

        for (String q : QUERIES) {
            String label = q.substring(0, Math.min(45, q.length())).replace("\n", " ");
            System.out.printf("%-50s", label);

            for (Dialect d : DIALECTS) {
                // Warmup
                for (int i = 0; i < WARMUP; i++) compiler.compile(q, d);

                // Measure
                long start = System.nanoTime();
                for (int i = 0; i < ITERATIONS; i++) compiler.compile(q, d);
                long elapsed = System.nanoTime() - start;

                double avgUs = elapsed / 1000.0 / ITERATIONS;
                System.out.printf(" %7.0f μs", avgUs);
            }
            System.out.println();
        }

        // Throughput summary
        System.out.println("\n── Throughput (compiles/sec) ──");
        for (Dialect d : DIALECTS) {
            long start = System.nanoTime();
            int count = 0;
            while (System.nanoTime() - start < 2_000_000_000L) { // 2 seconds
                for (String q : QUERIES) {
                    compiler.compile(q, d);
                    count++;
                }
            }
            long elapsed = System.nanoTime() - start;
            double rate = count * 1_000_000_000.0 / elapsed;
            System.out.printf("  %-10s %8.0f compiles/sec%n", d.displayName(), rate);
        }

        System.out.println("\n✔ Benchmark complete");
    }
}
