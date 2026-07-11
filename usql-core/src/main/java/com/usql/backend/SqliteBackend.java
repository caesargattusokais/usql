package com.usql.backend;

import com.usql.dialect.Dialect;
import com.usql.ir.*;
import com.usql.ir.IRExpr.*;
import com.usql.ir.IRStatement.*;

/**
 * Generates SQLite-compatible SQL from the Semantic IR.
 * SQLite is lightweight and permissive — types are suggestions.
 */
public class SqliteBackend extends AbstractDialectBackend {

    @Override
    public Dialect targetDialect() { return Dialect.SQLITE; }

    @Override
    public String generate(IRStatement statement, GenerateOptions options) {
        return switch (statement) {
            case IRSelect sel              -> generateSelect(sel, options);
            case IRInsert ins              -> generateInsert(ins, options);
            case IRUpdate upd              -> generateUpdate(upd, options);
            case IRDelete del              -> generateDelete(del, options);
            case IRMerge merge             -> generateMerge(merge, options);
            case IRCreateTable ct          -> generateCreateTable(ct, options);
            case IRCreateIndex ci          -> generateCreateIndex(ci, options);
            case IRDropTable dt            -> generateDropTable(dt, options);
            case IRDropIndex di            -> generateDropIndex(di, options);
            case IRTruncateTable tt        -> "DELETE FROM " + quoteIdentifier(tt.name());
            case IRAlterTableAddColumn aa  -> generateAlterTableAddColumn(aa, options);
            case IRAlterTableDropColumn ad -> generateAlterTableDropColumn(ad, options);
            case IRCreateProcedure cp      -> generateCreateProcedure(cp, options);
            case IRCreateFunction cf       -> generateCreateFunction(cf, options);
            case IRCall call               -> generateCall(call, options);
            default ->
                throw new UnsupportedOperationException("SQLite: " + statement.getClass().getSimpleName());
        };
    }

    @Override
    public String quoteIdentifier(String id) {
        return "\"" + id.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String mapType(DataType type) {
        return switch (type) {
            case DataType.IntType t -> "INTEGER";
            case DataType.FloatType f -> "REAL";
            case DataType.DecimalType d -> "REAL";
            case DataType.CharType c -> "TEXT";
            case DataType.VarcharType v -> "TEXT";
            case DataType.TextType t -> "TEXT";
            case DataType.BooleanType b -> "INTEGER";
            case DataType.DateType d -> "TEXT";
            case DataType.TimeType t -> "TEXT";
            case DataType.DatetimeType dt -> "TEXT";
            case DataType.TimestampType ts -> "TEXT";
            case DataType.JsonType j -> "TEXT";
            case DataType.UuidType u -> "TEXT";
            case DataType.BinaryType b -> "BLOB";
            case DataType.VarbinaryType vb -> "BLOB";
            case DataType.BlobType bl -> "BLOB";
            // CLOB not a separate type in DataType
            case DataType.EnumType e -> "TEXT";
            case DataType.NullType n -> "NULL";
            default -> "TEXT";
        };
    }

    // ═══════════════════════════════
    //  SELECT
    // ═══════════════════════════════

    private String generateSelect(IRSelect sel, GenerateOptions opt) {
        var sb = new StringBuilder();
        if (sel.core().withClause() != null && !sel.core().withClause().isEmpty()) {
            sb.append("WITH ");
            if (sel.core().withClause().get(0).recursive()) sb.append("RECURSIVE ");
            sb.append(sel.core().withClause().stream()
                .map(cte -> quoteIdentifier(cte.name()) + " AS (" + generateSelect(cte.query(), opt) + ")")
                .collect(java.util.stream.Collectors.joining(", ")));
            sb.append(" ");
        }
        sb.append("SELECT ");
        if (sel.core().distinct()) sb.append("DISTINCT ");
        sb.append(sel.core().projections().stream().map(p -> generateSelectItem(p, opt))
            .collect(java.util.stream.Collectors.joining(", ")));
        if (sel.core().from() != null && !sel.core().from().isEmpty()) {
            sb.append(" FROM ");
            sb.append(sel.core().from().stream().map(f -> generateTableRef(f, opt))
                .collect(java.util.stream.Collectors.joining(", ")));
        }
        if (sel.core().where() != null) sb.append(" WHERE ").append(generateExpr(sel.core().where(), opt));
        if (sel.core().groupBy() != null && !sel.core().groupBy().isEmpty()) {
            sb.append(" GROUP BY ");
            sb.append(sel.core().groupBy().stream().map(g -> generateExpr(g.expr(), opt))
                .collect(java.util.stream.Collectors.joining(", ")));
        }
        if (sel.core().having() != null) sb.append(" HAVING ").append(generateExpr(sel.core().having(), opt));
        if (sel.orderBy() != null && !sel.orderBy().isEmpty()) {
            sb.append(" ORDER BY ");
            sb.append(sel.orderBy().stream().map(o -> generateExpr(o.expr(), opt) + (o.dir() == OrderDir.DESC ? " DESC" : " ASC"))
                .collect(java.util.stream.Collectors.joining(", ")));
        }
        if (sel.fetch() != null) {
            if (sel.fetch().limit() != null) sb.append(" LIMIT ").append(generateExpr(sel.fetch().limit(), opt));
            if (sel.fetch().offset() != null) sb.append(" OFFSET ").append(generateExpr(sel.fetch().offset(), opt));
        }
        if (sel.core().setOp() != null && sel.core().setOperand() != null) {
            sb.append(" ").append(sel.core().setOp().name().replace("_", " "));
            sb.append(" ").append(generateSelect(sel.core().setOperand(), opt));
        }
        return sb.toString();
    }

    private String generateSelectItem(IRSelectItem item, GenerateOptions opt) {
        return switch (item) {
            case IRExprSelect es -> {
                String expr = generateExpr(es.expr(), opt);
                if (es.alias() != null) expr += " AS " + quoteIdentifier(es.alias());
                yield expr;
            }
            case IRWildcardSelect ws -> ws.wildcard().qualifier() != null
                ? quoteIdentifier(ws.wildcard().qualifier()) + ".*" : "*";
        };
    }

    private String generateTableRef(IRTableRef ref, GenerateOptions opt) {
        return switch (ref) {
            case IRTableName tn -> {
                String r = quoteIdentifier(tn.name());
                if (tn.alias() != null) r += " " + quoteIdentifier(tn.alias());
                yield r;
            }
            case IRJoin jn -> {
                String join = switch (jn.type()) {
                    case INNER -> "INNER JOIN";
                    case LEFT -> "LEFT JOIN";
                    case CROSS -> "CROSS JOIN";
                    default -> "JOIN";
                };
                yield generateTableRef(jn.left(), opt) + " " + join + " "
                    + generateTableRef(jn.right(), opt) + (jn.onCondition() != null
                    ? " ON " + generateExpr(jn.onCondition(), opt) : "");
            }
            case IRSubqueryTable sq -> "(" + generateSelect(sq.query(), opt) + ") " + quoteIdentifier(sq.alias());
            case IRFunctionTable ft -> ft.funcName() + "("
                + ft.args().stream().map(a -> generateExpr(a, opt)).collect(java.util.stream.Collectors.joining(", "))
                + ") " + quoteIdentifier(ft.alias());
        };
    }

    // ═══════════════════════════════
    //  Expressions
    // ═══════════════════════════════

    protected String generateExpr(IRExpr expr, GenerateOptions opt) {
        return switch (expr) {
            case IRLiteral lit -> generateLiteral(lit);
            case IRColumnRef cr -> cr.qualifier() != null
                ? quoteIdentifier(cr.qualifier()) + "." + quoteIdentifier(cr.name())
                : quoteIdentifier(cr.name());
            case IRWildcard wc -> wc.qualifier() != null ? quoteIdentifier(wc.qualifier()) + ".*" : "*";
            case IRParameter p -> "?";
            case IRBinaryOp bo -> generateBinaryOp(bo, opt);
            case IRUnaryOp uo -> generateUnaryOp(uo, opt);
            case IRFunctionCall fc -> generateFunctionCall(fc, opt);
            case IRCase cs -> generateCase(cs, opt);
            case IRCast ct -> "CAST(" + generateExpr(ct.expr(), opt) + " AS " + mapType(ct.targetType()) + ")";
            case IRSubquery sq -> "(" + generateSelect(sq.query(), opt) + ")";
            case IRBetween btw -> generateExpr(btw.expr(), opt) + (btw.not() ? " NOT" : "")
                + " BETWEEN " + generateExpr(btw.low(), opt) + " AND " + generateExpr(btw.high(), opt);
            case IRInList in -> {
                String r = generateExpr(in.expr(), opt) + (in.not() ? " NOT IN (" : " IN (");
                if (in.subquery() != null) r += generateSelect(in.subquery(), opt);
                else r += in.values().stream().map(v -> generateExpr(v, opt)).collect(java.util.stream.Collectors.joining(", "));
                yield r + ")";
            }
            case IRIsNull isn -> generateExpr(isn.expr(), opt) + (isn.not() ? " IS NOT NULL" : " IS NULL");
        };
    }

    private String generateLiteral(IRLiteral lit) {
        if (lit.value() == null) return "NULL";
        if (lit.type() == null) return lit.value().toString();
        return switch (lit.type()) {
            case DataType.IntType i -> lit.value().toString();
            case DataType.FloatType f -> lit.value().toString();
            case DataType.BooleanType b -> ((Boolean) lit.value()) ? "1" : "0";
            case DataType.NullType n -> "NULL";
            default -> "'" + lit.value().toString().replace("'", "''") + "'";
        };
    }

    private String generateBinaryOp(IRBinaryOp bo, GenerateOptions opt) {
        String left = generateExpr(bo.left(), opt);
        String right = generateExpr(bo.right(), opt);
        String op = switch (bo.op()) {
            case ADD -> " + "; case SUB -> " - "; case MUL -> " * "; case DIV -> " / "; case MOD -> " % ";
            case EQ -> " = "; case NEQ -> " <> "; case LT -> " < "; case GT -> " > ";
            case LTE -> " <= "; case GTE -> " >= "; case AND -> " AND "; case OR -> " OR ";
            case CONCAT -> " || "; case LIKE -> " LIKE "; default -> " " + bo.op().name() + " ";
        };
        return left + op + right;
    }

    private String generateUnaryOp(IRUnaryOp uo, GenerateOptions opt) {
        String operand = generateExpr(uo.operand(), opt);
        return switch (uo.op()) { case NOT -> "NOT " + operand; case NEG -> "-" + operand; default -> uo.op() + " " + operand; };
    }

    private String generateCase(IRCase cs, GenerateOptions opt) {
        var sb = new StringBuilder("CASE");
        for (var w : cs.whens()) sb.append(" WHEN ").append(generateExpr(w.condition(), opt)).append(" THEN ").append(generateExpr(w.result(), opt));
        if (cs.elseExpr() != null) sb.append(" ELSE ").append(generateExpr(cs.elseExpr(), opt));
        return sb.append(" END").toString();
    }

    // ═══════════════════════════════
    //  INSERT / UPDATE / DELETE / MERGE
    // ═══════════════════════════════

    private String generateInsert(IRInsert ins, GenerateOptions opt) {
        var sb = new StringBuilder("INSERT");
        if (ins.ignoreErrors()) sb.append(" OR IGNORE");
        sb.append(" INTO ").append(generateTableRef(ins.table(), opt));
        if (ins.columns() != null && !ins.columns().isEmpty())
            sb.append(" (").append(ins.columns().stream().map(this::quoteIdentifier).collect(java.util.stream.Collectors.joining(", "))).append(")");
        if (ins.selectSource() != null) sb.append(" ").append(generateSelect(ins.selectSource(), opt));
        else if (ins.values() != null && !ins.values().isEmpty()) {
            sb.append(" VALUES ");
            sb.append(ins.values().stream().map(row ->
                "(" + row.stream().map(v -> generateExpr(v, opt)).collect(java.util.stream.Collectors.joining(", ")) + ")")
                .collect(java.util.stream.Collectors.joining(", ")));
        }
        return sb.toString();
    }

    private String generateUpdate(IRUpdate upd, GenerateOptions opt) {
        var sb = new StringBuilder("UPDATE ").append(generateTableRef(upd.table(), opt)).append(" SET ");
        sb.append(upd.sets().stream().map(s -> quoteIdentifier(s.column()) + " = " + generateExpr(s.value(), opt))
            .collect(java.util.stream.Collectors.joining(", ")));
        if (upd.where() != null) sb.append(" WHERE ").append(generateExpr(upd.where(), opt));
        return sb.toString();
    }

    private String generateDelete(IRDelete del, GenerateOptions opt) {
        var sb = new StringBuilder("DELETE FROM ").append(generateTableRef(del.table(), opt));
        if (del.where() != null) sb.append(" WHERE ").append(generateExpr(del.where(), opt));
        return sb.toString();
    }

    private String generateMerge(IRMerge merge, GenerateOptions opt) {
        // SQLite 3.24+ has UPSERT: INSERT ... ON CONFLICT DO UPDATE
        var sb = new StringBuilder("INSERT INTO ").append(generateTableRef(merge.target(), opt));
        // Simplified: for now just compile as INSERT OR REPLACE
        return "-- MERGE not fully supported in SQLite, use INSERT OR REPLACE";
    }

    // ═══════════════════════════════
    //  DDL
    // ═══════════════════════════════

    private String generateCreateTable(IRCreateTable ct, GenerateOptions opt) {
        var sb = new StringBuilder("CREATE TABLE ");
        if (ct.ifNotExists()) sb.append("IF NOT EXISTS ");
        sb.append(generateTableRef(ct.name(), opt)).append(" (\n");
        sb.append(ct.columns().stream().map(c -> generateColumnDef(c, opt))
            .collect(java.util.stream.Collectors.joining(",\n")));
        if (ct.constraints() != null && !ct.constraints().isEmpty()) {
            sb.append(",\n");
            sb.append(ct.constraints().stream().map(c -> generateTableConstraint(c, opt))
                .collect(java.util.stream.Collectors.joining(",\n")));
        }
        sb.append("\n)");
        return sb.toString();
    }

    private String generateColumnDef(IRColumnDef col, GenerateOptions opt) {
        var sb = new StringBuilder("  ").append(quoteIdentifier(col.name())).append(" ").append(mapType(col.type()));
        if (col.constraints() != null) {
            boolean isPK = false, isAI = false;
            for (var c : col.constraints()) {
                if (c instanceof ColPrimaryKey pk) { isPK = true; isAI = pk.autoIncrement(); }
                else if (c instanceof ColNotNull) sb.append(" NOT NULL");
                else if (c instanceof ColUnique) sb.append(" UNIQUE");
                else if (c instanceof ColCheck chk) sb.append(" CHECK (").append(generateExpr(chk.condition(), opt)).append(")");
            }
            if (isPK) { sb.append(" PRIMARY KEY"); if (isAI) sb.append(" AUTOINCREMENT"); }
        }
        if (col.defaultValue() != null) sb.append(" DEFAULT ").append(generateExpr(col.defaultValue(), opt));
        return sb.toString();
    }

    private String generateTableConstraint(IRTableConstraint c, GenerateOptions opt) {
        return switch (c) {
            case TBPrimaryKey pk -> "  PRIMARY KEY (" + pk.columns().stream().map(this::quoteIdentifier)
                .collect(java.util.stream.Collectors.joining(", ")) + ")";
            case TBUnique uq -> "  UNIQUE (" + uq.columns().stream().map(this::quoteIdentifier)
                .collect(java.util.stream.Collectors.joining(", ")) + ")";
            case TBForeignKey fk -> "  FOREIGN KEY (" + fk.columns().stream().map(this::quoteIdentifier)
                .collect(java.util.stream.Collectors.joining(", ")) + ") REFERENCES "
                + quoteIdentifier(fk.targetTable()) + "(" + fk.targetColumns().stream().map(this::quoteIdentifier)
                .collect(java.util.stream.Collectors.joining(", ")) + ")";
            case TBCheck chk -> "  CHECK (" + generateExpr(chk.condition(), opt) + ")";
        };
    }

    private String generateCreateIndex(IRCreateIndex idx, GenerateOptions opt) {
        var sb = new StringBuilder("CREATE ");
        if (idx.unique()) sb.append("UNIQUE ");
        sb.append("INDEX ");
        if (idx.ifNotExists()) sb.append("IF NOT EXISTS ");
        sb.append(quoteIdentifier(idx.name())).append(" ON ").append(quoteIdentifier(idx.table().name()));
        sb.append(" (").append(idx.columns().stream().map(c -> quoteIdentifier(c.name()) + (c.dir() == OrderDir.DESC ? " DESC" : ""))
            .collect(java.util.stream.Collectors.joining(", "))).append(")");
        return sb.toString();
    }

    @Override
    protected String generateDropTable(IRDropTable dt, GenerateOptions opt) {
        return "DROP TABLE " + (dt.ifExists() ? "IF EXISTS " : "") + quoteIdentifier(dt.name());
    }

    @Override
    protected String generateDropIndex(IRDropIndex di, GenerateOptions opt) {
        return "DROP INDEX " + (di.ifExists() ? "IF EXISTS " : "") + quoteIdentifier(di.indexName());
    }

    @Override
    protected String generateAlterTableAddColumn(IRAlterTableAddColumn aa, GenerateOptions opt) {
        var col = aa.column();
        return "ALTER TABLE " + quoteIdentifier(aa.tableName()) + " ADD COLUMN "
            + quoteIdentifier(col.name()) + " " + mapType(col.type());
    }

    @Override
    protected String generateRenameColumn(IRRenameColumn rc, GenerateOptions opt) {
        return "ALTER TABLE " + quoteIdentifier(rc.tableName())
            + " RENAME COLUMN " + quoteIdentifier(rc.oldName()) + " TO " + quoteIdentifier(rc.newName());
    }
}
