package com.usql.backend;

import com.usql.dialect.Dialect;
import com.usql.ir.*;
import com.usql.ir.IRExpr.*;
import com.usql.ir.IRStatement.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Generates MySQL-compatible SQL from the Semantic IR.
 */
public class MySqlBackend extends AbstractDialectBackend {

    @Override
    public Dialect targetDialect() { return Dialect.MYSQL; }

    @Override
    public String generate(IRStatement statement, GenerateOptions options) {
        return switch (statement) {
            case IRSelect sel   -> generateSelect(sel, options);
            case IRInsert ins   -> generateInsert(ins, options);
            case IRUpdate upd   -> generateUpdate(upd, options);
            case IRDelete del   -> generateDelete(del, options);
            case IRMerge merge  -> generateMerge(merge, options);
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
                    "MySQL backend cannot generate statement '" + statement.getClass().getSimpleName()
                    + "'. Supported: IRSelect, IRInsert, IRUpdate, IRDelete, IRMerge, IRCreateTable, IRCreateIndex, IRCreateProcedure, IRCreateFunction, IRCall, IRDropTable, IRDropIndex, IRTruncateTable, IRAlterTableAddColumn, IRAlterTableDropColumn");
        };
    }

    @Override
    public String quoteIdentifier(String id) {
        return "`" + id.replace("`", "``") + "`";
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
            case DataType.FloatType f   -> f.bits() <= 32 ? "FLOAT" : "DOUBLE";
            case DataType.CharType c    -> "CHAR(" + c.length() + ")";
            case DataType.VarcharType v -> "VARCHAR(" + v.length() + ")";
            case DataType.TextType t    -> "LONGTEXT";
            case DataType.BooleanType b -> "TINYINT(1)";
            case DataType.DateType d    -> "DATE";
            case DataType.TimeType t    -> "TIME(" + t.fractionalSeconds() + ")";
            case DataType.DatetimeType dt -> "DATETIME(" + dt.fractionalSeconds() + ")";
            case DataType.TimestampType ts -> "TIMESTAMP(" + ts.fractionalSeconds() + ")";
            case DataType.JsonType j    -> "JSON";
            case DataType.UuidType u    -> "CHAR(36)";
            case DataType.BinaryType b  -> "BINARY(" + b.length() + ")";
            case DataType.VarbinaryType vb -> "VARBINARY(" + vb.length() + ")";
            case DataType.BlobType bl   -> "LONGBLOB";
            case DataType.EnumType e    -> "ENUM(" + e.values().stream()
                .map(v -> "'" + v.replace("'", "''") + "'")
                .collect(Collectors.joining(", ")) + ")";
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

        // Pass 2: generate SELECT items (keepIdx tracks which KEEP _keep_N to reference)
        keepIdx = 0;
        sb.append(sel.core().projections().stream()
            .map(p -> generateSelectItem(p, opt))
            .collect(Collectors.joining(", ")));

        // FROM — wrap in subquery with DENSE_RANK columns if KEEP exists
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
            // MySQL: ROLLUP uses WITH ROLLUP suffix, CUBE not supported
            boolean hasRollup = sel.core().groupBy().stream().anyMatch(g -> g.kind() == GroupByKind.ROLLUP);
            boolean hasCube = sel.core().groupBy().stream().anyMatch(g -> g.kind() == GroupByKind.CUBE);
            sb.append(" GROUP BY ");
            if (hasRollup || hasCube) {
                // Strip ROLLUP()/CUBE() wrapper for MySQL — just the column list
                var first = sel.core().groupBy().get(0);
                String expr = generateExpr(first.expr(), opt);
                // Remove ROLLUP( / CUBE( prefix and trailing )
                expr = expr.replaceFirst("^(?i)(ROLLUP|CUBE)\\(", "").replaceFirst("\\)$", "");
                sb.append(expr);
            } else {
                sb.append(sel.core().groupBy().stream().map(g -> generateExpr(g.expr(), opt)).collect(Collectors.joining(", ")));
            }
            if (hasRollup) sb.append(" WITH ROLLUP");
            // MySQL doesn't support CUBE — flattened to plain GROUP BY
        }
        if (sel.core().having() != null) sb.append(" HAVING ").append(generateExpr(sel.core().having(), opt));
        if (sel.orderBy() != null && !sel.orderBy().isEmpty()) {
            sb.append(" ORDER BY ");
            sb.append(sel.orderBy().stream()
                .map(o -> generateExpr(o.expr(), opt) + (o.dir() == OrderDir.DESC ? " DESC" : " ASC"))
                .collect(Collectors.joining(", ")));
        }
        if (sel.fetch() != null) {
            if (sel.fetch().limit() != null) sb.append(" LIMIT ").append(generateExpr(sel.fetch().limit(), opt));
            if (sel.fetch().offset() != null) sb.append(" OFFSET ").append(generateExpr(sel.fetch().offset(), opt));
        }

        // Set operations
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
                if (tn.alias() != null)
                    result += " " + quoteIdentifier(tn.alias());
                yield result;
            }
            case IRJoin jn -> {
                String left = generateTableRef(jn.left(), opt);
                String joinType = switch (jn.type()) {
                    case INNER -> "INNER JOIN";
                    case LEFT  -> "LEFT JOIN";
                    case RIGHT -> "RIGHT JOIN";
                    case CROSS -> "CROSS JOIN";
                    case FULL  -> "LEFT JOIN"; // MySQL → LEFT JOIN (polyfill handled earlier)
                };
                String right = generateTableRef(jn.right(), opt);
                String on = jn.onCondition() != null
                    ? " ON " + generateExpr(jn.onCondition(), opt)
                    : "";
                yield left + " " + joinType + " " + right + on;
            }
            case IRSubqueryTable sq -> "(" + generateSelect(sq.query(), opt) + ") " + quoteIdentifier(sq.alias());
            case IRFunctionTable ft -> (ft.lateral() ? "LATERAL " : "") + ft.funcName() + "(" +
                ft.args().stream().map(a -> generateExpr(a, opt)).collect(Collectors.joining(", ")) +
                ") " + quoteIdentifier(ft.alias());
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
                if (col.qualifier() != null)
                    name = quoteIdentifier(col.qualifier()) + "." + name;
                yield name;
            }
            case IRWildcard wc   -> {
                if (wc.qualifier() != null)
                    yield quoteIdentifier(wc.qualifier()) + ".*";
                yield "*";
            }
            case IRParameter p   -> "?";  // JDBC standard positional parameter
            case IRBinaryOp bo   -> generateBinaryOp(bo, opt);
            case IRUnaryOp uo    -> generateUnaryOp(uo, opt);
            case IRFunctionCall fc -> generateFunctionCall(fc, opt);
            case IRCase cs       -> generateCase(cs, opt);
            case IRCast ct       -> {
                String targetType = mapType(ct.targetType());
                // MySQL CAST doesn't support VARCHAR, use CHAR
                if (ct.targetType() instanceof DataType.VarcharType)
                    targetType = "CHAR(" + ((DataType.VarcharType) ct.targetType()).length() + ")";
                yield "CAST(" + generateExpr(ct.expr(), opt) + " AS " + targetType + ")";
            }
            case IRSubquery sq   -> "(" + generateSelect(sq.query(), opt) + ")";
            case IRBetween btw   -> generateExpr(btw.expr(), opt) + (btw.not() ? " NOT" : "") +
                                    " BETWEEN " + generateExpr(btw.low(), opt) + " AND " + generateExpr(btw.high(), opt);
            case IRInList in     -> {
                String r = generateExpr(in.expr(), opt) + (in.not() ? " NOT IN (" : " IN (");
                if (in.subquery() != null) r += generateSelect(in.subquery(), opt);
                else r += in.values().stream().map(v -> generateExpr(v, opt)).collect(Collectors.joining(", "));
                yield r + ")";
            }
            case IRIsNull isn    -> generateExpr(isn.expr(), opt) + (isn.not() ? " IS NOT NULL" : " IS NULL");
            default -> throw new UnsupportedOperationException(
                "MySQL backend cannot generate expression '" + expr.getClass().getSimpleName()
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
            case ADD    -> " + ";
            case SUB    -> " - ";
            case MUL    -> " * ";
            case DIV    -> " / ";
            case MOD    -> " % ";
            case EQ     -> " = ";
            case NEQ    -> " != ";
            case LT     -> " < ";
            case GT     -> " > ";
            case LTE    -> " <= ";
            case GTE    -> " >= ";
            case AND    -> " AND ";
            case OR     -> " OR ";
            case CONCAT -> {
                // MySQL CONCAT: treat null as empty string? Depends on mode.
                // For now, use CONCAT function
                yield " || "; // MySQL: || is OR by default unless PIPES_AS_CONCAT mode
            }
            case LIKE             -> " LIKE ";
            case NOT_LIKE         -> " NOT LIKE ";
            case IS_DISTINCT_FROM -> " <=> "; // MySQL's null-safe equals
            case IN               -> " IN ";
            case NOT_IN           -> " NOT IN ";
            case BETWEEN          -> " BETWEEN ";
        };
        return "(" + left + operator + right + ")";
    }

    private String generateUnaryOp(IRUnaryOp op, GenerateOptions opt) {
        String operand = generateExpr(op.operand(), opt);
        return switch (op.op()) {
            case NEG          -> "-(" + operand + ")";
            case NOT          -> "NOT (" + operand + ")";
            case IS_NULL      -> operand + " IS NULL";
            case IS_NOT_NULL  -> operand + " IS NOT NULL";
            case IS_TRUE      -> operand + " = 1";
            case IS_NOT_TRUE  -> "(" + operand + " IS NULL OR " + operand + " != 1)";
            case IS_FALSE     -> operand + " = 0";
            case IS_NOT_FALSE -> "(" + operand + " IS NULL OR " + operand + " != 0)";
            case EXISTS       -> "EXISTS " + operand;
            case UNIQUE       -> "UNIQUE " + operand;
        };
    }

    private String generateCase(IRCase cs, GenerateOptions opt) {
        var sb = new StringBuilder("CASE");
        for (var when : cs.whens()) {
            sb.append(" WHEN ").append(generateExpr(when.condition(), opt));
            sb.append(" THEN ").append(generateExpr(when.result(), opt));
        }
        if (cs.elseExpr() != null) {
            sb.append(" ELSE ").append(generateExpr(cs.elseExpr(), opt));
        }
        sb.append(" END");
        return sb.toString();
    }

    // ══════════════════════════════════════════════════
    //  INSERT
    // ══════════════════════════════════════════════════

    private String generateInsert(IRInsert ins, GenerateOptions opt) {
        var sb = new StringBuilder("INSERT");
        if (ins.ignoreErrors()) sb.append(" IGNORE");
        sb.append(" INTO ").append(generateTableRef(ins.table(), opt));

        if (ins.columns() != null && !ins.columns().isEmpty()) {
            sb.append(" (");
            sb.append(ins.columns().stream().map(this::quoteIdentifier).collect(Collectors.joining(", ")));
            sb.append(")");
        }

        if (ins.values() != null && !ins.values().isEmpty()) {
            sb.append(" VALUES ");
            sb.append(ins.values().stream()
                .map(row -> "(" + row.stream()
                    .map(v -> generateExpr(v, opt))
                    .collect(Collectors.joining(", ")) + ")")
                .collect(Collectors.joining(", ")));
        } else if (ins.selectSource() != null) {
            sb.append(" ").append(generateSelect(ins.selectSource(), opt));
        }

        return sb.toString();
    }

    // ══════════════════════════════════════════════════
    //  UPDATE
    // ══════════════════════════════════════════════════

    private String generateUpdate(IRUpdate upd, GenerateOptions opt) {
        var sb = new StringBuilder("UPDATE ");
        sb.append(generateTableRef(upd.table(), opt));
        sb.append(" SET ");
        sb.append(upd.sets().stream()
            .map(s -> quoteIdentifier(s.column()) + " = " + generateExpr(s.value(), opt))
            .collect(Collectors.joining(", ")));
        if (upd.where() != null) {
            sb.append(" WHERE ").append(generateExpr(upd.where(), opt));
        }
        return sb.toString();
    }

    // ══════════════════════════════════════════════════
    //  DELETE
    // ══════════════════════════════════════════════════

    private String generateDelete(IRDelete del, GenerateOptions opt) {
        var sb = new StringBuilder("DELETE FROM ");
        sb.append(generateTableRef(del.table(), opt));
        if (del.where() != null) {
            sb.append(" WHERE ").append(generateExpr(del.where(), opt));
        }
        return sb.toString();
    }

    // ══════════════════════════════════════════════════
    //  MERGE → INSERT ... ON DUPLICATE KEY UPDATE
    // ══════════════════════════════════════════════════

    private String generateMerge(IRMerge merge, GenerateOptions opt) {
        // MySQL: INSERT ... SELECT FROM source ... ON DUPLICATE KEY UPDATE
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
            sb.append(" ON DUPLICATE KEY UPDATE ");
            sb.append(upd.sets().stream()
                .map(s -> quoteIdentifier(s.column()) + " = VALUES(" + quoteIdentifier(s.column()) + ")")
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
        sb.append(generateTableRef(ct.name(), opt));
        sb.append(" (\n");

        // Columns
        sb.append(ct.columns().stream()
            .map(col -> generateColumnDef(col, opt))
            .collect(Collectors.joining(",\n")));

        // Table constraints
        if (ct.constraints() != null && !ct.constraints().isEmpty()) {
            sb.append(",\n");
            sb.append(ct.constraints().stream()
                .map(c -> generateTableConstraint(c, opt))
                .collect(Collectors.joining(",\n")));
        }

        sb.append("\n)");

        if (ct.options() != null) {
            if (ct.options().engine() != null)
                sb.append(" ENGINE=").append(ct.options().engine());
            if (ct.options().characterSet() != null)
                sb.append(" DEFAULT CHARSET=").append(ct.options().characterSet());
            if (ct.options().collation() != null)
                sb.append(" COLLATE=").append(ct.options().collation());
            if (ct.options().comment() != null)
                sb.append(" COMMENT='").append(escapeString(ct.options().comment())).append("'");
        }

        return sb.toString();
    }

    private String generateColumnDef(IRColumnDef col, GenerateOptions opt) {
        var sb = new StringBuilder("  ");
        sb.append(quoteIdentifier(col.name())).append(" ");
        sb.append(mapType(col.type()));

        // Constraints
        if (col.constraints() != null) {
            for (var c : col.constraints()) {
                if (c instanceof ColNotNull) sb.append(" NOT NULL");
                else if (c instanceof ColPrimaryKey pk) {
                    sb.append(" PRIMARY KEY");
                    if (pk.autoIncrement()) sb.append(" AUTO_INCREMENT");
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

        // Default
        if (col.defaultValue() != null) {
            sb.append(" DEFAULT ").append(generateExpr(col.defaultValue(), opt));
        }

        return sb.toString();
    }

    private String generateTableConstraint(IRTableConstraint constraint, GenerateOptions opt) {
        return switch (constraint) {
            case TBPrimaryKey pk -> {
                String cols = pk.columns().stream().map(this::quoteIdentifier)
                    .collect(Collectors.joining(", "));
                yield pk.constraintName() != null
                    ? "  CONSTRAINT " + quoteIdentifier(pk.constraintName()) + " PRIMARY KEY (" + cols + ")"
                    : "  PRIMARY KEY (" + cols + ")";
            }
            case TBUnique uq -> {
                String cols = uq.columns().stream().map(this::quoteIdentifier)
                    .collect(Collectors.joining(", "));
                yield uq.constraintName() != null
                    ? "  CONSTRAINT " + quoteIdentifier(uq.constraintName()) + " UNIQUE (" + cols + ")"
                    : "  UNIQUE (" + cols + ")";
            }
            case TBForeignKey fk -> {
                String cols = fk.columns().stream().map(this::quoteIdentifier)
                    .collect(Collectors.joining(", "));
                String targetCols = fk.targetColumns().stream().map(this::quoteIdentifier)
                    .collect(Collectors.joining(", "));
                yield "  FOREIGN KEY (" + cols + ") REFERENCES " +
                      quoteIdentifier(fk.targetTable()) + "(" + targetCols + ")";
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
        sb.append(quoteIdentifier(idx.name()));
        sb.append(" ON ").append(quoteIdentifier(idx.table().name()));
        sb.append(" (");
        sb.append(idx.columns().stream()
            .map(c -> quoteIdentifier(c.name()) + (c.dir() == OrderDir.DESC ? " DESC" : ""))
            .collect(Collectors.joining(", ")));
        sb.append(")");
        if (idx.type() != null && idx.type() != IndexType.BTREE) {
            sb.append(" USING ").append(idx.type().name());
        }

        if (idx.ifNotExists()) {
            String ddl = sb.toString().replace("'", "\\'");
            return "CREATE PROCEDURE _idx_guard() "
                + "BEGIN DECLARE EXIT HANDLER FOR 1061 BEGIN END; "
                + "EXECUTE IMMEDIATE '" + ddl + "'; END; "
                + "CALL _idx_guard(); DROP PROCEDURE _idx_guard;";
        }
        return sb.toString();
    }

    @Override
    protected String generateDropIndex(IRDropIndex di, GenerateOptions opt) {
        String sql = "DROP INDEX " + (di.ifExists() ? "IF EXISTS " : "") + quoteIdentifier(di.indexName());
        if (di.tableName() != null) sql += " ON " + quoteIdentifier(di.tableName());
        return sql;
    }

    @Override
    protected String generateAlterTableAddColumn(IRAlterTableAddColumn aa, GenerateOptions opt) {
        var col = aa.column();
        var sb = new StringBuilder("ALTER TABLE ").append(quoteIdentifier(aa.tableName()))
            .append(" ADD ");
        if (aa.ifNotExists()) sb.append("IF NOT EXISTS ");
        sb.append(quoteIdentifier(col.name())).append(" ").append(mapType(col.type()));
        if (col.constraints() != null) {
            for (var c : col.constraints()) {
                if (c instanceof ColNotNull) sb.append(" NOT NULL");
                else if (c instanceof ColPrimaryKey) sb.append(" PRIMARY KEY");
                else if (c instanceof ColUnique) sb.append(" UNIQUE");
            }
        }
        return sb.toString();
    }

    @Override
    protected String generateAlterColumnType(IRAlterColumnType act, GenerateOptions opt) {
        return "ALTER TABLE " + quoteIdentifier(act.tableName())
            + " MODIFY COLUMN " + quoteIdentifier(act.column()) + " " + mapType(act.newType());
    }

    @Override
    protected String generateCreateProcedure(IRCreateProcedure cp, GenerateOptions opt) {
        var sb = new StringBuilder("CREATE ");
        if (cp.orReplace()) sb.append("OR REPLACE ");
        sb.append("PROCEDURE ").append(quoteIdentifier(cp.name()));
        // MySQL requires () even when no params
        sb.append("()\n").append(cp.body());
        return sb.toString();
    }

    // ══════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════

    private String tableName(IRTableRef ref) {
        if (ref instanceof IRStatement.IRTableName tn) return quoteIdentifier(tn.name());
        return generateTableRef(ref, GenerateOptions.MINIMAL);
    }

    private String escapeString(String s) {
        return s.replace("'", "''").replace("\\", "\\\\");
    }
}
