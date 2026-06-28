package com.usql;

import com.usql.ast.USqlAst.Statement;
import com.usql.dialect.Dialect;
import com.usql.parser.AstBuilder;

import java.sql.*;
import java.util.*;

/**
 * Test each registered U-SQL function against all available real databases.
 *
 * Run: mvn test-compile exec:java -Dexec.mainClass=com.usql.FunctionVerificationTest
 */
public class FunctionVerificationTest {

    record Target(String name, Connection conn, Dialect dialect) {}
    record TestCase(String label, String usql, Object expected) {}

    static USqlCompiler compiler;
    static List<Target> targets = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        compiler = USqlCompiler.builder().build();

        tryConnect("MySQL", Dialect.MYSQL,
            "com.mysql.cj.jdbc.Driver",
            "jdbc:mysql://localhost:3306/login_db?useSSL=false&allowPublicKeyRetrieval=true",
            "login_user", "login123");
        tryConnect("PostgreSQL", Dialect.POSTGRESQL,
            "org.postgresql.Driver",
            "jdbc:postgresql://localhost:5432/mydb", "postgres", "postgres123");
        tryConnect("Oracle", Dialect.ORACLE,
            "oracle.jdbc.OracleDriver",
            "jdbc:oracle:thin:@localhost:1521/orclpdb1", "system", "oracle123");

        if (targets.isEmpty()) {
            System.out.println("No databases available. Exiting.");
            return;
        }

        List<TestCase> tests = buildTests();
        int pass = 0, fail = 0;

        for (Target target : targets) {
            System.out.println("\n=== " + target.name() + " ===");
            setupTables(target);

            for (TestCase tc : tests) {
                try {
                    if (testOne(target, tc)) {
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

            cleanupTables(target);
        }

        for (Target t : targets) { try { t.conn().close(); } catch (Exception ignored) {} }

        System.out.println("\n=== Result: " + pass + " passed, " + fail + " failed ===");
    }

    static void tryConnect(String name, Dialect dialect, String driver, String url, String user, String pw) {
        try {
            Class.forName(driver);
            Connection conn = DriverManager.getConnection(url, user, pw);
            targets.add(new Target(name, conn, dialect));
            System.out.println("  Connected to " + name);
        } catch (Exception e) {
            System.out.println("  SKIP " + name + ": " + e.getMessage());
        }
    }

    static List<TestCase> buildTests() {
        List<TestCase> tests = new ArrayList<>();
        tests.add(new TestCase("LENGTH",         "SELECT LENGTH('hello')",        5L));
        tests.add(new TestCase("UPPER",          "SELECT UPPER('hello')",        "HELLO"));
        tests.add(new TestCase("LOWER",          "SELECT LOWER('HELLO')",        "hello"));
        tests.add(new TestCase("TRIM",           "SELECT TRIM('  hi  ')",        "hi"));
        tests.add(new TestCase("SUBSTR",         "SELECT SUBSTR('hello', 2, 3)", "ell"));
        tests.add(new TestCase("REPLACE",        "SELECT REPLACE('hello', 'l', 'x')", "hexxo"));
        tests.add(new TestCase("CONCAT",         "SELECT CONCAT('a', 'b')",      "ab"));
        tests.add(new TestCase("LEFT",           "SELECT LEFT('hello', 2)",      "he"));
        tests.add(new TestCase("RIGHT",          "SELECT RIGHT('hello', 2)",     "lo"));
        tests.add(new TestCase("ABS",            "SELECT ABS(-5)",              5L));
        tests.add(new TestCase("ROUND",          "SELECT ROUND(3.14159, 2)",    3.14));
        tests.add(new TestCase("CEIL",           "SELECT CEIL(3.1)",            4L));
        tests.add(new TestCase("FLOOR",          "SELECT FLOOR(3.9)",           3L));
        tests.add(new TestCase("MOD",            "SELECT MOD(10, 3)",           1L));
        tests.add(new TestCase("POWER",          "SELECT POWER(2, 3)",          8.0));
        tests.add(new TestCase("SQRT",           "SELECT SQRT(9)",              3.0));
        tests.add(new TestCase("SIGN",           "SELECT SIGN(-5)",             -1L));
        tests.add(new TestCase("COALESCE",       "SELECT COALESCE(NULL, 42)",   42L));
        tests.add(new TestCase("NULLIF-equal",   "SELECT NULLIF(5, 5)",         null));
        tests.add(new TestCase("NULLIF-diff",    "SELECT NULLIF(5, 3)",         5L));
        tests.add(new TestCase("NVL-null",       "SELECT NVL(NULL, 99)",        99L));
        tests.add(new TestCase("NVL-value",      "SELECT NVL(1, 99)",           1L));
        tests.add(new TestCase("GREATEST",       "SELECT GREATEST(3, 7)",       7L));
        tests.add(new TestCase("LEAST",          "SELECT LEAST(3, 7)",          3L));
        tests.add(new TestCase("COUNT",          "SELECT COUNT(*) FROM func_test",        4L));
        tests.add(new TestCase("SUM",            "SELECT SUM(val) FROM func_test",        10L));
        tests.add(new TestCase("AVG",            "SELECT AVG(val) FROM func_test",        2.5));
        tests.add(new TestCase("MIN",            "SELECT MIN(val) FROM func_test",        1L));
        tests.add(new TestCase("MAX",            "SELECT MAX(val) FROM func_test",        4L));
        tests.add(new TestCase("INSTR",          "SELECT INSTR('hello', 'l')",            3L));
        return tests;
    }

    static boolean testOne(Target target, TestCase tc) throws Exception {
        Statement ast = AstBuilder.buildSingle(tc.usql());
        CompilationResult result = compiler.compileFromAst(ast, target.dialect());
        if (!result.isSuccess()) {
            System.out.println("  FAIL " + target.name() + " " + tc.label() + " — compile: " + result.report());
            return false;
        }
        String sql = result.getSql();

        try (java.sql.Statement stmt = target.conn().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (!rs.next()) {
                System.out.println("  FAIL " + target.name() + " " + tc.label() + " — no result row");
                return false;
            }
            Object val = rs.getObject(1);
            Object expected = tc.expected();

            if (expected == null) {
                boolean ok = val == null || rs.wasNull();
                if (!ok) System.out.println("  FAIL " + target.name() + " " + tc.label() + " — expected NULL, got " + val);
                return ok;
            }
            if (expected instanceof Number en) {
                if (val instanceof Number vn) {
                    double diff = Math.abs(vn.doubleValue() - en.doubleValue());
                    if (diff < 0.01) return true;
                    System.out.println("  FAIL " + target.name() + " " + tc.label() + " — expected " + expected + ", got " + val);
                    return false;
                }
            }
            String actual = val != null ? val.toString().trim() : "NULL";
            if (!actual.equals(expected.toString().trim())) {
                System.out.println("  FAIL " + target.name() + " " + tc.label() + " — expected " + expected + ", got " + actual);
                return false;
            }
            return true;
        }
    }

    static void setupTables(Target target) throws Exception {
        // Use quoted identifiers for Oracle to match compiler-generated SQL
        String quoted = target.dialect() == Dialect.ORACLE ? "\"func_test\"" : "func_test";
        try (java.sql.Statement s = target.conn().createStatement()) {
            try { s.execute("DROP TABLE " + quoted); } catch (SQLException ignored) {}
        }
        // Create table through the compiler so case handling matches generated queries
        Statement ast = AstBuilder.buildSingle("CREATE TABLE func_test (id INT, val INT)");
        CompilationResult ddl = compiler.compileFromAst(ast, target.dialect());
        try (java.sql.Statement s = target.conn().createStatement()) {
            s.execute(ddl.getSql());
            s.execute("INSERT INTO " + quoted + " VALUES (1,1)");
            s.execute("INSERT INTO " + quoted + " VALUES (2,2)");
            s.execute("INSERT INTO " + quoted + " VALUES (3,3)");
            s.execute("INSERT INTO " + quoted + " VALUES (4,4)");
        }
    }

    static void cleanupTables(Target target) {
        String quoted = target.dialect() == Dialect.ORACLE ? "\"func_test\"" : "func_test";
        try { target.conn().createStatement().execute("DROP TABLE " + quoted); } catch (SQLException ignored) {}
    }
}
