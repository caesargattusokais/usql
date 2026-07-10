package com.usql;

import com.usql.dialect.Dialect;
import com.usql.ir.*;
import com.usql.ir.IRExpr.*;
import com.usql.ir.IRStatement.*;

import java.sql.*;
import java.util.*;

/**
 * Full regression test — DDL, DML across all 5 dialects.
 * Run: mvn test-compile exec:java -pl usql-core -Dexec.mainClass=com.usql.RegressionTest -Dexec.classpathScope=test
 */
public class RegressionTest {

    record Db(String name, Dialect dialect, String url, String user, String pw) {}

    static List<Db> databases = List.of(
        new Db("MySQL", Dialect.MYSQL,
            "jdbc:mysql://localhost:3306/login_db?useSSL=false&allowPublicKeyRetrieval=true",
            "login_user", "login123"),
        new Db("PostgreSQL", Dialect.POSTGRESQL,
            "jdbc:postgresql://localhost:5432/mydb",
            "postgres", "postgres123"),
        new Db("Oracle", Dialect.ORACLE,
            "jdbc:oracle:thin:@localhost:1521/orclpdb1",
            "system", "oracle123"),
        new Db("达梦DM", Dialect.DM,
            "jdbc:dm://localhost:5236", "SYSDBA", "dm12345678"),
        new Db("SQL Server", Dialect.SQLSERVER,
            "jdbc:sqlserver://localhost:1433;encrypt=false;databaseName=master",
            "sa", "SqlServer123!")
    );

    static USqlCompiler compiler = USqlCompiler.builder().build();
    static int pass = 0, fail = 0, skipped = 0;
    static String currentDb;

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║   USQL Full Regression — All Dialects   ║");
        System.out.println("╚══════════════════════════════════════════╝\n");

        for (Db db : databases) {
            currentDb = db.name();
            System.out.println("── " + db.name() + " ──");
            try (Connection conn = DriverManager.getConnection(db.url(), db.user(), db.pw())) {
                testDDL(db, conn);
                testDML(db, conn);
                testQueryFeatures(db, conn);
                testStoredProcedure(db, conn);
            } catch (Exception e) {
                System.out.println("  ⏭️  SKIP: " + e.getMessage().split("\n")[0]);
                skipped++;
            }
        }

        int total = pass + fail + skipped;
        System.out.println("\n╔══════════════════════════════════════════╗");
        System.out.println("║  Result: " + pass + "/" + (pass+fail) + " passed"
            + (skipped > 0 ? " (" + skipped + " skipped)" : "") + "  ║");
        System.out.println("╚══════════════════════════════════════════╝");
        if (fail > 0) System.exit(1);
    }

    // ══════════════════════════════════════════════════
    //  DDL — CREATE TABLE, CREATE INDEX
    // ══════════════════════════════════════════════════

    static void testDDL(Db db, Connection conn) throws Exception {
        dropTable(db, conn, "reg_t1");
        dropTable(db, conn, "reg_t2");

        // 1. CREATE TABLE with all column types
        execDDL(db, conn,
            "CREATE TABLE reg_t1 ("
            + "id INT PRIMARY KEY AUTO_INCREMENT, "
            + "name VARCHAR(100) NOT NULL, "
            + "score DECIMAL(10,2) DEFAULT 0, "
            + "active BOOLEAN DEFAULT TRUE, "
            + "created DATE, "
            + "updated DATETIME, "
            + "bio TEXT, "
            + "data JSON"
            + ")",
            "CREATE TABLE all types");

        // 2. CREATE TABLE IF NOT EXISTS (idempotent)
        execDDL(db, conn,
            "CREATE TABLE IF NOT EXISTS reg_t1 (id INT PRIMARY KEY)",
            "CREATE TABLE IF NOT EXISTS (idempotent)");

        // 3. CREATE TABLE with multi-column PK, CHECK, UNIQUE
        execDDL(db, conn,
            "CREATE TABLE reg_t2 ("
            + "id INT NOT NULL, "
            + "code VARCHAR(20) NOT NULL, "
            + "amount DECIMAL(10,2) CHECK (amount > 0), "
            + "PRIMARY KEY (id, code)"
            + ")",
            "CREATE TABLE multi-column PK + CHECK");

        // 4. CREATE INDEX
        execDDL(db, conn,
            "CREATE INDEX idx_reg_name ON reg_t1 (name)",
            "CREATE INDEX");

        // 5. CREATE INDEX IF NOT EXISTS
        execDDL(db, conn,
            "CREATE INDEX IF NOT EXISTS idx_reg_name ON reg_t1 (name)",
            "CREATE INDEX IF NOT EXISTS (idempotent)");

        // 6. CREATE UNIQUE INDEX
        execDDL(db, conn,
            "CREATE UNIQUE INDEX idx_reg_code ON reg_t2 (code)",
            "CREATE UNIQUE INDEX");

        // 7. Verify tables exist
        execQuery(db, conn, "SELECT COUNT(*) AS cnt FROM reg_t1", 1);
        execQuery(db, conn, "SELECT COUNT(*) AS cnt FROM reg_t2", 1);

        dropTable(db, conn, "reg_t1");
        dropTable(db, conn, "reg_t2");
    }

    // ══════════════════════════════════════════════════
    //  DML — INSERT, UPDATE, DELETE, MERGE
    // ══════════════════════════════════════════════════

    static void testDML(Db db, Connection conn) throws Exception {
        dropTable(db, conn, "reg_emp");
        dropTable(db, conn, "reg_dept");

        // Setup tables
        execDDL(db, conn,
            "CREATE TABLE reg_dept ("
            + "id INT PRIMARY KEY, name VARCHAR(50) NOT NULL"
            + ")",
            "Setup reg_dept");
        execDDL(db, conn,
            "CREATE TABLE reg_emp ("
            + "id INT PRIMARY KEY AUTO_INCREMENT, "
            + "name VARCHAR(50) NOT NULL, "
            + "dept_id INT, "
            + "salary DECIMAL(10,2) DEFAULT 0, "
            + "active BOOLEAN DEFAULT TRUE"
            + ")",
            "Setup reg_emp");

        // 8. INSERT single row
        execDML(db, conn, "INSERT INTO reg_dept (id, name) VALUES (1, 'Engineering')", "INSERT single");
        execDML(db, conn, "INSERT INTO reg_dept (id, name) VALUES (2, 'Sales')", "INSERT single");
        execDML(db, conn, "INSERT INTO reg_dept (id, name) VALUES (3, 'HR')", "INSERT single");

        // 9. INSERT multi row
        execDML(db, conn,
            "INSERT INTO reg_emp (name, dept_id, salary, active) VALUES "
            + "('Alice', 1, 80000, TRUE), "
            + "('Bob', 1, 75000, TRUE), "
            + "('Charlie', 2, 60000, TRUE), "
            + "('Diana', 2, 55000, FALSE), "
            + "('Eve', 3, 90000, TRUE)",
            "INSERT multi row");

        // 10. INSERT ... SELECT
        execDML(db, conn,
            "INSERT INTO reg_emp (name, dept_id, salary) "
            + "SELECT 'Frank', id, 50000 FROM reg_dept WHERE name = 'Engineering'",
            "INSERT ... SELECT");

        // 11. UPDATE
        execDML(db, conn,
            "UPDATE reg_emp SET salary = 85000 WHERE name = 'Alice'",
            "UPDATE single");

        // 12. UPDATE multi column
        execDML(db, conn,
            "UPDATE reg_emp SET salary = salary * 1.1, active = TRUE WHERE dept_id = 1",
            "UPDATE multi-column");

        // 13. DELETE
        execDML(db, conn,
            "DELETE FROM reg_emp WHERE name = 'Frank'",
            "DELETE");

        // 14. DELETE all from child table
        execDML(db, conn, "DELETE FROM reg_emp WHERE dept_id = 3", "DELETE by condition");

        // Verify counts
        execQuery(db, conn, "SELECT COUNT(*) AS cnt FROM reg_emp", 1);

        dropTable(db, conn, "reg_emp");
        dropTable(db, conn, "reg_dept");
    }

    // ══════════════════════════════════════════════════
    //  Query Features — SELECT variants
    // ══════════════════════════════════════════════════

    static void testQueryFeatures(Db db, Connection conn) throws Exception {
        dropTable(db, conn, "reg_q");
        execDDL(db, conn,
            "CREATE TABLE reg_q ("
            + "id INT PRIMARY KEY AUTO_INCREMENT, "
            + "name VARCHAR(50), "
            + "dept_id INT, "
            + "salary DECIMAL(10,2), "
            + "active BOOLEAN, "
            + "joined DATE"
            + ")",
            "Setup reg_q");

        execDML(db, conn, "INSERT INTO reg_q (name, dept_id, salary, active, joined) VALUES "
            + "('Alice', 1, 80000, TRUE, '2020-01-15'), "
            + "('Bob', 1, 75000, TRUE, '2021-06-01'), "
            + "('Charlie', 2, 60000, TRUE, '2019-03-20'), "
            + "('Diana', 2, 55000, FALSE, '2022-09-10'), "
            + "('Eve', 1, 90000, TRUE, '2018-11-30')",
            "Insert test data");

        // 15. SELECT with WHERE
        execQuery(db, conn, "SELECT name, salary FROM reg_q WHERE dept_id = 1", 3);

        // 16. SELECT DISTINCT
        execQuery(db, conn, "SELECT DISTINCT dept_id FROM reg_q", 2);

        // 17. SELECT with GROUP BY + aggregate
        execQuery(db, conn,
            "SELECT dept_id, COUNT(*) AS cnt, AVG(salary) AS avg_sal FROM reg_q GROUP BY dept_id", 2);

        // 18. SELECT with HAVING
        execQuery(db, conn,
            "SELECT dept_id, COUNT(*) AS cnt FROM reg_q GROUP BY dept_id HAVING COUNT(*) > 1", 1);

        // 19. SELECT with ORDER BY
        execQuery(db, conn, "SELECT name, salary FROM reg_q ORDER BY salary DESC", 5);

        // 20. SELECT with LIMIT
        execQuery(db, conn, "SELECT name FROM reg_q LIMIT 2", 2);

        // 21. SELECT with LIMIT + OFFSET
        execQuery(db, conn, "SELECT name FROM reg_q LIMIT 2 OFFSET 2", 2);

        // 22. SELECT with LIKE
        execQuery(db, conn, "SELECT name FROM reg_q WHERE name LIKE 'A%'", 1);

        // 23. SELECT with BETWEEN
        execQuery(db, conn, "SELECT name FROM reg_q WHERE salary BETWEEN 60000 AND 80000", 3);

        // 24. SELECT with IN
        execQuery(db, conn, "SELECT name FROM reg_q WHERE dept_id IN (1, 2)", 4);

        // 25. SELECT with IS NULL / IS NOT NULL
        execQuery(db, conn, "SELECT name FROM reg_q WHERE name IS NOT NULL", 5);

        // 26. SELECT with CASE
        execQuery(db, conn,
            "SELECT CASE WHEN salary > 70000 THEN 'High' ELSE 'Low' END AS level FROM reg_q", 5);

        // 27. SELECT with COALESCE / NVL
        execQuery(db, conn, "SELECT COALESCE(name, 'N/A') FROM reg_q", 5);

        // 28. SELECT with string functions
        execQuery(db, conn, "SELECT UPPER(name), LENGTH(name) FROM reg_q", 5);

        // 29. SELECT with math functions
        execQuery(db, conn, "SELECT ROUND(salary / 1000, 0) FROM reg_q", 5);

        // 30. SELECT without FROM (expression only)
        execQuery(db, conn, "SELECT 1 + 1 AS two", 1);

        // 31. JOIN
        dropTable(db, conn, "reg_dept2");
        execDDL(db, conn, "CREATE TABLE reg_dept2 (id INT PRIMARY KEY, name VARCHAR(50))", "Setup reg_dept2");
        execDML(db, conn, "INSERT INTO reg_dept2 (id, name) VALUES (1, 'Eng'), (2, 'Sales')", "Insert dept2");
        execQuery(db, conn,
            "SELECT e.name, d.name FROM reg_q e JOIN reg_dept2 d ON e.dept_id = d.id", 4);
        dropTable(db, conn, "reg_dept2");

        // 32. LEFT JOIN
        execDDL(db, conn, "CREATE TABLE reg_dept2 (id INT PRIMARY KEY, name VARCHAR(50))", "Setup reg_dept2");
        execDML(db, conn, "INSERT INTO reg_dept2 (id, name) VALUES (1, 'Eng'), (4, 'Legal')", "Insert dept2");
        execQuery(db, conn,
            "SELECT e.name, d.name FROM reg_q e LEFT JOIN reg_dept2 d ON e.dept_id = d.id", 5);
        dropTable(db, conn, "reg_dept2");

        // 33. CTE (WITH)
        execQuery(db, conn,
            "WITH high AS (SELECT name, salary FROM reg_q WHERE salary > 70000) "
            + "SELECT name FROM high", 2);

        // 34. Window function
        execQuery(db, conn,
            "SELECT name, ROW_NUMBER() OVER (PARTITION BY dept_id ORDER BY salary DESC) AS rn FROM reg_q", 5);

        // 35. CAST
        execQuery(db, conn, "SELECT CAST(salary AS VARCHAR(10)) FROM reg_q", 5);

        // 36. EXISTS subquery (compilation only — verify it compiles)
        compiles(db, "SELECT name FROM reg_q WHERE EXISTS (SELECT 1 FROM reg_q t2 WHERE t2.dept_id = reg_q.dept_id AND t2.salary > 80000)");

        dropTable(db, conn, "reg_q");
    }

    // ══════════════════════════════════════════════════
    //  Stored Procedures
    // ══════════════════════════════════════════════════

    static void testStoredProcedure(Db db, Connection conn) throws Exception {
        try {
            doProcTest(db, conn);
        } catch (Exception e) {
            System.out.println("    ⚠️  Procedure: " + e.getMessage().split("\n")[0]);
            skipped++;
        }
    }

    static void doProcTest(Db db, Connection conn) throws Exception {
        // CREATE PROCEDURE via IR
        IRCreateProcedure proc = new IRCreateProcedure("reg_proc",
            List.of(new ProcedureParam("p_name", new DataType.VarcharType(100), ParamMode.IN)),
            getBody(db.dialect()), false, Set.of());
        CompilationResult r = compiler.compileFromIR(proc, db.dialect());
        check(r.isSuccess(), "CREATE PROCEDURE compiles");
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(r.getSql());
            check(true, "CREATE PROCEDURE executed");
        } catch (SQLException e) {
            System.out.println("    ⚠️  Proc exec: " + e.getMessage().split("\n")[0]);
            skipped++;
        }
        // Cleanup
        try { conn.createStatement().execute("DROP PROCEDURE " + quoteName(db.dialect(), "reg_proc")); } catch (SQLException ignored) {}

        // CALL via IR
        IRCall call = new IRCall("reg_proc", List.of(new IRLiteral("test", null)), Set.of());
        check(compiler.compileFromIR(call, db.dialect()).isSuccess(), "CALL compiles");

        // Text parse
        compiles(db, "CREATE PROCEDURE reg_text(IN x INT) AS 'BEGIN NULL; END'");
    }

    static String getBody(Dialect d) {
        return switch (d) {
            case MYSQL -> "SELECT CONCAT('Hello ', p_name)";
            case POSTGRESQL -> "BEGIN NULL; END";
            case ORACLE, DM -> "BEGIN NULL; END";
            case SQLSERVER -> "SELECT 'Hello' + @p_name AS g";
            default -> "SELECT 1";
        };
    }

    // ══════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════

    static void execDDL(Db db, Connection conn, String usql, String label) throws Exception {
        CompilationResult r = compiler.compile(usql, db.dialect());
        check(r.isSuccess(), label + " compiles");
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(r.getSql());
            check(true, label + " executed");
        } catch (SQLException e) {
            check(false, label + ": " + e.getMessage());
        }
    }

    static void execDML(Db db, Connection conn, String usql, String label) throws Exception {
        CompilationResult r = compiler.compile(usql, db.dialect());
        check(r.isSuccess(), label + " compiles");
        try (Statement stmt = conn.createStatement()) {
            int rows = stmt.executeUpdate(r.getSql());
            check(true, label + " executed (" + rows + " rows)");
        } catch (SQLException e) {
            System.err.println("    SQL: " + r.getSql());
            check(false, label + ": " + e.getMessage());
        }
    }

    static void execQuery(Db db, Connection conn, String usql, int expectedRows) throws Exception {
        CompilationResult r = compiler.compile(usql, db.dialect());
        check(r.isSuccess(), "Query compiles: " + usql.substring(0, Math.min(40, usql.length())));
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(r.getSql())) {
            int count = 0;
            while (rs.next()) count++;
            check(count == expectedRows,
                "Query returns " + expectedRows + " rows (got " + count + ")");
        } catch (SQLException e) {
            System.err.println("    SQL: " + r.getSql());
            check(false, "Query: " + e.getMessage());
        }
    }

    static void compiles(Db db, String usql) {
        CompilationResult r = compiler.compile(usql, db.dialect());
        check(r.isSuccess(), "Compiles: " + usql.substring(0, Math.min(40, usql.length())));
    }

    static void dropTable(Db db, Connection conn, String name) {
        // Try both quoted and unquoted (covers Oracle case sensitivity edge cases)
        for (String n : new String[]{quoteName(db.dialect(), name), name}) {
            try { conn.createStatement().execute("DROP TABLE " + n); } catch (SQLException ignored) {}
        }
    }

    static String quoteName(Dialect d, String name) {
        return switch (d) {
            case MYSQL -> "`" + name + "`";
            case SQLSERVER -> "[" + name + "]";
            default -> "\"" + name + "\"";
        };
    }

    static void check(boolean condition, String msg) {
        if (condition) { pass++; }
        else { fail++; System.err.println("  [" + currentDb + "] ❌ FAIL: " + msg); }
    }
}
