package com.usql;

import com.usql.ast.USqlAst.Statement;
import com.usql.dialect.Dialect;
import com.usql.parser.AstBuilder;

import java.sql.*;
import java.util.*;

/**
 * DDL verification: CREATE TABLE, INSERT, UPDATE, DELETE through the compiler
 * across all 4 target databases (MySQL, PostgreSQL, Oracle, 达梦DM).
 *
 * Run: mvn test-compile exec:java -Dexec.mainClass=com.usql.DdlVerificationTest
 */
public class DdlVerificationTest {

    record Target(String name, Connection conn, Dialect dialect) {
        boolean isQuoted() { return dialect == Dialect.ORACLE || dialect == Dialect.DM; }
        String q(String name) { return isQuoted() ? "\"" + name + "\"" : name; }
    }

    static USqlCompiler compiler;
    static List<Target> targets = new ArrayList<>();
    static int pass, fail;

    public static void main(String[] args) throws Exception {
        compiler = USqlCompiler.builder().build();

        connect("MySQL", Dialect.MYSQL, "com.mysql.cj.jdbc.Driver",
            "jdbc:mysql://localhost:3306/login_db?useSSL=false&allowPublicKeyRetrieval=true",
            "login_user", "login123");
        connect("PostgreSQL", Dialect.POSTGRESQL, "org.postgresql.Driver",
            "jdbc:postgresql://localhost:5432/mydb", "postgres", "postgres123");
        connect("Oracle", Dialect.ORACLE, "oracle.jdbc.OracleDriver",
            "jdbc:oracle:thin:@localhost:1521/orclpdb1", "system", "oracle123");
        connect("达梦DM", Dialect.DM, "dm.jdbc.driver.DmDriver",
            "jdbc:dm://localhost:5236", "SYSDBA", "dm12345678");

        for (Target t : targets) {
            System.out.println("\n=== " + t.name() + " ===");
            cleanup(t);
            try {
                testCreateTable(t);
                testInsert(t);
                testUpdate(t);
                testDelete(t);
                testIndex(t);
            } finally {
                cleanup(t);
            }
        }

        for (Target t : targets) { try { t.conn().close(); } catch (Exception ignored) {} }
        System.out.println("\n=== DDL Result: " + pass + " passed, " + fail + " failed ===");
    }

    static void connect(String name, Dialect d, String driver, String url, String user, String pw) {
        try { Class.forName(driver); targets.add(new Target(name, DriverManager.getConnection(url, user, pw), d));
            System.out.println("  Connected to " + name); }
        catch (Exception e) { System.out.println("  SKIP " + name + ": " + e.getMessage()); }
    }

    // ══════════════════════════════════════════════════
    //  Test: CREATE TABLE
    // ══════════════════════════════════════════════════

    static void testCreateTable(Target t) throws Exception {
        // U-SQL DDL with various column types and constraints
        String usql = """
            CREATE TABLE test_users (
                id INT PRIMARY KEY AUTO_INCREMENT,
                name VARCHAR(100) NOT NULL,
                email VARCHAR(200),
                salary DECIMAL(10,2) DEFAULT 0.00,
                active BOOLEAN DEFAULT TRUE,
                created_date DATE,
                bio TEXT
            )
            """;

        Statement ast = AstBuilder.buildSingle(usql);
        CompilationResult r = compiler.compileFromAst(ast, t.dialect());
        check(r.isSuccess(), "CREATE TABLE compiled", t, r);

        try (java.sql.Statement stmt = t.conn().createStatement()) {
            stmt.execute(r.getSql());
        }
        System.out.println("  PASS: CREATE TABLE test_users");

        // Verify table exists by inserting and querying
        String tbl = t.q("test_users");
        // Use quoted column names for Oracle/DM to match compiler-generated DDL
        boolean q = t.isQuoted();
        String cName = q ? "\"name\"" : "name";
        String cEmail = q ? "\"email\"" : "email";
        String cSalary = q ? "\"salary\"" : "salary";
        String cActive = q ? "\"active\"" : "active";
        String cDate = q ? "\"created_date\"" : "created_date";

        try (java.sql.Statement stmt = t.conn().createStatement()) {
            String bt = (t.dialect() == Dialect.POSTGRESQL) ? "TRUE" : "1";
            String dt = (t.dialect() == Dialect.POSTGRESQL) ? "'2024-01-15'" : "DATE '2024-01-15'";
            if (t.dialect() == Dialect.MYSQL) dt = "'2024-01-15'";
            String insDml = "INSERT INTO " + tbl + " (" + cName + ", " + cEmail + ", " + cSalary + ", " + cActive + ", " + cDate
                + ") VALUES ('Alice', 'alice@test.com', 75000.00, " + bt + ", " + dt + ")";
            stmt.execute(insDml);

            ResultSet rs = stmt.executeQuery("SELECT " + cName + ", " + cEmail + ", " + cSalary + ", " + cActive + " FROM " + tbl + " WHERE " + cName + " = 'Alice'");
            check(rs.next(), "Row inserted and queried", t, null);
            check("Alice".equals(rs.getString(1)), "name = Alice", t, null);
            check("alice@test.com".equals(rs.getString(2)), "email correct", t, null);
            check(Math.abs(rs.getDouble(3) - 75000.00) < 0.01, "salary correct", t, null);
        }
        pass++;
    }

    // ══════════════════════════════════════════════════
    //  Test: INSERT
    // ══════════════════════════════════════════════════

    static void testInsert(Target t) throws Exception {
        String tbl = t.q("test_users");
        // Insert via U-SQL
        String usql = "INSERT INTO test_users (name, email, salary) VALUES ('Bob', 'bob@test.com', 60000.00)";
        Statement ast = AstBuilder.buildSingle(usql);
        CompilationResult r = compiler.compileFromAst(ast, t.dialect());
        check(r.isSuccess(), "INSERT compiled", t, r);

        try (java.sql.Statement stmt = t.conn().createStatement()) {
            stmt.execute(r.getSql());
        }

        try (java.sql.Statement stmt = t.conn().createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + tbl)) {
            rs.next();
            check(rs.getInt(1) >= 2, "INSERT: at least 2 rows in table", t, null);
        }
        System.out.println("  PASS: INSERT");
        pass++;
    }

    // ══════════════════════════════════════════════════
    //  Test: UPDATE
    // ══════════════════════════════════════════════════

    static void testUpdate(Target t) throws Exception {
        String tbl = t.q("test_users");
        String usql = "UPDATE test_users SET salary = 80000.00 WHERE name = 'Bob'";
        Statement ast = AstBuilder.buildSingle(usql);
        CompilationResult r = compiler.compileFromAst(ast, t.dialect());
        check(r.isSuccess(), "UPDATE compiled", t, r);

        try (java.sql.Statement stmt = t.conn().createStatement()) {
            stmt.execute(r.getSql());
        }

        try (java.sql.Statement stmt = t.conn().createStatement();
             ResultSet rs = stmt.executeQuery("SELECT " + t.q("salary") + " FROM " + tbl + " WHERE " + t.q("name") + " = 'Bob'")) {
            rs.next();
            check(Math.abs(rs.getDouble(1) - 80000.00) < 0.01, "UPDATE: salary = 80000", t, null);
        }
        System.out.println("  PASS: UPDATE");
        pass++;
    }

    // ══════════════════════════════════════════════════
    //  Test: DELETE
    // ══════════════════════════════════════════════════

    static void testDelete(Target t) throws Exception {
        String tbl = t.q("test_users");
        String usql = "DELETE FROM test_users WHERE name = 'Bob'";
        Statement ast = AstBuilder.buildSingle(usql);
        CompilationResult r = compiler.compileFromAst(ast, t.dialect());
        check(r.isSuccess(), "DELETE compiled", t, r);

        try (java.sql.Statement stmt = t.conn().createStatement()) {
            stmt.execute(r.getSql());
        }

        try (java.sql.Statement stmt = t.conn().createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + tbl + " WHERE " + t.q("name") + " = 'Bob'")) {
            rs.next();
            check(rs.getInt(1) == 0, "DELETE: Bob removed", t, null);
        }
        System.out.println("  PASS: DELETE");
        pass++;
    }

    // ══════════════════════════════════════════════════
    //  Test: CREATE INDEX
    // ══════════════════════════════════════════════════

    static void testIndex(Target t) throws Exception {
        String usql = "CREATE UNIQUE INDEX idx_email ON test_users (email)";
        Statement ast = AstBuilder.buildSingle(usql);
        CompilationResult r = compiler.compileFromAst(ast, t.dialect());
        check(r.isSuccess(), "CREATE INDEX compiled", t, r);

        try (java.sql.Statement stmt = t.conn().createStatement()) {
            stmt.execute(r.getSql());
        }
        System.out.println("  PASS: CREATE INDEX");
        pass++;
    }

    // ══════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════

    static void cleanup(Target t) {
        try (java.sql.Statement s = t.conn().createStatement()) {
            for (String tbl : List.of("test_users", "idx_email")) {
                try { s.execute("DROP TABLE " + t.q(tbl)); } catch (SQLException ignored) {}
                try { s.execute("DROP INDEX " + t.q(tbl)); } catch (SQLException ignored) {}
            }
        } catch (SQLException ignored) {}
    }

    static void check(boolean cond, String msg, Target t, CompilationResult r) {
        if (!cond) {
            System.out.println("  FAIL " + t.name() + ": " + msg);
            if (r != null && !r.isSuccess()) System.out.println("    " + r.report());
            fail++;
        }
    }
}
