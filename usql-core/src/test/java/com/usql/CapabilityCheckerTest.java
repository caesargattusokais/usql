package com.usql;

import com.usql.capability.CapabilityChecker;
import com.usql.capability.CapabilityChecker.CapabilityReport;
import com.usql.capability.CapabilityChecker.Severity;
import com.usql.dialect.Dialect;
import com.usql.ir.Capability;
import com.usql.ir.IRExpr;
import com.usql.ir.IRExpr.*;
import com.usql.ir.IRStatement;
import com.usql.ir.IRStatement.*;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Tests for CapabilityChecker — no database required.
 */
public class CapabilityCheckerTest {

    private static final CapabilityChecker checker = new CapabilityChecker();

    public static void main(String[] args) {
        System.out.println("=== CapabilityChecker Test ===\n");

        int pass = 0;

        // ── 1. PostgreSQL: RECURSIVE_CTE supported → no issues ──
        {
            IRSelect cteQuery = buildCteQuery();
            CapabilityReport r = checker.check(cteQuery, Dialect.POSTGRESQL);
            check(r.allSupported(), "PG supports RECURSIVE_CTE: allSupported = true");
            check(!r.hasMissing(), "PG supports RECURSIVE_CTE: hasMissing = false");
            System.out.println("  ✅ 1. PG + RECURSIVE_CTE → all supported");
            pass++;
        }

        // ── 2. MySQL: RECURSIVE_CTE not supported → fatal ERROR ──
        {
            IRSelect cteQuery = buildCteQuery();
            CapabilityReport r = checker.check(cteQuery, Dialect.MYSQL);
            check(!r.allSupported(), "MySQL RECURSIVE_CTE: allSupported = false");
            check(r.hasMissing(), "MySQL RECURSIVE_CTE: hasMissing = true");
            check(r.hasFatal(), "MySQL RECURSIVE_CTE: hasFatal = true");
            check(r.findings().stream().anyMatch(f -> f.severity() == Severity.ERROR),
                "MySQL RECURSIVE_CTE: finding severity = ERROR");
            System.out.println("  ✅ 2. MySQL + RECURSIVE_CTE → fatal ERROR");
            pass++;
        }

        // ── 3. Oracle: LIMIT_OFFSET not supported → WARNING polyfill ──
        {
            IRSelect limitQuery = buildLimitQuery();
            CapabilityReport r = checker.check(limitQuery, Dialect.ORACLE);
            check(!r.allSupported(), "Oracle LIMIT_OFFSET: allSupported = false");
            check(r.hasMissing(), "Oracle LIMIT_OFFSET: hasMissing = true");
            check(!r.hasFatal(), "Oracle LIMIT_OFFSET: hasFatal = false (polyfillable)");
            check(r.polyfillableCapabilities().contains(Capability.LIMIT_OFFSET),
                "Oracle LIMIT_OFFSET: polyfillable");
            System.out.println("  ✅ 3. Oracle + LIMIT_OFFSET → WARNING polyfill");
            pass++;
        }

        // ── 4. MySQL: FULL_OUTER_JOIN not supported → WARNING polyfill ──
        {
            IRSelect fullJoinQuery = buildFullJoinQuery();
            CapabilityReport r = checker.check(fullJoinQuery, Dialect.MYSQL);
            check(!r.allSupported(), "MySQL FULL_OUTER_JOIN: allSupported = false");
            check(!r.hasFatal(), "MySQL FULL_OUTER_JOIN: hasFatal = false (polyfillable)");
            check(r.polyfillableCapabilities().contains(Capability.FULL_OUTER_JOIN),
                "MySQL FULL_OUTER_JOIN: polyfillable");
            System.out.println("  ✅ 4. MySQL + FULL_OUTER_JOIN → WARNING polyfill");
            pass++;
        }

        // ── 5. PostgreSQL + WINDOW_FUNCTION → supported ──
        {
            IRSelect windowQuery = buildWindowQuery();
            CapabilityReport r = checker.check(windowQuery, Dialect.POSTGRESQL);
            check(r.allSupported(), "PG supports WINDOW_FUNCTION: allSupported = true");
            check(r.polyfillableCapabilities().isEmpty(),
                "PG WINDOW_FUNCTION: no polyfill needed");
            System.out.println("  ✅ 5. PostgreSQL + WINDOW_FUNCTION → supported");
            pass++;
        }

        // ── 6. All dialects support basic aggregate ──
        {
            IRSelect aggQuery = buildAggregateQuery();
            for (Dialect d : Dialect.values()) {
                if (d == Dialect.H2) continue;
                CapabilityReport r = checker.check(aggQuery, d);
                check(r.allSupported(), d.displayName() + " supports AGGREGATE");
            }
            System.out.println("  ✅ 6. All 5 dialects support AGGREGATE");
            pass++;
        }

        // ── 7. MySQL does NOT support ENUM_TYPE ──
        // Actually MySQL DOES support ENUM_TYPE... Let's test FULL_OUTER_JOIN instead
        {
            for (Dialect d : new Dialect[]{Dialect.MYSQL}) {
                IRSelect fullJoin = buildFullJoinQuery();
                CapabilityReport r = checker.check(fullJoin, d);
                check(r.hasMissing(), d.displayName() + " missing FULL_OUTER_JOIN");
            }
            System.out.println("  ✅ 7. MySQL missing FULL_OUTER_JOIN → hasMissing");
            pass++;
        }

        // ── 8. Empty query (no special capabilities) → all supported ──
        {
            IRSelect simpleQuery = buildSimpleQuery();
            for (Dialect d : Dialect.values()) {
                if (d == Dialect.H2) continue;
                CapabilityReport r = checker.check(simpleQuery, d);
                check(r.allSupported(), d.displayName() + " simple query: allSupported = true");
            }
            System.out.println("  ✅ 8. Simple SELECT: all dialects support");
            pass++;
        }

        // ── 9. MERGE_INTO: Oracle supports natively ──
        {
            IRMerge mergeQuery = buildMergeQuery();
            CapabilityReport r = checker.check(mergeQuery, Dialect.ORACLE);
            check(r.allSupported(), "Oracle supports MERGE_INTO natively");
            System.out.println("  ✅ 9. Oracle + MERGE_INTO → supported");
            pass++;
        }

        // ── 10. MERGE_INTO: MySQL needs polyfill ──
        {
            IRMerge mergeQuery = buildMergeQuery();
            CapabilityReport r = checker.check(mergeQuery, Dialect.MYSQL);
            check(!r.allSupported(), "MySQL MERGE_INTO: not all supported");
            check(r.polyfillableCapabilities().contains(Capability.MERGE_INTO),
                "MySQL MERGE_INTO: polyfillable");
            System.out.println("  ✅ 10. MySQL + MERGE_INTO → polyfillable");
            pass++;
        }

        System.out.println("\n=== Result: " + pass + "/10 passed ===");
    }

    // ══════════════════════════════════════════════════
    //  Query builders
    // ══════════════════════════════════════════════════

    private static IRSelect buildCteQuery() {
        return new IRSelect(
            new SelectCore(
                List.of(new IRExprSelect(new IRColumnRef("id", null, null), null)),
                List.of(new IRTableName("t", null, null)),
                null, null, null,
                List.of(new IRCommonTable("cte", List.of("id", "name"),
                    new IRSelect(new SelectCore(
                        List.of(new IRExprSelect(new IRLiteral(1, null), "id"),
                                new IRExprSelect(new IRLiteral("x", null), "name")),
                        null, null, null, null, null, null, false
                    ), null, null, Set.of()), true)),
                null, null, false
            ), null, null,
            Set.of(Capability.RECURSIVE_CTE)
        );
    }

    private static IRSelect buildLimitQuery() {
        return new IRSelect(
            new SelectCore(
                List.of(new IRExprSelect(new IRColumnRef("id", null, null), null)),
                List.of(new IRTableName("t", null, null)),
                null, null, null, null, null, null, false
            ), null,
            new FetchClause(new IRLiteral(10, null), new IRLiteral(0, null)),
            Set.of(Capability.LIMIT_OFFSET)
        );
    }

    private static IRSelect buildFullJoinQuery() {
        return new IRSelect(
            new SelectCore(
                List.of(new IRExprSelect(new IRColumnRef("a", "id", null), null)),
                List.of(new IRJoin(
                    new IRTableName("t1", null, null),
                    JoinType.FULL,
                    new IRTableName("t2", null, null),
                    null
                )),
                null, null, null, null, null, null, false
            ), null, null,
            Set.of(Capability.FULL_OUTER_JOIN)
        );
    }

    private static IRSelect buildWindowQuery() {
        return new IRSelect(
            new SelectCore(
                List.of(new IRExprSelect(
                    new IRFunctionCall("ROW_NUMBER", List.of(), null,
                        new IRWindowOver(
                            List.of(new IRColumnRef("dept", null, null)),
                            List.of(new OrderBy(new IRColumnRef("salary", null, null),
                                OrderDir.DESC, nulls)),
                            null
                        ), null),
                    "rn"
                )),
                List.of(new IRTableName("emp", null, null)),
                null, null, null, null, null, null, false
            ), null, null,
            Set.of(Capability.WINDOW_FUNCTION)
        );
    }

    private static IRSelect buildAggregateQuery() {
        return new IRSelect(
            new SelectCore(
                List.of(new IRExprSelect(new IRFunctionCall("COUNT", List.of(new IRWildcard(null)), null, null, null), "cnt")),
                List.of(new IRTableName("t", null, null)),
                null,
                List.of(new IRGroupBy(new IRColumnRef("x", null, null), GroupByKind.PLAIN)),
                null, null, null, null, false
            ), null, null,
            Set.of(Capability.AGGREGATE)
        );
    }

    private static IRSelect buildSimpleQuery() {
        return new IRSelect(
            new SelectCore(
                List.of(new IRExprSelect(new IRColumnRef("id", null, null), null)),
                List.of(new IRTableName("t", null, null)),
                null, null, null, null, null, null, false
            ), null, null, Set.of()
        );
    }

    private static IRMerge buildMergeQuery() {
        return new IRMerge(
            new IRTableName("target", null, null),
            new IRTableName("source", null, null),
            new IRBinaryOp(new IRColumnRef("t", "id", null), "=", new IRColumnRef("s", "id", null), null),
            List.of(new MergeUpdate(List.of(new SetClause("name",
                new IRColumnRef("s", "name", null))))),
            Set.of(Capability.MERGE_INTO)
        );
    }
}
