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
 * Generates PostgreSQL-compatible SQL from the Semantic IR.
 */
public class PgBackend extends AbstractDialectBackend {

    @Override
    public Dialect targetDialect() { return Dialect.POSTGRESQL; }

    @Override
    public String generate(IRStatement statement, GenerateOptions options) {
        return switch (statement) {
            case IRSelect sel    -> generateSelect(sel, options);
            case IRInsert ins    -> generateInsert(ins, options);
            case IRUpdate upd    -> generateUpdate(upd, options);
            case IRDelete del    -> generateDelete(del, options);
            case IRMerge merge   -> generateMerge(merge, options);
            case IRCreateTable ct      -> generateCreateTable(ct, options);
            case IRCreateIndex ci       -> generateCreateIndex(ci, options);
            case IRCreateProcedure cp        -> generateCreateProcedure(cp, options);
            case IRCreateFunction cf         -> generateCreateFunction(cf, options);
            case IRCall call                 -> generateCall(call, options);
            case IRDropTable dt              -> generateDropTable(dt, options);
            case IRDropIndex di              -> generateDropIndex(di, options);
            case IRTruncateTable tt          -> generateTruncateTable(tt, options);
            case IRAlterTableAddColumn aa    -> generateAlterTableAddColumn(aa, options);
            case IRAlterTableDropColumn ad   -> generateAlterTableDropColumn(ad, options);
            case IRAlterColumnType act       -> generateAlterColumnType(act, options);
            case IRAlterColumnSetDefault acs -> generateAlterColumnSetDefault(acs, options);
            case IRAlterColumnDropDefault acd -> generateAlterColumnDropDefault(acd, options);
            case IRRenameColumn rc           -> generateRenameColumn(rc, options);
            default ->
                throw new UnsupportedOperationException(
                    "PostgreSQL backend cannot generate statement '" + statement.getClass().getSimpleName()
                    + "'. Supported: IRSelect, IRInsert, IRUpdate, IRDelete, IRMerge, IRCreateTable, IRCreateIndex, IRCreateProcedure, IRCreateFunction, IRCall, IRDropTable, IRDropIndex, IRTruncateTable, IRAlterTableAddColumn, IRAlterTableDropColumn");
        };
    }

    @Override
    public String quoteIdentifier(String id) {
        return "\"" + id.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String mapType(DataType type) {
        return switch (type) {
            case DataType.IntType t -> switch (t.bits()) {
                case 8  -> "SMALLINT";  // PG has no TINYINT
                case 16 -> "SMALLINT";
                case 32 -> "INTEGER";
                case 64 -> "BIGINT";
                default -> "INTEGER";
            };
            case DataType.DecimalType d -> "NUMERIC(" + d.precision() + "," + d.scale() + ")";
            case DataType.FloatType f   -> f.bits() <= 32 ? "REAL" : "DOUBLE PRECISION";
            case DataType.CharType c    -> "CHAR(" + c.length() + ")";
            case DataType.VarcharType v -> "VARCHAR(" + v.length() + ")";
            case DataType.TextType t    -> "TEXT";
            case DataType.BooleanType b -> "BOOLEAN";
            case DataType.DateType d    -> "DATE";
            case DataType.TimeType t    -> "TIME(" + t.fractionalSeconds() + ")";
            case DataType.DatetimeType dt -> "TIMESTAMP(" + dt.fractionalSeconds() + ")";
            case DataType.TimestampType ts -> "TIMESTAMPTZ(" + ts.fractionalSeconds() + ")";
            case DataType.IntervalYearMonth i -> "INTERVAL YEAR TO MONTH";
            case DataType.IntervalDaySecond i -> "INTERVAL DAY TO SECOND(" + i.fractionalSeconds() + ")";
            case DataType.JsonType j    -> "JSONB";
            case DataType.UuidType u    -> "UUID";
            case DataType.BinaryType b  -> "BYTEA";
            case DataType.VarbinaryType vb -> "BYTEA";
            case DataType.BlobType bl   -> "BYTEA";
            case DataType.ArrayType arr -> mapType(arr.elementType()) + "[]";
            case DataType.EnumType e    -> "VARCHAR(255)";
            default -> type.typeName();
        };
    }

    // ══════════════════════════════════════════════════
    //  SELECT
    // ══════════════════════════════════════════════════

    private String generateSelect(IRSelect sel, GenerateOptions opt) {
        // Scan for KEEP aggregates
        scanKeepFromSelect(sel);
        boolean hasKeep = !keepCols.isEmpty();
        String partitionBy = partitionFromGroupBy(sel, opt);

        var sb = new StringBuilder();

        // WITH clause
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

        keepIdx = 0;
        sb.append(sel.core().projections().stream()
            .map(p -> generateSelectItem(p, opt))
            .collect(Collectors.joining(", ")));

        if (sel.core().from() != null && !sel.core().from().isEmpty()) {
            if (hasKeep) {
                String fromSql = sel.core().from().stream().map(f -> generateTableRef(f, opt)).collect(Collectors.joining(", "));
                sb.append(wrapFromWithKeep(fromSql, partitionBy));
            } else {
                sb.append(" FROM ");
                sb.append(sel.core().from().stream()
                    .map(f -> generateTableRef(f, opt))
                    .collect(Collectors.joining(", ")));
            }
        }

        if (sel.core().where() != null) {
            sb.append(" WHERE ").append(generateExpr(sel.core().where(), opt));
        }

        if (sel.core().groupBy() != null && !sel.core().groupBy().isEmpty()) {
            sb.append(" GROUP BY ");
            sb.append(sel.core().groupBy().stream()
                .map(g -> {
                    String expr = generateExpr(g.expr(), opt);
                    return switch (g.kind()) {
                        case PLAIN -> expr;
                        case ROLLUP -> "ROLLUP(" + stripFunc(expr, "ROLLUP") + ")";
                        case CUBE -> "CUBE(" + stripFunc(expr, "CUBE") + ")";
                        case GROUPING_SETS -> "GROUPING SETS(" + stripFunc(expr, "GROUPING SETS") + ")";
                    };
                })
                .collect(Collectors.joining(", ")));
        }

        if (sel.core().having() != null) {
            sb.append(" HAVING ").append(generateExpr(sel.core().having(), opt));
        }

        if (sel.orderBy() != null && !sel.orderBy().isEmpty()) {
            sb.append(" ORDER BY ");
            sb.append(sel.orderBy().stream()
                .map(o -> {
                    String s = generateExpr(o.expr(), opt);
                    if (o.dir() == OrderDir.DESC) s += " DESC";
                    if (o.nulls() == NullsOrder.FIRST) s += " NULLS FIRST";
                    else if (o.nulls() == NullsOrder.LAST) s += " NULLS LAST";
                    return s;
                })
                .collect(Collectors.joining(", ")));
        }

        // PG supports LIMIT/OFFSET natively
        if (sel.fetch() != null) {
            if (sel.fetch().limit() != null)
                sb.append(" LIMIT ").append(generateExpr(sel.fetch().limit(), opt));
            if (sel.fetch().offset() != null)
                sb.append(" OFFSET ").append(generateExpr(sel.fetch().offset(), opt));
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
                if (tn.alias() != null) result += " " + quoteIdentifier(tn.alias());
                yield result;
            }
            case IRJoin jn -> {
                String left = generateTableRef(jn.left(), opt);
                String joinType = switch (jn.type()) {
                    case INNER -> "INNER JOIN";
                    case LEFT  -> "LEFT JOIN";
                    case RIGHT -> "RIGHT JOIN";
                    case CROSS -> "CROSS JOIN";
                    case FULL  -> "FULL OUTER JOIN";
                };
                String right = generateTableRef(jn.right(), opt);
                String on = jn.onCondition() != null
                    ? " ON " + generateExpr(jn.onCondition(), opt) : "";
                yield left + " " + joinType + " " + right + on;
            }
            case IRSubqueryTable sq -> "(" + generateSelect(sq.query(), opt) + ") " + quoteIdentifier(sq.alias());
            case IRFunctionTable ft -> {
                String result = ft.lateral() ? "LATERAL " : "";
                result += ft.funcName() + "(" +
                    ft.args().stream().map(a -> generateExpr(a, opt)).collect(Collectors.joining(", ")) +
                    ") " + quoteIdentifier(ft.alias());
                yield result;
            }
        };
    }

    // ══════════════════════════════════════════════════
    //  Expressions
    // ══════════════════════════════════════════════════

    protected String generateExpr(IRExpr expr, GenerateOptions opt) {
        return switch (expr) {
            case IRLiteral lit -> generateLiteral(lit);
            case IRColumnRef col -> {
                String name = quoteIdentifier(col.name());
                if (col.qualifier() != null) name = quoteIdentifier(col.qualifier()) + "." + name;
                yield name;
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
            case IRBetween btw -> generateExpr(btw.expr(), opt) + (btw.not() ? " NOT BETWEEN " : " BETWEEN ") +
                                  generateExpr(btw.low(), opt) + " AND " + generateExpr(btw.high(), opt);
            case IRInList in -> {
                String r = generateExpr(in.expr(), opt) + (in.not() ? " NOT IN (" : " IN (");
                if (in.subquery() != null) r += generateSelect(in.subquery(), opt);
                else r += in.values().stream().map(v -> generateExpr(v, opt)).collect(Collectors.joining(", "));
                yield r + ")";
            }
            case IRIsNull isn -> generateExpr(isn.expr(), opt) + (isn.not() ? " IS NOT NULL" : " IS NULL");
            default -> throw new UnsupportedOperationException(
                "PostgreSQL backend cannot generate expression '" + expr.getClass().getSimpleName()
                + "'. Supported: IRLiteral, IRColumnRef, IRWildcard, IRParameter, IRBinaryOp, IRUnaryOp, "
                + "IRFunctionCall, IRCase, IRCast, IRSubquery, IRBetween, IRInList, IRIsNull");
        };
    }

    private String generateLiteral(IRLiteral lit) {
        if (lit.value() == null) return "NULL";
        if (lit.type() == null) return lit.value().toString();
        return switch (lit.type()) {
            case DataType.IntType i     -> lit.value().toString();
            case DataType.FloatType f   -> lit.value().toString();
            case DataType.DecimalType d -> lit.value().toString();
            case DataType.BooleanType b -> ((Boolean) lit.value()) ? "TRUE" : "FALSE";
            case DataType.NullType n    -> "NULL";
            default -> "'" + escapeString(lit.value().toString()) + "'";
        };
    }

    private String generateBinaryOp(IRBinaryOp op, GenerateOptions opt) {
        String left = generateExpr(op.left(), opt);
        String right = generateExpr(op.right(), opt);
        String operator = switch (op.op()) {
            case ADD -> " + "; case SUB -> " - "; case MUL -> " * "; case DIV -> " / ";
            case MOD -> " % ";
            case EQ -> " = "; case NEQ -> " != ";
            case LT -> " < "; case GT -> " > "; case LTE -> " <= "; case GTE -> " >= ";
            case AND -> " AND "; case OR -> " OR ";
            case CONCAT -> " || ";
            case LIKE -> " LIKE "; case NOT_LIKE -> " NOT LIKE ";
            case IS_DISTINCT_FROM -> " IS DISTINCT FROM ";
            case IN -> " IN "; case NOT_IN -> " NOT IN "; case BETWEEN -> " BETWEEN ";
        };
        return "(" + left + operator + right + ")";
    }

    private String generateUnaryOp(IRUnaryOp op, GenerateOptions opt) {
        String operand = generateExpr(op.operand(), opt);
        return switch (op.op()) {
            case NEG -> "-(" + operand + ")";
            case NOT -> "NOT (" + operand + ")";
            case IS_NULL -> operand + " IS NULL";
            case IS_NOT_NULL -> operand + " IS NOT NULL";
            case IS_TRUE -> operand + " IS TRUE";
            case IS_NOT_TRUE -> operand + " IS NOT TRUE";
            case IS_FALSE -> operand + " IS FALSE";
            case IS_NOT_FALSE -> operand + " IS NOT FALSE";
            case EXISTS -> "EXISTS " + operand;
            case UNIQUE -> "UNIQUE " + operand;
        };
    }

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
        var sb = new StringBuilder("INSERT INTO ");
        sb.append(generateTableRef(ins.table(), opt));
        if (ins.columns() != null && !ins.columns().isEmpty()) {
            sb.append(" (").append(ins.columns().stream().map(this::quoteIdentifier)
                .collect(Collectors.joining(", "))).append(")");
        }
        if (ins.values() != null && !ins.values().isEmpty()) {
            sb.append(" VALUES ");
            sb.append(ins.values().stream()
                .map(row -> "(" + row.stream().map(v -> generateExpr(v, opt))
                    .collect(Collectors.joining(", ")) + ")")
                .collect(Collectors.joining(", ")));
        } else if (ins.selectSource() != null) {
            sb.append(" ").append(generateSelect(ins.selectSource(), opt));
        }
        if (ins.ignoreErrors()) sb.append(" ON CONFLICT DO NOTHING");
        return sb.toString();
    }

    private String generateUpdate(IRUpdate upd, GenerateOptions opt) {
        var sb = new StringBuilder("UPDATE ");
        sb.append(generateTableRef(upd.table(), opt)).append(" SET ");
        sb.append(upd.sets().stream()
            .map(s -> quoteIdentifier(s.column()) + " = " + generateExpr(s.value(), opt))
            .collect(Collectors.joining(", ")));
        if (upd.where() != null) sb.append(" WHERE ").append(generateExpr(upd.where(), opt));
        return sb.toString();
    }

    private String generateDelete(IRDelete del, GenerateOptions opt) {
        var sb = new StringBuilder("DELETE FROM ");
        sb.append(generateTableRef(del.table(), opt));
        if (del.where() != null) sb.append(" WHERE ").append(generateExpr(del.where(), opt));
        return sb.toString();
    }

    // ══════════════════════════════════════════════════
    //  MERGE → INSERT ON CONFLICT
    // ══════════════════════════════════════════════════

    private String generateMerge(IRMerge merge, GenerateOptions opt) {
        IRMerge.MergeInsert ins = null;
        IRMerge.MergeUpdate upd = null;
        for (var a : merge.actions()) {
            if (a instanceof IRMerge.MergeInsert i) ins = i;
            if (a instanceof IRMerge.MergeUpdate u) upd = u;
        }
        var sb = new StringBuilder("INSERT INTO ");
        sb.append(tableName(merge.target()));
        if (ins != null) {
            sb.append(" (").append(ins.columns().stream().map(this::quoteIdentifier)
                .collect(Collectors.joining(", "))).append(") ");
            sb.append("SELECT ").append(ins.values().stream()
                .map(v -> generateExpr(v, opt)).collect(Collectors.joining(", ")));
            sb.append(" FROM ").append(generateTableRef(merge.source(), opt));
        }
        if (upd != null) {
            sb.append(" ON CONFLICT (");
            if (ins != null && !ins.columns().isEmpty())
                sb.append(quoteIdentifier(ins.columns().get(0)));
            sb.append(") DO UPDATE SET ");
            sb.append(upd.sets().stream()
                .map(s -> quoteIdentifier(s.column()) + " = EXCLUDED." + quoteIdentifier(s.column()))
                .collect(Collectors.joining(", ")));
        }
        return sb.toString();
    }

    // ══════════════════════════════════════════════════
    //  CREATE TABLE
    // ══════════════════════════════════════════════════

    private String generateCreateTable(IRCreateTable ct, GenerateOptions opt) {
        var sb = new StringBuilder("CREATE TABLE ");
        if (ct.ifNotExists()) sb.append("IF NOT EXISTS ");
        sb.append(generateTableRef(ct.name(), opt)).append(" (\n");
        sb.append(ct.columns().stream()
            .map(col -> generateColumnDef(col, opt))
            .collect(Collectors.joining(",\n")));
        // ENUM CHECKs
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
        if (col.constraints() != null) {
            for (var c : col.constraints()) {
                if (c instanceof ColNotNull) sb.append(" NOT NULL");
                else if (c instanceof ColPrimaryKey) {
                    sb.append(" PRIMARY KEY");
                    // PG uses GENERATED ALWAYS AS IDENTITY for auto-increment
                    if (((ColPrimaryKey) c).autoIncrement()) sb.append(" GENERATED ALWAYS AS IDENTITY");
                } else if (c instanceof ColUnique) sb.append(" UNIQUE");
                else if (c instanceof ColCheck chk) sb.append(" CHECK (").append(generateExpr(chk.condition(), opt)).append(")");
                else if (c instanceof ColReferences ref) {
                    sb.append(" REFERENCES ").append(quoteIdentifier(ref.targetTable()))
                      .append("(").append(quoteIdentifier(ref.targetColumn())).append(")");
                } else if (c instanceof ColGenerated gen) {
                    sb.append(" GENERATED ALWAYS AS (").append(generateExpr(gen.expression(), opt)).append(") STORED");
                }
            }
        }
        if (col.defaultValue() != null) sb.append(" DEFAULT ").append(generateExpr(col.defaultValue(), opt));
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
                String def = "  FOREIGN KEY (" + cols + ") REFERENCES " + quoteIdentifier(fk.targetTable()) + "(" + tcols + ")";
                if (fk.deferrable()) def += " DEFERRABLE INITIALLY DEFERRED";
                yield def;
            }
            case TBCheck chk -> "  CHECK (" + generateExpr(chk.condition(), opt) + ")";
        };
    }

    // ══════════════════════════════════════════════════
    //  CREATE INDEX
    // ══════════════════════════════════════════════════

    private String generateCreateIndex(IRCreateIndex idx, GenerateOptions opt) {
        var sb = new StringBuilder("CREATE ");
        if (idx.unique()) sb.append("UNIQUE ");
        sb.append("INDEX ");
        if (idx.ifNotExists()) sb.append("IF NOT EXISTS ");
        sb.append(quoteIdentifier(idx.name())).append(" ON ").append(quoteIdentifier(idx.table().name()));
        sb.append(" (").append(idx.columns().stream()
            .map(c -> quoteIdentifier(c.name()) + (c.dir() == OrderDir.DESC ? " DESC" : ""))
            .collect(Collectors.joining(", "))).append(")");
        if (idx.whereClause() != null)
            sb.append(" WHERE ").append(generateExpr(idx.whereClause(), opt));
        return sb.toString();
    }

    private String tableName(IRTableRef ref) {
        if (ref instanceof IRStatement.IRTableName tn) return quoteIdentifier(tn.name());
        return generateTableRef(ref, GenerateOptions.MINIMAL);
    }

    // ══════════════════════════════════════════════════
    //  Stored procedures — PostgreSQL-specific syntax
    // ══════════════════════════════════════════════════

    @Override
    protected String generateCreateProcedure(IRCreateProcedure cp, GenerateOptions opt) {
        var sb = new StringBuilder("CREATE ");
        if (cp.orReplace()) sb.append("OR REPLACE ");
        sb.append("PROCEDURE ").append(quoteIdentifier(cp.name()));
        String p = paramsDecl(cp.params(), opt);
        sb.append(p.isEmpty() ? "()" : p);
        sb.append(" LANGUAGE plpgsql AS $$\n").append(cp.body()).append("\n$$;");
        return sb.toString();
    }

    @Override
    protected String generateCreateFunction(IRCreateFunction cf, GenerateOptions opt) {
        var sb = new StringBuilder("CREATE ");
        if (cf.orReplace()) sb.append("OR REPLACE ");
        sb.append("FUNCTION ").append(quoteIdentifier(cf.name()));
        String p = paramsDecl(cf.params(), opt);
        sb.append(p.isEmpty() ? "()" : p);
        sb.append(" RETURNS ").append(mapType(cf.returnType()));
        sb.append(" LANGUAGE plpgsql AS $$\n").append(cf.body()).append("\n$$;");
        return sb.toString();
    }

    @Override
    protected String paramDecl(ProcedureParam p, GenerateOptions opt) {
        String mode = switch (p.mode()) {
            case IN -> "IN ";
            case OUT -> "OUT ";
            case INOUT -> "INOUT ";
        };
        return mode + quoteIdentifier(p.name()) + " " + mapType(p.type());
    }

    private String stripFunc(String expr, String name) {
        return expr.replaceFirst("^(?i)" + name.replace(" ", "\\s*") + "\\(", "").replaceFirst("\\)$", "");
    }

    private String escapeString(String s) {
        return s.replace("'", "''").replace("\\", "\\\\");
    }
}
