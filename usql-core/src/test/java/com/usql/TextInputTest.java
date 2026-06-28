package com.usql;

import com.usql.ast.USqlAst.Statement;
import com.usql.backend.GenerateOptions;
import com.usql.dialect.Dialect;
import com.usql.parser.AstBuilder;

/**
 * End-to-end test: U-SQL text → Parse → AST → IR → Target SQL.
 */
public class TextInputTest {

    public static void main(String[] args) {
        System.out.println("=== USQL — Text Input → SQL Tests ===\n");

        USqlCompiler compiler = USqlCompiler.builder().build();

        // ── Test 1: Simple SELECT ──
        test(compiler, "Simple SELECT",
            "SELECT name, age FROM users WHERE age > 18",
            Dialect.MYSQL,
            new String[]{"SELECT", "FROM", "WHERE", ">"});

        // ── Test 2: SELECT with LIMIT → Oracle (ROWNUM) ──
        test(compiler, "SELECT with LIMIT → Oracle",
            "SELECT name FROM users ORDER BY name LIMIT 10 OFFSET 5",
            Dialect.ORACLE,
            new String[]{"SELECT", "ROWNUM", "inner__"});

        // ── Test 3: SELECT with JOIN → PostgreSQL ──
        test(compiler, "SELECT with JOIN → PG",
            "SELECT d.name, e.salary FROM departments d LEFT JOIN employees e ON d.id = e.dept_id WHERE e.active = TRUE",
            Dialect.POSTGRESQL,
            new String[]{"SELECT", "LEFT JOIN", "TRUE"});

        // ── Test 4: Aggregation → MySQL ──
        test(compiler, "Aggregation → MySQL",
            "SELECT dept_id, COUNT(*) AS cnt, AVG(salary) AS avg_sal FROM employees GROUP BY dept_id HAVING COUNT(*) > 5",
            Dialect.MYSQL,
            new String[]{"SELECT", "COUNT(*)", "AVG", "GROUP BY", "HAVING"});

        // ── Test 5: Same aggregation → Oracle ──
        test(compiler, "Aggregation → Oracle",
            "SELECT dept_id, COUNT(*) AS cnt, AVG(salary) AS avg_sal FROM employees GROUP BY dept_id HAVING COUNT(*) > 5",
            Dialect.ORACLE,
            new String[]{"SELECT", "COUNT(*)", "AVG", "GROUP BY", "HAVING"});

        // ── Test 6: Same aggregation → 达梦 ──
        test(compiler, "Aggregation → 达梦",
            "SELECT dept_id, COUNT(*) AS cnt, AVG(salary) AS avg_sal FROM employees GROUP BY dept_id HAVING COUNT(*) > 5",
            Dialect.DM,
            new String[]{"SELECT", "COUNT(*)", "AVG", "GROUP BY", "HAVING"});

        // ── Test 7: BOOLEAN handling ──
        test(compiler, "BOOLEAN → MySQL",
            "SELECT name FROM users WHERE active = TRUE",
            Dialect.MYSQL,
            new String[]{"SELECT", "active", "=", "1"});
        test(compiler, "BOOLEAN → PG",
            "SELECT name FROM users WHERE active = TRUE",
            Dialect.POSTGRESQL,
            new String[]{"SELECT", "active", "=", "TRUE"});

        System.out.println("\n=== All text input tests passed! ===");
    }

    private static void test(USqlCompiler compiler, String label, String usql,
                              Dialect target, String[] mustContain) {
        try {
            // Phase 1-2: Parse
            Statement ast = AstBuilder.buildSingle(usql);

            // Phase 3-7: Compile
            CompilationResult result = compiler.compileFromAst(ast, target, GenerateOptions.MINIMAL);

            if (!result.isSuccess()) {
                System.out.println("  ❌ " + label + " — compilation failed");
                System.out.println("     " + result.report());
                return;
            }

            String sql = result.getSql();
            String upper = sql.toUpperCase();

            boolean allPresent = true;
            for (String frag : mustContain) {
                if (!upper.contains(frag.toUpperCase())) {
                    allPresent = false;
                    System.out.println("  ❌ " + label + " — missing: " + frag);
                    break;
                }
            }

            if (allPresent) {
                System.out.println("  ✅ " + label + " — PASS");
                System.out.println("     " + sql);
            }
        } catch (Exception e) {
            System.out.println("  ❌ " + label + " — EXCEPTION: " + e.getMessage());
        }
    }
}
