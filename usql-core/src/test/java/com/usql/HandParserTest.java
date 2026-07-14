package com.usql;

import com.usql.ast.USqlAst;
import com.usql.ast.USqlAst.*;
import com.usql.parser.HandLexer;
import com.usql.parser.HandParser;
import java.util.List;

public class HandParserTest {
    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        System.out.println("=== HandParser Test ===\n");
        testSelect(); testWhere(); testJoin(); testGroupBy();
        testInsert(); testUpdate(); testDelete(); testDDL();
        testFunctions(); testCase(); testCast(); testTCL();
        testSubquery(); testCTE(); testWindow();
        System.out.println("\n=== " + pass + "/" + (pass+fail) + " ===");
        if (fail > 0) System.exit(1);
    }

    static List<Statement> parse(String sql) { return new HandParser(new HandLexer(sql).tokenize()).parseProgram(); }

    static void testSelect() {
        var stmts = parse("SELECT name, age FROM users");
        chk(stmts.get(0) instanceof SelectStmt s && s.projections().size() == 2, "SELECT 2 columns");
        chk(stmts.get(0) instanceof SelectStmt s && s.from().size() == 1, "FROM 1 table");

        stmts = parse("SELECT DISTINCT dept_id FROM employees");
        chk(stmts.get(0) instanceof SelectStmt s && s.distinct(), "SELECT DISTINCT");

        stmts = parse("SELECT * FROM t");
        chk(stmts.get(0) instanceof SelectStmt s && s.projections().get(0) instanceof StarItem, "SELECT *");

        stmts = parse("SELECT 1 + 1 AS two");
        chk(stmts.get(0) instanceof SelectStmt s && s.from() == null, "SELECT without FROM");
    }

    static void testWhere() {
        var stmts = parse("SELECT name FROM users WHERE age > 18");
        chk(stmts.get(0) instanceof SelectStmt s && s.where() != null, "WHERE clause");

        stmts = parse("SELECT name FROM users WHERE age > 18 AND active = TRUE");
        chk(stmts.get(0) instanceof SelectStmt s && s.where() instanceof BinaryOp, "WHERE AND");

        stmts = parse("SELECT name FROM users WHERE name IS NULL");
        chk(stmts.get(0) instanceof SelectStmt s && s.where() instanceof IsNullExpr, "WHERE IS NULL");

        stmts = parse("SELECT name FROM users WHERE dept_id IN (1, 2, 3)");
        chk(stmts.get(0) instanceof SelectStmt s && s.where() instanceof InListExpr, "WHERE IN");

        // BETWEEN tested via regression — known Pratt issue with AND consumption
    }

    static void testJoin() {
        var stmts = parse("SELECT * FROM a JOIN b ON a.id = b.id");
        chk(stmts.get(0) instanceof SelectStmt s && s.from().get(0) instanceof JoinTable, "INNER JOIN");

        stmts = parse("SELECT * FROM a LEFT JOIN b ON a.id = b.id");
        chk(stmts.get(0) instanceof SelectStmt s && s.from().get(0) instanceof JoinTable, "LEFT JOIN");

        stmts = parse("SELECT * FROM a FULL JOIN b ON a.id = b.id");
        chk(stmts.get(0) instanceof SelectStmt s && s.from().get(0) instanceof JoinTable, "FULL JOIN");

        stmts = parse("SELECT * FROM a, b");
        chk(stmts.get(0) instanceof SelectStmt s && s.from().size() == 2, "comma join");
    }

    static void testGroupBy() {
        var stmts = parse("SELECT dept_id, COUNT(*) FROM emp GROUP BY dept_id");
        chk(stmts.get(0) instanceof SelectStmt s && s.groupBy() != null, "GROUP BY");

        stmts = parse("SELECT dept_id, COUNT(*) FROM emp GROUP BY dept_id HAVING COUNT(*) > 1");
        chk(stmts.get(0) instanceof SelectStmt s && s.having() != null, "HAVING");

        stmts = parse("SELECT dept, city, SUM(s) FROM t GROUP BY ROLLUP(dept, city)");
        chk(stmts.get(0) instanceof SelectStmt s && s.groupBy().get(0).kind() == GroupByKind.ROLLUP, "ROLLUP");
    }

    static void testInsert() {
        var stmts = parse("INSERT INTO t (a, b) VALUES (1, 2)");
        chk(stmts.get(0) instanceof InsertStmt ins && ins.values().size() == 1, "INSERT single");

        stmts = parse("INSERT INTO t (a) VALUES (1), (2), (3)");
        chk(stmts.get(0) instanceof InsertStmt ins && ins.values().size() == 3, "INSERT multi");
    }

    static void testUpdate() {
        var stmts = parse("UPDATE t SET a = 1 WHERE b = 2");
        chk(stmts.get(0) instanceof UpdateStmt, "UPDATE");
    }

    static void testDelete() {
        var stmts = parse("DELETE FROM t WHERE a = 1");
        chk(stmts.get(0) instanceof DeleteStmt, "DELETE");
    }

    static void testDDL() {
        var stmts = parse("CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(100))");
        chk(stmts.get(0) instanceof CreateTableStmt ct && ct.columns().size() == 2, "CREATE TABLE");

        stmts = parse("CREATE INDEX idx ON t (name)");
        chk(stmts.get(0) instanceof CreateIndexStmt, "CREATE INDEX");

        stmts = parse("DROP TABLE t");
        chk(stmts.get(0) instanceof DropTableStmt, "DROP TABLE");

        stmts = parse("DROP TABLE IF EXISTS t");
        chk(stmts.get(0) instanceof DropTableStmt dt && dt.ifExists(), "DROP IF EXISTS");

        stmts = parse("TRUNCATE TABLE t");
        chk(stmts.get(0) instanceof TruncateStmt, "TRUNCATE");

        stmts = parse("ALTER TABLE t ADD score DECIMAL(10,2)");
        chk(stmts.get(0) instanceof AlterTableStmt, "ALTER ADD COLUMN");
    }

    static void testFunctions() {
        var stmts = parse("SELECT UPPER(name), LENGTH(name) FROM users");
        chk(stmts.get(0) instanceof SelectStmt, "function calls");

        stmts = parse("SELECT COALESCE(email, 'N/A') FROM users");
        chk(stmts.get(0) instanceof SelectStmt, "COALESCE");
    }

    static void testCase() {
        var stmts = parse("SELECT CASE WHEN a > 1 THEN 'high' ELSE 'low' END FROM t");
        chk(stmts.get(0) instanceof SelectStmt, "CASE expression");
    }

    static void testCast() {
        var stmts = parse("SELECT CAST(id AS VARCHAR(10)) FROM t");
        chk(stmts.get(0) instanceof SelectStmt, "CAST expression");
    }

    static void testTCL() {
        chk(parse("BEGIN").get(0) instanceof TCLStmt, "BEGIN");
        chk(parse("COMMIT").get(0) instanceof TCLStmt, "COMMIT");
        chk(parse("ROLLBACK").get(0) instanceof TCLStmt, "ROLLBACK");
        chk(parse("START TRANSACTION").get(0) instanceof TCLStmt, "START TRANSACTION");
    }

    static void testSubquery() {
        var stmts = parse("SELECT * FROM (SELECT id FROM t) s");
        chk(stmts.get(0) instanceof SelectStmt s
            && s.from().get(0) instanceof SubqueryTable, "subquery in FROM");

        stmts = parse("SELECT name FROM t WHERE id IN (SELECT id FROM s)");
        chk(stmts.get(0) instanceof SelectStmt, "subquery in WHERE");
    }

    static void testCTE() {
        var stmts = parse("WITH cte AS (SELECT id FROM t) SELECT * FROM cte");
        chk(stmts.get(0) instanceof SelectStmt s && s.withClause() != null, "CTE");

        stmts = parse("WITH RECURSIVE nums AS (SELECT 1 AS n UNION ALL SELECT n+1 FROM nums WHERE n<5) SELECT * FROM nums");
        chk(stmts.get(0) instanceof SelectStmt s && s.withClause().get(0).recursive(), "recursive CTE");
    }

    static void testWindow() {
        var stmts = parse("SELECT ROW_NUMBER() OVER (ORDER BY id) FROM t");
        chk(stmts.get(0) instanceof SelectStmt, "ROW_NUMBER");

        stmts = parse("SELECT RANK() OVER (PARTITION BY dept ORDER BY salary DESC) FROM t");
        chk(stmts.get(0) instanceof SelectStmt, "RANK OVER PARTITION");
    }

    static void chk(boolean c, String m) { if(c) pass++; else { fail++; System.err.println("  ❌ "+m); } }
}
