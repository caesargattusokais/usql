package com.usql.backend;

import com.usql.dialect.Dialect;
import com.usql.ir.*;
import com.usql.ir.IRExpr.*;
import com.usql.ir.IRStatement.*;
import java.util.stream.Collectors;

/**
 * ClickHouse backend — columnar analytical database.
 * Extends MySqlBackend for similar LIMIT/OFFSET and general SQL patterns.
 */
public class ClickHouseBackend extends MySqlBackend {

    @Override
    public Dialect targetDialect() { return Dialect.CLICKHOUSE; }

    @Override
    public String generate(IRStatement statement, GenerateOptions options) {
        return switch (statement) {
            case IRCreateTable ct       -> chCreateTable(ct, options);
            case IRMerge merge          -> "-- ClickHouse: MERGE not supported";
            case IRCreateProcedure cp   -> "-- ClickHouse: stored procedures not supported";
            case IRCreateFunction cf    -> "-- ClickHouse: stored functions not supported";
            case IRCall call            -> "-- ClickHouse: CALL not supported";
            default -> super.generate(statement, options);
        };
    }

    @Override
    protected String generateAlterTableAddColumn(IRAlterTableAddColumn aa, GenerateOptions opt) {
        var col = aa.column();
        var sb = new StringBuilder("ALTER TABLE ").append(quoteIdentifier(aa.tableName()))
            .append(" ADD COLUMN ");
        if (aa.ifNotExists()) sb.append("IF NOT EXISTS ");
        sb.append(quoteIdentifier(col.name())).append(" ").append(mapType(col.type()));
        return sb.toString();
    }

    @Override
    public String mapType(DataType type) {
        return switch (type) {
            case DataType.IntType t -> switch (t.bits()) {
                case 8 -> "Int8"; case 16 -> "Int16"; case 32 -> "Int32"; default -> "Int64";
            };
            case DataType.DecimalType d -> "Decimal(" + d.precision() + "," + d.scale() + ")";
            case DataType.FloatType f -> f.bits() <= 32 ? "Float32" : "Float64";
            case DataType.VarcharType v -> "String";
            case DataType.CharType c -> "FixedString(" + c.length() + ")";
            case DataType.TextType t -> "String";
            case DataType.BooleanType b -> "UInt8";
            case DataType.DateType d -> "Date";
            case DataType.DatetimeType dt -> "DateTime";
            case DataType.TimestampType ts -> "DateTime64(3)";
            case DataType.JsonType j -> "String";
            case DataType.UuidType u -> "UUID";
            case DataType.BinaryType b -> "String";
            case DataType.BlobType bl -> "String";
            case DataType.EnumType e -> "Enum8(" + e.values().stream().map(v -> "'" + v.replace("'", "''") + "'").collect(Collectors.joining(", ")) + ")";
            default -> "String";
        };
    }

    @Override
    public String quoteIdentifier(String id) { return "`" + id.replace("`", "``") + "`"; }

    // ═══════════════════════
    //  CREATE TABLE
    // ═══════════════════════

    private String chCreateTable(IRCreateTable ct, GenerateOptions opt) {
        var sb = new StringBuilder("CREATE TABLE ");
        if (ct.ifNotExists()) sb.append("IF NOT EXISTS ");
        sb.append(quoteIdentifier(ct.name().name())).append(" (\n");
        sb.append(ct.columns().stream().map(c -> chColumnDef(c, opt)).collect(Collectors.joining(",\n")));
        sb.append("\n) ENGINE = MergeTree()");
        // ORDER BY first column (or PK)
        String orderCol = ct.columns().get(0).name();
        if (ct.columns().get(0).constraints() != null) {
            for (var col : ct.columns()) {
                if (col.constraints() != null) {
                    for (var c : col.constraints()) {
                        if (c instanceof ColPrimaryKey) { orderCol = col.name(); break; }
                    }
                }
            }
        }
        sb.append(" ORDER BY ").append(quoteIdentifier(orderCol));
        return sb.toString();
    }

    private String chColumnDef(IRColumnDef col, GenerateOptions opt) {
        var sb = new StringBuilder("  ").append(quoteIdentifier(col.name())).append(" ").append(mapType(col.type()));
        if (col.defaultValue() != null)
            sb.append(" DEFAULT ").append(superGenerateExpr(col.defaultValue(), opt));
        if (col.constraints() != null) {
            for (var c : col.constraints()) {
                if (c instanceof ColNotNull) sb.append(" NOT NULL");
                else if (c instanceof ColPrimaryKey) sb.append(" PRIMARY KEY");
            }
        }
        return sb.toString();
    }

    // ClickHouse doesn't support standard ALTER COLUMN TYPE with the same syntax
    @Override
    protected String generateAlterColumnType(IRAlterColumnType act, GenerateOptions opt) {
        return "ALTER TABLE " + quoteIdentifier(act.tableName())
            + " MODIFY COLUMN " + quoteIdentifier(act.column()) + " " + mapType(act.newType());
    }

    // Expr hack — same as DuckDB
    private String superGenerateExpr(IRExpr expr, GenerateOptions opt) {
        var dummy = new IRSelect(new SelectCore(
            java.util.List.of(new IRExprSelect(expr, null)),
            null, null, null, null, null, null, null, false),
            null, null, java.util.Set.of());
        String sql = super.generate(dummy, GenerateOptions.MINIMAL);
        int idx = sql.indexOf(' ');
        return idx >= 0 ? sql.substring(idx + 1).trim() : sql.trim();
    }
}
