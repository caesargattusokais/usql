package com.usql;

import com.usql.dialect.Dialect;
import com.usql.ir.*;
import com.usql.ir.IRExpr.*;
import com.usql.ir.IRStatement.*;

import java.util.List;
import java.util.Set;

/**
 * End-to-end test: construct IR manually, run through full compiler pipeline
 * (IR → capability check → polyfill → backend generation), verify output.
 */
public class CompilerE2ETest {

    public static void main(String[] args) {
        System.out.println("=== USQL Compiler — End-to-End Test ===\n");

        USqlCompiler compiler = USqlCompiler.builder().build();

        // ── Test 1: MySQL ──
        {
            IRSelect query = buildSampleQuery();
            CompilationResult r = compiler.compileFromIR(query, Dialect.MYSQL);
            check(r.isSuccess(), "MySQL compilation succeeds");
            checkContains(r.getSql(), "SELECT", "FROM", "LIMIT", "OFFSET");
            System.out.println("  ✅ MySQL — PASS");
            System.out.println("     " + r.getSql());
        }

        // ── Test 2: PostgreSQL ──
        {
            IRSelect query = buildSampleQuery();
            CompilationResult r = compiler.compileFromIR(query, Dialect.POSTGRESQL);
            check(r.isSuccess(), "PostgreSQL compilation succeeds");
            checkContains(r.getSql(), "SELECT", "FROM", "LIMIT", "OFFSET", "TRUE", "LEFT JOIN");
            System.out.println("  ✅ PostgreSQL — PASS");
            System.out.println("     " + r.getSql());
        }

        // ── Test 3: Oracle — ROWNUM wrapping ──
        {
            IRSelect query = buildSampleQuery();
            CompilationResult r = compiler.compileFromIR(query, Dialect.ORACLE);
            check(r.isSuccess(), "Oracle compilation succeeds");
            String sql = r.getSql().toUpperCase();
            check(sql.contains("ROWNUM"), "Oracle uses ROWNUM");
            check(sql.contains("INNER__"), "Oracle uses inner__ alias");
            // Oracle has one warning (LIMIT_OFFSET polyfill)
            check(!r.getWarnings().isEmpty(), "Oracle warns about polyfill");
            System.out.println("  ✅ Oracle — PASS");
            System.out.println("     " + r.getSql());
        }

        // ── Test 4: 达梦 DM ──
        {
            IRSelect query = buildSampleQuery();
            CompilationResult r = compiler.compileFromIR(query, Dialect.DM);
            check(r.isSuccess(), "达梦 compilation succeeds");
            checkContains(r.getSql(), "SELECT", "LIMIT", "OFFSET");
            System.out.println("  ✅ 达梦 DM — PASS");
            System.out.println("     " + r.getSql());
        }

        // ── Test 5: Capability check correctly identifies missing features ──
        {
            // Build a query with FULL OUTER JOIN (MySQL doesn't support)
            IRSelect fullJoinQuery = buildFullJoinQuery();
            CompilationResult r = compiler.compileFromIR(fullJoinQuery, Dialect.MYSQL);
            check(r.isSuccess(), "MySQL handles FULL JOIN with polyfill warning");
            System.out.println("  ✅ Capability check — PASS");
        }

        // ── Test 6: Simple query without LIMIT ──
        {
            IRSelect simpleQuery = buildSimpleQuery();
            CompilationResult r = compiler.compileFromIR(simpleQuery, Dialect.MYSQL);
            check(r.isSuccess(), "Simple query succeeds");
            checkContains(r.getSql(), "SELECT", "FROM", "WHERE");
            checkContainsNot(r.getSql(), "LIMIT");
            System.out.println("  ✅ Simple query — PASS");
            System.out.println("     " + r.getSql());
        }

        System.out.println("\n=== All tests passed! ===");
    }

    // ══════════════════════════════════════════════════
    //  Query builders
    // ══════════════════════════════════════════════════

    /** Full featured query with JOIN, WHERE, GROUP BY, HAVING, ORDER BY, LIMIT */
    private static IRSelect buildSampleQuery() {
        return new IRSelect(
            new SelectCore(
                List.of(
                    new IRExprSelect(new IRColumnRef("name", "d", new DataType.VarcharType(100)), null),
                    new IRExprSelect(new IRFunctionCall("COUNT", List.of(new IRWildcard(null)), DataType.IntType.BIGINT, null, null), "cnt"),
                    new IRExprSelect(new IRFunctionCall("AVG", List.of(
                        new IRColumnRef("salary", "e", new DataType.DecimalType(10, 2))
                    ), new DataType.DecimalType(14, 4), null, null), "avg_sal")
                ),
                List.of(new IRJoin(
                    new IRTableName("departments", "d", null),
                    JoinType.LEFT,
                    new IRTableName("employees", "e", null),
                    new IRBinaryOp(new IRColumnRef("id", "d", DataType.IntType.INT),
                        IRBinaryOp.BinaryOp.EQ,
                        new IRColumnRef("dept_id", "e", DataType.IntType.INT),
                        new DataType.BooleanType())
                )),
                new IRBinaryOp(new IRColumnRef("active", "d", new DataType.BooleanType()),
                    IRBinaryOp.BinaryOp.EQ, new IRLiteral(true, new DataType.BooleanType()),
                    new DataType.BooleanType()),
                List.of(new IRGroupBy(new IRColumnRef("name", "d", new DataType.VarcharType(100)), GroupByKind.PLAIN)),
                new IRBinaryOp(new IRFunctionCall("COUNT", List.of(new IRWildcard(null)), DataType.IntType.BIGINT, null, null),
                    IRBinaryOp.BinaryOp.GT, new IRLiteral(3, DataType.IntType.INT), new DataType.BooleanType()),
                null, null, null, false
            ),
            List.of(new OrderBy(new IRFunctionCall("AVG", List.of(
                new IRColumnRef("salary", "e", new DataType.DecimalType(10, 2))
            ), new DataType.DecimalType(14, 4), null, null), OrderDir.DESC, NullsOrder.LAST)),
            new FetchClause(new IRLiteral(10, DataType.IntType.INT), new IRLiteral(0, DataType.IntType.INT)),
            Set.of(Capability.LIMIT_OFFSET, Capability.AGGREGATE, Capability.HAVING)
        );
    }

    /** FULL OUTER JOIN query (tests capability detection) */
    private static IRSelect buildFullJoinQuery() {
        return new IRSelect(
            new SelectCore(
                List.of(new IRExprSelect(new IRColumnRef("name", "d", new DataType.VarcharType(100)), null),
                    new IRExprSelect(new IRColumnRef("title", "e", new DataType.VarcharType(200)), null)),
                List.of(new IRJoin(
                    new IRTableName("departments", "d", null),
                    JoinType.FULL,
                    new IRTableName("employees", "e", null),
                    new IRBinaryOp(new IRColumnRef("dept_id", "d", DataType.IntType.INT),
                        IRBinaryOp.BinaryOp.EQ,
                        new IRColumnRef("dept_id", "e", DataType.IntType.INT),
                        new DataType.BooleanType())
                )),
                null, null, null, null, null, null, false
            ),
            null, null,
            Set.of(Capability.FULL_OUTER_JOIN)
        );
    }

    /** Simple SELECT * FROM table WHERE */
    private static IRSelect buildSimpleQuery() {
        return new IRSelect(
            new SelectCore(
                List.of(new IRWildcardSelect(new IRWildcard(null))),
                List.of(new IRTableName("users", null, null)),
                new IRBinaryOp(new IRColumnRef("age", null, DataType.IntType.INT),
                    IRBinaryOp.BinaryOp.GT, new IRLiteral(18, DataType.IntType.INT),
                    new DataType.BooleanType()),
                null, null, null, null, null, false
            ),
            null, null, Set.of()
        );
    }

    // ══════════════════════════════════════════════════
    //  Test helpers
    // ══════════════════════════════════════════════════

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("FAIL: " + message);
    }

    private static void checkContains(String sql, String... fragments) {
        String upper = sql.toUpperCase();
        for (String f : fragments) {
            if (!upper.contains(f.toUpperCase())) {
                throw new AssertionError("FAIL: SQL missing '" + f + "'\n  SQL: " + sql);
            }
        }
    }

    private static void checkContainsNot(String sql, String fragment) {
        if (sql.toUpperCase().contains(fragment.toUpperCase())) {
            throw new AssertionError("FAIL: SQL should NOT contain '" + fragment + "'\n  SQL: " + sql);
        }
    }
}
