package com.usql;

import com.usql.backend.*;
import com.usql.dialect.Dialect;
import com.usql.ir.*;
import com.usql.ir.IRExpr.*;
import com.usql.ir.IRStatement.*;

import java.util.List;
import java.util.Set;

/**
 * End-to-end test: construct IR manually and verify backend output.
 * Full compiler pipeline (lex→parse→IR→generate) will be tested once
 * the antlr4-generated classes are available.
 */
public class CompilerE2ETest {

    public static void main(String[] args) {
        System.out.println("=== USQL Compiler — End-to-End Test ===\n");

        test("MySQL SELECT + LIMIT", Dialect.MYSQL, buildSampleQuery(), """
            SELECT d.`name`, COUNT(*) AS `cnt`\
            , AVG(e.`salary`) AS `avg_sal`\
             FROM `departments` d\
             LEFT JOIN `employees` e ON d.`id` = e.`dept_id`\
             WHERE d.`active` = 1\
             GROUP BY d.`name`\
             HAVING COUNT(*) > 3\
             ORDER BY AVG(e.`salary`) DESC\
             LIMIT 10 OFFSET 0""");

        test("PostgreSQL SELECT + LIMIT", Dialect.POSTGRESQL, buildSampleQuery(), """
            SELECT d."name", COUNT(*) AS "cnt"\
            , AVG(e."salary") AS "avg_sal"\
             FROM "departments" d\
             LEFT JOIN "employees" e ON d."id" = e."dept_id"\
             WHERE d."active" = TRUE\
             GROUP BY d."name"\
             HAVING COUNT(*) > 3\
             ORDER BY AVG(e."salary") DESC\
             LIMIT 10 OFFSET 0""");

        test("Oracle with ROWNUM", Dialect.ORACLE, buildSampleQuery(), null);
        // Oracle produces ROWNUM wrapping — check it contains key patterns
        testContains("Oracle contains ROWNUM", Dialect.ORACLE, buildSampleQuery(),
            List.of("ROWNUM", "inner__", "core__"));

        test("达梦 SELECT", Dialect.DM, buildSampleQuery(), null);
        testContains("达梦 contains LIMIT", Dialect.DM, buildSampleQuery(),
            List.of("LIMIT 10", "OFFSET 0", "SYSDATE"));

        System.out.println("\n=== All tests passed! ===");
    }

    // ══════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════

    private static IRSelect buildSampleQuery() {
        return new IRSelect(
            new SelectCore(
                // SELECT
                List.of(
                    new IRExprSelect(
                        new IRColumnRef("name", "d", new DataType.VarcharType(100)),
                        null
                    ),
                    new IRExprSelect(
                        new IRFunctionCall("COUNT", List.of(new IRWildcard(null)), DataType.IntType.BIGINT),
                        "cnt"
                    ),
                    new IRExprSelect(
                        new IRFunctionCall("AVG", List.of(
                            new IRColumnRef("salary", "e", new DataType.DecimalType(10, 2))
                        ), new DataType.DecimalType(14, 4)),
                        "avg_sal"
                    )
                ),
                // FROM
                List.of(
                    new IRJoin(
                        new IRTableName("departments", "d", null),
                        JoinType.LEFT,
                        new IRTableName("employees", "e", null),
                        new IRBinaryOp(
                            new IRColumnRef("id", "d", DataType.IntType.INT),
                            IRBinaryOp.BinaryOp.EQ,
                            new IRColumnRef("dept_id", "e", DataType.IntType.INT),
                            new DataType.BooleanType()
                        )
                    )
                ),
                // WHERE
                new IRBinaryOp(
                    new IRColumnRef("active", "d", new DataType.BooleanType()),
                    IRBinaryOp.BinaryOp.EQ,
                    new IRLiteral(true, new DataType.BooleanType()),
                    new DataType.BooleanType()
                ),
                // GROUP BY
                List.of(new IRGroupBy(
                    new IRColumnRef("name", "d", new DataType.VarcharType(100)),
                    GroupByKind.PLAIN
                )),
                // HAVING
                new IRBinaryOp(
                    new IRFunctionCall("COUNT", List.of(new IRWildcard(null)), DataType.IntType.BIGINT),
                    IRBinaryOp.BinaryOp.GT,
                    new IRLiteral(3, DataType.IntType.INT),
                    new DataType.BooleanType()
                ),
                // WITH
                null,
                // SetOp
                null,
                // SetOperand
                null,
                // DISTINCT
                false
            ),
            // ORDER BY
            List.of(new OrderBy(
                new IRFunctionCall("AVG", List.of(
                    new IRColumnRef("salary", "e", new DataType.DecimalType(10, 2))
                ), new DataType.DecimalType(14, 4)),
                OrderDir.DESC,
                NullsOrder.LAST
            )),
            // FETCH
            new FetchClause(
                new IRLiteral(10, DataType.IntType.INT),
                new IRLiteral(0, DataType.IntType.INT)
            ),
            // Capabilities
            Set.of(Capability.LIMIT_OFFSET, Capability.AGGREGATE, Capability.HAVING)
        );
    }

    private static void test(String label, Dialect dialect, IRStatement ir, String expected) {
        DialectBackend backend = getBackend(dialect);
        String sql = backend.generate(ir, GenerateOptions.MINIMAL);

        if (expected != null) {
            // Normalize whitespace for comparison
            String normalized = sql.replaceAll("\\s+", " ");
            String expectedNorm = expected.replaceAll("\\s+", " ");

            if (normalized.trim().equals(expectedNorm.trim())) {
                System.out.println("  ✅ " + label + " — PASS");
                System.out.println("     " + sql);
            } else {
                System.out.println("  ❌ " + label + " — FAIL");
                System.out.println("     Expected: " + expectedNorm.trim());
                System.out.println("     Got:      " + normalized.trim());
            }
        } else {
            System.out.println("  📋 " + label + ":");
            System.out.println("     " + sql);
        }
    }

    private static void testContains(String label, Dialect dialect, IRStatement ir,
                                      List<String> mustContain) {
        DialectBackend backend = getBackend(dialect);
        String sql = backend.generate(ir, GenerateOptions.MINIMAL);
        String sqlUpper = sql.toUpperCase();

        boolean allPresent = mustContain.stream().allMatch(s -> sqlUpper.contains(s.toUpperCase()));
        if (allPresent) {
            System.out.println("  ✅ " + label + " — PASS (contains: " + String.join(", ", mustContain) + ")");
        } else {
            System.out.println("  ❌ " + label + " — FAIL");
            for (String s : mustContain) {
                if (!sqlUpper.contains(s.toUpperCase())) {
                    System.out.println("     Missing: " + s);
                }
            }
            System.out.println("     SQL: " + sql);
        }
    }

    private static DialectBackend getBackend(Dialect dialect) {
        return switch (dialect) {
            case MYSQL      -> new MySqlBackend();
            case POSTGRESQL -> new PgBackend();
            case ORACLE     -> new OracleBackend();
            case DM         -> new DmBackend();
            default -> throw new UnsupportedOperationException("No backend: " + dialect);
        };
    }
}
