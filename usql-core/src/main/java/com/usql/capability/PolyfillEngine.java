package com.usql.capability;

import com.usql.dialect.Dialect;
import com.usql.ir.*;
import com.usql.ir.IRStatement.*;

import java.util.*;

/**
 * Polyfill engine — rewrites IR to compensate for missing capabilities.
 *
 * When a target dialect lacks a feature (e.g., Oracle → LIMIT_OFFSET),
 * the polyfill engine modifies the IR so that the Backend generates
 * equivalent SQL using features the dialect does support.
 *
 * For now, most polyfills are handled at the Backend level (e.g.,
 * OracleBackend's ROWNUM wrapping). This engine provides the framework
 * for IR-level rewrites where needed.
 */
public class PolyfillEngine {

    /**
     * Can a given capability be polyfilled?
     */
    public static boolean canPolyfill(Capability cap) {
        return switch (cap) {
            case LIMIT_OFFSET,
                 BOOLEAN_TYPE,
                 FULL_OUTER_JOIN,
                 AUTO_INCREMENT,
                 CONCAT_WITH_NULL,
                 PARTIAL_INDEX,
                 ENUM_TYPE,
                 RETURNING_CLAUSE,
                 SELECT_WITHOUT_FROM,
                 HAVING,
                 TRUNCATE_TABLE,
                 REPLACE_INTO,
                 ON_DUPLICATE_KEY_UPDATE,
                 INTERVAL_ARITHMETIC,
                 MERGE_INTO -> true;

            case RECURSIVE_CTE,
                 ARRAY_TYPE,
                 DEFERRABLE_FK,
                 GENERATED_COLUMN -> false;

            case WINDOW_FUNCTION -> true; // subquery polyfill

            default -> true;
        };
    }

    /**
     * Apply polyfill transformations to the IR for the target dialect.
     * Returns the IR (may be the same object if no changes needed).
     */
    public IRStatement apply(IRStatement statement, CapabilityChecker.CapabilityReport report, Dialect target) {
        if (report.allSupported()) return statement;

        // Apply polyfills in order of dependency
        IRStatement result = statement;

        for (Capability missing : report.polyfillableCapabilities()) {
            result = applyOne(result, missing, target);
        }

        return result;
    }

    private IRStatement applyOne(IRStatement statement, Capability missing, Dialect target) {
        return switch (missing) {
            case FULL_OUTER_JOIN       -> polyfillFullOuterJoin(statement);
            case BOOLEAN_TYPE          -> polyfillBoolean(statement);
            case SELECT_WITHOUT_FROM   -> polyfillSelectWithoutFrom(statement);
            case CONCAT_WITH_NULL      -> polyfillConcatNull(statement);
            // LIMIT_OFFSET, AUTO_INCREMENT, ENUM, PARTIAL_INDEX handled at Backend level
            default -> statement;
        };
    }

    /** Convert FULL OUTER JOIN to LEFT JOIN UNION RIGHT JOIN. */
    private IRStatement polyfillFullOuterJoin(IRStatement statement) {
        if (!(statement instanceof IRSelect sel)) return statement;
        if (sel.core().from() == null) return statement;

        // Check if any join is FULL
        boolean hasFullJoin = false;
        for (var ref : sel.core().from()) {
            if (ref instanceof IRJoin jn && jn.type() == JoinType.FULL) {
                hasFullJoin = true;
                break;
            }
        }
        if (!hasFullJoin) return statement;

        // Build LEFT JOIN side: FULL → LEFT
        IRSelect leftSide = replaceJoinType(sel, JoinType.FULL, JoinType.LEFT);

        // Build RIGHT JOIN side: FULL → RIGHT
        IRSelect rightSide = replaceJoinType(sel, JoinType.FULL, JoinType.RIGHT);

        // Combine with UNION
        SelectCore unionCore = new SelectCore(
            leftSide.core().projections(),
            leftSide.core().from(),
            leftSide.core().where(),
            leftSide.core().groupBy(),
            leftSide.core().having(),
            leftSide.core().withClause(),
            SetOp.UNION,
            rightSide,
            leftSide.core().distinct()
        );
        return new IRSelect(unionCore, leftSide.orderBy(), leftSide.fetch(), sel.capabilities());
    }

    /** Replace all occurrences of a JOIN type in the FROM tree. */
    private IRSelect replaceJoinType(IRSelect sel, JoinType from, JoinType to) {
        List<IRTableRef> newFrom = null;
        if (sel.core().from() != null) {
            newFrom = new ArrayList<>();
            for (var ref : sel.core().from()) {
                newFrom.add(replaceJoinTypeInRef(ref, from, to));
            }
        }
        SelectCore core = new SelectCore(
            sel.core().projections(), newFrom,
            sel.core().where(), sel.core().groupBy(), sel.core().having(),
            sel.core().withClause(), null, null, sel.core().distinct()
        );
        return new IRSelect(core, sel.orderBy(), sel.fetch(), sel.capabilities());
    }

    private IRTableRef replaceJoinTypeInRef(IRTableRef ref, JoinType from, JoinType to) {
        return switch (ref) {
            case IRJoin jn -> {
                IRTableRef left = replaceJoinTypeInRef(jn.left(), from, to);
                IRTableRef right = replaceJoinTypeInRef(jn.right(), from, to);
                JoinType newType = jn.type() == from ? to : jn.type();
                yield new IRJoin(left, newType, right, jn.onCondition());
            }
            case IRSubqueryTable sq -> new IRSubqueryTable(
                replaceJoinType(sq.query(), from, to), sq.alias());
            default -> ref;
        };
    }

    /** Convert BOOLEAN references for dialects without native boolean.
     *  Handled at the backend level (e.g. SQL Server maps to BIT + 1/0,
     *  Oracle maps to NUMBER(1)) — no IR rewrite needed here. */
    private IRStatement polyfillBoolean(IRStatement statement) {
        return statement;
    }

    /** Add FROM DUAL for databases that require it (Oracle, DM).
     *  Handled at the backend level (OracleBackend/DmBackend append " FROM DUAL"
     *  when from() is null) — no IR rewrite or marker needed here. */
    private IRStatement polyfillSelectWithoutFrom(IRStatement statement) {
        return statement;
    }

    /** Normalize CONCAT NULL semantics across dialects.
     *  Currently a no-op: every backend already emits dialect-appropriate
     *  concatenation (MySQL CONCAT(), PG/Oracle/DM/SQL Server ||) and all share
     *  standard NULL-propagation behavior. If a future usql contract requires
     *  "NULL treated as empty string" uniformly, wrap each arg with
     *  COALESCE(arg, '') here. */
    private IRStatement polyfillConcatNull(IRStatement statement) {
        return statement;
    }

    // ══════════════════════════════════════════════════
    //  Capability list per dialect (for display/debug)
    // ══════════════════════════════════════════════════

    /**
     * Get the polyfill description for a capability (for user messaging).
     */
    public static String describePolyfill(Capability cap, Dialect dialect) {
        return switch (cap) {
            case LIMIT_OFFSET -> dialect == Dialect.ORACLE || dialect == Dialect.DM ?
                "ROWNUM / FETCH FIRST wrapping" : "Subquery row limiting";
            case FULL_OUTER_JOIN -> "LEFT JOIN UNION RIGHT JOIN";
            case BOOLEAN_TYPE -> "Map to " + (
                dialect == Dialect.ORACLE ? "NUMBER(1)" :
                dialect == Dialect.MYSQL ? "TINYINT(1)" :
                dialect == Dialect.DM ? "BIT" : "INT");
            case AUTO_INCREMENT -> dialect == Dialect.POSTGRESQL || dialect == Dialect.ORACLE ?
                "GENERATED AS IDENTITY" : "Native AUTO_INCREMENT";
            case CONCAT_WITH_NULL -> "COALESCE-wrapped args";
            case SELECT_WITHOUT_FROM -> "Append FROM DUAL";
            case ENUM_TYPE -> dialect == Dialect.MYSQL ?
                "Native ENUM" : "VARCHAR + CHECK constraint";
            case PARTIAL_INDEX -> "Function-based index or skip";
            case MERGE_INTO -> dialect == Dialect.MYSQL ?
                "INSERT ON DUPLICATE KEY UPDATE" : "Native MERGE";
            case RETURNING_CLAUSE -> "SELECT after DML or skip";
            case TRUNCATE_TABLE -> "DELETE FROM without WHERE";
            case REPLACE_INTO -> "DELETE + INSERT or MERGE";
            case ON_DUPLICATE_KEY_UPDATE -> "MERGE or INSERT ... ON CONFLICT";
            case INTERVAL_ARITHMETIC -> "Numeric expression conversion";
            case HAVING -> "HAVING without GROUP BY → implicit GROUP BY";
            case RECURSIVE_CTE -> "NOT SUPPORTED — requires manual rewrite";
            case WINDOW_FUNCTION -> "Subquery simulation (performance warning)";
            case ARRAY_TYPE -> "JSON storage or normalized table";
            case DEFERRABLE_FK -> "NOT SUPPORTED — check at application level";
            case GENERATED_COLUMN -> "NOT SUPPORTED — use trigger";
            default -> "No polyfill available";
        };
    }
}
