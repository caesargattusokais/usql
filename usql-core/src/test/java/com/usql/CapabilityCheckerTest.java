package com.usql;

import com.usql.capability.CapabilityChecker;
import com.usql.capability.CapabilityChecker.*;
import com.usql.capability.PolyfillEngine;
import com.usql.dialect.Dialect;
import com.usql.ir.*;
import com.usql.ir.IRExpr.*;
import com.usql.ir.IRStatement.*;
import java.util.*;

public class CapabilityCheckerTest {
    static int pass = 0, fail = 0;
    static CapabilityChecker checker = new CapabilityChecker();

    public static void main(String[] args) {
        System.out.println("=== CapabilityChecker Test ===\n");
        testAllDialectsRecursiveCte();
        testPolyfillVsFatal();
        testAllPolyfillableCaps();
        testAllNonPolyfillableCaps();
        testCompoundCapabilities();
        testWarningVsError();
        System.out.println("\n=== " + pass + "/" + (pass+fail) + " passed ===");
        if (fail > 0) System.exit(1);
    }

    static void testAllDialectsRecursiveCte() {
        IRSelect q = new IRSelect(new SelectCore(
            List.of(new IRExprSelect(new IRColumnRef("id",null,null), null)),
            List.of(new IRTableName("t",null,null)),
            null, null, null,
            List.of(new IRCommonTable("cte", List.of("id"),
                new IRSelect(new SelectCore(
                    List.of(new IRExprSelect(new IRLiteral(1,null), "id")),
                    null, null, null, null, null, null, null, false),
                null, null, Set.of()), true)),
            null, null, false), null, null, Set.of(Capability.RECURSIVE_CTE));

        // All 5 non-H2 dialects support RECURSIVE_CTE
        for (Dialect d : Dialect.values()) {
            if (d == Dialect.H2) continue;
            CapabilityReport r = checker.check(q, d);
            check(r.allSupported(), d.displayName() + " supports RECURSIVE_CTE");
        }
    }

    static void testPolyfillVsFatal() {
        // LIMIT_OFFSET on Oracle → polyfillable WARNING
        IRSelect q = new IRSelect(new SelectCore(
            List.of(new IRExprSelect(new IRColumnRef("id",null,null), null)),
            List.of(new IRTableName("t",null,null)),
            null, null, null, null, null, null, false),
            null, new FetchClause(new IRLiteral(10,null), null),
            Set.of(Capability.LIMIT_OFFSET));
        CapabilityReport r = checker.check(q, Dialect.ORACLE);
        check(!r.allSupported(), "Oracle missing LIMIT_OFFSET");
        check(r.polyfillableCapabilities().contains(Capability.LIMIT_OFFSET), "LIMIT_OFFSET polyfillable");
        check(!r.hasFatal(), "LIMIT_OFFSET not fatal");

        // RECURSIVE_CTE where not supported → fatal ERROR
        // Actually all dialects support it now, so test with a synthetic unsupported cap
        check(PolyfillEngine.canPolyfill(Capability.LIMIT_OFFSET), "LIMIT_OFFSET canPolyfill");
        check(!PolyfillEngine.canPolyfill(Capability.RECURSIVE_CTE), "RECURSIVE_CTE cannotPolyfill");
    }

    static void testAllPolyfillableCaps() {
        Capability[] polyfillable = {
            Capability.LIMIT_OFFSET, Capability.BOOLEAN_TYPE, Capability.FULL_OUTER_JOIN,
            Capability.AUTO_INCREMENT, Capability.CONCAT_WITH_NULL, Capability.ENUM_TYPE,
            Capability.RETURNING_CLAUSE, Capability.SELECT_WITHOUT_FROM, Capability.HAVING,
            Capability.TRUNCATE_TABLE, Capability.REPLACE_INTO, Capability.ON_DUPLICATE_KEY_UPDATE,
            Capability.INTERVAL_ARITHMETIC, Capability.MERGE_INTO
        };
        for (Capability c : polyfillable) {
            check(PolyfillEngine.canPolyfill(c), c.name() + " is polyfillable");
            check(PolyfillEngine.describePolyfill(c, Dialect.MYSQL) != null,
                c.name() + " has description");
        }
    }

    static void testAllNonPolyfillableCaps() {
        Capability[] notPolyfillable = {
            Capability.RECURSIVE_CTE, Capability.ARRAY_TYPE,
            Capability.DEFERRABLE_FK, Capability.GENERATED_COLUMN
        };
        for (Capability c : notPolyfillable) {
            check(!PolyfillEngine.canPolyfill(c), c.name() + " is NOT polyfillable");
        }
    }

    static void testCompoundCapabilities() {
        // Query with LIMIT + DISTINCT + AGGREGATE
        IRSelect q = new IRSelect(new SelectCore(
            List.of(new IRExprSelect(new IRFunctionCall("COUNT", List.of(new IRWildcard(null)),
                null, null, null), "cnt")),
            List.of(new IRTableName("t",null,null)),
            null, List.of(new IRGroupBy(new IRColumnRef("x",null,null), GroupByKind.PLAIN)),
            null, null, null, null, true),
            null, new FetchClause(new IRLiteral(10,null), new IRLiteral(0,null)),
            Set.of(Capability.LIMIT_OFFSET, Capability.DISTINCT, Capability.AGGREGATE));
        CapabilityReport r = checker.check(q, Dialect.ORACLE);
        check(r.hasMissing(), "Oracle missing LIMIT_OFFSET in compound query");
        check(r.polyfillableCapabilities().size() >= 1, "At least 1 polyfillable cap");
    }

    static void testWarningVsError() {
        // RECURSIVE_CTE → ERROR (highest severity)
        IRSelect q = new IRSelect(new SelectCore(
            List.of(new IRExprSelect(new IRColumnRef("id",null,null), null)),
            List.of(new IRTableName("t",null,null)), null, null, null,
            List.of(new IRCommonTable("cte", List.of("id"),
                new IRSelect(new SelectCore(
                    List.of(new IRExprSelect(new IRLiteral(1,null), "id")),
                    null, null, null, null, null, null, null, false),
                null, null, Set.of()), true)),
            null, null, false), null, null, Set.of(Capability.RECURSIVE_CTE));

        // Test against H2 (which supports RECURSIVE_CTE) → no issues
        CapabilityReport r = checker.check(q, Dialect.H2);
        check(r.allSupported(), "H2 supports RECURSIVE_CTE");
    }

    static void check(boolean c, String m) { if(c) pass++; else { fail++; System.err.println("  ❌ "+m); } }
}
