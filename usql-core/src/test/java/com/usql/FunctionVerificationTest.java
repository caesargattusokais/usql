package com.usql;

import com.usql.ast.USqlAst.Statement;
import com.usql.dialect.Dialect;
import com.usql.parser.AstBuilder;

import java.sql.*;
import java.util.*;

/**
 * Test each registered U-SQL function against real databases (MySQL, PostgreSQL).
 * Verifies that the function catalog's dialect mappings produce correct results.
 *
 * Each function is tested via the full compiler pipeline:
 *   U-SQL → Parse → AST → IR → Target SQL → execute on real DB
 *
 * Run: mvn test-compile exec:java -Dexec.mainClass=com.usql.FunctionVerificationTest
 */
public class FunctionVerificationTest {

    record TestCase(String label, String usql, Object expected) {}

    static USqlCompiler compiler;
    static Connection mysql, pg;

    public static void main(String[] args) throws Exception {
        compiler = USqlCompiler.builder().build();

        // Connect
        Class.forName("com.mysql.cj.jdbc.Driver");
        mysql = DriverManager.getConnection(
            "jdbc:mysql://localhost:3306/login_db?useSSL=false&allowPublicKeyRetrieval=true",
            "login_user", "login123");

        Class.forName("org.postgresql.Driver");
        pg = DriverManager.getConnection(
            "jdbc:postgresql://localhost:5432/mydb", "postgres", "postgres123");

        // Build function test cases
        List<TestCase> tests = new ArrayList<>();

        // ── String functions ──
        tests.add(new TestCase("LENGTH",         "SELECT LENGTH('hello')",        5L));
        tests.add(new TestCase("UPPER",          "SELECT UPPER('hello')",        "HELLO"));
        tests.add(new TestCase("LOWER",          "SELECT LOWER('HELLO')",        "hello"));
        tests.add(new TestCase("TRIM",           "SELECT TRIM('  hi  ')",        "hi"));
        tests.add(new TestCase("SUBSTR",         "SELECT SUBSTR('hello', 2, 3)", "ell"));
        tests.add(new TestCase("REPLACE",        "SELECT REPLACE('hello', 'l', 'x')", "hexxo"));
        tests.add(new TestCase("CONCAT",         "SELECT CONCAT('a', 'b')",      "ab"));
        tests.add(new TestCase("LEFT",           "SELECT LEFT('hello', 2)",      "he"));
        tests.add(new TestCase("RIGHT",          "SELECT RIGHT('hello', 2)",     "lo"));

        // ── Numeric functions ──
        tests.add(new TestCase("ABS",            "SELECT ABS(-5)",              5L));
        tests.add(new TestCase("ROUND",          "SELECT ROUND(3.14159, 2)",    3.14));
        tests.add(new TestCase("CEIL",           "SELECT CEIL(3.1)",            4L));
        tests.add(new TestCase("FLOOR",          "SELECT FLOOR(3.9)",           3L));
        tests.add(new TestCase("MOD",            "SELECT MOD(10, 3)",           1L));
        tests.add(new TestCase("POWER",          "SELECT POWER(2, 3)",          8.0));
        tests.add(new TestCase("SQRT",           "SELECT SQRT(9)",              3.0));
        tests.add(new TestCase("SIGN",           "SELECT SIGN(-5)",             -1L));

        // ── NULL handling ──
        tests.add(new TestCase("COALESCE",       "SELECT COALESCE(NULL, 42)",   42L));
        tests.add(new TestCase("NULLIF-equal",   "SELECT NULLIF(5, 5)",         null));
        tests.add(new TestCase("NULLIF-diff",    "SELECT NULLIF(5, 3)",         5L));
        tests.add(new TestCase("NVL-null",       "SELECT NVL(NULL, 99)",        99L));
        tests.add(new TestCase("NVL-value",      "SELECT NVL(1, 99)",           1L));

        // ── GREATEST / LEAST ──
        tests.add(new TestCase("GREATEST",       "SELECT GREATEST(3, 7)",       7L));
        tests.add(new TestCase("LEAST",          "SELECT LEAST(3, 7)",          3L));

        // ── Date functions (tolerance-based) ──
        // CURRENT_DATE, CURRENT_TIME, CURRENT_TIMESTAMP — skip exact value check,
        // just verify they execute without error

        // ── Aggregate functions ──
        setupTables();
        tests.add(new TestCase("COUNT",          "SELECT COUNT(*) FROM func_test",        4L));
        tests.add(new TestCase("SUM",            "SELECT SUM(val) FROM func_test",        10L));
        tests.add(new TestCase("AVG",            "SELECT AVG(val) FROM func_test",        2.5));
        tests.add(new TestCase("MIN",            "SELECT MIN(val) FROM func_test",        1L));
        tests.add(new TestCase("MAX",            "SELECT MAX(val) FROM func_test",        4L));
        tests.add(new TestCase("INSTR",          "SELECT INSTR('hello', 'l')",            3L));

        // Run
        int pass = 0, fail = 0;
        System.out.println("=== Function Verification ===\n");

        for (TestCase tc : tests) {
            try {
                // MySQL
                boolean mysqlPass = testOne("MySQL", mysql, tc);
                // PostgreSQL
                boolean pgPass = testOne("PG", pg, tc);

                if (mysqlPass && pgPass) {
                    System.out.println("  PASS: " + tc.label());
                    pass++;
                } else {
                    fail++;
                }
            } catch (Exception e) {
                System.out.println("  ERROR: " + tc.label() + " — " + e.getMessage());
                fail++;
            }
        }

        cleanupTables();
        mysql.close();
        pg.close();

        System.out.println("\n=== Result: " + pass + " passed, " + fail + " failed ===");
    }

    static boolean testOne(String name, Connection conn, TestCase tc) throws Exception {
        Dialect dialect = name.equals("MySQL") ? Dialect.MYSQL : Dialect.POSTGRESQL;

        // Compile U-SQL through the full pipeline
        Statement ast = AstBuilder.buildSingle(tc.usql());
        CompilationResult result = compiler.compileFromAst(ast, dialect);
        if (!result.isSuccess()) {
            System.out.println("  FAIL " + name + " " + tc.label() + " — compile: " + result.report());
            return false;
        }
        String sql = result.getSql();
        // DEBUG: show generated SQL for first test
        if (tc.label().equals("LENGTH")) {
            System.out.println("  DEBUG " + name + ": [" + sql + "]");
        }

        try (java.sql.Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (!rs.next()) {
                System.out.println("  FAIL " + name + " " + tc.label() + " — no result row");
                return false;
            }
            Object val = rs.getObject(1);
            Object expected = tc.expected();

            if (expected == null) {
                boolean ok = val == null || rs.wasNull();
                if (!ok) System.out.println("  FAIL " + name + " " + tc.label() + " — expected NULL, got " + val);
                return ok;
            }

            if (expected instanceof Number en) {
                if (val instanceof Number vn) {
                    double diff = Math.abs(vn.doubleValue() - en.doubleValue());
                    if (diff < 0.01) return true;
                    System.out.println("  FAIL " + name + " " + tc.label() + " — expected " + expected + ", got " + val);
                    return false;
                }
            }

            String actual = val != null ? val.toString().trim() : "NULL";
            String expStr = expected.toString().trim();
            if (!actual.equals(expStr)) {
                System.out.println("  FAIL " + name + " " + tc.label() + " — expected " + expStr + ", got " + actual);
                return false;
            }
            return true;
        }
    }

    static void setupTables() throws SQLException {
        try {
            try (java.sql.Statement s = mysql.createStatement()) {
                s.execute("DROP TABLE IF EXISTS func_test");
                s.execute("CREATE TABLE func_test (id INT, val INT)");
                s.execute("INSERT INTO func_test VALUES (1,1), (2,2), (3,3), (4,4)");
            }
        } catch (SQLException ignored) {}
        try {
            try (java.sql.Statement s = pg.createStatement()) {
                s.execute("DROP TABLE IF EXISTS func_test");
                s.execute("CREATE TABLE func_test (id INT, val INT)");
                s.execute("INSERT INTO func_test VALUES (1,1), (2,2), (3,3), (4,4)");
            }
        } catch (SQLException ignored) {}
    }

    static void cleanupTables() throws SQLException {
        try { mysql.createStatement().execute("DROP TABLE IF EXISTS func_test"); } catch (SQLException ignored) {}
        try { pg.createStatement().execute("DROP TABLE IF EXISTS func_test"); } catch (SQLException ignored) {}
    }
}
