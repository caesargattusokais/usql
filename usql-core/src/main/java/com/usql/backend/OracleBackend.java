package com.usql.backend;

import com.usql.dialect.Dialect;
import com.usql.ir.*;
import com.usql.ir.IRExpr.*;
import com.usql.ir.IRStatement.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Generates Oracle-compatible SQL from the Semantic IR.
 * Key adaptations:
 *   - LIMIT/OFFSET → ROWNUM wrapping
 *   - BOOLEAN → NUMBER(1) comparison
 *   - Identifiers use uppercase double-quotes (matches Oracle's auto-fold behavior)
 *   - VARCHAR → VARCHAR2
 */
public class OracleBackend extends AbstractDialectBackend {

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
            case IRDropDatabase dd           -> generateDropDatabase(dd, options);
            case IRCreateView cv             -> generateCreateView(cv, options);
            case IRCreateSchema cs           -> generateCreateSchema(cs, options);
            case IRTCL tcl                  -> generateTCL(tcl, options);
            case IRAlterColumnSetDefault acs -> generateAlterColumnSetDefault(acs, options);
            case IRAlterColumnDropDefault acd -> generateAlterColumnDropDefault(acd, options);
            case IRRenameColumn rc           -> generateRenameColumn(rc, options);
            default ->
                throw new UnsupportedOperationException(
                    "Oracle backend cannot generate statement '" + statement.getClass().getSimpleName()
                    + "'. Supported: IRSelect, IRInsert, IRUpdate, IRDelete, IRMerge, IRCreateTable, IRCreateIndex, IRCreateProcedure, IRCreateFunction, IRCall, IRDropTable, IRDropIndex, IRTruncateTable, IRAlterTableAddColumn, IRAlterTableDropColumn");
        };
    }

    @Override
    public String quoteIdentifier(String id) {
        // Oracle auto-folds unquoted identifiers to UPPERCASE.
        // Double-quoting preserves case — so we quote but keep original case.
        // This allows case-sensitive identifiers (e.g. "myCol") while still
        // protecting reserved words.
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
            case DataType.IntervalDaySecond i -> "INTERVAL DAY(2) TO SECOND(" + i.fractionalSeconds() + ")";
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

            if (hasOffset) {
                // ROWNUM 3-layer wrap: Oracle classic pattern
                // SELECT * FROM (SELECT inner.*, ROWNUM rn FROM (...) WHERE ROWNUM <= max) WHERE rn > min
                String limitExpr = generateExpr(sel.fetch().limit(), opt);
                String offsetExpr = generateExpr(sel.fetch().offset(), opt);

                // Always add offset to ROWNUM limit (works for both literal and parameterized offsets)
                return "SELECT " + quoteIdentifier("inner__") + ".* FROM (\n" +
                       "  SELECT " + quoteIdentifier("core__") + ".*, ROWNUM AS " + quoteIdentifier("rn__") + " FROM (\n" +
                       "    " + innerSQL + "\n" +
                       "  ) " + quoteIdentifier("core__") + "\n" +
                       "  WHERE ROWNUM <= " + limitExpr + " + " + offsetExpr + "\n" +
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
            // Oracle uses WITH for both recursive and non-recursive CTEs
            // Recursive CTEs need column alias list
            sb.append(sel.core().withClause().stream()
                .map(cte -> {
                    String cols = "";
                    if (cte.recursive() && (cte.columns() == null || cte.columns().isEmpty())) {
                        // Derive column names from anchor SELECT
                        List<String> names = new ArrayList<>();
                        if (cte.query().core().projections() != null) {
                            for (var p : cte.query().core().projections()) {
                                if (p instanceof IRExprSelect es && es.alias() != null)
                                    names.add(quoteIdentifier(es.alias()));
                            }
                        }
                        if (!names.isEmpty())
                            cols = " (" + String.join(", ", names) + ")";
                    }
                    return quoteIdentifier(cte.name()) + cols + " AS (" + generateSelectCore(cte.query(), opt, false) + ")";
                })
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
            String op = sel.core().setOp().name().replace("_", " ");
            if (sel.core().setOp() == SetOp.EXCEPT) op = "MINUS"; // Oracle uses MINUS
            sb.append(" ").append(op);
            sb.append(" ").append(generateSelectCore(sel.core().setOperand(), opt, false));
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
                yield (ft.lateral() ? "LATERAL " : "") + "TABLE(" + ft.funcName() + "(" +
                    ft.args().stream().map(a -> generateExpr(a, opt)).collect(Collectors.joining(", ")) +
                    ")) " + quoteIdentifier(ft.alias());
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
                "Oracle backend cannot generate expression '" + expr.getClass().getSimpleName()
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
            case MOD -> " MOD "; // handled separately below as MOD(a, b) function
            case EQ -> " = "; case NEQ -> " != ";
            case LT -> " < "; case GT -> " > "; case LTE -> " <= "; case GTE -> " >= ";
            case AND -> " AND "; case OR -> " OR ";
            case CONCAT -> " || ";
            case LIKE -> " LIKE "; case NOT_LIKE -> " NOT LIKE ";
            case IS_DISTINCT_FROM -> " IS DISTINCT FROM ";
            case IN -> " IN "; case NOT_IN -> " NOT IN "; case BETWEEN -> " BETWEEN ";
        };
        // Oracle does not support MOD as infix operator — use MOD(a, b) function call
        if (op.op() == IRBinaryOp.BinaryOp.MOD) {
            return "MOD(" + left + ", " + right + ")";
        }
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

    @Override
    protected String generateFunctionCall(IRFunctionCall fc, GenerateOptions opt) {
        List<String> argList = fc.args().stream().map(a -> generateExpr(a, opt))
            .collect(Collectors.toList());
        String argsStr = String.join(", ", argList);

        // Oracle native: KEEP must come BEFORE OVER
        // Build the base function call (name + args) without OVER
        String baseFunc;
        if (functionCatalog != null) {
            var def = functionCatalog.get(fc.funcName());
            if (def.isPresent()) {
                var mapping = def.get().forDialect(Dialect.ORACLE);
                if (mapping.isPresent()) {
                    String tpl = mapping.get().renderTemplate();
                    if (tpl != null) {
                        baseFunc = tpl;
                        for (int i = 0; i < argList.size(); i++)
                            baseFunc = baseFunc.replace("{" + i + "}", argList.get(i));
                    } else {
                        baseFunc = mapping.get().nativeName() + "(" + argsStr + ")";
                    }
                } else { baseFunc = fc.funcName() + "(" + argsStr + ")"; }
            } else { baseFunc = fc.funcName() + "(" + argsStr + ")"; }
        } else { baseFunc = fc.funcName() + "(" + argsStr + ")"; }

        String result = baseFunc;

        // Oracle native KEEP clause (before OVER)
        if (fc.keep() != null) {
            result += " KEEP (DENSE_RANK ";
            result += (fc.keep() instanceof KeepSpec.Last) ? "LAST" : "FIRST";
            result += " ORDER BY " + fc.keep().orderBy().stream()
                .map(o -> generateExpr(o.expr(), opt) + (o.dir() == OrderDir.DESC ? " DESC" : ""))
                .collect(Collectors.joining(", "));
            result += ")";
        }

        // OVER clause (after KEEP)
        if (fc.over() != null) {
            result += " OVER (";
            if (fc.over().partitionBy() != null && !fc.over().partitionBy().isEmpty()) {
                result += "PARTITION BY " + fc.over().partitionBy().stream()
                    .map(e -> generateExpr(e, opt)).collect(Collectors.joining(", "));
            }
            if (fc.over().orderBy() != null && !fc.over().orderBy().isEmpty()) {
                if (fc.over().partitionBy() != null && !fc.over().partitionBy().isEmpty()) result += " ";
                result += "ORDER BY " + fc.over().orderBy().stream()
                    .map(o -> generateExpr(o.expr(), opt) + (o.dir() == OrderDir.DESC ? " DESC" : " ASC"))
                    .collect(Collectors.joining(", "));
            }
            if (fc.over().frame() != null) result += " " + fc.over().frame().toSql();
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
        boolean multiRow = ins.values() != null && ins.values().size() > 1;
        if (multiRow) {
            // Oracle: SELECT UNION ALL — wrap duplicate expressions to avoid ORA-00918
            var sb = new StringBuilder("INSERT INTO ").append(generateTableRef(ins.table(), opt));
            if (ins.columns() != null && !ins.columns().isEmpty()) {
                sb.append(" (").append(ins.columns().stream().map(this::quoteIdentifier)
                    .collect(Collectors.joining(", "))).append(")");
            }
            for (int i = 0; i < ins.values().size(); i++) {
                if (i > 0) sb.append(" UNION ALL");
                sb.append("\n  SELECT ");
                var row = ins.values().get(i);
                List<String> exprs = row.stream().map(v -> generateExpr(v, opt))
                    .collect(Collectors.toList());
                // Deduplicate identical expressions to avoid ORA-00918
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (int j = 0; j < exprs.size(); j++) {
                    if (j > 0) sb.append(", ");
                    String e = exprs.get(j);
                    if (!seen.add(e)) e = "CAST(" + e + " AS NUMBER)";
                    sb.append(e);
                }
                sb.append(" FROM DUAL");
            }
            return sb.toString();
        }
        var sb = new StringBuilder("INSERT INTO ");
        sb.append(generateTableRef(ins.table(), opt));
        if (ins.columns() != null && !ins.columns().isEmpty()) {
            sb.append(" (").append(ins.columns().stream().map(this::quoteIdentifier)
                .collect(Collectors.joining(", "))).append(")");
        }
        if (ins.values() != null && !ins.values().isEmpty()) {
            sb.append(" VALUES (");
            sb.append(ins.values().get(0).stream().map(v -> generateExpr(v, opt))
                .collect(Collectors.joining(", ")));
            sb.append(")");
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
        // Only swallow ORA-00955 (name already used), re-raise everything else
        String ddl = sb.toString().replace("'", "''");
        return "BEGIN EXECUTE IMMEDIATE '" + ddl + "'; " +
               "EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;";
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

        if (!idx.ifNotExists()) return sb.toString();

        String ddl = sb.toString().replace("'", "''");
        return "BEGIN EXECUTE IMMEDIATE '" + ddl + "'; " +
               "EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;";
    }

    // ══════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════

    // ══════════════════════════════════════════════════
    //  Stored procedures — Oracle-specific syntax
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
    protected String generateCall(IRCall call, GenerateOptions opt) {
        String args = call.args() != null
            ? call.args().stream().map(a -> generateExpr(a, opt))
                .collect(Collectors.joining(", "))
            : "";
        return "BEGIN " + quoteIdentifier(call.procedureName())
            + (args.isEmpty() ? "" : "(" + args + ")") + "; END;";
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
    protected String generateAlterColumnType(IRAlterColumnType act, GenerateOptions opt) {
        return "ALTER TABLE " + quoteIdentifier(act.tableName())
            + " MODIFY " + quoteIdentifier(act.column()) + " " + mapType(act.newType());
    }

    @Override
    protected String generateAlterColumnSetDefault(IRAlterColumnSetDefault acs, GenerateOptions opt) {
        return "ALTER TABLE " + quoteIdentifier(acs.tableName())
            + " MODIFY " + quoteIdentifier(acs.column()) + " DEFAULT " + generateExpr(acs.value(), opt);
    }

    @Override
    protected String generateAlterColumnDropDefault(IRAlterColumnDropDefault acd, GenerateOptions opt) {
        return "ALTER TABLE " + quoteIdentifier(acd.tableName())
            + " MODIFY " + quoteIdentifier(acd.column()) + " DEFAULT NULL";
    }

    @Override
    protected String generateDropTable(IRDropTable dt, GenerateOptions opt) {
        String cascadeSuffix = dt.cascade() ? " CASCADE CONSTRAINTS" : "";
        if (!dt.ifExists())
            return "DROP TABLE " + quoteIdentifier(dt.name()) + cascadeSuffix;
        return "BEGIN EXECUTE IMMEDIATE 'DROP TABLE " + quoteIdentifier(dt.name()).replace("'", "''")
            + cascadeSuffix + "'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -942 THEN RAISE; END IF; END;";
    }

    private String escapeString(String s) {
        return s.replace("'", "''");
    }
}
