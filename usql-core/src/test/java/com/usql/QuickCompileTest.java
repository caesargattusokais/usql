package com.usql;

import com.usql.dialect.Dialect;
import java.util.List;

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
            "SELECT NVL(NULL, 'default'), COALESCE(NULL, NULL, 42)",
            // KEEP clause — Oracle aggregate extension
            "SELECT MAX(MODIFIED_TIME) AS maxTime, MAX(ID) KEEP (DENSE_RANK LAST ORDER BY MODIFIED_TIME) AS maxId FROM PR_PRCERT_AQSC",
            "SELECT MIN(VALUE) KEEP (DENSE_RANK FIRST ORDER BY CREATE_DATE) AS firstVal FROM ORDERS",
            "SELECT SUM(AMOUNT) KEEP (DENSE_RANK LAST ORDER BY TRANS_DATE) AS total FROM TRANSACTIONS"
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

        // Window functions
        System.out.println("\n=== Window Functions ===");
        String[] windowTests = {
            "SELECT name, ROW_NUMBER() OVER (ORDER BY salary DESC) AS rn FROM employees",
            "SELECT name, RANK() OVER (ORDER BY salary DESC) AS rk FROM employees",
            "SELECT name, DENSE_RANK() OVER (PARTITION BY dept_id ORDER BY salary DESC) AS dr FROM employees",
            "SELECT name, LAG(salary) OVER (ORDER BY hire_date) AS prev_salary FROM employees",
            "SELECT name, LEAD(salary) OVER (ORDER BY hire_date) AS next_salary FROM employees",
            "SELECT name, FIRST_VALUE(salary) OVER (PARTITION BY dept_id ORDER BY hire_date) AS first_sal FROM employees",
            "SELECT name, LAST_VALUE(salary) OVER (PARTITION BY dept_id ORDER BY hire_date ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) AS last_sal FROM employees",
            "SELECT name, NTILE(4) OVER (ORDER BY salary) AS quartile FROM employees"
        };
        for (String usql : windowTests) {
            System.out.println("U-SQL: " + usql.substring(0, Math.min(usql.length(), 80)) + "...");
            for (Dialect target : List.of(Dialect.SQLSERVER, Dialect.MYSQL, Dialect.ORACLE)) {
                var r = compiler.compile(usql, target);
                System.out.println("  " + target.name() + ": " + (r.isSuccess() ? r.getSql().substring(0, Math.min(r.getSql().length(), 100)) + "..." : "FAIL: " + r.report()));
            }
        }

        // Test error handling
        System.out.println("\nError test:");
        var bad = compiler.compile("SELECTT bad syntax!!!", Dialect.MYSQL);
        System.out.println("  " + (bad.isSuccess() ? "UNEXPECTED PASS" : "Expected fail: OK"));
    }
}
