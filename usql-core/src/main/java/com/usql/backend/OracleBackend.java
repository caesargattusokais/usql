package com.usql.backend;

import com.usql.catalog.FunctionCatalog;
import com.usql.dialect.Dialect;
import com.usql.ir.*;
import com.usql.ir.IRExpr.*;
import com.usql.ir.IRStatement.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Generates Oracle-compatible SQL from the Semantic IR.
 * Key adaptations:
 *   - LIMIT/OFFSET → ROWNUM wrapping
 *   - BOOLEAN → NUMBER(1) comparison
 *   - Identifiers use double-quotes
 *   - VARCHAR → VARCHAR2
 */
public class OracleBackend implements DialectBackend {

    private FunctionCatalog functionCatalog;

    @Override
    public void setFunctionCatalog(FunctionCatalog catalog) { this.functionCatalog = catalog; }

    @Override
    public Dialect targetDialect() { return Dialect.ORACLE; }

    @Override
    public String generate(IRStatement statement, GenerateOptions options) {
        return switch (statement) {
            case IRSelect sel    -> generateSelect(sel, options);
            case IRInsert ins    -> generateInsert(ins, options);
            case IRUpdate upd    -> generateUpdate(upd, options);
            case IRDelete del    -> generateDelete(del, options);
            case IRMerge merge   -> generateMerge(merge, options);
            case IRCreateTable ct -> generateCreateTable(ct, options);
            case IRCreateIndex ci  -> generateCreateIndex(ci, options);
            default ->
                throw new UnsupportedOperationException("Unsupported: " + statement.getClass().getSimpleName());
        };
    }

    @Override
    public String quoteIdentifier(String id) {
        return "\"" + id.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String mapType(DataType type) {
        return switch (type) {
            case DataType.IntType t -> "NUMBER(" + switch (t.bits()) {
                case 8 -> 3; case 16 -> 5; case 32 -> 10; case 64 -> 19; default -> 10;
            } + ")";
            case DataType.DecimalType d -> "NUMBER(" + d.precision() + "," + d.scale() + ")";
            case DataType.FloatType f   -> f.bits() <= 32 ? "BINARY_FLOAT" : "BINARY_DOUBLE";
            case DataType.CharType c    -> "CHAR(" + c.length() + ")";
            case DataType.VarcharType v -> "VARCHAR2(" + v.length() + " CHAR)";
            case DataType.TextType t    -> "CLOB";
            case DataType.BooleanType b -> "NUMBER(1)";
            case DataType.DateType d    -> "DATE";
            case DataType.TimeType t    -> "INTERVAL DAY(0) TO SECOND(" + t.fractionalSeconds() + ")";
            case DataType.DatetimeType dt -> "TIMESTAMP(" + dt.fractionalSeconds() + ")";
            case DataType.TimestampType ts -> "TIMESTAMP(" + ts.fractionalSeconds() + ") WITH TIME ZONE";
            case DataType.IntervalYearMonth i -> "INTERVAL YEAR TO MONTH";
            case DataType.IntervalDaySecond i -> "INTERVAL DAY(" + i.fractionalSeconds() + ") TO SECOND";
            case DataType.JsonType j    -> "CLOB";
            case DataType.UuidType u    -> "RAW(16)";
            case DataType.BinaryType b  -> "RAW(" + b.length() + ")";
            case DataType.VarbinaryType vb -> "RAW(" + vb.length() + ")";
            case DataType.BlobType bl   -> "BLOB";
            case DataType.EnumType e    -> "VARCHAR2(255)";
            default -> type.typeName();
        };
    }

    // ══════════════════════════════════════════════════
    //  SELECT (with ROWNUM wrapping for LIMIT/OFFSET)
    // ══════════════════════════════════════════════════

    private String generateSelect(IRSelect sel, GenerateOptions opt) {
        String innerSQL = generateSelectCore(sel, opt, false);

        // Oracle uses FETCH FIRST (12c+) for LIMIT with ORDER BY
        // For broader compat, use ROWNUM wrapping
        if (sel.fetch() != null && sel.fetch().limit() != null) {
            boolean hasOffset = sel.fetch().offset() != null;
            long offsetVal = 0;

            // Check if offset is a literal for simpler generation
            if (sel.fetch().offset() instanceof IRLiteral lit && lit.value() instanceof Number n) {
                offsetVal = n.longValue();
            }

            if (hasOffset) {
                // ROWNUM 3-layer wrap: Oracle classic pattern
                // SELECT * FROM (SELECT inner.*, ROWNUM rn FROM (...) WHERE ROWNUM <= max) WHERE rn > min
                String limitExpr = generateExpr(sel.fetch().limit(), opt);
                String offsetExpr = generateExpr(sel.fetch().offset(), opt);

                return "SELECT " + quoteIdentifier("inner__") + ".* FROM (\n" +
                       "  SELECT " + quoteIdentifier("core__") + ".*, ROWNUM AS " + quoteIdentifier("rn__") + " FROM (\n" +
                       "    " + innerSQL + "\n" +
                       "  ) " + quoteIdentifier("core__") + "\n" +
                       "  WHERE ROWNUM <= " + limitExpr + (offsetVal > 0 ? " + " + offsetVal : "") + "\n" +
                       ") " + quoteIdentifier("inner__") + "\n" +
                       "WHERE " + quoteIdentifier("rn__") + " > " + offsetExpr;
            } else {
                // Simple ROWNUM: no offset
                return "SELECT * FROM (\n  " + innerSQL + "\n) WHERE ROWNUM <= " +
                       generateExpr(sel.fetch().limit(), opt);
            }
        }

        return innerSQL;
    }

    private String generateSelectCore(IRSelect sel, GenerateOptions opt, boolean stripOrderBy) {
        var sb = new StringBuilder();

        // WITH clause
        if (sel.core().withClause() != null && !sel.core().withClause().isEmpty()) {
            sb.append("WITH ");
            if (sel.core().withClause().get(0).recursive()) sb.append("RECURSIVE ");
            sb.append(sel.core().withClause().stream()
                .map(cte -> quoteIdentifier(cte.name()) + " AS (" + generateSelectCore(cte.query(), opt, false) + ")")
                .collect(Collectors.joining(", ")));
            sb.append(" ");
        }

        sb.append("SELECT ");
        if (sel.core().distinct()) sb.append("DISTINCT ");
        sb.append(sel.core().projections().stream()
            .map(p -> generateSelectItem(p, opt))
            .collect(Collectors.joining(", ")));

        if (sel.core().from() != null && !sel.core().from().isEmpty()) {
            sb.append(" FROM ");
            sb.append(sel.core().from().stream()
                .map(f -> generateTableRef(f, opt))
                .collect(Collectors.joining(", ")));
        } else {
            sb.append(" FROM DUAL"); // DUAL is unquoted in Oracle
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

        if (!stripOrderBy && sel.orderBy() != null && !sel.orderBy().isEmpty()) {
            sb.append(" ORDER BY ");
            sb.append(sel.orderBy().stream()
                .map(o -> generateExpr(o.expr(), opt) + (o.dir() == OrderDir.DESC ? " DESC" : " ASC"))
                .collect(Collectors.joining(", ")));
        }

        if (sel.core().setOp() != null && sel.core().setOperand() != null) {
            sb.append(" ").append(sel.core().setOp().name().replace("_", " "));
            sb.append(" ").append(generateSelectCore(sel.core().setOperand(), opt, false));
        }

        return sb.toString();
    }

    private String generateSelectItem(IRSelectItem item, GenerateOptions opt) {
        return switch (item) {
            case IRExprSelect es -> {
                String expr = generateExpr(es.expr(), opt);
                if (es.alias() != null) expr += " " + quoteIdentifier(es.alias()); // Oracle prefers no AS
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
                // Oracle uses TABLE() for table functions
                yield "TABLE(" + ft.funcName() + "(" +
                    ft.args().stream().map(a -> generateExpr(a, opt)).collect(Collectors.joining(", ")) +
                    ")) " + quoteIdentifier(ft.alias());
            }
        };
    }

    // ══════════════════════════════════════════════════
    //  Expressions
    // ══════════════════════════════════════════════════

    private String generateExpr(IRExpr expr, GenerateOptions opt) {
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
            default -> throw new UnsupportedOperationException("Unknown: " + expr.getClass().getSimpleName());
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
            case DataType.DateType d    -> "DATE '" + lit.value() + "'";
            case DataType.DatetimeType dt -> "TIMESTAMP '" + lit.value() + "'";
            default -> "'" + escapeString(lit.value().toString()) + "'";
        };
    }

    private String generateBinaryOp(IRBinaryOp op, GenerateOptions opt) {
        String left = generateExpr(op.left(), opt);
        String right = generateExpr(op.right(), opt);
        String operator = switch (op.op()) {
            case ADD -> " + "; case SUB -> " - "; case MUL -> " * "; case DIV -> " / ";
            case MOD -> " MOD "; // Oracle: MOD(a, b) function — but keeps infix for inline
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
            // Oracle: no boolean type → compare with 1/0
            case IS_TRUE -> operand + " = 1";
            case IS_NOT_TRUE -> "(" + operand + " IS NULL OR " + operand + " != 1)";
            case IS_FALSE -> operand + " = 0";
            case IS_NOT_FALSE -> "(" + operand + " IS NULL OR " + operand + " != 0)";
            case EXISTS -> "EXISTS " + operand;
            case UNIQUE -> "UNIQUE " + operand;
        };
    }

    private String generateFunctionCall(IRFunctionCall fc, GenerateOptions opt) {
        List<String> argList = fc.args().stream().map(a -> generateExpr(a, opt))
            .collect(Collectors.toList());
        String argsStr = String.join(", ", argList);

        String result;
        if (functionCatalog != null) {
            var def = functionCatalog.get(fc.funcName());
            if (def.isPresent()) {
                var mapping = def.get().forDialect(Dialect.ORACLE);
                if (mapping.isPresent()) {
                    String tpl = mapping.get().renderTemplate();
                    if (tpl != null) {
                        result = tpl;
                        for (int i = 0; i < argList.size(); i++)
                            result = result.replace("{" + i + "}", argList.get(i));
                    } else {
                        result = mapping.get().nativeName() + "(" + argsStr + ")";
                    }
                } else { result = fc.funcName() + "(" + argsStr + ")"; }
            } else { result = fc.funcName() + "(" + argsStr + ")"; }
        } else { result = fc.funcName() + "(" + argsStr + ")"; }

        // Oracle KEEP clause — native support
        if (fc.keep() != null) {
            result += " KEEP (DENSE_RANK ";
            result += (fc.keep() instanceof KeepSpec.Last) ? "LAST" : "FIRST";
            result += " ORDER BY " + fc.keep().orderBy().stream()
                .map(o -> generateExpr(o.expr(), opt) + (o.dir() == IRStatement.OrderDir.DESC ? " DESC" : ""))
                .collect(Collectors.joining(", "));
            result += ")";
        }

        if (fc.over() != null) {
            result += " OVER (";
            var over = fc.over();
            if (over.partitionBy() != null && !over.partitionBy().isEmpty())
                result += "PARTITION BY " + over.partitionBy().stream()
                    .map(e -> generateExpr(e, opt)).collect(Collectors.joining(", "));
            if (over.orderBy() != null && !over.orderBy().isEmpty()) {
                if (over.partitionBy() != null && !over.partitionBy().isEmpty()) result += " ";
                result += "ORDER BY " + over.orderBy().stream()
                    .map(o -> generateExpr(o.expr(), opt) + (o.dir() == IRStatement.OrderDir.DESC ? " DESC" : " ASC"))
                    .collect(Collectors.joining(", "));
            }
            if (over.frame() != null) result += " " + over.frame().toSql();
            result += ")";
        }
        return result;
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
        var sb = new StringBuilder("INSERT");
        // Oracle doesn't have INSERT IGNORE — use INSERT /*+ ignore_row_on_dupkey_index */ or just INSERT
        sb.append(" INTO ").append(generateTableRef(ins.table(), opt));
        if (ins.columns() != null && !ins.columns().isEmpty()) {
            sb.append(" (").append(ins.columns().stream().map(this::quoteIdentifier)
                .collect(Collectors.joining(", "))).append(")");
        }
        if (ins.values() != null && !ins.values().isEmpty()) {
            // Oracle: multi-row INSERT uses INSERT ALL ... SELECT FROM DUAL
            if (ins.values().size() > 1) {
                sb.append("\nSELECT ");
                int rowNum = 0;
                for (var row : ins.values()) {
                    if (rowNum > 0) sb.append(" UNION ALL\n  SELECT ");
                    else sb.append("\n  ");
                    sb.append(row.stream().map(v -> generateExpr(v, opt))
                        .collect(Collectors.joining(", ")));
                    sb.append(" FROM DUAL");
                    rowNum++;
                }
            } else {
                sb.append(" VALUES (");
                sb.append(ins.values().get(0).stream().map(v -> generateExpr(v, opt))
                    .collect(Collectors.joining(", ")));
                sb.append(")");
            }
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
    //  MERGE — Oracle native syntax
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

        // Oracle doesn't support IF NOT EXISTS — PL/SQL wrapper
        String ddl = sb.toString().replace("'", "''");
        return "BEGIN EXECUTE IMMEDIATE '" + ddl + "'; " +
               "EXCEPTION WHEN OTHERS THEN IF SQLCODE = -955 THEN NULL; ELSE RAISE; END IF; END;";
    }

    private String generateColumnDef(IRColumnDef col, GenerateOptions opt) {
        var sb = new StringBuilder("  ").append(quoteIdentifier(col.name())).append(" ").append(mapType(col.type()));
        // Oracle: DEFAULT before NOT NULL
        if (col.defaultValue() != null)
            sb.append(" DEFAULT ").append(generateExpr(col.defaultValue(), opt));
        if (col.constraints() != null) {
            for (var c : col.constraints()) {
                if (c instanceof ColNotNull) sb.append(" NOT NULL");
                else if (c instanceof ColPrimaryKey pk) {
                    if (pk.autoIncrement()) {
                        sb.append(" GENERATED BY DEFAULT ON NULL AS IDENTITY PRIMARY KEY");
                    } else {
                        sb.append(" PRIMARY KEY");
                    }
                }
                else if (c instanceof ColUnique) sb.append(" UNIQUE");
                else if (c instanceof ColCheck chk)
                    sb.append(" CHECK (").append(generateExpr(chk.condition(), opt)).append(")");
                else if (c instanceof ColReferences ref)
                    sb.append(" REFERENCES ").append(quoteIdentifier(ref.targetTable()))
                      .append("(").append(quoteIdentifier(ref.targetColumn())).append(")");
                else if (c instanceof ColGenerated gen) {
                    sb.append(" GENERATED ALWAYS AS (");
                    sb.append(generateExpr(gen.expression(), opt)).append(")");
                    if (!gen.virtual()) sb.append(" STORED");
                }
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

    // ══════════════════════════════════════════════════
    //  CREATE INDEX
    // ══════════════════════════════════════════════════

    private String generateCreateIndex(IRCreateIndex idx, GenerateOptions opt) {
        var sb = new StringBuilder("CREATE ");
        if (idx.unique()) sb.append("UNIQUE ");
        sb.append("INDEX ").append(quoteIdentifier(idx.name()));
        sb.append(" ON ").append(quoteIdentifier(idx.table().name())).append(" (");
        sb.append(idx.columns().stream()
            .map(c -> quoteIdentifier(c.name()) + (c.dir() == OrderDir.DESC ? " DESC" : ""))
            .collect(Collectors.joining(", ")));
        sb.append(")");
        return sb.toString();
    }

    // ══════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════

    private String escapeString(String s) {
        return s.replace("'", "''");
    }
}
