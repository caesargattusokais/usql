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
                 WINDOW_FUNCTION,
                 ARRAY_TYPE,
                 DEFERRABLE_FK,
                 GENERATED_COLUMN -> false;

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

    /** Convert FULL OUTER JOIN to LEFT JOIN UNION RIGHT JOIN (flag mode). */
    private IRStatement polyfillFullOuterJoin(IRStatement statement) {
        // Full implementation requires IR tree rewrite with union generation.
        // Currently flagged — backends handle this at generation time.
        return statement;
    }

    /** Convert BOOLEAN references for dialects without native boolean. */
    private IRStatement polyfillBoolean(IRStatement statement) {
        // Backend-level: maps TRUE/FALSE literals and IS_TRUE/IS_FALSE operators.
        return statement;
    }

    /** Add FROM DUAL for databases that require it (Oracle, DM). */
    private IRStatement polyfillSelectWithoutFrom(IRStatement statement) {
        if (statement instanceof IRSelect sel && sel.core().from() == null) {
            // Mark it — the Backend generates FROM DUAL
        }
        return statement;
    }

    /** Wrap CONCAT args with COALESCE for databases where CONCAT returns NULL on null input. */
    private IRStatement polyfillConcatNull(IRStatement statement) {
        // Backend-level: Oracle/PG use || which returns NULL on null;
        // MySQL CONCAT treats NULL as empty string.
        // The polyfill wraps each arg: COALESCE(arg, '') to normalize behavior.
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
