package com.usql;

import com.usql.ast.USqlAst.Statement;
import com.usql.dialect.Dialect;
import com.usql.parser.AstBuilder;
import com.usql.verify.SemanticVerifier.ColumnMeta;
import com.usql.verify.SemanticVerifier.VerificationReport;
import com.usql.verify.SemanticVerifier.VerificationReport.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Dual-execution semantic verification:
 *   H2 (reference) vs real databases via Docker.
 *
 * Requires: Docker containers running (see docker/docker-compose.yml)
 *
 * Run: mvn test-compile exec:java -Dexec.mainClass=com.usql.SemanticVerificationTest
 */
public class SemanticVerificationTest {

    // Docker connection configs
    record DbConfig(String jdbcUrl, String user, String password, String driverClass) {}

    private static final Map<Dialect, DbConfig> CONFIGS = Map.of(
        Dialect.MYSQL, new DbConfig(
            "jdbc:mysql://localhost:3306/login_db?useSSL=false&allowPublicKeyRetrieval=true",
            "login_user", "login123", "com.mysql.cj.jdbc.Driver"),
        Dialect.POSTGRESQL, new DbConfig(
            "jdbc:postgresql://localhost:5432/mydb",
            "postgres", "postgres123", "org.postgresql.Driver"),
        Dialect.ORACLE, new DbConfig(
            "jdbc:oracle:thin:@localhost:1521/orclpdb1",
            "system", "oracle123", "oracle.jdbc.OracleDriver"),
        Dialect.DM, new DbConfig(
            "jdbc:dm://localhost:5236",
            "SYSDBA", "dm12345678", "dm.jdbc.driver.DmDriver")
    );

    private USqlCompiler compiler;
    private Connection h2Conn;

    public static void main(String[] args) {
        var test = new SemanticVerificationTest();
        int passed = 0, failed = 0, skipped = 0;

        try {
            test.setup();
        } catch (Exception e) {
            System.out.println("FATAL: Cannot initialize H2: " + e.getMessage());
            return;
        }

        // Test queries — comprehensive coverage
        Map<String, String> queries = new LinkedHashMap<>();
        queries.put("1. Simple SELECT",       "SELECT id, name FROM departments ORDER BY id");
        queries.put("2. WHERE filter",        "SELECT name, salary FROM employees WHERE salary > 50000 ORDER BY name");
        queries.put("3. COUNT aggregate",     "SELECT dept_id, COUNT(*) AS cnt FROM employees GROUP BY dept_id ORDER BY dept_id");
        queries.put("4. AVG aggregate",       "SELECT dept_id, AVG(salary) AS avg_sal FROM employees GROUP BY dept_id ORDER BY dept_id");
        queries.put("5. SUM aggregate",       "SELECT dept_id, SUM(salary) AS total FROM employees GROUP BY dept_id ORDER BY dept_id");
        queries.put("6. MIN/MAX aggregate",   "SELECT dept_id, MIN(salary) AS lo, MAX(salary) AS hi FROM employees GROUP BY dept_id ORDER BY dept_id");
        queries.put("7. INNER JOIN",          "SELECT d.name, e.name FROM departments d JOIN employees e ON d.id = e.dept_id ORDER BY d.name, e.name");
        queries.put("8. LEFT JOIN",           "SELECT d.name, COUNT(e.id) AS cnt FROM departments d LEFT JOIN employees e ON d.id = e.dept_id GROUP BY d.name ORDER BY d.name");
        queries.put("9. Three-table JOIN",    "SELECT d.name, e1.name AS emp1, e2.name AS emp2 FROM departments d JOIN employees e1 ON d.id = e1.dept_id JOIN employees e2 ON d.id = e2.dept_id WHERE e1.id < e2.id ORDER BY d.name");
        queries.put("10. LIMIT OFFSET",       "SELECT name FROM employees ORDER BY name LIMIT 3 OFFSET 1");
        queries.put("11. DISTINCT",           "SELECT DISTINCT dept_id FROM employees ORDER BY dept_id");
        queries.put("12. ORDER BY DESC",      "SELECT name, salary FROM employees ORDER BY salary DESC, name ASC");
        queries.put("13. IS NULL filter",     "SELECT name FROM employees WHERE dept_id IS NULL ORDER BY name");
        queries.put("14. IS NOT NULL",        "SELECT name FROM employees WHERE dept_id IS NOT NULL ORDER BY name");
        queries.put("15. BETWEEN",            "SELECT name, salary FROM employees WHERE salary BETWEEN 50000 AND 80000 ORDER BY salary");
        queries.put("16. LIKE pattern",       "SELECT name FROM employees WHERE name LIKE 'A%' ORDER BY name");
        queries.put("17. IN subquery",        "SELECT name FROM employees WHERE dept_id IN (SELECT id FROM departments WHERE name = 'Engineering') ORDER BY name");
        queries.put("18. NOT IN subquery",    "SELECT name FROM employees WHERE dept_id NOT IN (SELECT id FROM departments WHERE name = 'HR') ORDER BY name");
        queries.put("19. EXISTS subquery",    "SELECT d.name FROM departments d WHERE EXISTS (SELECT 1 FROM employees e WHERE e.dept_id = d.id) ORDER BY d.name");
        queries.put("20. Scalar subquery",    "SELECT name, salary, (SELECT AVG(salary) FROM employees) AS avg_all FROM employees ORDER BY name");
        queries.put("21. UNION ALL",          "SELECT name FROM employees WHERE dept_id = 1 UNION ALL SELECT name FROM employees WHERE dept_id = 2 ORDER BY name");
        queries.put("22. CASE expression",    "SELECT name, CASE WHEN salary > 70000 THEN 'High' WHEN salary > 50000 THEN 'Medium' ELSE 'Low' END AS level FROM employees ORDER BY name");
        queries.put("23. Expression no FROM", "SELECT 1 + 2 AS sum, LENGTH('hello') AS len, UPPER('test') AS up");
        queries.put("24. COALESCE",           "SELECT name, COALESCE(dept_id, 0) AS dept FROM employees ORDER BY name");
        queries.put("25. Double aggregate",   "SELECT COUNT(*) AS cnt, AVG(salary) AS avg_sal, SUM(salary) AS total, MIN(salary) AS min_sal, MAX(salary) AS max_sal FROM employees WHERE dept_id IS NOT NULL");
        // KEEP — Oracle native; non-Oracle polyfill requires no GROUP BY (OVER() has no PARTITION BY)
        queries.put("26. KEEP LAST + GROUP BY",  "SELECT dept_id, MAX(salary) KEEP (DENSE_RANK LAST ORDER BY hire_date) AS last_salary FROM employees GROUP BY dept_id ORDER BY dept_id");
        queries.put("27. KEEP FIRST + GROUP BY", "SELECT dept_id, MIN(salary) KEEP (DENSE_RANK FIRST ORDER BY hire_date) AS first_salary FROM employees GROUP BY dept_id ORDER BY dept_id");
        queries.put("28. KEEP DESC (no GROUP BY)", "SELECT MAX(name) KEEP (DENSE_RANK LAST ORDER BY salary DESC) AS top_paid FROM employees");

        // Run against each available target
        for (Dialect target : List.of(Dialect.MYSQL, Dialect.POSTGRESQL, Dialect.ORACLE, Dialect.DM)) {
            System.out.println("\n=== " + target.displayName() + " ===");
            try (Connection targetConn = test.connect(target)) {
                if (targetConn == null) {
                    System.out.println("  SKIP — driver not available");
                    continue;
                }
                test.setupTargetSchema(targetConn, target);

                for (var entry : queries.entrySet()) {
                    String label = entry.getKey();
                    String usql = entry.getValue();

                    try {
                        VerificationReport report = test.verify(usql, target, targetConn);
                        if (report.passed()) {
                            System.out.println("  PASS: " + label);
                            passed++;
                        } else {
                            System.out.println("  FAIL: " + label);
                            for (var m : report.mismatches()) {
                                System.out.println("    [" + m.severity() + "] " + m.detail());
                            }
                            failed++;
                        }
                    } catch (Exception e) {
                        System.out.println("  ERROR: " + label + " — " + e.getMessage());
                        failed++;
                    }
                }

                test.cleanupTargetSchema(targetConn);
            } catch (Exception e) {
                System.out.println("  SKIP — cannot connect: " + e.getMessage());
                skipped++;
            }
        }

        System.out.println("\n=== Summary: " + passed + " passed, " + failed + " failed, " + skipped + " skipped ===");
    }

    // ══════════════════════════════════════════════════
    //  Setup
    // ══════════════════════════════════════════════════

    void setup() throws SQLException {
        h2Conn = DriverManager.getConnection("jdbc:h2:mem:verify_ref;DB_CLOSE_DELAY=-1");
        compiler = USqlCompiler.builder().build();
        setupH2Schema();
    }

    private void setupH2Schema() throws SQLException {
        try (java.sql.Statement stmt = h2Conn.createStatement()) {
            stmt.execute("CREATE TABLE departments (id INT PRIMARY KEY, name VARCHAR(100))");
            stmt.execute("CREATE TABLE employees (" +
                "id INT PRIMARY KEY, name VARCHAR(100), dept_id INT, " +
                "salary DECIMAL(10,2), hire_date DATE, active BOOLEAN)");

            stmt.execute("INSERT INTO departments VALUES (1, 'Engineering')");
            stmt.execute("INSERT INTO departments VALUES (2, 'Sales')");
            stmt.execute("INSERT INTO departments VALUES (3, 'Marketing')");
            stmt.execute("INSERT INTO departments VALUES (4, 'HR')");

            stmt.execute("INSERT INTO employees VALUES (1, 'Alice', 1, 80000.00, DATE '2020-01-15', TRUE)");
            stmt.execute("INSERT INTO employees VALUES (2, 'Bob', 1, 75000.00, DATE '2021-06-01', TRUE)");
            stmt.execute("INSERT INTO employees VALUES (3, 'Charlie', 2, 60000.00, DATE '2019-03-20', TRUE)");
            stmt.execute("INSERT INTO employees VALUES (4, 'Diana', 2, 55000.00, DATE '2022-09-10', FALSE)");
            stmt.execute("INSERT INTO employees VALUES (5, 'Eve', 1, 90000.00, DATE '2018-11-30', TRUE)");
            stmt.execute("INSERT INTO employees VALUES (6, 'Frank', 3, 45000.00, DATE '2023-02-14', TRUE)");
            stmt.execute("INSERT INTO employees VALUES (7, 'Grace', NULL, 50000.00, DATE '2021-08-05', TRUE)");
            stmt.execute("INSERT INTO employees VALUES (8, 'Henry', 3, 48000.00, DATE '2022-12-01', FALSE)");
        }
    }

    void setupTargetSchema(Connection conn, Dialect dialect) throws Exception {
        // Use compiler-generated DDL so table/column names match generated queries
        USqlCompiler compiler = USqlCompiler.builder().build();

        // Drop first — try both quoted and unquoted for Oracle
        try (java.sql.Statement stmt = conn.createStatement()) {
            for (String tbl : List.of("employees", "departments")) {
                try { stmt.execute("DROP TABLE " + tbl); } catch (SQLException ignored) {}
                try { stmt.execute("DROP TABLE \"" + tbl + "\""); } catch (SQLException ignored) {}
            }
        }

        // Create via compiler
        for (String ddl : List.of(
            "CREATE TABLE departments (id INT PRIMARY KEY, name VARCHAR(100))",
            "CREATE TABLE employees (id INT PRIMARY KEY, name VARCHAR(100), dept_id INT, salary DECIMAL(10,2), hire_date DATE, active BOOLEAN)"
        )) {
            var ast = com.usql.parser.AstBuilder.buildSingle(ddl);
            var result = compiler.compileFromAst(ast, dialect);
            try (java.sql.Statement stmt = conn.createStatement()) {
                stmt.execute(result.getSql());
            }
        }

        // Insert test data — use quoted identifiers for Oracle/DM case-sensitivity
        boolean quoted = dialect == Dialect.ORACLE || dialect == Dialect.DM;
        String dep = quoted ? "\"departments\"" : "departments";
        String emp = quoted ? "\"employees\"" : "employees";
        String bt = dialect == Dialect.POSTGRESQL ? "TRUE" : "1";
        String bf = dialect == Dialect.POSTGRESQL ? "FALSE" : "0";
        // Oracle/DM use DATE '...', MySQL/PG accept bare 'YYYY-MM-DD'
        String dt = (dialect == Dialect.ORACLE || dialect == Dialect.DM) ? "DATE " : "";

        try (java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO " + dep + " VALUES (1, 'Engineering')");
            stmt.execute("INSERT INTO " + dep + " VALUES (2, 'Sales')");
            stmt.execute("INSERT INTO " + dep + " VALUES (3, 'Marketing')");
            stmt.execute("INSERT INTO " + dep + " VALUES (4, 'HR')");
            stmt.execute("INSERT INTO " + emp + " VALUES (1, 'Alice', 1, 80000.00, " + dt + "'2020-01-15', " + bt + ")");
            stmt.execute("INSERT INTO " + emp + " VALUES (2, 'Bob', 1, 75000.00, " + dt + "'2021-06-01', " + bt + ")");
            stmt.execute("INSERT INTO " + emp + " VALUES (3, 'Charlie', 2, 60000.00, " + dt + "'2019-03-20', " + bt + ")");
            stmt.execute("INSERT INTO " + emp + " VALUES (4, 'Diana', 2, 55000.00, " + dt + "'2022-09-10', " + bf + ")");
            stmt.execute("INSERT INTO " + emp + " VALUES (5, 'Eve', 1, 90000.00, " + dt + "'2018-11-30', " + bt + ")");
            stmt.execute("INSERT INTO " + emp + " VALUES (6, 'Frank', 3, 45000.00, " + dt + "'2023-02-14', " + bt + ")");
            stmt.execute("INSERT INTO " + emp + " VALUES (7, 'Grace', NULL, 50000.00, " + dt + "'2021-08-05', " + bt + ")");
            stmt.execute("INSERT INTO " + emp + " VALUES (8, 'Henry', 3, 48000.00, " + dt + "'2022-12-01', " + bf + ")");
        }
    }

    void cleanupTargetSchema(Connection conn) throws SQLException {
        try (java.sql.Statement stmt = conn.createStatement()) {
            try { stmt.execute("DROP TABLE employees"); } catch (SQLException ignored) {}
            try { stmt.execute("DROP TABLE departments"); } catch (SQLException ignored) {}
        }
    }

    // ══════════════════════════════════════════════════
    //  Connection
    // ══════════════════════════════════════════════════

    Connection connect(Dialect dialect) {
        DbConfig cfg = CONFIGS.get(dialect);
        if (cfg == null) return null;
        try {
            Class.forName(cfg.driverClass());
            return DriverManager.getConnection(cfg.jdbcUrl(), cfg.user(), cfg.password());
        } catch (ClassNotFoundException e) {
            System.out.println("  Driver not found: " + cfg.driverClass());
            return null;
        } catch (SQLException e) {
            System.out.println("  Connection failed: " + e.getMessage());
            return null;
        }
    }

    // ══════════════════════════════════════════════════
    //  Verify
    // ══════════════════════════════════════════════════

    VerificationReport verify(String usql, Dialect target, Connection targetConn) throws Exception {
        // Compile U-SQL to target dialect
        Statement ast = AstBuilder.buildSingle(usql);
        CompilationResult result = compiler.compileFromAst(ast, target);

        if (!result.isSuccess()) {
            return new VerificationReport(false,
                List.of(new Mismatch(-1, -1, "COMPILE", result.report(), Severity.HARD_MISMATCH)),
                List.of(), "", "", "");
        }

        String targetSQL = result.getSql();

        // KEEP is Oracle-only — skip on other dialects
        if (usql.contains("KEEP") && target != Dialect.ORACLE) {
            return new VerificationReport(true, List.of(), List.of(),
                usql, targetSQL, "KEEP — Oracle-only, skipped on " + target.name());
        }

        // Standard queries: dual-execution semantic verification
        String refSQL = compiler.compileFromAst(ast, Dialect.H2).getSql();

        List<List<Object>> refRows;
        List<ColumnMeta> refColumns;
        try (java.sql.Statement stmt = h2Conn.createStatement();
             ResultSet rs = stmt.executeQuery(refSQL)) {
            refColumns = extractColumns(rs);
            refRows = extractRows(rs);
        }

        // Execute on target
        List<List<Object>> tgtRows;
        List<ColumnMeta> tgtColumns;
        try (java.sql.Statement stmt = targetConn.createStatement();
             ResultSet rs = stmt.executeQuery(targetSQL)) {
            tgtColumns = extractColumns(rs);
            tgtRows = extractRows(rs);
        }

        // Sort both result sets for stable comparison (handles NULL ordering differences)
        Comparator<List<Object>> rowCmp = (a, b) -> {
            for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
                if (a.get(i) == null && b.get(i) == null) continue;
                if (a.get(i) == null) return -1;
                if (b.get(i) == null) return 1;
                int c = a.get(i).toString().compareTo(b.get(i).toString());
                if (c != 0) return c;
            }
            return Integer.compare(a.size(), b.size());
        };
        refRows.sort(rowCmp);
        tgtRows.sort(rowCmp);

        // Compare
        List<Mismatch> mismatches = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (refRows.size() != tgtRows.size()) {
            mismatches.add(new Mismatch(-1, -1, "ROW_COUNT",
                "Row count: H2=" + refRows.size() + " vs " + target + "=" + tgtRows.size(),
                Severity.HARD_MISMATCH));
        }

        int maxRows = Math.min(refRows.size(), tgtRows.size());
        for (int row = 0; row < maxRows; row++) {
            var refRow = refRows.get(row);
            var tgtRow = tgtRows.get(row);
            int maxCols = Math.min(refRow.size(), tgtRow.size());

            for (int col = 0; col < maxCols; col++) {
                String colName = col < refColumns.size() ? refColumns.get(col).name() : "col" + col;
                Object refVal = refRow.get(col);
                Object tgtVal = tgtRow.get(col);

                if (refVal == null && tgtVal == null) continue;
                if (refVal == null || tgtVal == null) {
                    mismatches.add(new Mismatch(row, col, colName,
                        "NULL mismatch: " + refVal + " vs " + tgtVal, Severity.HARD_MISMATCH));
                    continue;
                }

                // Numeric comparison with tolerance
                if (refVal instanceof Number rn && tgtVal instanceof Number tn) {
                    double diff = Math.abs(rn.doubleValue() - tn.doubleValue());
                    if (diff > 0.01) {
                        mismatches.add(new Mismatch(row, col, colName,
                            "Value mismatch: " + refVal + " vs " + tgtVal, Severity.HARD_MISMATCH));
                    }
                } else if (!refVal.toString().equals(tgtVal.toString())) {
                    mismatches.add(new Mismatch(row, col, colName,
                        "Value mismatch: " + refVal + " vs " + tgtVal, Severity.HARD_MISMATCH));
                }
            }
        }

        boolean passed = mismatches.isEmpty();
        return new VerificationReport(passed, mismatches, warnings, refSQL, targetSQL, target.displayName());
    }

    // ══════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════

    private List<ColumnMeta> extractColumns(ResultSet rs) throws SQLException {
        var meta = rs.getMetaData();
        var cols = new ArrayList<ColumnMeta>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            cols.add(new ColumnMeta(meta.getColumnName(i), meta.getColumnLabel(i), null, i));
        }
        return cols;
    }

    private List<List<Object>> extractRows(ResultSet rs) throws SQLException {
        var rows = new ArrayList<List<Object>>();
        int cols = rs.getMetaData().getColumnCount();
        while (rs.next()) {
            var row = new ArrayList<Object>();
            for (int i = 1; i <= cols; i++) {
                row.add(rs.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }
}
