package com.usql;

import com.usql.ast.USqlAst.*;
import com.usql.capability.PolyfillEngine;
import com.usql.dialect.Dialect;
import com.usql.ir.*;
import com.usql.ir.IRExpr.*;
import com.usql.ir.IRStatement.*;

import java.util.List;
import java.util.Set;

/**
 * Tests for PolyfillEngine FULL OUTER JOIN and capability checks.
 * No database required.
 */
public class PolyfillEngineTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        System.out.println("=== PolyfillEngine Test ===\n");

        testFullOuterJoinPolyfill();
        testCanPolyfill();

        System.out.println("\n=== Result: " + pass + "/" + (pass + fail) + " passed ===");
        if (fail > 0) System.exit(1);
    }

    static void testFullOuterJoinPolyfill() {
        // Build FULL OUTER JOIN query
        IRJoin fullJoin = new IRJoin(
            new IRTableName("t1", null, null),
            IRStatement.JoinType.FULL,
            new IRTableName("t2", null, null),
            new IRBinaryOp(
                new IRColumnRef("t1", "id", null), IRBinaryOp.BinaryOp.EQ,
                new IRColumnRef("t2", "id", null), null));

        IRSelect query = new IRSelect(
            new SelectCore(
                List.of(new IRExprSelect(new IRColumnRef("t1", "id", null), null)),
                List.of(fullJoin),
                null, null, null, null, null, null, false),
            null, null, Set.of(Capability.FULL_OUTER_JOIN));

        // Check polyfillable
        check(PolyfillEngine.canPolyfill(Capability.FULL_OUTER_JOIN),
            "FULL_OUTER_JOIN is polyfillable");

        // Apply polyfill
        com.usql.capability.CapabilityChecker checker = new com.usql.capability.CapabilityChecker();
        var report = checker.check(query, Dialect.MYSQL);
        check(report.hasMissing(), "MySQL missing FULL_OUTER_JOIN");

        PolyfillEngine engine = new PolyfillEngine();
        IRStatement result = engine.apply(query, report, Dialect.MYSQL);

        // Should become UNION of LEFT and RIGHT
        check(result instanceof IRSelect, "Result is IRSelect");
        IRSelect sel = (IRSelect) result;
        check(sel.core().setOp() == IRStatement.SetOp.UNION, "FULL JOIN → UNION");
        check(sel.core().setOperand() != null, "Has right-side operand");

        System.out.println("  ✅ FULL OUTER JOIN polyfill: creates UNION with LEFT+RIGHT arms");
    }

    static void testCanPolyfill() {
        // Polyfillable
        check(PolyfillEngine.canPolyfill(Capability.LIMIT_OFFSET), "LIMIT_OFFSET polyfillable");
        check(PolyfillEngine.canPolyfill(Capability.BOOLEAN_TYPE), "BOOLEAN_TYPE polyfillable");
        check(PolyfillEngine.canPolyfill(Capability.MERGE_INTO), "MERGE_INTO polyfillable");
        check(PolyfillEngine.canPolyfill(Capability.ENUM_TYPE), "ENUM_TYPE polyfillable");
        check(PolyfillEngine.canPolyfill(Capability.WINDOW_FUNCTION), "WINDOW_FUNCTION polyfillable");

        // Not polyfillable
        check(!PolyfillEngine.canPolyfill(Capability.RECURSIVE_CTE), "RECURSIVE_CTE not polyfillable");
        check(!PolyfillEngine.canPolyfill(Capability.ARRAY_TYPE), "ARRAY_TYPE not polyfillable");
        check(!PolyfillEngine.canPolyfill(Capability.DEFERRABLE_FK), "DEFERRABLE_FK not polyfillable");

        // Descriptions
        String desc = PolyfillEngine.describePolyfill(Capability.LIMIT_OFFSET, Dialect.ORACLE);
        check(desc.contains("ROWNUM"), "Oracle LIMIT_OFFSET → ROWNUM description");

        desc = PolyfillEngine.describePolyfill(Capability.MERGE_INTO, Dialect.MYSQL);
        check(desc.contains("ON DUPLICATE KEY"), "MySQL MERGE → INSERT ON DUPLICATE KEY");

        System.out.println("  ✅ canPolyfill: " + (pass) + " checks");
    }

    static void check(boolean condition, String msg) {
        if (condition) { pass++; }
        else { fail++; System.err.println("  ❌ FAIL: " + msg); }
    }
}
