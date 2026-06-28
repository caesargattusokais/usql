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
                 RETURNING_CLAUSE -> true;

            case RECURSIVE_CTE,
                 WINDOW_FUNCTION,
                 ARRAY_TYPE,
                 DEFERRABLE_FK,
                 GENERATED_COLUMN -> false;  // too complex or impossible to polyfill

            default -> false;
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
            case FULL_OUTER_JOIN -> polyfillFullOuterJoin(statement);
            case BOOLEAN_TYPE     -> polyfillBoolean(statement);
            // Other polyfills handled at Backend level
            default -> statement;
        };
    }

    /**
     * Convert FULL OUTER JOIN to LEFT JOIN UNION RIGHT JOIN.
     */
    private IRStatement polyfillFullOuterJoin(IRStatement statement) {
        if (!(statement instanceof IRSelect sel)) return statement;

        // Walk the from clause to find FULL joins
        // For MVP: flag it; the Backend handles the UNION generation
        // Full implementation: transform the IR with a rewriter visitor
        return statement;
    }

    /**
     * Convert BOOLEAN references for dialects without native boolean.
     * The Backend already handles this in generation;
     * IR-level polyfill would transform IRBinaryOp IS_TRUE into EQ(1).
     */
    private IRStatement polyfillBoolean(IRStatement statement) {
        return statement; // Handled at Backend level for MVP
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
                "Using ROWNUM / FETCH FIRST wrapping" :
                "Using subquery row limiting";
            case FULL_OUTER_JOIN -> "Converting to LEFT JOIN UNION RIGHT JOIN";
            case BOOLEAN_TYPE -> "Mapping BOOLEAN to " + (
                dialect == Dialect.ORACLE ? "NUMBER(1)" :
                dialect == Dialect.MYSQL ? "TINYINT(1)" :
                dialect == Dialect.DM ? "BIT" : "INT");
            case AUTO_INCREMENT -> dialect == Dialect.POSTGRESQL || dialect == Dialect.ORACLE ?
                "Creating SEQUENCE + TRIGGER" : "Using IDENTITY column";
            case CONCAT_WITH_NULL -> "Wrapping args with COALESCE to handle NULL";
            default -> "No polyfill available";
        };
    }
}
