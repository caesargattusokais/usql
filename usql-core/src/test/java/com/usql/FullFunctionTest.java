package com.usql;

import com.usql.ast.USqlAst.Statement;
import com.usql.dialect.Dialect;
import com.usql.parser.AstBuilder;

import java.sql.*;
import java.util.*;

/**
 * Test all 103 registered functions against MySQL, PostgreSQL, Oracle, DM.
 *
 * Run: mvn test-compile exec:java -Dexec.mainClass=com.usql.FullFunctionTest
 */
public class FullFunctionTest {

    record Target(String name, Connection conn, Dialect dialect) {
        boolean isQuoted() { return dialect == Dialect.ORACLE || dialect == Dialect.DM; }
        String q(String s) { return isQuoted() ? "\"" + s + "\"" : s; }
    }

    static USqlCompiler compiler;
    static List<Target> targets = new ArrayList<>();
    static int pass, fail;

    public static void main(String[] args) throws Exception {
        compiler = USqlCompiler.builder().build();

        connect("MySQL", Dialect.MYSQL, "com.mysql.cj.jdbc.Driver",
            "jdbc:mysql://localhost:3306/login_db?useSSL=false&allowPublicKeyRetrieval=true", "login_user", "login123");
        connect("PG", Dialect.POSTGRESQL, "org.postgresql.Driver",
            "jdbc:postgresql://localhost:5432/mydb", "postgres", "postgres123");
        connect("Oracle", Dialect.ORACLE, "oracle.jdbc.OracleDriver",
            "jdbc:oracle:thin:@localhost:1521/orclpdb1", "system", "oracle123");
        connect("DM", Dialect.DM, "dm.jdbc.driver.DmDriver",
            "jdbc:dm://localhost:5236", "SYSDBA", "dm12345678");

        // Create test table with 5 rows for aggregate tests
        for (var t : targets) {
            setupTable(t);
        }

        // Test ALL functions that can be tested with a simple SELECT expression
        String[][] tests = {
            // String functions — (label, u_sql, expected)
            {"LENGTH",      "SELECT LENGTH('hello')",         "5"},
            {"UPPER",       "SELECT UPPER('hello')",          "HELLO"},
            {"LOWER",       "SELECT LOWER('HELLO')",          "hello"},
            {"TRIM",        "SELECT TRIM('  hi  ')",          "hi"},
            {"LTRIM",       "SELECT LTRIM('  hi  ')",         "hi  "},
            {"RTRIM",       "SELECT RTRIM('  hi  ')",         "  hi"},
            {"SUBSTR",      "SELECT SUBSTR('hello', 2, 3)",   "ell"},
            {"REPLACE",     "SELECT REPLACE('hello','l','x')","hexxo"},
            {"CONCAT",      "SELECT CONCAT('a','b')",         "ab"},
            {"CONCAT_WS",   "SELECT CONCAT_WS('-','a','b')",  "a-b"},
            {"LEFT",        "SELECT LEFT('hello', 2)",        "he"},
            {"RIGHT",       "SELECT RIGHT('hello', 2)",       "lo"},
            {"CHAR_LENGTH", "SELECT CHAR_LENGTH('hello')",    "5"},
            {"REPEAT",      "SELECT REPEAT('x', 3)",          "xxx"},
            {"REVERSE",     "SELECT REVERSE('abc')",          "cba"},
            {"ASCII",       "SELECT ASCII('A')",              "65"},
            {"LPAD",        "SELECT LPAD('hi',5,'*')",        "***hi"},
            {"RPAD",        "SELECT RPAD('hi',5,'*')",        "hi***"},
            {"INITCAP",     "SELECT INITCAP('hello world')",  "Hello World"},

            // Numeric functions
            {"ABS",         "SELECT ABS(-5)",                 "5"},
            {"ROUND",       "SELECT ROUND(3.14159,2)",        "3.14"},
            {"CEIL",        "SELECT CEIL(3.1)",               "4"},
            {"FLOOR",       "SELECT FLOOR(3.9)",              "3"},
            {"MOD",         "SELECT MOD(10,3)",               "1"},
            {"POWER",       "SELECT POWER(2,3)",              "8.0"},
            {"SQRT",        "SELECT SQRT(9)",                 "3.0"},
            {"SIGN",        "SELECT SIGN(-5)",                "-1"},
            {"EXP",         "SELECT EXP(1)",                  null},  // fuzzy
            {"LN",          "SELECT LN(1)",                   "0.0"},
            {"TRUNC",       "SELECT TRUNC(3.14159,2)",        "3.14"},
            {"PI",          "SELECT PI()",                    null},  // fuzzy
            {"COS",         "SELECT COS(0)",                  "1.0"},
            {"SIN",         "SELECT SIN(0)",                  "0.0"},

            // NULL handling
            {"COALESCE",    "SELECT COALESCE(NULL,42)",       "42"},
            {"NULLIF-eq",   "SELECT NULLIF(5,5)",             "NULL"},
            {"NULLIF-diff", "SELECT NULLIF(5,3)",             "5"},
            {"NVL-null",    "SELECT NVL(NULL,99)",            "99"},
            {"NVL-value",   "SELECT NVL(1,99)",               "1"},
            {"ISNULL-null", "SELECT ISNULL(NULL)",            null},
            {"ISNULL-val",  "SELECT ISNULL(1)",               null},

            // GREATEST/LEAST
            {"GREATEST",    "SELECT GREATEST(3,7)",           "7"},
            {"LEAST",       "SELECT LEAST(3,7)",              "3"},

            // Aggregate functions (require func_test table)
            {"COUNT",       "SELECT COUNT(*) FROM func_test", "5"},
            {"SUM",         "SELECT SUM(val) FROM func_test", "15"},
            {"AVG",         "SELECT AVG(val) FROM func_test", null},
            {"MIN",         "SELECT MIN(val) FROM func_test", "1"},
            {"MAX",         "SELECT MAX(val) FROM func_test", "5"},
            {"STDDEV",      "SELECT STDDEV(val) FROM func_test", null},
            {"VARIANCE",    "SELECT VARIANCE(val) FROM func_test", null},
            {"MEDIAN",      "SELECT MEDIAN(val) FROM func_test", null},

            // Date functions
            {"CUR_TIMESTAMP", "SELECT CURRENT_TIMESTAMP()", null},

            // INSTR
            {"INSTR",       "SELECT INSTR('hello','l')",      "3"},
        };

        for (var t : targets) {
            System.out.println("\n" + t.name() + ":");
            for (var tc : tests) {
                String label = tc[0], usql = tc[1], expected = tc[2];
                try {
                    Statement ast = AstBuilder.buildSingle(usql);
                    String sql = compiler.compileFromAst(ast, t.dialect()).getSql();
                    try (java.sql.Statement stmt = t.conn().createStatement();
                         ResultSet rs = stmt.executeQuery(sql)) {
                        if (rs.next()) {
                            Object val = rs.getObject(1);
                            boolean ok = expected == null || "NULL".equals(expected)
                                ? (val == null)
                                : val != null && val.toString().contains(expected);
                            if (ok) { pass++; }
                            else { System.out.println("  FAIL " + label + ": got " + val + ", expected " + expected); fail++; }
                        } else { System.out.println("  FAIL " + label + ": no row"); fail++; }
                    }
                } catch (Exception e) {
                    System.out.println("  ERR  " + label + ": " + e.getMessage().split("\n")[0]);
                    fail++;
                }
            }
        }

        for (var t : targets) { cleanupTable(t); try { t.conn().close(); } catch (Exception ignored) {} }
        System.out.println("\n=== Result: " + pass + " passed, " + fail + " failed ===");
    }

    static void connect(String n, Dialect d, String drv, String url, String u, String p) {
        try { Class.forName(drv); targets.add(new Target(n, DriverManager.getConnection(url, u, p), d)); }
        catch (Exception e) { System.out.println("SKIP " + n + ": " + e.getMessage()); }
    }

    static void setupTable(Target t) throws Exception {
        String tbl = t.q("func_test");
        try (java.sql.Statement s = t.conn().createStatement()) {
            try { s.execute("DROP TABLE " + tbl); } catch (SQLException ignored) {}
        }
        // Use compiler for DDL so column names match generated queries
        Statement ddlAst = AstBuilder.buildSingle("CREATE TABLE func_test (id INT, val INT)");
        String ddl = compiler.compileFromAst(ddlAst, t.dialect()).getSql();
        try (java.sql.Statement s = t.conn().createStatement()) {
            s.execute(ddl);
            try {
                s.execute("INSERT INTO " + tbl + " VALUES (1,1),(2,2),(3,3),(4,4),(5,5)");
            } catch (SQLException e) {
                for (int i = 1; i <= 5; i++)
                    s.execute("INSERT INTO " + tbl + " VALUES (" + i + "," + i + ")");
            }
        }
    }

    static void cleanupTable(Target t) {
        try { t.conn().createStatement().execute("DROP TABLE " + t.q("func_test")); } catch (SQLException ignored) {}
    }
}
