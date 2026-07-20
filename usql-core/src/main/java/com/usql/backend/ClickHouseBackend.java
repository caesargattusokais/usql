package com.usql.backend;

import com.usql.dialect.Dialect;
import com.usql.ir.*;
import com.usql.ir.IRExpr.*;
import com.usql.ir.IRStatement.*;
import java.util.stream.Collectors;

/**
 * ClickHouse backend — columnar analytical database.
 * Extends MySqlBackend for similar LIMIT/OFFSET and general SQL patterns.
 *
 * Key ClickHouse differences from MySQL:
 * - UNION requires explicit ALL or DISTINCT
 * - UPDATE/DELETE don't support qualified column references (table.col)
 * - CREATE INDEX requires TYPE clause
 * - ALTER COLUMN uses MODIFY COLUMN syntax
 * - No CASCADE on DROP TABLE
 * - No MERGE, stored procedures, or functions
 */
public class ClickHouseBackend extends MySqlBackend {

    @Override
    public Dialect targetDialect() { return Dialect.CLICKHOUSE; }

    @Override
    public String generate(IRStatement statement, GenerateOptions options) {
        return switch (statement) {
            case IRCreateTable ct       -> chCreateTable(ct, options);
            case IRCreateIndex ci       -> chCreateIndex(ci, options);
            case IRSelect sel           -> chSelect(sel, options);
            case IRUpdate upd           -> chUpdate(upd, options);
            case IRDelete del           -> chDelete(del, options);
            case IRDropTable dt         -> chDropTable(dt, options);
            case IRMerge merge          -> "SELECT 1 /* ClickHouse: MERGE not supported */";
            case IRCreateProcedure cp   -> "SELECT 1 /* ClickHouse: stored procedures not supported */";
            case IRCreateFunction cf    -> "SELECT 1 /* ClickHouse: stored functions not supported */";
            case IRCall call            -> "SELECT 1 /* ClickHouse: CALL not supported */";
            case IRAlterColumnSetDefault ad  -> chAlterSetDefault(ad, options);
            case IRAlterColumnDropDefault dd  -> chAlterDropDefault(dd, options);
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

    // ═══════════════════════
    //  CREATE INDEX — ClickHouse requires TYPE
    // ═══════════════════════

    private String chCreateIndex(IRCreateIndex ci, GenerateOptions opt) {
        if (ci.unique()) {
            // ClickHouse does not support UNIQUE index; emit a harmless no-op query
            return "SELECT 1 /* ClickHouse: CREATE UNIQUE INDEX not supported */";
        }
        var sb = new StringBuilder("CREATE INDEX ");
        if (ci.ifNotExists()) sb.append("IF NOT EXISTS ");
        sb.append(quoteIdentifier(ci.name())).append(" ON ");
        sb.append(quoteIdentifier(ci.table().name())).append(" (");
        sb.append(ci.columns().stream()
            .map(c -> quoteIdentifier(c.name()))
            .collect(Collectors.joining(", ")));
        sb.append(") TYPE minmax GRANULARITY 1");
        return sb.toString();
    }

    // ═══════════════════════
    //  SELECT — ClickHouse requires UNION ALL / UNION DISTINCT
    // ═══════════════════════

    private String chSelect(IRSelect sel, GenerateOptions opt) {
        String sql = super.generate(sel, opt);
        // ClickHouse requires explicit ALL or DISTINCT after UNION
        // Replace bare "UNION SELECT" with "UNION DISTINCT SELECT"
        sql = sql.replace(" UNION SELECT", " UNION DISTINCT SELECT");
        // Guard against double-replacement
        sql = sql.replace(" UNION DISTINCT DISTINCT SELECT", " UNION DISTINCT SELECT");
        return sql;
    }

    // ═══════════════════════
    //  UPDATE / DELETE — strip table qualifier from column references
    // ═══════════════════════

    private String chUpdate(IRUpdate upd, GenerateOptions opt) {
        // ClickHouse UPDATE doesn't support qualified column references (table.col)
        return super.generate(stripUpdateQualifiers(upd), opt);
    }

    private String chDelete(IRDelete del, GenerateOptions opt) {
        return super.generate(stripDeleteQualifiers(del), opt);
    }

    /** Strip table qualifiers from UPDATE's SET clauses and WHERE. */
    private IRUpdate stripUpdateQualifiers(IRUpdate upd) {
        var sets = upd.sets().stream()
            .map(s -> new SetClause(s.column(), stripQual(s.value())))
            .toList();
        IRExpr where = upd.where() != null ? stripQual(upd.where()) : null;
        return new IRUpdate(upd.table(), sets, where, upd.capabilities());
    }

    private IRDelete stripDeleteQualifiers(IRDelete del) {
        IRExpr where = del.where() != null ? stripQual(del.where()) : null;
        return new IRDelete(del.table(), where, del.capabilities());
    }

    /** Recursively strip qualifiers from column references in an expression. */
    private IRExpr stripQual(IRExpr expr) {
        return switch (expr) {
            case IRColumnRef cr -> cr.qualifier() != null
                ? new IRColumnRef(cr.name(), null, cr.type())
                : cr;
            case IRBinaryOp bo -> new IRBinaryOp(stripQual(bo.left()), bo.op(), stripQual(bo.right()), bo.type());
            case IRUnaryOp uo -> new IRUnaryOp(uo.op(), stripQual(uo.operand()), uo.type());
            case IRBetween bw -> new IRBetween(stripQual(bw.expr()), stripQual(bw.low()), stripQual(bw.high()), bw.not(), bw.type());
            case IRInList il -> {
                var vals = il.values() != null ? il.values().stream().map(this::stripQual).toList() : null;
                yield new IRInList(stripQual(il.expr()), vals, il.subquery(), il.not(), il.type());
            }
            case IRIsNull isn -> new IRIsNull(stripQual(isn.expr()), isn.not(), isn.type());
            case IRFunctionCall fc -> new IRFunctionCall(fc.funcName(), fc.args().stream().map(this::stripQual).toList(), fc.type(), fc.over(), fc.keep());
            case IRLiteral lit -> lit;
            default -> expr;
        };
    }

    // ═══════════════════════
    //  ALTER COLUMN DEFAULT
    // ═══════════════════════

    /** ClickHouse: ALTER TABLE t MODIFY COLUMN c DEFAULT val */
    private String chAlterSetDefault(IRAlterColumnSetDefault ad, GenerateOptions opt) {
        return "ALTER TABLE " + quoteIdentifier(ad.tableName())
            + " MODIFY COLUMN " + quoteIdentifier(ad.column())
            + " DEFAULT " + generateExpr(ad.value(), opt);
    }

    /** ClickHouse: ALTER TABLE t MODIFY COLUMN c REMOVE DEFAULT */
    private String chAlterDropDefault(IRAlterColumnDropDefault dd, GenerateOptions opt) {
        return "ALTER TABLE " + quoteIdentifier(dd.tableName())
            + " MODIFY COLUMN " + quoteIdentifier(dd.column())
            + " REMOVE DEFAULT";
    }

    // ClickHouse doesn't support standard ALTER COLUMN TYPE with the same syntax
    @Override
    protected String generateAlterColumnType(IRAlterColumnType act, GenerateOptions opt) {
        return "ALTER TABLE " + quoteIdentifier(act.tableName())
            + " MODIFY COLUMN " + quoteIdentifier(act.column()) + " " + mapType(act.newType());
    }

    // ═══════════════════════
    //  DROP TABLE — ClickHouse doesn't support CASCADE
    // ═══════════════════════

    private String chDropTable(IRDropTable dt, GenerateOptions opt) {
        var sb = new StringBuilder("DROP TABLE ");
        if (dt.ifExists()) sb.append("IF EXISTS ");
        sb.append(quoteIdentifier(dt.name()));
        // ClickHouse doesn't support CASCADE — omit it
        return sb.toString();
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
