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

    /** Scan expression tree for KEEP aggregates, collect sort column info. */
    protected void scanKeep(IRExpr expr) {
        if (expr == null) return;
        if (expr instanceof IRFunctionCall fc && fc.keep() != null) {
            var o = fc.keep().orderBy().get(0);
            boolean isFirst = fc.keep() instanceof KeepSpec.First;
            boolean desc = (isFirst && o.dir() == OrderDir.DESC) || (!isFirst && o.dir() == OrderDir.ASC);
            keepCols.add(new KeepCol(generateExpr(o.expr(), GenerateOptions.MINIMAL), desc));
            return;
        }
        if (expr instanceof IRFunctionCall fc) fc.args().forEach(this::scanKeep);
        else if (expr instanceof IRBinaryOp bo) { scanKeep(bo.left()); scanKeep(bo.right()); }
        else if (expr instanceof IRUnaryOp uo) scanKeep(uo.operand());
        else if (expr instanceof IRCase cs) { cs.whens().forEach(w -> { scanKeep(w.condition()); scanKeep(w.result()); }); if (cs.elseExpr() != null) scanKeep(cs.elseExpr()); }
        else if (expr instanceof IRCast ct) scanKeep(ct.expr());
        else if (expr instanceof IRBetween bt) { scanKeep(bt.expr()); scanKeep(bt.low()); scanKeep(bt.high()); }
        else if (expr instanceof IRInList il) { scanKeep(il.expr()); il.values().forEach(this::scanKeep); }
        else if (expr instanceof IRIsNull isn) scanKeep(isn.expr());
    }

    /** Scan all projections, HAVING, ORDER BY for KEEP aggregates. */
    protected void scanKeepFromSelect(IRSelect sel) {
        keepCols = new ArrayList<>();
        if (sel.core().projections() != null)
            sel.core().projections().forEach(p -> { if (p instanceof IRExprSelect es) scanKeep(es.expr()); });
        if (sel.core().having() != null) scanKeep(sel.core().having());
        if (sel.orderBy() != null) sel.orderBy().forEach(o -> scanKeep(o.expr()));
    }

    /** Generate the outer aggregate: AGG(CASE WHEN _keep_N = 1 THEN valueExpr END) */
    protected String polyfillKeep(IRFunctionCall fc, String argsStr) {
        return fc.funcName() + "(CASE WHEN " + quoteIdentifier("_keep_" + keepIdx++)
            + " = 1 THEN " + argsStr + " END)";
    }

    /** Build the subquery-wrapped FROM clause with DENSE_RANK columns.
     *  @param originalFrom the original FROM clause SQL (already rendered) */
    protected String wrapFromWithKeep(String originalFrom, String partitionBy) {
        var sb = new StringBuilder(" FROM (SELECT " + quoteIdentifier("_t") + ".*");
        for (int i = 0; i < keepCols.size(); i++) {
            var kc = keepCols.get(i);
            sb.append(", DENSE_RANK() OVER (").append(partitionBy)
              .append("ORDER BY ").append(kc.sortExpr());
            if (kc.desc()) sb.append(" DESC");
            sb.append(") AS ").append(quoteIdentifier("_keep_" + i));
        }
        sb.append(" FROM ").append(originalFrom)
          .append(" ").append(quoteIdentifier("_t"))
          .append(") ").append(quoteIdentifier("_src"));
        return sb.toString();
    }

    /** Build PARTITION BY clause from GROUP BY for DENSE_RANK. */
    protected String partitionFromGroupBy(IRSelect sel, GenerateOptions opt) {
        if (sel.core().groupBy() == null || sel.core().groupBy().isEmpty()) return "";
        return "PARTITION BY " + sel.core().groupBy().stream()
            .filter(g -> g.kind() == GroupByKind.PLAIN)
            .map(g -> generateExpr(g.expr(), opt))
            .collect(Collectors.joining(", ")) + " ";
    }

    // ══════════════════════════════════════════════════
    //  Abstract methods — each dialect provides these
    // ══════════════════════════════════════════════════

    /** Generate SQL for a single expression. */
    protected abstract String generateExpr(IRExpr expr, GenerateOptions opt);
}
