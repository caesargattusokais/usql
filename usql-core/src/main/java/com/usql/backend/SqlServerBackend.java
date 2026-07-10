package com.usql.backend;

import com.usql.catalog.FunctionCatalog;
import com.usql.dialect.Dialect;
import com.usql.ir.*;
import com.usql.ir.IRExpr.*;
import com.usql.ir.IRStatement.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Generates SQL Server (T-SQL) compatible SQL from the Semantic IR.
 */
public class SqlServerBackend extends AbstractDialectBackend {

    @Override
    public Dialect targetDialect() { return Dialect.SQLSERVER; }

    @Override
    public String generate(IRStatement statement, GenerateOptions options) {
        return switch (statement) {
            case IRSelect sel   -> generateSelect(sel, options);
            case IRInsert ins   -> generateInsert(ins, options);
            case IRUpdate upd   -> generateUpdate(upd, options);
            case IRDelete del   -> generateDelete(del, options);
            case IRMerge merge  -> generateMerge(merge, options);
            case IRCreateTable ct -> generateCreateTable(ct, options);
            case IRCreateIndex ci  -> generateCreateIndex(ci, options);
            default ->
                throw new UnsupportedOperationException(
                    "SQL Server backend cannot generate statement '" + statement.getClass().getSimpleName()
                    + "'. Supported: IRSelect, IRInsert, IRUpdate, IRDelete, IRMerge, IRCreateTable, IRCreateIndex");
        };
    }

    @Override
    public String quoteIdentifier(String id) {
        return "[" + id.replace("]", "]]") + "]";
    }

    @Override
    public String mapType(DataType type) {
        return switch (type) {
            case DataType.IntType t -> switch (t.bits()) {
                case 8  -> "TINYINT";
                case 16 -> "SMALLINT";
                case 32 -> "INT";
                case 64 -> "BIGINT";
                default -> "INT";
            };
            case DataType.DecimalType d -> "DECIMAL(" + d.precision() + "," + d.scale() + ")";
            case DataType.FloatType f   -> f.bits() <= 32 ? "REAL" : "FLOAT(53)";
            case DataType.CharType c    -> "CHAR(" + c.length() + ")";
            case DataType.VarcharType v -> "VARCHAR(" + v.length() + ")";
            case DataType.TextType t    -> "VARCHAR(MAX)";
            case DataType.BooleanType b -> "BIT";
            case DataType.DateType d    -> "DATE";
            case DataType.TimeType t    -> "TIME(" + t.fractionalSeconds() + ")";
            case DataType.DatetimeType dt -> "DATETIME2(" + dt.fractionalSeconds() + ")";
            case DataType.TimestampType ts -> "DATETIME2(" + ts.fractionalSeconds() + ")";
            case DataType.JsonType j    -> "NVARCHAR(MAX)";
            case DataType.UuidType u    -> "UNIQUEIDENTIFIER";
            case DataType.BinaryType b  -> "BINARY(" + b.length() + ")";
            case DataType.VarbinaryType vb -> "VARBINARY(" + vb.length() + ")";
            case DataType.BlobType bl   -> "VARBINARY(MAX)";
            case DataType.EnumType e    -> "VARCHAR(255)";
            default -> type.typeName();
        };
    }

    // ══════════════════════════════════════════════════
    //  SELECT
    // ══════════════════════════════════════════════════

    private String generateSelect(IRSelect sel, GenerateOptions opt) {
        scanKeepFromSelect(sel);
        boolean hasKeep = !keepCols.isEmpty();
        String partitionBy = partitionFromGroupBy(sel, opt);

        var sb = new StringBuilder();

        // CTE
        if (sel.core().withClause() != null && !sel.core().withClause().isEmpty()) {
            sb.append("WITH ");
            if (sel.core().withClause().get(0).recursive()) sb.append("RECURSIVE ");
            sb.append(sel.core().withClause().stream()
                .map(cte -> quoteIdentifier(cte.name()) + " AS (" + generateSelect(cte.query(), opt) + ")")
                .collect(Collectors.joining(", ")));
            sb.append(" ");
        }

        sb.append("SELECT ");
        if (sel.core().distinct()) sb.append("DISTINCT ");

        // Generate SELECT items
        keepIdx = 0;
        sb.append(sel.core().projections().stream()
            .map(p -> generateSelectItem(p, opt))
            .collect(Collectors.joining(", ")));

        // FROM
        if (sel.core().from() != null && !sel.core().from().isEmpty()) {
            if (hasKeep) {
                String fromSql = sel.core().from().stream().map(f -> generateTableRef(f, opt)).collect(Collectors.joining(", "));
                sb.append(wrapFromWithKeep(fromSql, partitionBy));
            } else {
                sb.append(" FROM ");
                sb.append(sel.core().from().stream().map(f -> generateTableRef(f, opt)).collect(Collectors.joining(", ")));
            }
        }

        if (sel.core().where() != null) sb.append(" WHERE ").append(generateExpr(sel.core().where(), opt));
        if (sel.core().groupBy() != null && !sel.core().groupBy().isEmpty()) {
            sb.append(" GROUP BY ");
            sb.append(sel.core().groupBy().stream().map(g -> generateExpr(g.expr(), opt)).collect(Collectors.joining(", ")));
        }
        if (sel.core().having() != null) sb.append(" HAVING ").append(generateExpr(sel.core().having(), opt));

        // ORDER BY
        if (sel.orderBy() != null && !sel.orderBy().isEmpty()) {
            sb.append(" ORDER BY ");
            sb.append(sel.orderBy().stream()
                .map(o -> generateExpr(o.expr(), opt) + (o.dir() == OrderDir.DESC ? " DESC" : " ASC"))
                .collect(Collectors.joining(", ")));
        }

        // SQL Server 2012+: OFFSET ... FETCH
        if (sel.fetch() != null) {
            if (sel.fetch().offset() != null) {
                sb.append(" OFFSET ").append(generateExpr(sel.fetch().offset(), opt)).append(" ROWS");
            }
            if (sel.fetch().limit() != null) {
                if (sel.fetch().offset() == null) sb.append(" OFFSET 0 ROWS");
                sb.append(" FETCH NEXT ").append(generateExpr(sel.fetch().limit(), opt)).append(" ROWS ONLY");
            }
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
            case IRWildcardSelect ws -> {
                if (ws.wildcard().qualifier() != null)
                    yield quoteIdentifier(ws.wildcard().qualifier()) + ".*";
                yield "*";
            }
        };
    }

    // ══════════════════════════════════════════════════
    //  Table references
    // ══════════════════════════════════════════════════

    private String generateTableRef(IRTableRef ref, GenerateOptions opt) {
        return switch (ref) {
            case IRTableName tn -> {
                String result = quoteIdentifier(tn.name());
                if (tn.alias() != null) result += " AS " + quoteIdentifier(tn.alias());
                yield result;
            }
            case IRJoin jn -> {
                String left = generateTableRef(jn.left(), opt);
                String right = generateTableRef(jn.right(), opt);
                String joinType = switch (jn.type()) {
                    case INNER -> "INNER JOIN";
                    case LEFT  -> "LEFT JOIN";
                    case RIGHT -> "RIGHT JOIN";
                    case CROSS -> "CROSS JOIN";
                    case FULL  -> "FULL OUTER JOIN";
                };
                String on = jn.onCondition() != null ? " ON " + generateExpr(jn.onCondition(), opt) : "";
                yield left + " " + joinType + " " + right + on;
            }
            case IRSubqueryTable sq -> "(" + generateSelect(sq.query(), opt) + ") AS " + quoteIdentifier(sq.alias());
            case IRFunctionTable ft -> ft.funcName() + "(" + ft.args().stream().map(a -> generateExpr(a, opt)).collect(Collectors.joining(", ")) + ") AS " + quoteIdentifier(ft.alias());
        };
    }

    // ══════════════════════════════════════════════════
    //  Expressions
    // ══════════════════════════════════════════════════

    protected String generateExpr(IRExpr expr, GenerateOptions opt) {
        return switch (expr) {
            case IRLiteral lit -> generateLiteral(lit);
            case IRColumnRef cr -> {
                if (cr.qualifier() != null)
                    yield quoteIdentifier(cr.qualifier()) + "." + quoteIdentifier(cr.name());
                yield quoteIdentifier(cr.name());
            }
            case IRWildcard wc -> {
                if (wc.qualifier() != null) yield quoteIdentifier(wc.qualifier()) + ".*";
                yield "*";
            }
            case IRParameter p -> "?";
            case IRBinaryOp bo -> generateBinaryOp(bo, opt);
            case IRUnaryOp uo -> generateUnaryOp(uo, opt);
            case IRFunctionCall fc -> generateFunctionCall(fc, opt);
            case IRCase cs -> generateCase(cs, opt);
            case IRCast ct -> "CAST(" + generateExpr(ct.expr(), opt) + " AS " + mapType(ct.targetType()) + ")";
            case IRSubquery sq -> "(" + generateSelect(sq.query(), opt) + ")";
            case IRBetween bt -> generateExpr(bt.expr(), opt) + (bt.not() ? " NOT" : "") + " BETWEEN "
                + generateExpr(bt.low(), opt) + " AND " + generateExpr(bt.high(), opt);
            case IRInList il -> {
                String r = generateExpr(il.expr(), opt) + (il.not() ? " NOT" : "") + " IN (";
                if (il.subquery() != null) r += generateSelect(il.subquery(), opt);
                else r += il.values().stream().map(v -> generateExpr(v, opt)).collect(Collectors.joining(", "));
                yield r + ")";
            }
            case IRIsNull isn -> generateExpr(isn.expr(), opt) + (isn.not() ? " IS NOT NULL" : " IS NULL");
            default -> throw new UnsupportedOperationException(
                "SQL Server backend cannot generate expression '" + expr.getClass().getSimpleName()
                + "'. Supported: IRLiteral, IRColumnRef, IRWildcard, IRParameter, IRBinaryOp, IRUnaryOp, "
                + "IRFunctionCall, IRCase, IRCast, IRSubquery, IRBetween, IRInList, IRIsNull");
        };
    }

    private String generateLiteral(IRLiteral lit) {
        if (lit.value() == null) return "NULL";
        return switch (lit.type()) {
            case DataType.IntType i     -> lit.value().toString();
            case DataType.FloatType f   -> lit.value().toString();
            case DataType.DecimalType d -> lit.value().toString();
            case DataType.BooleanType b -> ((Boolean) lit.value()) ? "1" : "0";
            case DataType.NullType n    -> "NULL";
            default -> "'" + lit.value().toString().replace("'", "''") + "'";
        };
    }

    private String generateBinaryOp(IRBinaryOp bo, GenerateOptions opt) {
        String left = generateExpr(bo.left(), opt);
        String right = generateExpr(bo.right(), opt);
        String op = switch (bo.op()) {
            case ADD -> " + ";
            case SUB -> " - ";
            case MUL -> " * ";
            case DIV -> " / ";
            case MOD -> " % ";
            case EQ  -> " = ";
            case NEQ -> " <> ";
            case LT  -> " < ";
            case GT  -> " > ";
            case LTE -> " <= ";
            case GTE -> " >= ";
            case AND -> " AND ";
            case OR  -> " OR ";
            case CONCAT -> " + ";     // SQL Server string concat uses +
            case LIKE -> " LIKE ";
            case NOT_LIKE -> " NOT LIKE ";
            case BETWEEN -> " BETWEEN ";
            case IS_DISTINCT_FROM -> " IS DISTINCT FROM ";
            default -> " " + bo.op().name() + " ";
        };
        return "(" + left + op + right + ")";
    }

    private String generateUnaryOp(IRUnaryOp uo, GenerateOptions opt) {
        String operand = generateExpr(uo.operand(), opt);
        return switch (uo.op()) {
            case NEG -> "-" + operand;
            case NOT -> "NOT " + operand;
            case IS_NULL -> operand + " IS NULL";
            case IS_NOT_NULL -> operand + " IS NOT NULL";
            default -> uo.op().name() + "(" + operand + ")";
        };
    }

    // ══════════════════════════════════════════════════
    //  Functions
    // ══════════════════════════════════════════════════

    private String generateCase(IRCase cs, GenerateOptions opt) {
        var sb = new StringBuilder("CASE");
        for (var when : cs.whens())
            sb.append(" WHEN ").append(generateExpr(when.condition(), opt))
              .append(" THEN ").append(generateExpr(when.result(), opt));
        if (cs.elseExpr() != null) sb.append(" ELSE ").append(generateExpr(cs.elseExpr(), opt));
        return sb.append(" END").toString();
    }

    // ══════════════════════════════════════════════════
    //  INSERT / UPDATE / DELETE
    // ══════════════════════════════════════════════════

    private String generateInsert(IRInsert ins, GenerateOptions opt) {
        var sb = new StringBuilder("INSERT INTO ").append(generateTableRef(ins.table(), opt));
        if (ins.columns() != null && !ins.columns().isEmpty())
            sb.append(" (").append(ins.columns().stream().map(this::quoteIdentifier).collect(Collectors.joining(", "))).append(")");
        if (ins.selectSource() != null)
            sb.append(" ").append(generateSelect(ins.selectSource(), opt));
        else if (ins.values() != null && !ins.values().isEmpty()) {
            sb.append(" VALUES ");
            sb.append(ins.values().stream()
                .map(row -> "(" + row.stream().map(v -> generateExpr(v, opt)).collect(Collectors.joining(", ")) + ")")
                .collect(Collectors.joining(", ")));
        }
        return sb.toString();
    }

    private String generateUpdate(IRUpdate upd, GenerateOptions opt) {
        var sb = new StringBuilder("UPDATE ").append(generateTableRef(upd.table(), opt));
        sb.append(" SET ");
        sb.append(upd.sets().stream()
            .map(s -> quoteIdentifier(s.column()) + " = " + generateExpr(s.value(), opt))
            .collect(Collectors.joining(", ")));
        if (upd.where() != null) sb.append(" WHERE ").append(generateExpr(upd.where(), opt));
        return sb.toString();
    }

    private String generateDelete(IRDelete del, GenerateOptions opt) {
        var sb = new StringBuilder("DELETE FROM ").append(generateTableRef(del.table(), opt));
        if (del.where() != null) sb.append(" WHERE ").append(generateExpr(del.where(), opt));
        return sb.toString();
    }

    private String generateMerge(IRMerge merge, GenerateOptions opt) {
        var sb = new StringBuilder("MERGE INTO ").append(generateTableRef(merge.target(), opt));
        sb.append(" USING ").append(generateTableRef(merge.source(), opt));
        sb.append(" ON ").append(generateExpr(merge.onCondition(), opt));
        for (var action : merge.actions()) {
            if (action instanceof IRStatement.MergeInsert mi) {
                sb.append(" WHEN NOT MATCHED THEN INSERT");
                if (mi.columns() != null && !mi.columns().isEmpty())
                    sb.append(" (").append(mi.columns().stream().map(this::quoteIdentifier).collect(Collectors.joining(", "))).append(")");
                sb.append(" VALUES (");
                sb.append(mi.values().stream().map(v -> generateExpr(v, opt)).collect(Collectors.joining(", ")));
                sb.append(")");
            } else if (action instanceof IRStatement.MergeUpdate mu) {
                sb.append(" WHEN MATCHED THEN UPDATE SET ");
                sb.append(mu.sets().stream()
                    .map(s -> quoteIdentifier(s.column()) + " = " + generateExpr(s.value(), opt))
                    .collect(Collectors.joining(", ")));
            } else if (action instanceof IRStatement.MergeDelete) {
                sb.append(" WHEN MATCHED THEN DELETE");
            }
        }
        sb.append(";");
        return sb.toString();
    }

    // ══════════════════════════════════════════════════
    //  DDL
    // ══════════════════════════════════════════════════

    private String generateCreateTable(IRCreateTable ct, GenerateOptions opt) {
        var sb = new StringBuilder("CREATE TABLE ");
        sb.append(generateTableRef(ct.name(), opt)).append(" (\n");
        sb.append(ct.columns().stream()
            .map(col -> generateColumnDef(col, opt))
            .collect(Collectors.joining(",\n")));
        for (var col : ct.columns()) {
            for (var check : generateEnumChecks(col)) {
                sb.append(",\n").append(check);
            }
        }
        if (ct.constraints() != null && !ct.constraints().isEmpty()) {
            sb.append(",\n");
            sb.append(ct.constraints().stream()
                .map(c -> generateTableConstraint(c, opt))
                .collect(Collectors.joining(",\n")));
        }
        sb.append("\n)");
        return sb.toString();
    }

    private String generateColumnDef(IRColumnDef col, GenerateOptions opt) {
        var sb = new StringBuilder("  ").append(quoteIdentifier(col.name())).append(" ").append(mapType(col.type()));
        // IDENTITY for auto-increment
        boolean hasIdentity = false;
        if (col.constraints() != null) {
            for (var c : col.constraints()) {
                if (c instanceof ColPrimaryKey pk && pk.autoIncrement()) {
                    sb.append(" IDENTITY(1,1)");
                    hasIdentity = true;
                }
            }
        }
        if (col.defaultValue() != null)
            sb.append(" DEFAULT ").append(generateExpr(col.defaultValue(), opt));
        if (col.constraints() != null) {
            for (var c : col.constraints()) {
                if (c instanceof ColNotNull) sb.append(" NOT NULL");
                else if (c instanceof ColPrimaryKey pk && !pk.autoIncrement()) sb.append(" PRIMARY KEY");
                else if (c instanceof ColUnique) sb.append(" UNIQUE");
                else if (c instanceof ColCheck chk)
                    sb.append(" CHECK (").append(generateExpr(chk.condition(), opt)).append(")");
                else if (c instanceof ColReferences ref)
                    sb.append(" REFERENCES ").append(quoteIdentifier(ref.targetTable()))
                      .append("(").append(quoteIdentifier(ref.targetColumn())).append(")");
            }
        }
        return sb.toString();
    }

    private List<String> generateEnumChecks(IRColumnDef col) {
        if (col.type() instanceof DataType.EnumType e) {
            String values = e.values().stream()
                .map(v -> "'" + v.replace("'", "''") + "'")
                .collect(Collectors.joining(", "));
            return List.of("  CHECK (" + quoteIdentifier(col.name()) + " IN (" + values + "))");
        }
        return List.of();
    }

    private String generateTableConstraint(IRTableConstraint c, GenerateOptions opt) {
        return switch (c) {
            case TBPrimaryKey pk -> {
                String cols = pk.columns().stream().map(this::quoteIdentifier).collect(Collectors.joining(", "));
                yield "  PRIMARY KEY (" + cols + ")";
            }
            case TBUnique uq -> {
                String cols = uq.columns().stream().map(this::quoteIdentifier).collect(Collectors.joining(", "));
                yield "  UNIQUE (" + cols + ")";
            }
            case TBForeignKey fk -> {
                String cols = fk.columns().stream().map(this::quoteIdentifier).collect(Collectors.joining(", "));
                String tcols = fk.targetColumns().stream().map(this::quoteIdentifier).collect(Collectors.joining(", "));
                yield "  FOREIGN KEY (" + cols + ") REFERENCES " +
                    quoteIdentifier(fk.targetTable()) + "(" + tcols + ")";
            }
            case TBCheck chk -> "  CHECK (" + generateExpr(chk.condition(), opt) + ")";
        };
    }

    private String generateCreateIndex(IRCreateIndex idx, GenerateOptions opt) {
        var sb = new StringBuilder("CREATE ");
        if (idx.unique()) sb.append("UNIQUE ");
        sb.append("INDEX ").append(quoteIdentifier(idx.name()));
        sb.append(" ON ").append(quoteIdentifier(idx.table().name())).append(" (");
        sb.append(idx.columns().stream()
            .map(c -> quoteIdentifier(c.name()) + (c.dir() == OrderDir.DESC ? " DESC" : ""))
            .collect(Collectors.joining(", ")));
        sb.append(")");
        if (idx.whereClause() != null) sb.append(" WHERE ").append(generateExpr(idx.whereClause(), opt));
        return sb.toString();
    }
}
