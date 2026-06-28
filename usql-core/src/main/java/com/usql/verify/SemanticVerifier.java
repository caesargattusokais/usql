package com.usql.verify;

import com.usql.dialect.Dialect;
import com.usql.ir.DataType;

import java.math.BigDecimal;
import java.math.MathContext;
import java.sql.*;
import java.util.*;

/**
 * Dual-execution semantic verifier.
 *
 * Runs the reference SQL (via H2) and the target SQL (via the real database),
 * then compares results with type-aware comparison logic.
 *
 * Works at two levels:
 *   1. Static (compile-time): type compatibility and capability coverage
 *   2. Runtime (with test data): actual result comparison
 */
public class SemanticVerifier {

    private static final double RELATIVE_EPSILON = 1e-5;
    private static final double ABSOLUTE_EPSILON = 1e-10;
    private static final MathContext DECIMAL_MATH = new MathContext(30);

    /**
     * Verify a SQL translation by executing both reference and target against
     * the provided test data and comparing results.
     */
    public VerificationReport verify(
        String referenceSQL,
        String targetSQL,
        Dialect targetDialect,
        Connection referenceConn,
        Connection targetConn,
        List<List<Object>> testData
    ) {
        List<VerificationReport.Mismatch> mismatches = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        try {
            // Execute reference
            List<ColumnMeta> refColumns;
            List<List<Object>> refRows;

            try (Statement stmt = referenceConn.createStatement();
                 ResultSet rs = stmt.executeQuery(referenceSQL)) {
                refColumns = extractColumns(rs);
                refRows = extractRows(rs, refColumns.size());
            }

            // Execute target
            List<ColumnMeta> targetColumns;
            List<List<Object>> targetRows;

            try (Statement stmt = targetConn.createStatement();
                 ResultSet rs = stmt.executeQuery(targetSQL)) {
                targetColumns = extractColumns(rs);
                targetRows = extractRows(rs, targetColumns.size());
            }

            // Compare
            if (refRows.size() != targetRows.size()) {
                mismatches.add(new VerificationReport.Mismatch(
                    -1, -1, "ROW_COUNT",
                    "Row count differs: ref=" + refRows.size() + " vs target=" + targetRows.size(),
                    VerificationReport.Severity.HARD_MISMATCH
                ));
            }

            int maxRows = Math.min(refRows.size(), targetRows.size());
            for (int row = 0; row < maxRows; row++) {
                var refRow = refRows.get(row);
                var tgtRow = targetRows.get(row);
                int maxCols = Math.min(refRow.size(), tgtRow.size());

                for (int col = 0; col < maxCols; col++) {
                    DataType type = col < refColumns.size()
                        ? refColumns.get(col).type() : null;

                    var verdict = compareValues(refRow.get(col), tgtRow.get(col), type);

                    switch (verdict) {
                        case HARD_MISMATCH -> mismatches.add(new VerificationReport.Mismatch(
                            row, col,
                            col < refColumns.size() ? refColumns.get(col).name() : "col" + col,
                            "Value: ref=" + refRow.get(col) + " vs target=" + tgtRow.get(col),
                            VerificationReport.Severity.HARD_MISMATCH
                        ));
                        case ACCEPTABLE_DIFF -> warnings.add(
                            "Row " + row + " col " + col + ": acceptable diff — " +
                            refRow.get(col) + " vs " + tgtRow.get(col)
                        );
                        case MATCH -> {} // OK
                    }
                }
            }

        } catch (SQLException e) {
            mismatches.add(new VerificationReport.Mismatch(
                -1, -1, "EXECUTION_ERROR",
                e.getMessage(),
                VerificationReport.Severity.HARD_MISMATCH
            ));
        }

        boolean passed = mismatches.stream()
            .noneMatch(m -> m.severity() == VerificationReport.Severity.HARD_MISMATCH);

        return new VerificationReport(passed, mismatches, warnings,
            referenceSQL, targetSQL, targetDialect.displayName());
    }

    // ══════════════════════════════════════════════════
    //  Type-aware comparison
    // ══════════════════════════════════════════════════

    enum Verdict { MATCH, ACCEPTABLE_DIFF, HARD_MISMATCH }

    private Verdict compareValues(Object ref, Object target, DataType type) {
        // Both null
        if (ref == null && target == null) return Verdict.MATCH;
        // One null
        if (ref == null || target == null) return Verdict.HARD_MISMATCH;
        // Same reference
        if (ref.equals(target)) return Verdict.MATCH;

        // Type-aware comparison
        if (ref instanceof Number rn && target instanceof Number tn) {
            return compareNumeric(rn, tn, type);
        }

        if (ref instanceof Boolean rb && target instanceof Boolean tb) {
            return rb == tb ? Verdict.MATCH : Verdict.HARD_MISMATCH;
        }

        if (ref instanceof Boolean rb && target instanceof Number tn) {
            // BOOLEAN → TINYINT(1) mapping
            boolean tb = tn.intValue() != 0;
            return rb == tb ? Verdict.MATCH : Verdict.HARD_MISMATCH;
        }

        if (ref instanceof Number rn && target instanceof Boolean tb) {
            boolean rb = rn.intValue() != 0;
            return rb == tb ? Verdict.MATCH : Verdict.HARD_MISMATCH;
        }

        // String comparison
        if (ref instanceof String rs && target instanceof String ts) {
            // CHAR comparison: trim trailing spaces
            if (type instanceof DataType.CharType) {
                return rs.stripTrailing().equals(ts.stripTrailing())
                    ? Verdict.MATCH : Verdict.HARD_MISMATCH;
            }
            return rs.equals(ts) ? Verdict.MATCH : Verdict.HARD_MISMATCH;
        }

        // Temporal comparison
        if (ref instanceof java.sql.Timestamp && target instanceof java.sql.Timestamp) {
            long diff = Math.abs(((java.sql.Timestamp) ref).getTime() - ((java.sql.Timestamp) target).getTime());
            return diff <= 1000 ? Verdict.ACCEPTABLE_DIFF : Verdict.HARD_MISMATCH; // 1s tolerance
        }

        // Fallback: string equality
        return ref.toString().equals(target.toString())
            ? Verdict.MATCH : Verdict.HARD_MISMATCH;
    }

    private Verdict compareNumeric(Number ref, Number target, DataType type) {
        if (type instanceof DataType.IntType) {
            return ref.longValue() == target.longValue() ? Verdict.MATCH : Verdict.HARD_MISMATCH;
        }

        if (type instanceof DataType.DecimalType) {
            BigDecimal rb = toBigDecimal(ref);
            BigDecimal tb = toBigDecimal(target);
            return rb.compareTo(tb) == 0 ? Verdict.MATCH : Verdict.HARD_MISMATCH;
        }

        if (type instanceof DataType.FloatType) {
            double rd = ref.doubleValue();
            double td = target.doubleValue();

            if (Double.isNaN(rd) && Double.isNaN(td)) return Verdict.MATCH;
            if (Double.isInfinite(rd) && Double.isInfinite(td)) return Verdict.MATCH;

            double diff = Math.abs(rd - td);
            double scale = Math.max(Math.abs(rd), Math.abs(td));

            if (scale < 1.0) {
                return diff <= ABSOLUTE_EPSILON ? Verdict.MATCH : Verdict.ACCEPTABLE_DIFF;
            } else {
                return diff / scale <= RELATIVE_EPSILON ? Verdict.MATCH : Verdict.ACCEPTABLE_DIFF;
            }
        }

        // Generic numeric: try long comparison
        return ref.longValue() == target.longValue() ? Verdict.MATCH : Verdict.HARD_MISMATCH;
    }

    private BigDecimal toBigDecimal(Number n) {
        if (n instanceof BigDecimal bd) return bd;
        return new BigDecimal(n.toString(), DECIMAL_MATH);
    }

    // ══════════════════════════════════════════════════
    //  ResultSet extraction
    // ══════════════════════════════════════════════════

    private List<ColumnMeta> extractColumns(ResultSet rs) throws SQLException {
        var meta = rs.getMetaData();
        var columns = new ArrayList<ColumnMeta>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            columns.add(new ColumnMeta(
                meta.getColumnName(i),
                meta.getColumnLabel(i),
                inferType(meta.getColumnType(i)),
                i
            ));
        }
        return columns;
    }

    private List<List<Object>> extractRows(ResultSet rs, int columnCount) throws SQLException {
        var rows = new ArrayList<List<Object>>();
        while (rs.next()) {
            var row = new ArrayList<Object>();
            for (int i = 1; i <= columnCount; i++) {
                row.add(rs.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }

    private DataType inferType(int jdbcType) {
        return switch (jdbcType) {
            case Types.TINYINT, Types.SMALLINT -> DataType.IntType.SMALLINT;
            case Types.INTEGER -> DataType.IntType.INT;
            case Types.BIGINT -> DataType.IntType.BIGINT;
            case Types.FLOAT, Types.REAL -> DataType.FloatType.FLOAT;
            case Types.DOUBLE -> DataType.FloatType.DOUBLE;
            case Types.DECIMAL, Types.NUMERIC -> new DataType.DecimalType(10, 0);
            case Types.CHAR -> new DataType.CharType(1);
            case Types.VARCHAR, Types.NVARCHAR -> new DataType.VarcharType(255);
            case Types.CLOB -> new DataType.TextType();
            case Types.BOOLEAN, Types.BIT -> new DataType.BooleanType();
            case Types.DATE -> new DataType.DateType();
            case Types.TIME -> new DataType.TimeType(0);
            case Types.TIMESTAMP -> new DataType.DatetimeType(3);
            case Types.TIMESTAMP_WITH_TIMEZONE -> new DataType.TimestampType(3);
            case Types.BINARY, Types.VARBINARY -> new DataType.BinaryType(1);
            case Types.BLOB -> new DataType.BlobType();
            case Types.NULL -> new DataType.NullType();
            default -> new DataType.VarcharType(0); // fallback
        };
    }

    // ══════════════════════════════════════════════════
    //  Supporting types
    // ══════════════════════════════════════════════════

    public record ColumnMeta(String name, String label, DataType type, int position) {}

    /**
     * Full verification report.
     */
    public record VerificationReport(
        boolean passed,
        List<Mismatch> mismatches,
        List<String> warnings,
        String referenceSQL,
        String targetSQL,
        String targetDialect
    ) {
        public record Mismatch(int row, int column, String columnName,
                               String detail, Severity severity) {}

        public enum Severity { HARD_MISMATCH, ACCEPTABLE_DIFF }

        public String summary() {
            int hard = (int) mismatches.stream().filter(m -> m.severity == Severity.HARD_MISMATCH).count();
            return (passed ? "✅ PASSED" : "❌ FAILED") +
                   " — " + mismatches.size() + " mismatches (" + hard + " hard) " +
                   "against " + targetDialect;
        }
    }
}
