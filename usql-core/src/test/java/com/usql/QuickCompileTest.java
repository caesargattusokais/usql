package com.usql;

import com.usql.dialect.Dialect;

/**
 * Quick smoke test: compile(String) end-to-end.
 * Run: mvn exec:java -Dexec.mainClass=com.usql.QuickCompileTest
 */
public class QuickCompileTest {
    public static void main(String[] args) {
        var compiler = USqlCompiler.builder().build();

        String[] tests = {
            "SELECT name, age FROM users WHERE age > 18",
            "SELECT COUNT(*) AS cnt FROM orders GROUP BY status HAVING COUNT(*) > 5",
            "SELECT d.name, e.salary FROM dept d JOIN emp e ON d.id = e.dept_id LIMIT 10 OFFSET 0",
            "SELECT LENGTH('hello'), UPPER('world'), ROUND(3.14159, 2)",
            "SELECT NVL(NULL, 'default'), COALESCE(NULL, NULL, 42)"
        };

        for (String usql : tests) {
            System.out.println("U-SQL: " + usql);
            for (Dialect target : Dialect.values()) {
                if (target == Dialect.H2) continue;
                var r = compiler.compile(usql, target);
                if (r.isSuccess()) {
                    System.out.println("  " + target.name() + ": " + r.getSql());
                } else {
                    System.out.println("  " + target.name() + ": FAIL — " + r.report());
                }
            }
            System.out.println();
        }

        // Test error handling
        System.out.println("Error test:");
        var bad = compiler.compile("SELECTT bad syntax!!!", Dialect.MYSQL);
        System.out.println("  " + (bad.isSuccess() ? "UNEXPECTED PASS" : "Expected fail: OK"));
    }
}
