package com.usql.backend;

import com.usql.catalog.FunctionCatalog;
import com.usql.dialect.Dialect;
import com.usql.ir.*;
import com.usql.ir.IRExpr.*;
import com.usql.ir.IRStatement.*;

import java.util.ArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Generates 达梦 DM SQL from the Semantic IR.
 *
 * 达梦 is primarily Oracle-compatible but with several key differences:
 *   - Uses VARCHAR (not VARCHAR2)
 *   - Uses BIT for boolean (not NUMBER(1))
 *   - Supports both AUTO_INCREMENT (like MySQL) and SEQUENCE (like Oracle)
 *   - Uses LIMIT/OFFSET natively (DM 8+)
 *   - DATE also contains time portion (like Oracle)
 */
public class DmBackend extends AbstractDialectBackend {

    @Override
    public Dialect targetDialect() { return Dialect.DM; }

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
            case IRTruncateTable tt          -> generateTruncateTable(tt, options);
            case IRAlterTableAddColumn aa    -> generateAlterTableAddColumn(aa, options);
            case IRAlterTableDropColumn ad   -> generateAlterTableDropColumn(ad, options);
            default ->
                throw new UnsupportedOperationException(
                    "DM backend cannot generate statement '" + statement.getClass().getSimpleName()
                    + "'. Supported: IRSelect, IRInsert, IRUpdate, IRDelete, IRMerge, IRCreateTable, IRCreateIndex, IRCreateProcedure, IRCreateFunction, IRCall, IRDropTable, IRTruncateTable, IRAlterTableAddColumn, IRAlterTableDropColumn");
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
                case 8  -> "TINYINT";
                case 16 -> "SMALLINT";
                case 32 -> "INT";
                case 64 -> "BIGINT";
                default -> "INT";
            };
            case DataType.DecimalType d -> "DECIMAL(" + d.precision() + "," + d.scale() + ")";
            case DataType.FloatType f   -> f.bits() <= 32 ? "REAL" : "DOUBLE";
            case DataType.CharType c    -> "CHAR(" + c.length() + ")";
            case DataType.VarcharType v -> "VARCHAR(" + v.length() + ")";
            case DataType.TextType t    -> "CLOB";
            case DataType.BooleanType b -> "BIT";
            case DataType.DateType d    -> "DATE";
            case DataType.TimeType t    -> "TIME(" + t.fractionalSeconds() + ")";
            case DataType.DatetimeType dt -> "TIMESTAMP(" + dt.fractionalSeconds() + ")";
            case DataType.TimestampType ts -> "TIMESTAMP(" + ts.fractionalSeconds() + ") WITH TIME ZONE";
            case DataType.IntervalYearMonth i -> "INTERVAL YEAR TO MONTH";
            case DataType.IntervalDaySecond i -> "INTERVAL DAY TO SECOND(" + i.fractionalSeconds() + ")";
            case DataType.JsonType j    -> "CLOB";
            case DataType.UuidType u    -> "VARCHAR(36)";
            case DataType.BinaryType b  -> "BINARY(" + b.length() + ")";
            case DataType.VarbinaryType vb -> "VARBINARY(" + vb.length() + ")";
            case DataType.BlobType bl   -> "BLOB";
            case DataType.EnumType e    -> "VARCHAR(255)";
            default -> type.typeName();
        };
    }

    // ══════════════════════════════════════════════════
    //  SELECT — DM 8+ supports LIMIT/OFFSET natively
    // ══════════════════════════════════════════════════

    private String generateSelect(IRSelect sel, GenerateOptions opt) {
        scanKeepFromSelect(sel);
        boolean hasKeep = !keepCols.isEmpty();
        String partitionBy = partitionFromGroupBy(sel, opt);

        var sb = new StringBuilder();

        if (sel.core().withClause() != null && !sel.core().withClause().isEmpty()) {
            sb.append("WITH ");
            // DM uses WITH for both recursive and non-recursive CTEs
            // Recursive CTEs need column alias list
            sb.append(sel.core().withClause().stream()
                .map(cte -> {
                    String cols = "";
                    if (cte.recursive() && (cte.columns() == null || cte.columns().isEmpty())) {
                        List<String> names = new ArrayList<>();
                        if (cte.query().core().projections() != null) {
                            for (var p : cte.query().core().projections()) {
                                if (p instanceof IRExprSelect es && es.alias() != null)
                                    names.add(quoteIdentifier(es.alias()));
                            }
                        }
                        if (!names.isEmpty()) cols = " (" + String.join(", ", names) + ")";
                    }
                    return quoteIdentifier(cte.name()) + cols + " AS (" + generateSelect(cte.query(), opt) + ")";
                })
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
        } else {
            sb.append(" FROM DUAL");
        }

        if (sel.core().where() != null)
            sb.append(" WHERE ").append(generateExpr(sel.core().where(), opt));

        if (sel.core().groupBy() != null && !sel.core().groupBy().isEmpty()) {
            sb.append(" GROUP BY ");
            sb.append(sel.core().groupBy().stream()
                .map(g -> generateExpr(g.expr(), opt))
                .collect(Collectors.joining(", ")));
        }

        if (sel.core().having() != null)
            sb.append(" HAVING ").append(generateExpr(sel.core().having(), opt));

        if (sel.orderBy() != null && !sel.orderBy().isEmpty()) {
            sb.append(" ORDER BY ");
            sb.append(sel.orderBy().stream()
                .map(o -> generateExpr(o.expr(), opt) + (o.dir() == OrderDir.DESC ? " DESC" : " ASC"))
                .collect(Collectors.joining(", ")));
        }

        // DM 8+ supports native LIMIT/OFFSET
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
                if (es.alias() != null) expr += " " + quoteIdentifier(es.alias());
                yield expr;
            }
            case IRWildcardSelect ws -> {
                if (ws.wildcard().qualifier() != null)
                    yield quoteIdentifier(ws.wildcard().qualifier()) + ".*";
                yield "*";
            }
        };
    }

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
                    case INNER -> "INNER JOIN"; case LEFT -> "LEFT JOIN";
                    case RIGHT -> "RIGHT JOIN"; case CROSS -> "CROSS JOIN";
                    case FULL -> "FULL OUTER JOIN";
                };
                String right = generateTableRef(jn.right(), opt);
                String on = jn.onCondition() != null
                    ? " ON " + generateExpr(jn.onCondition(), opt) : "";
                yield left + " " + joinType + " " + right + on;
            }
            case IRSubqueryTable sq -> "(" + generateSelect(sq.query(), opt) + ") " + quoteIdentifier(sq.alias());
            case IRFunctionTable ft -> ft.funcName() + "(" +
                ft.args().stream().map(a -> generateExpr(a, opt)).collect(Collectors.joining(", ")) +
                ") " + quoteIdentifier(ft.alias());
        };
    }

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
                "DM backend cannot generate expression '" + expr.getClass().getSimpleName()
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
            case DataType.BooleanType b -> ((Boolean) lit.value()) ? "1" : "0";
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
            case IS_TRUE -> operand + " = 1";
            case IS_NOT_TRUE -> "(" + operand + " IS NULL OR " + operand + " != 1)";
            case IS_FALSE -> operand + " = 0";
            case IS_NOT_FALSE -> "(" + operand + " IS NULL OR " + operand + " != 0)";
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
    //  MERGE — DM native (Oracle-compatible)
    // ══════════════════════════════════════════════════

    private String generateMerge(IRMerge merge, GenerateOptions opt) {
        var sb = new StringBuilder("MERGE INTO ");
        sb.append(generateTableRef(merge.target(), opt));
        sb.append(" USING ").append(generateTableRef(merge.source(), opt));
        sb.append(" ON (").append(generateExpr(merge.onCondition(), opt)).append(")");
        for (var action : merge.actions()) {
            if (action instanceof IRMerge.MergeUpdate upd) {
                sb.append(" WHEN MATCHED THEN UPDATE SET ");
                sb.append(upd.sets().stream()
                    .map(s -> quoteIdentifier(s.column()) + " = " + generateExpr(s.value(), opt))
                    .collect(Collectors.joining(", ")));
            } else if (action instanceof IRMerge.MergeInsert ins) {
                sb.append(" WHEN NOT MATCHED THEN INSERT (");
                sb.append(ins.columns().stream().map(this::quoteIdentifier)
                    .collect(Collectors.joining(", ")));
                sb.append(") VALUES (");
                sb.append(ins.values().stream().map(v -> generateExpr(v, opt))
                    .collect(Collectors.joining(", ")));
                sb.append(")");
            }
        }
        return sb.toString();
    }

    // ══════════════════════════════════════════════════
    //  CREATE TABLE
    // ══════════════════════════════════════════════════

    private String generateCreateTable(IRCreateTable ct, GenerateOptions opt) {
        var sb = new StringBuilder();
        sb.append("CREATE TABLE ");
        sb.append(generateTableRef(ct.name(), opt));
        sb.append(" (\n");
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
        if (!ct.ifNotExists()) return sb.toString();

        // DM doesn't support IF NOT EXISTS — PL/SQL wrapper (catch all duplicate errors)
        String ddl = sb.toString().replace("'", "''");
        return "BEGIN EXECUTE IMMEDIATE '" + ddl + "'; " +
               "EXCEPTION WHEN OTHERS THEN NULL; END;";
    }

    private String generateColumnDef(IRColumnDef col, GenerateOptions opt) {
        var sb = new StringBuilder("  ").append(quoteIdentifier(col.name())).append(" ").append(mapType(col.type()));
        if (col.constraints() != null) {
            for (var c : col.constraints()) {
                if (c instanceof ColNotNull) sb.append(" NOT NULL");
                else if (c instanceof ColPrimaryKey pk) {
                    sb.append(" PRIMARY KEY");
                    if (pk.autoIncrement()) sb.append(" IDENTITY"); // DM uses IDENTITY
                }
                else if (c instanceof ColUnique) sb.append(" UNIQUE");
                else if (c instanceof ColCheck chk)
                    sb.append(" CHECK (").append(generateExpr(chk.condition(), opt)).append(")");
                else if (c instanceof ColReferences ref)
                    sb.append(" REFERENCES ").append(quoteIdentifier(ref.targetTable()))
                      .append("(").append(quoteIdentifier(ref.targetColumn())).append(")");
                else if (c instanceof ColGenerated gen) {
                    sb.append(" GENERATED ALWAYS AS (").append(generateExpr(gen.expression(), opt)).append(")");
                    if (!gen.virtual()) sb.append(" STORED");
                }
            }
        }
        if (col.defaultValue() != null)
            sb.append(" DEFAULT ").append(generateExpr(col.defaultValue(), opt));
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

        if (!idx.ifNotExists()) return sb.toString();

        String ddl = sb.toString().replace("'", "''");
        return "BEGIN EXECUTE IMMEDIATE '" + ddl + "'; " +
               "EXCEPTION WHEN OTHERS THEN NULL; END;";
    }

    // ══════════════════════════════════════════════════
    //  Stored procedures — DM (Oracle-compatible) syntax
    // ══════════════════════════════════════════════════

    @Override
    protected String generateCreateProcedure(IRCreateProcedure cp, GenerateOptions opt) {
        var sb = new StringBuilder("CREATE ");
        if (cp.orReplace()) sb.append("OR REPLACE ");
        sb.append("PROCEDURE ").append(quoteIdentifier(cp.name()));
        sb.append(paramsDecl(cp.params(), opt));
        sb.append(" AS\n").append(cp.body()).append(";");
        return sb.toString();
    }

    @Override
    protected String generateCreateFunction(IRCreateFunction cf, GenerateOptions opt) {
        var sb = new StringBuilder("CREATE ");
        if (cf.orReplace()) sb.append("OR REPLACE ");
        sb.append("FUNCTION ").append(quoteIdentifier(cf.name()));
        sb.append(paramsDecl(cf.params(), opt));
        sb.append(" RETURN ").append(mapType(cf.returnType()));
        sb.append(" AS\n").append(cf.body()).append(";");
        return sb.toString();
    }

    @Override
    protected String paramDecl(ProcedureParam p, GenerateOptions opt) {
        String mode = switch (p.mode()) {
            case IN -> "";
            case OUT -> "OUT ";
            case INOUT -> "IN OUT ";
        };
        return quoteIdentifier(p.name()) + " " + mode + mapType(p.type());
    }

    @Override
    protected String generateDropTable(IRDropTable dt, GenerateOptions opt) {
        if (!dt.ifExists()) return "DROP TABLE " + quoteIdentifier(dt.name());
        return "BEGIN EXECUTE IMMEDIATE 'DROP TABLE " + quoteIdentifier(dt.name()).replace("'", "''")
            + "'; EXCEPTION WHEN OTHERS THEN NULL; END;";
    }

    private String escapeString(String s) {
        return s.replace("'", "''");
    }
}
