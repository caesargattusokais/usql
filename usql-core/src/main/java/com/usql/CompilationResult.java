package com.usql;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Result of compiling a U-SQL statement.
 * Contains either the generated SQL + metadata, or a list of errors.
 */
public class CompilationResult {

    private final String sql;
    private final String referenceSql;    // H2 reference SQL when verification enabled
    private final List<Warning> warnings;
    private final List<Error> errors;
    private final boolean success;
    private final String sourceLocation;

    private CompilationResult(String sql, String referenceSql, List<Warning> warnings,
                              List<Error> errors, boolean success, String sourceLocation) {
        this.sql = sql;
        this.referenceSql = referenceSql;
        this.warnings = List.copyOf(warnings);
        this.errors = List.copyOf(errors);
        this.success = success;
        this.sourceLocation = sourceLocation;
    }

    public static CompilationResult success(String sql, List<Warning> warnings) {
        return new CompilationResult(sql, null, warnings, List.of(), true, null);
    }

    public static CompilationResult success(String sql) {
        return success(sql, List.of());
    }

    public static CompilationResult success(String sql, String referenceSql, List<Warning> warnings) {
        return new CompilationResult(sql, referenceSql, warnings, List.of(), true, null);
    }

    public static CompilationResult failed(List<Error> errors) {
        return new CompilationResult(null, null, List.of(), errors, false, null);
    }

    public static CompilationResult failed(List<Error> errors, String source) {
        return new CompilationResult(null, null, List.of(), errors, false, source);
    }

    // ── Getters ──

    public boolean isSuccess() { return success; }
    public String getSql() { return sql; }
    /** H2 reference SQL, only populated when verification is enabled */
    public String getReferenceSql() { return referenceSql; }
    public List<Warning> getWarnings() { return warnings; }
    public List<Error> getErrors() { return errors; }

    /** Combine warnings and errors into a human-readable report */
    public String report() {
        var sb = new StringBuilder();
        sb.append("── USQL Compilation ").append(success ? "✅ SUCCESS" : "❌ FAILED").append(" ──\n");
        if (sourceLocation != null) {
            sb.append("Source: ").append(sourceLocation).append('\n');
        }
        for (var err : errors) {
            sb.append("  Error [").append(err.line).append(':').append(err.col).append("] ")
              .append(err.message).append('\n');
            if (err.hint != null) {
                sb.append("    → Hint: ").append(err.hint).append('\n');
            }
        }
        for (var warn : warnings) {
            sb.append("  Warning [").append(warn.line).append(':').append(warn.col).append("] ")
              .append(warn.message).append('\n');
            if (warn.hint != null) {
                sb.append("    → Hint: ").append(warn.hint).append('\n');
            }
        }
        if (success) {
            sb.append("\nGenerated SQL:\n").append(sql).append('\n');
            if (referenceSql != null) {
                sb.append("\n[H2 Reference]:\n").append(referenceSql).append('\n');
            }
        }
        return sb.toString();
    }

    // ══════════════════════════════════════════════════
    //  Supporting types
    // ══════════════════════════════════════════════════

    public record Error(int line, int col, String message, String hint) {
        public static Error of(int line, int col, String message, String hint) {
            return new Error(line, col, message, hint);
        }
        public static Error of(int line, int col, String message) {
            return new Error(line, col, message, null);
        }
    }

    public record Warning(int line, int col, String message, String hint) {
        public static Warning of(int line, int col, String message) {
            return new Warning(line, col, message, null);
        }
        public static Warning of(int line, int col, String message, String hint) {
            return new Warning(line, col, message, hint);
        }
    }
}
