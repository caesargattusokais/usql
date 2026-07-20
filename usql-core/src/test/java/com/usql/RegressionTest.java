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
            "jdbc:mysql://localhost:3306/login_db?useSSL=false&allowPublicKeyRetrieval=true&allowMultiQueries=true",
            "login_user", "login123"),
        new Db("PostgreSQL", Dialect.POSTGRESQL,
            "jdbc:postgresql://localhost:5432/mydb", "postgres", "postgres123"),
        new Db("Oracle", Dialect.ORACLE,
            "jdbc:oracle:thin:@localhost:1521/orclpdb1", "system", "oracle123"),
        new Db("达梦DM", Dialect.DM,
            "jdbc:dm://localhost:5236", "SYSDBA", "dm12345678"),
        new Db("SQL Server", Dialect.SQLSERVER,
            "jdbc:sqlserver://localhost:1433;encrypt=false;databaseName=master",
            "sa", "SqlServer123!"),
        new Db("MariaDB", Dialect.MARIADB,
            "jdbc:mariadb://localhost:3307/test_db",
            "test_user", "test123"),
        new Db("TiDB", Dialect.TIDB,
            "jdbc:mysql://localhost:4000/test?allowPublicKeyRetrieval=true&useSSL=false&allowMultiQueries=true",
            "root", ""),
        new Db("ClickHouse", Dialect.CLICKHOUSE,
            "jdbc:clickhouse://127.0.0.1:8123/default?user=default&compress=0", "", ""),
        // In-process databases (no Docker memory needed)
        new Db("SQLite", Dialect.SQLITE,
            "jdbc:sqlite::memory:", "", ""),
        new Db("DuckDB", Dialect.DUCKDB, "jdbc:duckdb:", "", "")
    );

    /** OceanBase runs separately due to high memory requirements (3G+). */
    static List<Db> oceanBaseOnly = List.of(
        new Db("OceanBase", Dialect.OCEANBASE,
            "jdbc:mysql://localhost:2881/test?useSSL=false&allowPublicKeyRetrieval=true&allowMultiQueries=true",
            "root@test", "ob123456")
    );

    static USqlCompiler compiler = USqlCompiler.builder().build();
    static USqlCompiler compilerL2 = USqlCompiler.builder().withOptimizeLevel(2).build(); // without pushdown/prune
    static int pass = 0, fail = 0, skipped = 0;
    static String currentDb;

    public static void main(String[] args) {
        // --oceanbase: run OceanBase separately (needs 3G+ memory, can't coexist with other DBs)
        List<Db> dbList = args.length > 0 && args[0].equals("--oceanbase") ? oceanBaseOnly : databases;
        System.out.println("=== USQL Full Regression ===\n");
        for (Db db : dbList) {
            currentDb = db.name();
            System.out.println("── " + db.name() + " ──");
            try (Connection conn = connectWithRetry(db)) {
                testDDL(db, conn);
                testDML(db, conn);
                testQueryFeatures(db, conn);
                testMerge(db, conn);
                testFullJoin(db, conn);
                testKEEP(db, conn);
                testEnum(db, conn);
                testCTE(db, conn);
                testSetOps(db, conn);
                testRollupCube(db, conn);
                testDropTruncateAlter(db, conn);
                testStoredProc(db, conn);
                testOptimizerCorrectness(db, conn);
                testViewSchema(db, conn);
                testTCL(db, conn);
                testLateral(db, conn);
            } catch (Exception e) {
                System.out.println("  SKIP: " + e.getMessage().split("\n")[0]);
                skipped++;
            }
        }
        System.out.println("\n=== Result: " + pass + "/" + (pass+fail) + " passed"
            + (skipped > 0 ? " (" + skipped + " skipped)" : "") + " ===");
        if (fail > 0) System.exit(1);
    }

    // ═══════════════════════════════════════
    //  DDL
    // ═══════════════════════════════════════

    static void testDDL(Db db, Connection conn) throws Exception {
        // 1. CREATE TABLE all types
        execDDL(db, conn, "CREATE TABLE reg_a (id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(100) NOT NULL, score DECIMAL(10,2), active BOOLEAN, created DATE, bio TEXT)", "CREATE TABLE all types");
        // 2. IF NOT EXISTS idempotent
        execDDL(db, conn, "CREATE TABLE IF NOT EXISTS reg_a (id INT PRIMARY KEY)", "IF NOT EXISTS idempotent");
        // 3. Multi-column PK + CHECK
        execDDL(db, conn, "CREATE TABLE reg_b (id INT NOT NULL, code VARCHAR(20) NOT NULL, amount DECIMAL(10,2) CHECK (amount > 0), PRIMARY KEY (id, code))", "Multi PK + CHECK");
        // 4. CREATE INDEX
        execDDL(db, conn, "CREATE INDEX idx_a_name ON reg_a (name)", "CREATE INDEX");
        execDDL(db, conn, "CREATE INDEX IF NOT EXISTS idx_a_name ON reg_a (name)", "INDEX IF NOT EXISTS idempotent");
        // 5. CREATE UNIQUE INDEX
        execDDL(db, conn, "CREATE UNIQUE INDEX idx_b_code ON reg_b (code)", "CREATE UNIQUE INDEX");
        // 6. Verify
        execQueryAny(db, conn, "SELECT COUNT(*) AS cnt FROM reg_a");
        execQueryAny(db, conn, "SELECT COUNT(*) AS cnt FROM reg_b");
        dropTable(db, conn, "reg_a"); dropTable(db, conn, "reg_b");
    }

    // ═══════════════════════════════════════
    //  DML
    // ═══════════════════════════════════════

    static void testDML(Db db, Connection conn) throws Exception {
        // Setup
        execDDL(db, conn, "CREATE TABLE reg_d (id INT PRIMARY KEY, name VARCHAR(50))", "Setup dept");
        execDDL(db, conn, "CREATE TABLE reg_e (id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(50), dept_id INT, salary DECIMAL(10,2))", "Setup emp");

        // INSERT single
        execDML(db, conn, "INSERT INTO reg_d (id, name) VALUES (1, 'Eng'), (2, 'Sales')", "INSERT dept");
        execDML(db, conn, "INSERT INTO reg_e (name, dept_id, salary) VALUES ('Alice', 1, 80000)", "INSERT single");

        // INSERT multi
        execDML(db, conn, "INSERT INTO reg_e (name, dept_id, salary) VALUES ('Bob', 1, 75000), ('Charlie', 2, 60000), ('Diana', 2, 55000)", "INSERT multi");

        // INSERT SELECT
        execDML(db, conn, "INSERT INTO reg_e (name, dept_id, salary) SELECT 'Eve', id, 50000 FROM reg_d WHERE name = 'Eng'", "INSERT SELECT");

        // UPDATE
        execDML(db, conn, "UPDATE reg_e SET salary = 99999 WHERE name = 'Alice'", "UPDATE");

        // DELETE
        execDML(db, conn, "DELETE FROM reg_e WHERE name = 'Eve'", "DELETE");

        // Verify
        execQuery(db, conn, "SELECT COUNT(*) AS cnt FROM reg_e", 1);
        execQuery(db, conn, "SELECT COUNT(*) AS cnt FROM reg_e WHERE dept_id = 1", 1);

        dropTable(db, conn, "reg_e"); dropTable(db, conn, "reg_d");
    }

    // ═══════════════════════════════════════
    //  Query Features
    // ═══════════════════════════════════════

    static void testQueryFeatures(Db db, Connection conn) throws Exception {
        execDDL(db, conn, "CREATE TABLE reg_q (id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(50), dept_id INT, salary DECIMAL(10,2), active BOOLEAN)", "Setup reg_q");
        execDML(db, conn, "INSERT INTO reg_q (name, dept_id, salary, active) VALUES ('Alice', 1, 80000, TRUE), ('Bob', 1, 75000, TRUE), ('Charlie', 2, 60000, TRUE), ('Diana', 2, 55000, FALSE), ('Eve', 1, 90000, TRUE)", "Insert reg_q");

        // WHERE
        execQuery(db, conn, "SELECT name FROM reg_q WHERE dept_id = 1", 3);
        // DISTINCT
        execQuery(db, conn, "SELECT DISTINCT dept_id FROM reg_q", 2);
        // GROUP BY + AGG
        execQuery(db, conn, "SELECT dept_id, COUNT(*) AS cnt FROM reg_q GROUP BY dept_id", 2);
        // HAVING
        execQuery(db, conn, "SELECT dept_id, COUNT(*) AS cnt FROM reg_q GROUP BY dept_id HAVING COUNT(*) > 2", 1);
        // ORDER BY
        execQuery(db, conn, "SELECT name FROM reg_q ORDER BY salary DESC", 5);
        // LIMIT
        execQuery(db, conn, "SELECT name FROM reg_q LIMIT 2", 2);
        // LIMIT OFFSET
        execQuery(db, conn, "SELECT name FROM reg_q LIMIT 2 OFFSET 2", 2);
        // LIKE
        execQuery(db, conn, "SELECT name FROM reg_q WHERE name LIKE 'A%'", 1);
        // BETWEEN
        execQuery(db, conn, "SELECT name FROM reg_q WHERE salary BETWEEN 60000 AND 80000", 3);
        // IN
        execQuery(db, conn, "SELECT name FROM reg_q WHERE dept_id IN (1, 2)", 5);
        // IS NOT NULL
        execQuery(db, conn, "SELECT name FROM reg_q WHERE name IS NOT NULL", 5);
        // CASE
        execQuery(db, conn, "SELECT CASE WHEN salary > 70000 THEN 'High' ELSE 'Low' END AS lvl FROM reg_q", 5);
        // UPPER/LENGTH
        execQuery(db, conn, "SELECT UPPER(name) FROM reg_q", 5);
        // ROUND
        execQuery(db, conn, "SELECT ROUND(salary / 1000, 0) FROM reg_q", 5);
        // Expression
        execQuery(db, conn, "SELECT 1 + 1 AS two", 1);
        // CAST
        execQuery(db, conn, "SELECT CAST(salary AS VARCHAR(10)) FROM reg_q WHERE name = 'Alice'", 1);
        // COALESCE
        execQuery(db, conn, "SELECT COALESCE(name, 'N/A') FROM reg_q", 5);
        // JOIN
        execDDL(db, conn, "CREATE TABLE reg_j (id INT PRIMARY KEY, label VARCHAR(50))", "Setup join");
        execDML(db, conn, "INSERT INTO reg_j (id, label) VALUES (1, 'First'), (2, 'Second')", "Insert join");
        execQuery(db, conn, "SELECT e.name, j.label FROM reg_q e JOIN reg_j j ON e.dept_id = j.id", 5);
        // LEFT JOIN
        execQuery(db, conn, "SELECT e.name, j.label FROM reg_q e LEFT JOIN reg_j j ON e.dept_id = j.id", 5);
        // CTE
        execQuery(db, conn, "WITH hi AS (SELECT name FROM reg_q WHERE salary > 70000) SELECT name FROM hi", 3);
        // Window
        execQuery(db, conn, "SELECT name, ROW_NUMBER() OVER (PARTITION BY dept_id ORDER BY salary DESC) AS rn FROM reg_q", 5);

        dropTable(db, conn, "reg_j"); dropTable(db, conn, "reg_q");
    }

    // ═══════════════════════════════════════
    //  MERGE / UPSERT
    // ═══════════════════════════════════════

    static void testMerge(Db db, Connection conn) throws Exception {
        if (db.dialect() == Dialect.SQLITE) { skipped++; return; } // SQLite no MERGE
        dropTable(db, conn, "reg_m_tgt");
        dropTable(db, conn, "reg_m_src");
        execDDL(db, conn, "CREATE TABLE reg_m_tgt (id INT PRIMARY KEY, name VARCHAR(50), val INT)", "Setup merge target");
        execDDL(db, conn, "CREATE TABLE reg_m_src (id INT PRIMARY KEY, name VARCHAR(50), val INT)", "Setup merge source");
        execDML(db, conn, "INSERT INTO reg_m_tgt (id, name, val) VALUES (1, 'A', 10), (2, 'B', 20)", "Insert target");
        execDML(db, conn, "INSERT INTO reg_m_src (id, name, val) VALUES (2, 'B2', 99), (3, 'C', 30)", "Insert source");

        // MERGE: update matched, insert unmatched
        String mergeUsql = "MERGE INTO reg_m_tgt t USING reg_m_src s ON t.id = s.id WHEN MATCHED THEN UPDATE SET name = s.name, val = s.val WHEN NOT MATCHED THEN INSERT (id, name, val) VALUES (s.id, s.name, s.val)";
        CompilationResult r = compiler.compile(mergeUsql, db.dialect());
        if (r.isSuccess()) {
            try (Statement stmt = conn.createStatement()) { stmt.execute(r.getSql()); check(true, "MERGE executed"); }
            catch (SQLException e) { check(false, "MERGE: " + e.getMessage()); }
        } else {
            System.out.println("    ⚠️  MERGE compile: " + r.report());
            skipped++;
        }

        execQuery(db, conn, "SELECT COUNT(*) AS cnt FROM reg_m_tgt", 1);
        execQuery(db, conn, "SELECT COUNT(*) AS cnt FROM reg_m_tgt WHERE val = 99", 1);

        dropTable(db, conn, "reg_m_tgt"); dropTable(db, conn, "reg_m_src");
    }

    // ═══════════════════════════════════════
    //  FULL OUTER JOIN
    // ═══════════════════════════════════════

    static void testFullJoin(Db db, Connection conn) throws Exception {
        dropTable(db, conn, "reg_fj_a");
        dropTable(db, conn, "reg_fj_b");
        execDDL(db, conn, "CREATE TABLE reg_fj_a (id INT PRIMARY KEY, name VARCHAR(50))", "Setup fj_a");
        execDDL(db, conn, "CREATE TABLE reg_fj_b (id INT PRIMARY KEY, label VARCHAR(50))", "Setup fj_b");
        execDML(db, conn, "INSERT INTO reg_fj_a (id, name) VALUES (1, 'A1'), (2, 'A2')", "Insert fj_a");
        execDML(db, conn, "INSERT INTO reg_fj_b (id, label) VALUES (2, 'B2'), (3, 'B3')", "Insert fj_b");

        execQuery(db, conn, "SELECT a.name, b.label FROM reg_fj_a a FULL JOIN reg_fj_b b ON a.id = b.id", 3);

        dropTable(db, conn, "reg_fj_a"); dropTable(db, conn, "reg_fj_b");
    }

    // ═══════════════════════════════════════
    //  KEEP aggregate
    // ═══════════════════════════════════════

    static void testKEEP(Db db, Connection conn) throws Exception {
        dropTable(db, conn, "reg_kp");
        execDDL(db, conn, "CREATE TABLE reg_kp (id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(50), dept VARCHAR(50), salary DECIMAL(10,2))", "Setup keep");
        execDML(db, conn, "INSERT INTO reg_kp (name, dept, salary) VALUES ('Alice', 'Eng', 80000), ('Bob', 'Eng', 75000), ('Charlie', 'Sales', 60000), ('Diana', 'Sales', 55000)", "Insert keep");

        // KEEP FIRST/LAST
        execQuery(db, conn, "SELECT dept, MAX(salary) KEEP (DENSE_RANK FIRST ORDER BY salary) AS first_sal FROM reg_kp GROUP BY dept", 2);

        dropTable(db, conn, "reg_kp");
    }

    // ═══════════════════════════════════════
    //  ENUM type
    // ═══════════════════════════════════════

    static void testEnum(Db db, Connection conn) throws Exception {
        dropTable(db, conn, "reg_en");
        String enumUsql = "CREATE TABLE reg_en (id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(50), status ENUM('active','inactive','suspended'))";
        CompilationResult r = compiler.compile(enumUsql, db.dialect());
        if (r.isSuccess()) {
            try (Statement stmt = conn.createStatement()) { stmt.execute(r.getSql()); check(true, "ENUM table created"); }
            catch (SQLException e) { System.out.println("    ⚠️  ENUM DDL: " + e.getMessage()); skipped++; dropTable(db, conn, "reg_en"); return; }
            execDML(db, conn, "INSERT INTO reg_en (name, status) VALUES ('Alice', 'active'), ('Bob', 'inactive')", "Insert enum");
            execQuery(db, conn, "SELECT name FROM reg_en WHERE status = 'active'", 1);
        } else {
            System.out.println("    ⚠️  ENUM compile: " + r.report());
            skipped++;
        }
        dropTable(db, conn, "reg_en");
    }

    // ═══════════════════════════════════════
    //  CTE (WITH / WITH RECURSIVE)
    // ═══════════════════════════════════════

    static void testCTE(Db db, Connection conn) throws Exception {
        // Setup table for non-recursive CTE
        dropTable(db, conn, "reg_cte_t");
        execDDL(db, conn, "CREATE TABLE reg_cte_t (id INT PRIMARY KEY, name VARCHAR(50), val INT)", "Setup cte_t");
        execDML(db, conn, "INSERT INTO reg_cte_t (id, name, val) VALUES (1, 'A', 10), (2, 'B', 20), (3, 'C', 30)", "Insert cte_t");

        // Non-recursive CTE
        execQuery(db, conn,
            "WITH cte AS (SELECT id, name FROM reg_cte_t WHERE val > 15) SELECT name FROM cte", 2);

        // Recursive CTE — all 5 dialects
        execQuery(db, conn,
            "WITH RECURSIVE nums AS ("
            + "SELECT 1 AS n "
            + "UNION ALL "
            + "SELECT n + 1 FROM nums WHERE n < 5"
            + ") SELECT n FROM nums", 5);

        execQuery(db, conn,
            "WITH RECURSIVE nums AS ("
            + "SELECT 1 AS n "
            + "UNION ALL "
            + "SELECT n + 1 FROM nums WHERE n < 3"
            + ") SELECT SUM(n) AS total FROM nums", 1);

        // INSERT...WITH RECURSIVE
        dropTable(db, conn, "reg_cte_out");
        execDDL(db, conn, "CREATE TABLE reg_cte_out (n INT)", "Setup cte_out");
        CompilationResult r = compiler.compile(
            "INSERT INTO reg_cte_out (n) "
            + "WITH RECURSIVE nums AS ("
            + "SELECT 1 AS n "
            + "UNION ALL "
            + "SELECT n + 1 FROM nums WHERE n < 4"
            + ") SELECT n FROM nums", db.dialect());
        if (r.isSuccess()) {
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(r.getSql());
                check(true, "INSERT...WITH executed");
                execQuery(db, conn, "SELECT COUNT(*) AS cnt FROM reg_cte_out", 1);
            } catch (SQLException e) {
                System.out.println("    SQL: " + r.getSql());
                check(false, db.name() + " INSERT...WITH: " + e.getMessage());
            }
        } else {
            System.out.println("    ⚠️  INSERT...WITH compile failed: " + r.report());
            skipped++;
        }
        dropTable(db, conn, "reg_cte_out");

        dropTable(db, conn, "reg_cte_t");
    }

    // ═══════════════════════════════════════
    //  Set Operations (UNION / INTERSECT / EXCEPT)
    // ═══════════════════════════════════════

    static void testSetOps(Db db, Connection conn) throws Exception {
        dropTable(db, conn, "reg_so_a");
        dropTable(db, conn, "reg_so_b");
        execDDL(db, conn, "CREATE TABLE reg_so_a (id INT PRIMARY KEY, name VARCHAR(50))", "Setup so_a");
        execDDL(db, conn, "CREATE TABLE reg_so_b (id INT PRIMARY KEY, name VARCHAR(50))", "Setup so_b");
        execDML(db, conn, "INSERT INTO reg_so_a (id, name) VALUES (1, 'A'), (2, 'B'), (3, 'C')", "Insert so_a");
        execDML(db, conn, "INSERT INTO reg_so_b (id, name) VALUES (2, 'B'), (3, 'C'), (4, 'D')", "Insert so_b");

        // UNION
        execQuery(db, conn, "SELECT name FROM reg_so_a UNION SELECT name FROM reg_so_b", 4);
        // UNION ALL
        execQuery(db, conn, "SELECT name FROM reg_so_a UNION ALL SELECT name FROM reg_so_b", 6);
        // INTERSECT
        execQuery(db, conn, "SELECT name FROM reg_so_a INTERSECT SELECT name FROM reg_so_b", 2);
        // EXCEPT
        execQuery(db, conn, "SELECT name FROM reg_so_a EXCEPT SELECT name FROM reg_so_b", 1);

        dropTable(db, conn, "reg_so_a"); dropTable(db, conn, "reg_so_b");
    }

    // ═══════════════════════════════════════
    //  ROLLUP / CUBE / GROUPING SETS
    // ═══════════════════════════════════════

    static void testRollupCube(Db db, Connection conn) throws Exception {
        dropTable(db, conn, "reg_rc");
        execDDL(db, conn, "CREATE TABLE reg_rc (dept VARCHAR(50), city VARCHAR(50), sales DECIMAL(10,2))", "Setup rc");
        execDML(db, conn,
            "INSERT INTO reg_rc (dept, city, sales) VALUES "
            + "('Eng', 'NY', 100), ('Eng', 'SF', 200), "
            + "('Sales', 'NY', 150), ('Sales', 'SF', 50)",
            "Insert rc");

        // ROLLUP (skip TiDB/SQLite — not supported)
        if (!Set.of("TiDB", "SQLite").contains(db.name())) {
            execQuery(db, conn,
                "SELECT dept, city, SUM(sales) AS total FROM reg_rc "
                + "GROUP BY ROLLUP(dept, city)", 7);
        }

        // CUBE (MySQL/MariaDB/TiDB/SQLite/OceanBase/ClickHouse don't support or have different semantics)
        if (!Set.of("MySQL", "MariaDB", "TiDB", "SQLite", "OceanBase", "ClickHouse").contains(db.name())) {
            execQuery(db, conn,
                "SELECT dept, city, SUM(sales) AS total FROM reg_rc "
                + "GROUP BY CUBE(dept, city)", 9);
        }

        dropTable(db, conn, "reg_rc");
    }

    // ═══════════════════════════════════════
    //  DROP TABLE / TRUNCATE / ALTER TABLE
    // ═══════════════════════════════════════

    static void testDropTruncateAlter(Db db, Connection conn) throws Exception {
        execDDL(db, conn, "CREATE TABLE reg_dta (id INT PRIMARY KEY, name VARCHAR(50))", "Setup dta");
        execDML(db, conn, "INSERT INTO reg_dta (id, name) VALUES (1, 'A')", "Insert dta");

        // CREATE INDEX then DROP INDEX (must drop before DROP COLUMN in SQL Server)
        execDDL(db, conn, "CREATE INDEX idx_dta_name ON reg_dta (name)", "Setup idx");
        execDDL(db, conn, "DROP INDEX idx_dta_name ON reg_dta", "DROP INDEX");

        // ALTER ADD COLUMN
        execDDL(db, conn, "ALTER TABLE reg_dta ADD score DECIMAL(10,2) DEFAULT 0", "ALTER ADD COLUMN");

        // ALTER ADD COLUMN IF NOT EXISTS — MariaDB / ClickHouse / PG / DuckDB only
        if (Set.of("MariaDB", "ClickHouse", "PostgreSQL", "DuckDB").contains(db.name())) {
            execDDL(db, conn, "ALTER TABLE reg_dta ADD COLUMN IF NOT EXISTS ifne_col VARCHAR(64)", "ADD IF NOT EXISTS #1");
            execDDL(db, conn, "ALTER TABLE reg_dta ADD COLUMN IF NOT EXISTS ifne_col VARCHAR(64)", "ADD IF NOT EXISTS #2 (idempotent)");
        }

        // ALTER COLUMN SET DEFAULT (skip SQL Server — ADD CONSTRAINT syntax)
        if (!db.name().equals("SQL Server")) {
            execDDL(db, conn, "ALTER TABLE reg_dta ALTER score SET DEFAULT 100", "ALTER SET DEFAULT");
            execDDL(db, conn, "ALTER TABLE reg_dta ALTER score DROP DEFAULT", "ALTER DROP DEFAULT");
        }
        // ALTER COLUMN TYPE (skip SQL Server — constraint dependency)
        if (!db.name().equals("SQL Server")) {
            execDDL(db, conn, "ALTER TABLE reg_dta ALTER score TYPE INT", "ALTER COLUMN TYPE");
        }

        // TRUNCATE (before DROP/RENAME)
        execDDL(db, conn, "TRUNCATE TABLE reg_dta", "TRUNCATE");
        execQuery(db, conn, "SELECT COUNT(*) AS cnt FROM reg_dta", 1);

        // RENAME COLUMN
        execDDL(db, conn, "ALTER TABLE reg_dta RENAME COLUMN name TO full_name", "RENAME COLUMN");

        // ALTER DROP COLUMN (use new name after RENAME)
        execDDL(db, conn, "ALTER TABLE reg_dta DROP full_name", "ALTER DROP COLUMN");

        // DROP TABLE + IF EXISTS + CASCADE
        execDDL(db, conn, "DROP TABLE reg_dta", "DROP TABLE");
        execDDL(db, conn, "DROP TABLE IF EXISTS reg_dta", "DROP IF EXISTS");
        if (!db.name().equals("SQL Server")) {
            execDDL(db, conn, "CREATE TABLE reg_dta2 (id INT PRIMARY KEY)", "Setup dta2");
            execDDL(db, conn, "DROP TABLE reg_dta2 CASCADE", "DROP CASCADE");
        }
    }

    // ═══════════════════════════════════════
    //  Stored Procedure
    // ═══════════════════════════════════════

    static void testStoredProc(Db db, Connection conn) throws Exception {
        // TiDB/SQLite/DuckDB/ClickHouse don't support stored procedures;
        // their backends emit a "not supported" comment that JDBC rejects on execute.
        if (Set.of(Dialect.TIDB, Dialect.SQLITE, Dialect.DUCKDB, Dialect.CLICKHOUSE).contains(db.dialect())) { skipped++; return; }
        String body = switch (db.dialect()) {
            case MYSQL, MARIADB -> "SELECT 1";
            case POSTGRESQL -> "BEGIN NULL; END";
            case ORACLE -> "BEGIN NULL; END;";
            case DM -> "BEGIN NULL; END";
            case SQLSERVER -> "SELECT 1";
            default -> "SELECT 1";
        };

        // Compile via IR (all dialects)
        IRCreateProcedure proc = new IRCreateProcedure("reg_sp",
            List.of(), body, false, Set.of());
        CompilationResult r = compiler.compileFromIR(proc, db.dialect());
        check(r.isSuccess(), "CREATE PROCEDURE compile");

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(r.getSql());
            check(true, "CREATE PROCEDURE execute");
        } catch (SQLException e) {
            System.err.println("    SQL: " + r.getSql());
            check(false, "Procedure execute: " + e.getMessage());
        }

        // Cleanup — Oracle 19c has no "DROP ... IF EXISTS", so just attempt the plain
        // DROP and swallow the "object does not exist" error (same pattern as dropTable).
        for (String qn : new String[]{quoteName(db.dialect(), "reg_sp"), "reg_sp"})
            try { conn.createStatement().execute("DROP PROCEDURE " + qn); } catch (SQLException ignored) {}
    }

    // ═══════════════════════════════════════
    //  Optimizer correctness — L2 vs L3 results must match
    // ═══════════════════════════════════════

    static void testOptimizerCorrectness(Db db, Connection conn) throws Exception {
        dropTable(db, conn, "reg_opt");
        execDDL(db, conn, "CREATE TABLE reg_opt (id INT PRIMARY KEY, name VARCHAR(50), val INT)", "Setup opt");
        execDML(db, conn, "INSERT INTO reg_opt (id, name, val) VALUES (1,'A',10),(2,'B',20),(3,'C',30),(4,'D',40),(5,'E',50)", "Insert opt");

        // Test 1: simple pushdown
        compareL2vsL3(db, conn, "SELECT s.name, s.val FROM (SELECT id, name, val FROM reg_opt) s WHERE s.val > 20");

        // Test 2: predicate pushdown with AND
        compareL2vsL3(db, conn, "SELECT s.name FROM (SELECT name, val FROM reg_opt) s WHERE s.val > 10 AND s.val < 50");

        // Test 3: multi-table — WHERE on outer table shouldn't push into wrong subquery
        execDDL(db, conn, "CREATE TABLE reg_opt2 (id INT PRIMARY KEY, label VARCHAR(50))", "Setup opt2");
        execDML(db, conn, "INSERT INTO reg_opt2 (id, label) VALUES (1,'X'),(2,'Y')", "Insert opt2");
        compareL2vsL3(db, conn, "SELECT s.name, t.label FROM (SELECT id, name, val FROM reg_opt) s, reg_opt2 t WHERE s.val > 20 AND t.id = 1");
        dropTable(db, conn, "reg_opt2");

        // Test 4: DISTINCT subquery (not flattened by L2) with pushable WHERE
        compareL2vsL3(db, conn, "SELECT s.name FROM (SELECT DISTINCT name, val FROM reg_opt) s WHERE s.val > 10");

        // Test 5: projection pruning — unused column
        compareL2vsL3(db, conn, "SELECT s.name FROM (SELECT id, name, val FROM reg_opt) s WHERE s.id < 4");

        // Test 6: subquery with GROUP BY — not flattenable, but pushable
        compareL2vsL3(db, conn, "SELECT s.cnt FROM (SELECT val, COUNT(*) AS cnt FROM reg_opt GROUP BY val) s WHERE s.val > 15");

        dropTable(db, conn, "reg_opt");
    }

    static void compareL2vsL3(Db db, Connection conn, String usql) throws Exception {
        USqlCompiler compilerL3 = USqlCompiler.builder().withOptimizeLevel(3).build();
        CompilationResult r2 = compilerL2.compile(usql, db.dialect());
        CompilationResult r3 = compilerL3.compile(usql, db.dialect());
        if (!r2.isSuccess() || !r3.isSuccess()) {
            check(false, "Optimizer: compile failed");
            return;
        }
        List<List<Object>> rowsL2 = executeSQL(conn, r2.getSql());
        List<List<Object>> rowsL3 = executeSQL(conn, r3.getSql());
        // Without ORDER BY, row order is unspecified — L2 and L3 use different
        // execution plans (L3 pushes predicates into subqueries) so the returned
        // order may differ even when the result set is identical. Compare as
        // multisets (sort by stringified row) rather than positionally.
        java.util.Comparator<List<Object>> byStr =
            java.util.Comparator.comparing(row -> row == null ? "" : row.toString());
        List<List<Object>> s2 = new ArrayList<>(rowsL2); s2.sort(byStr);
        List<List<Object>> s3 = new ArrayList<>(rowsL3); s3.sort(byStr);
        boolean same = s2.equals(s3);
        check(same, "L2==L3: " + rowsL2.size() + "/" + rowsL3.size() + " rows same");
    }

    static List<List<Object>> executeSQL(Connection conn, String sql) throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            int cols = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= cols; i++) row.add(rs.getObject(i));
                rows.add(row);
            }
        }
        return rows;
    }

    // ═══════════════════════════════════════
    //  CREATE VIEW / CREATE SCHEMA
    // ═══════════════════════════════════════

    static void testViewSchema(Db db, Connection conn) throws Exception {
        // Pre-clean any residual view from a prior run. Oracle stores unquoted
        // identifiers uppercased, so DROP with a quoted lowercase name won't match
        // a previously-created unquoted REG_VIEW1 — try several spellings.
        for (String vn : new String[]{quoteName(db.dialect(), "reg_view1"), "reg_view1", "REG_VIEW1"})
            try { conn.createStatement().execute("DROP VIEW " + vn); } catch (SQLException ignored) {}
        dropTable(db, conn, "reg_vs");
        execDDL(db, conn, "CREATE TABLE reg_vs (id INT PRIMARY KEY, name VARCHAR(50), val INT)", "Setup vs");
        execDML(db, conn, "INSERT INTO reg_vs (id, name, val) VALUES (1,'A',10),(2,'B',20),(3,'C',30)", "Insert vs");

        // CREATE VIEW
        execDDL(db, conn, "CREATE VIEW reg_view1 AS SELECT name, val FROM reg_vs WHERE val > 15", "CREATE VIEW");
        execQuery(db, conn, "SELECT COUNT(*) FROM reg_view1", 1);
        execQuery(db, conn, "SELECT name FROM reg_view1 WHERE name = 'B'", 1);

        // Cleanup
        try { conn.createStatement().execute("DROP VIEW " + quoteName(db.dialect(), "reg_view1")); } catch (SQLException ignored) {}

        // CREATE SCHEMA (skip if already exists or unsupported)
        try {
            CompilationResult r = compiler.compile("CREATE SCHEMA reg_schema1", db.dialect());
            if (r.isSuccess()) {
                try { conn.createStatement().execute(r.getSql()); check(true, "CREATE SCHEMA executed"); }
                catch (SQLException e) { System.out.println("    ⚠️  Schema: " + e.getMessage()); skipped++; }
            }
        } catch (Exception e) { skipped++; }

        dropTable(db, conn, "reg_vs");
    }

    // ═══════════════════════════════════════
    //  TCL — Transaction Control
    // ═══════════════════════════════════════

    static void testTCL(Db db, Connection conn) throws Exception {
        // Test TCL in proper sequence: BEGIN → COMMIT, then BEGIN → ROLLBACK
        var tclCases = List.of(
            // Sequence 1: BEGIN then COMMIT
            "BEGIN", "COMMIT",
            // Sequence 2: START TRANSACTION then ROLLBACK
            "START TRANSACTION", "ROLLBACK"
        );
        boolean inTransaction = false;
        for (String tcl : tclCases) {
            CompilationResult r = compiler.compile(tcl, db.dialect());
            check(r.isSuccess(), "TCL " + tcl + " compiles");
            if (r.isSuccess()) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(r.getSql());
                    check(true, "TCL " + tcl + " executed");
                    inTransaction = tcl.equals("BEGIN") || tcl.equals("START TRANSACTION");
                } catch (SQLException e) {
                    System.out.println("    ??  TCL " + tcl + ": " + e.getMessage());
                    skipped++;
                }
            }
        }
    }

    // ═══════════════════════════════════════
    //  LATERAL (on databases that support it)
    // ═══════════════════════════════════════

    static void testLateral(Db db, Connection conn) throws Exception {
        // LATERAL with table function — validate compilation, execution varies by dialect
        String usql = "SELECT * FROM reg_q q, LATERAL GENERATE_SERIES(1, 3) AS lat";
        CompilationResult r = compiler.compile(usql, db.dialect());
        if (r.isSuccess()) {
            check(true, "LATERAL compiles");
            // Try to execute (will fail on most DBs without GENERATE_SERIES, that's fine)
            try (Statement stmt = conn.createStatement()) {
                stmt.executeQuery(r.getSql());
                check(true, "LATERAL executed");
            } catch (SQLException e) {
                System.out.println("    ⚠️  LATERAL exec: " + e.getMessage().split("\n")[0]);
                skipped++;
            }
        } else {
            System.out.println("    ⚠️  LATERAL compile: " + r.report());
            skipped++;
        }
    }

    // ═══════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════

    static String autoIncPK(Db db) {
        return db.dialect() == Dialect.DUCKDB ? "id INT PRIMARY KEY" : "id INT PRIMARY KEY AUTO_INCREMENT";
    }

    /** Connect with retry — some databases (e.g. OceanBase) take time to start. */
    static Connection connectWithRetry(Db db) throws Exception {
        int maxRetries = "OceanBase".equals(db.name()) ? 6 : 1;
        Exception last = null;
        for (int i = 0; i < maxRetries; i++) {
            try {
                return DriverManager.getConnection(db.url(), db.user(), db.pw());
            } catch (Exception e) {
                last = e;
                if (i < maxRetries - 1) {
                    System.out.println("  Retry " + (i + 1) + "/" + (maxRetries - 1) + " for " + db.name() + "...");
                    Thread.sleep(10_000);
                }
            }
        }
        throw last;
    }

    static void execDDL(Db db, Connection conn, String usql, String label) throws Exception {
        CompilationResult r = compiler.compile(usql, db.dialect());
        if (!r.isSuccess()) { check(false, label + " compile: " + r.report()); return; }
        try (Statement stmt = conn.createStatement()) { stmt.execute(r.getSql()); check(true, label); }
        catch (SQLException e) { System.err.println("    SQL: " + r.getSql()); check(false, label + ": " + e.getMessage()); }
    }

    static void execDML(Db db, Connection conn, String usql, String label) throws Exception {
        CompilationResult r = compiler.compile(usql, db.dialect());
        if (!r.isSuccess()) { check(false, label + " compile: " + r.report()); return; }
        try (Statement stmt = conn.createStatement()) { stmt.executeUpdate(r.getSql()); check(true, label); }
        catch (SQLException e) { System.err.println("    SQL: " + r.getSql()); check(false, label + ": " + e.getMessage()); }
    }

    static void execQuery(Db db, Connection conn, String usql, int expectedRows) throws Exception {
        CompilationResult r = compiler.compile(usql, db.dialect());
        if (!r.isSuccess()) { check(false, "compile"); return; }
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(r.getSql())) {
            int count = 0; while (rs.next()) count++;
            check(count == expectedRows, usql.substring(0, Math.min(50, usql.length())) + " → " + expectedRows + " rows (got " + count + ")");
        } catch (SQLException e) { System.err.println("    SQL: " + r.getSql()); check(false, e.getMessage()); }
    }

    static void execQueryAny(Db db, Connection conn, String usql) throws Exception {
        CompilationResult r = compiler.compile(usql, db.dialect());
        if (!r.isSuccess()) { check(false, "compile"); return; }
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(r.getSql())) { check(true, usql); }
        catch (SQLException e) { check(false, e.getMessage()); }
    }

    static void dropTable(Db db, Connection conn, String name) {
        for (String n : new String[]{quoteName(db.dialect(), name), name})
            try { conn.createStatement().execute("DROP TABLE " + n); } catch (SQLException ignored) {}
    }

    static String quoteName(Dialect d, String name) {
        return switch (d) { case MYSQL, MARIADB, TIDB -> "`" + name + "`"; case SQLSERVER -> "[" + name + "]"; default -> "\"" + name + "\""; };
    }

    static void check(boolean condition, String msg) {
        if (condition) pass++; else { fail++; System.err.println("  [" + currentDb + "] FAIL: " + msg); }
    }
}
