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
 * Shared base for dialect backends — provides KEEP polyfill (subquery + DENSE_RANK)
 * and other cross-dialect logic so each backend only implements dialect-specific code.
 */
public abstract class AbstractDialectBackend implements DialectBackend {

    protected FunctionCatalog functionCatalog;
    protected List<KeepCol> keepCols;
    protected int keepIdx;
    protected record KeepCol(String sortExpr, boolean desc) {}

    @Override
    public void setFunctionCatalog(FunctionCatalog catalog) { this.functionCatalog = catalog; }

    // ══════════════════════════════════════════════════
    //  KEEP polyfill — shared by MySQL/PG/DM/SQL Server
    // ══════════════════════════════════════════════════

    /** Scan expression tree for KEEP aggregates — only recurses into function arguments. */
    protected void scanKeep(IRExpr expr) {
        if (expr == null) return;
        if (expr instanceof IRFunctionCall fc) {
            if (fc.keep() != null) {
                var o = fc.keep().orderBy().get(0);
                boolean isFirst = fc.keep() instanceof KeepSpec.First;
                boolean desc = (isFirst && o.dir() == OrderDir.DESC) || (!isFirst && o.dir() == OrderDir.ASC);
                keepCols.add(new KeepCol(generateExpr(o.expr(), GenerateOptions.MINIMAL), desc));
            } else {
                // Only recurse into function arguments (most expressions don't contain KEEP)
                fc.args().forEach(this::scanKeep);
            }
        }
    }

    /** Scan all projections, HAVING, ORDER BY for KEEP aggregates. */
    protected void scanKeepFromSelect(IRSelect sel) {
        keepCols = new ArrayList<>();
        if (sel.core().projections() != null) {
            for (var p : sel.core().projections()) {
                if (p instanceof IRExprSelect es) scanKeep(es.expr());
            }
        }
        // Only scan HAVING/ORDER BY if we found KEEP in projections
        if (!keepCols.isEmpty()) {
            if (sel.core().having() != null) scanKeep(sel.core().having());
            if (sel.orderBy() != null) sel.orderBy().forEach(o -> scanKeep(o.expr()));
        }
    }

    /** Generate the outer aggregate: AGG(CASE WHEN _keep_N = 1 THEN valueExpr END) */
    protected String polyfillKeep(IRFunctionCall fc, String argsStr) {
        return fc.funcName() + "(CASE WHEN " + quoteIdentifier("_keep_" + keepIdx++)
            + " = 1 THEN " + argsStr + " END)";
    }

    /** Build the subquery-wrapped FROM clause with DENSE_RANK columns.
     *  @param originalFrom the original FROM clause SQL (already rendered)
     *  @param partitionBy the PARTITION BY clause (references original table alias)
     *  @param originalTableAlias the original table alias/name used in FROM (to rewrite to _t) */
    protected String wrapFromWithKeep(String originalFrom, String partitionBy, String originalTableAlias) {
        // Inside the subquery, the table is aliased as _t, so rewrite references
        // from originalTableAlias to _t in PARTITION BY and ORDER BY expressions
        String rewrittenPartitionBy = originalTableAlias != null && !originalTableAlias.isEmpty()
            ? partitionBy.replace(quoteIdentifier(originalTableAlias) + ".", quoteIdentifier("_t") + ".")
            : partitionBy;
        var sb = new StringBuilder(" FROM (SELECT " + quoteIdentifier("_t") + ".*");
        for (int i = 0; i < keepCols.size(); i++) {
            var kc = keepCols.get(i);
            String sortExpr = originalTableAlias != null && !originalTableAlias.isEmpty()
                ? kc.sortExpr().replace(quoteIdentifier(originalTableAlias) + ".", quoteIdentifier("_t") + ".")
                : kc.sortExpr();
            sb.append(", DENSE_RANK() OVER (").append(rewrittenPartitionBy)
              .append("ORDER BY ").append(sortExpr);
            if (kc.desc()) sb.append(" DESC");
            sb.append(") AS ").append(quoteIdentifier("_keep_" + i));
        }
        sb.append(" FROM ").append(originalFrom)
          .append(" ").append(quoteIdentifier("_t"))
          .append(") ").append(quoteIdentifier("_src"));
        return sb.toString();
    }

    /** Build the subquery-wrapped FROM clause with DENSE_RANK columns.
     *  @param originalFrom the original FROM clause SQL (already rendered)
     *  @param partitionBy the PARTITION BY clause */
    protected String wrapFromWithKeep(String originalFrom, String partitionBy) {
        return wrapFromWithKeep(originalFrom, partitionBy, null);
    }

    /** Build PARTITION BY clause from GROUP BY for DENSE_RANK. */
    protected String partitionFromGroupBy(IRSelect sel, GenerateOptions opt) {
        if (sel.core().groupBy() == null || sel.core().groupBy().isEmpty()) return "";
        return "PARTITION BY " + sel.core().groupBy().stream()
            .filter(g -> g.kind() == GroupByKind.PLAIN)
            .map(g -> generateExpr(g.expr(), opt))
            .collect(Collectors.joining(", ")) + " ";
    }

    /** Extract the first table name/alias from the FROM clause (for KEEP polyfill rewriting). */
    protected String extractFirstTableAlias(IRSelect sel) {
        if (sel.core().from() == null || sel.core().from().isEmpty()) return null;
        IRTableRef first = sel.core().from().get(0);
        if (first instanceof IRTableName tn) {
            return tn.alias() != null ? tn.alias() : tn.name();
        }
        return null;
    }

    // ══════════════════════════════════════════════════
    //  Shared function call generation
    // ══════════════════════════════════════════════════

    /**
     * Catalog lookup + template rendering + OVER clause.
     * Shared by all dialects — the only difference is which Dialect
     * is passed to {@code forDialect()}, resolved via {@link #targetDialect()}.
     */
    protected String resolveFunctionCall(String funcName, List<String> argList, String argsStr,
                                          IRWindowOver over, GenerateOptions opt) {
        String result;
        if (functionCatalog != null) {
            var def = functionCatalog.get(funcName);
            if (def.isPresent()) {
                var mapping = def.get().forDialect(targetDialect());
                if (mapping.isPresent()) {
                    String tpl = mapping.get().renderTemplate();
                    if (tpl != null) {
                        result = tpl;
                        for (int i = 0; i < argList.size(); i++)
                            result = result.replace("{" + i + "}", argList.get(i));
                    } else {
                        result = mapping.get().nativeName() + "(" + argsStr + ")";
                    }
                } else { result = funcName + "(" + argsStr + ")"; }
            } else { result = funcName + "(" + argsStr + ")"; }
        } else { result = funcName + "(" + argsStr + ")"; }

        if (over != null) {
            result += " OVER (";
            if (over.partitionBy() != null && !over.partitionBy().isEmpty()) {
                result += "PARTITION BY " + over.partitionBy().stream()
                    .map(e -> generateExpr(e, opt)).collect(Collectors.joining(", "));
            }
            if (over.orderBy() != null && !over.orderBy().isEmpty()) {
                if (over.partitionBy() != null && !over.partitionBy().isEmpty()) result += " ";
                result += "ORDER BY " + over.orderBy().stream()
                    .map(o -> generateExpr(o.expr(), opt) + (o.dir() == OrderDir.DESC ? " DESC" : " ASC"))
                    .collect(Collectors.joining(", "));
            }
            if (over.frame() != null) result += " " + over.frame().toSql();
            result += ")";
        }
        return result;
    }

    /**
     * Generate SQL for a function call.
     * Default implementation handles KEEP via polyfill (MySQL/PG/DM/SQL Server).
     * Oracle overrides for native KEEP syntax.
     */
    protected String generateFunctionCall(IRFunctionCall fc, GenerateOptions opt) {
        List<String> argList = fc.args().stream()
            .map(a -> generateExpr(a, opt))
            .collect(Collectors.toList());
        String argsStr = String.join(", ", argList);

        if (fc.keep() != null) return polyfillKeep(fc, argsStr);
        return resolveFunctionCall(fc.funcName(), argList, argsStr, fc.over(), opt);
    }

    // ══════════════════════════════════════════════════
    //  Stored procedure generation (shared)
    // ══════════════════════════════════════════════════

    protected String generateCreateProcedure(IRCreateProcedure cp, GenerateOptions opt) {
        var sb = new StringBuilder("CREATE ");
        if (cp.orReplace()) sb.append("OR REPLACE ");
        sb.append("PROCEDURE ").append(quoteIdentifier(cp.name()));
        sb.append(paramsDecl(cp.params(), opt));
        sb.append("\n").append(cp.body());
        return sb.toString();
    }

    protected String generateCreateFunction(IRCreateFunction cf, GenerateOptions opt) {
        var sb = new StringBuilder("CREATE ");
        if (cf.orReplace()) sb.append("OR REPLACE ");
        sb.append("FUNCTION ").append(quoteIdentifier(cf.name()));
        sb.append(paramsDecl(cf.params(), opt));
        sb.append(" RETURNS ").append(mapType(cf.returnType()));
        sb.append("\n").append(cf.body());
        return sb.toString();
    }

    protected String generateCall(IRCall call, GenerateOptions opt) {
        String args = call.args() != null && !call.args().isEmpty()
            ? call.args().stream().map(a -> generateExpr(a, opt))
                .collect(Collectors.joining(", "))
            : "";
        return "CALL " + quoteIdentifier(call.procedureName())
            + (args.isEmpty() ? "" : "(" + args + ")");
    }

    /** Comma-separated parameter list */
    protected String paramsDecl(List<ProcedureParam> params, GenerateOptions opt) {
        if (params == null || params.isEmpty()) return "";
        return "(" + params.stream()
            .map(p -> paramDecl(p, opt))
            .collect(Collectors.joining(", ")) + ")";
    }

    /** Parameter declaration for dialect */
    protected String paramDecl(ProcedureParam p, GenerateOptions opt) {
        String mode = switch (p.mode()) {
            case IN -> "IN ";
            case OUT -> "OUT ";
            case INOUT -> "INOUT ";
        };
        return mode + quoteIdentifier(p.name()) + " " + mapType(p.type());
    }

    // ══════════════════════════════════════════════════
    //  DROP / TRUNCATE / ALTER TABLE (shared)
    // ══════════════════════════════════════════════════

    protected String generateDropTable(IRDropTable dt, GenerateOptions opt) {
        return "DROP TABLE " + (dt.ifExists() ? "IF EXISTS " : "") + quoteIdentifier(dt.name())
            + (dt.cascade() ? " CASCADE" : "");
    }

    protected String generateDropDatabase(IRDropDatabase dd, GenerateOptions opt) {
        return "DROP DATABASE " + (dd.ifExists() ? "IF EXISTS " : "") + quoteIdentifier(dd.name());
    }

    protected String generateCreateView(IRCreateView cv, GenerateOptions opt) {
        return "CREATE VIEW " + quoteIdentifier(cv.name()) + " AS " + generate(cv.query(), opt);
    }

    protected String generateCreateSchema(IRCreateSchema cs, GenerateOptions opt) {
        return "CREATE SCHEMA " + quoteIdentifier(cs.name());
    }

    protected String generateTCL(IRTCL tcl, GenerateOptions opt) {
        return tcl.sql(); // pass-through — TCL is standard across databases
    }

    protected String generateDropIndex(IRDropIndex di, GenerateOptions opt) {
        return "DROP INDEX " + (di.ifExists() ? "IF EXISTS " : "") + quoteIdentifier(di.indexName());
    }

    protected String generateTruncateTable(IRTruncateTable tt, GenerateOptions opt) {
        return "TRUNCATE TABLE " + quoteIdentifier(tt.name());
    }

    protected String generateAlterTableAddColumn(IRAlterTableAddColumn aa, GenerateOptions opt) {
        var col = aa.column();
        var sb = new StringBuilder("ALTER TABLE ").append(quoteIdentifier(aa.tableName()))
            .append(" ADD ").append(quoteIdentifier(col.name())).append(" ").append(mapType(col.type()));
        if (col.constraints() != null) {
            for (var c : col.constraints()) {
                if (c instanceof ColNotNull) sb.append(" NOT NULL");
                else if (c instanceof ColPrimaryKey) sb.append(" PRIMARY KEY");
                else if (c instanceof ColUnique) sb.append(" UNIQUE");
            }
        }
        return sb.toString();
    }

    protected String generateAlterTableDropColumn(IRAlterTableDropColumn ad, GenerateOptions opt) {
        return "ALTER TABLE " + quoteIdentifier(ad.tableName())
            + " DROP COLUMN " + quoteIdentifier(ad.columnName());
    }

    protected String generateAlterColumnType(IRAlterColumnType act, GenerateOptions opt) {
        return "ALTER TABLE " + quoteIdentifier(act.tableName())
            + " ALTER COLUMN " + quoteIdentifier(act.column()) + " TYPE " + mapType(act.newType());
    }

    protected String generateAlterColumnSetDefault(IRAlterColumnSetDefault acs, GenerateOptions opt) {
        return "ALTER TABLE " + quoteIdentifier(acs.tableName())
            + " ALTER COLUMN " + quoteIdentifier(acs.column()) + " SET DEFAULT " + generateExpr(acs.value(), opt);
    }

    protected String generateAlterColumnDropDefault(IRAlterColumnDropDefault acd, GenerateOptions opt) {
        return "ALTER TABLE " + quoteIdentifier(acd.tableName())
            + " ALTER COLUMN " + quoteIdentifier(acd.column()) + " DROP DEFAULT";
    }

    protected String generateRenameColumn(IRRenameColumn rc, GenerateOptions opt) {
        return "ALTER TABLE " + quoteIdentifier(rc.tableName())
            + " RENAME COLUMN " + quoteIdentifier(rc.oldName()) + " TO " + quoteIdentifier(rc.newName());
    }

    // ══════════════════════════════════════════════════
    //  Abstract methods — each dialect provides these
    // ══════════════════════════════════════════════════

    /** Generate SQL for a single expression. */
    protected abstract String generateExpr(IRExpr expr, GenerateOptions opt);
}
