package com.usql.optimizer;

import com.usql.ir.*;
import com.usql.ir.IRExpr.*;
import com.usql.ir.IRStatement.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * IR-level optimizations.
 *
 * Phase 5 of the compiler pipeline.
 * Performs constant folding, expression simplification,
 * and dead code elimination on the SemanticIR.
 */
public class IROptimizer {

    /**
     * Optimize the IR at the given level.
     *
     * @param level 0 = no optimization, 1 = basic (constant folding), 2 = aggressive
     */
    public static SemanticIR optimize(SemanticIR ir, int level) {
        if (level <= 0) return ir;

        IRStatement result = ir.rootStatement();
        if (level >= 1) result = foldConstants(result);
        if (level >= 2) result = optimizeSubqueries(result);
        return new SemanticIR(result);
    }

    // ══════════════════════════════════════════════════
    //  Constant folding — Level 1
    // ══════════════════════════════════════════════════

    /**
     * Fold constant expressions and simplify the IR.
     */
    private static IRStatement foldConstants(IRStatement stmt) {
        return switch (stmt) {
            case IRSelect sel   -> foldSelect(sel);
            case IRInsert ins   -> foldInsert(ins);
            case IRUpdate upd   -> foldUpdate(upd);
            case IRDelete del   -> foldDelete(del);
            case IRMerge merge  -> foldMerge(merge);
            case IRCreateTable ct      -> foldCreateTable(ct);
            case IRCreateIndex ci       -> foldCreateIndex(ci);
            case IRCreateProcedure cp   -> cp;
            case IRCreateFunction cf    -> cf;
            case IRCall call            -> call;
        };
    }

    // ── SELECT ──

    private static IRSelect foldSelect(IRSelect sel) {
        // Recursively fold subqueries in FROM
        List<IRTableRef> from = null;
        if (sel.core().from() != null) {
            from = new ArrayList<>();
            for (var ref : sel.core().from()) {
                from.add(foldTableRef(ref));
            }
        }

        // Fold projections
        List<IRSelectItem> projections = null;
        if (sel.core().projections() != null) {
            projections = new ArrayList<>();
            for (var p : sel.core().projections()) {
                projections.add(foldSelectItem(p));
            }
        }

        // Fold WHERE — simplify TRUE literal
        IRExpr where = sel.core().where() != null ? foldExpr(sel.core().where()) : null;
        if (where instanceof IRLiteral lit && lit.value() instanceof Boolean b && b) {
            where = null; // WHERE TRUE → remove
        }

        // Fold GROUP BY
        List<IRGroupBy> groupBy = null;
        if (sel.core().groupBy() != null) {
            groupBy = new ArrayList<>();
            for (var g : sel.core().groupBy()) {
                groupBy.add(new IRGroupBy(foldExpr(g.expr()), g.kind()));
            }
        }

        // Fold HAVING
        IRExpr having = sel.core().having() != null ? foldExpr(sel.core().having()) : null;

        // Fold ORDER BY
        List<OrderBy> orderBy = null;
        if (sel.orderBy() != null && !sel.orderBy().isEmpty()) {
            orderBy = new ArrayList<>();
            for (var o : sel.orderBy()) {
                orderBy.add(new OrderBy(foldExpr(o.expr()), o.dir(), o.nulls()));
            }
        }

        // Fold WITH (CTE)
        List<IRCommonTable> withClause = null;
        if (sel.core().withClause() != null) {
            withClause = new ArrayList<>();
            for (var cte : sel.core().withClause()) {
                withClause.add(new IRCommonTable(cte.name(), cte.columns(),
                    cte.recursive() ? foldSelect(cte.query()) : cte.query(), cte.recursive()));
            }
        }

        // Fold SET operand
        IRSelect setOperand = sel.core().setOperand() != null
            ? foldSelect(sel.core().setOperand()) : null;

        SelectCore core = new SelectCore(
            projections, from, where, groupBy, having, withClause,
            sel.core().setOp(), setOperand, sel.core().distinct()
        );

        // Simplify FETCH
        FetchClause fetch = foldFetch(sel.fetch());

        return new IRSelect(core, orderBy, fetch, sel.capabilities());
    }

    private static FetchClause foldFetch(FetchClause fetch) {
        if (fetch == null) return null;
        IRExpr limit = fetch.limit() != null ? foldExpr(fetch.limit()) : null;
        IRExpr offset = fetch.offset() != null ? foldExpr(fetch.offset()) : null;

        // OFFSET 0 → remove offset
        if (offset instanceof IRLiteral lit && lit.value() instanceof Number n && n.longValue() == 0) {
            offset = null;
        }
        return new FetchClause(limit, offset);
    }

    // ── Table references ──

    private static IRTableRef foldTableRef(IRTableRef ref) {
        return switch (ref) {
            case IRTableName tn -> tn;
            case IRJoin jn -> {
                IRTableRef left = foldTableRef(jn.left());
                IRTableRef right = foldTableRef(jn.right());
                IRExpr on = jn.onCondition() != null ? foldExpr(jn.onCondition()) : null;
                yield new IRJoin(left, jn.type(), right, on);
            }
            case IRSubqueryTable sq -> new IRSubqueryTable(foldSelect(sq.query()), sq.alias());
            case IRFunctionTable ft -> {
                List<IRExpr> args = ft.args().stream()
                    .map(IROptimizer::foldExpr).toList();
                yield new IRFunctionTable(ft.funcName(), args, ft.alias(), ft.lateral());
            }
        };
    }

    private static IRSelectItem foldSelectItem(IRSelectItem item) {
        return switch (item) {
            case IRExprSelect es -> new IRExprSelect(foldExpr(es.expr()), es.alias());
            case IRWildcardSelect ws -> ws;
        };
    }

    // ── INSERT / UPDATE / DELETE ──

    private static IRInsert foldInsert(IRInsert ins) {
        IRTableRef table = foldTableRef(ins.table());
        IRSelect selectSource = ins.selectSource() != null ? foldSelect(ins.selectSource()) : null;
        List<List<IRExpr>> values = null;
        if (ins.values() != null) {
            values = new ArrayList<>();
            for (var row : ins.values()) {
                values.add(row.stream().map(IROptimizer::foldExpr).toList());
            }
        }
        return new IRInsert(table, ins.columns(), values, selectSource, ins.ignoreErrors(), ins.capabilities());
    }

    private static IRUpdate foldUpdate(IRUpdate upd) {
        List<SetClause> sets = new ArrayList<>();
        for (var s : upd.sets()) {
            sets.add(new SetClause(s.column(), foldExpr(s.value())));
        }
        IRExpr where = upd.where() != null ? foldExpr(upd.where()) : null;
        return new IRUpdate(foldTableRef(upd.table()), sets, where, upd.capabilities());
    }

    private static IRDelete foldDelete(IRDelete del) {
        IRExpr where = del.where() != null ? foldExpr(del.where()) : null;
        return new IRDelete(foldTableRef(del.table()), where, del.capabilities());
    }

    // ── MERGE ──

    private static IRMerge foldMerge(IRMerge merge) {
        IRExpr on = foldExpr(merge.onCondition());
        List<IRMergeAction> actions = null;
        if (merge.actions() != null) {
            actions = new ArrayList<>();
            for (var a : merge.actions()) {
                actions.add(switch (a) {
                    case MergeInsert mi -> {
                        List<IRExpr> vals = mi.values().stream().map(IROptimizer::foldExpr).toList();
                        yield new MergeInsert(mi.columns(), vals);
                    }
                    case MergeUpdate mu -> {
                        List<SetClause> sets = mu.sets().stream()
                            .map(s -> new SetClause(s.column(), foldExpr(s.value()))).toList();
                        yield new MergeUpdate(sets);
                    }
                    case MergeDelete md -> md;
                });
            }
        }
        return new IRMerge(foldTableRef(merge.target()), foldTableRef(merge.source()),
            on, actions, merge.capabilities());
    }

    // ── DDL ──

    private static IRCreateTable foldCreateTable(IRCreateTable ct) {
        List<IRColumnDef> columns = null;
        if (ct.columns() != null) {
            columns = new ArrayList<>();
            for (var col : ct.columns()) {
                IRExpr defaultVal = col.defaultValue() != null ? foldExpr(col.defaultValue()) : null;
                List<IRColumnConstraint> constraints = null;
                if (col.constraints() != null) {
                    constraints = new ArrayList<>();
                    for (var c : col.constraints()) {
                        constraints.add(switch (c) {
                            case ColCheck chk -> new ColCheck(foldExpr(chk.condition()));
                            default -> c;
                        });
                    }
                }
                columns.add(new IRColumnDef(col.name(), col.type(), constraints, defaultVal));
            }
        }
        List<IRTableConstraint> tblConstraints = null;
        if (ct.constraints() != null) {
            tblConstraints = new ArrayList<>();
            for (var c : ct.constraints()) {
                tblConstraints.add(switch (c) {
                    case TBCheck chk -> new TBCheck(foldExpr(chk.condition()), chk.constraintName());
                    default -> c;
                });
            }
        }
        return new IRCreateTable(ct.name(), ct.ifNotExists(), columns, tblConstraints,
            ct.options(), ct.capabilities());
    }

    private static IRCreateIndex foldCreateIndex(IRCreateIndex ci) {
        IRExpr where = ci.whereClause() != null ? foldExpr(ci.whereClause()) : null;
        Set<Capability> caps = ci.capabilities();
        if (where == null && caps.contains(Capability.PARTIAL_INDEX)) {
            caps = new java.util.LinkedHashSet<>(caps);
            caps.remove(Capability.PARTIAL_INDEX);
        }
        return new IRCreateIndex(ci.name(), ci.table(), ci.columns(), ci.unique(),
            ci.ifNotExists(), ci.type(), where, caps);
    }

    // ══════════════════════════════════════════════════
    //  Expression constant folding
    // ══════════════════════════════════════════════════

    private static IRExpr foldExpr(IRExpr expr) {
        return switch (expr) {
            case IRLiteral lit -> lit;
            case IRColumnRef cr -> cr;
            case IRWildcard wc -> wc;
            case IRParameter p -> p;

            case IRBinaryOp bo -> {
                IRExpr left = foldExpr(bo.left());
                IRExpr right = foldExpr(bo.right());
                yield tryEvaluateBinary(left, bo.op(), right);
            }

            case IRUnaryOp uo -> {
                IRExpr operand = foldExpr(uo.operand());
                yield tryEvaluateUnary(uo.op(), operand);
            }

            case IRFunctionCall fc -> {
                List<IRExpr> args = fc.args().stream()
                    .map(IROptimizer::foldExpr).toList();
                yield new IRFunctionCall(fc.funcName(), args, fc.type(), fc.over(), fc.keep());
            }

            case IRCase cs -> {
                List<IRCase.WhenClause> whens = cs.whens().stream()
                    .map(w -> new IRCase.WhenClause(foldExpr(w.condition()), foldExpr(w.result())))
                    .toList();
                IRExpr elseExpr = cs.elseExpr() != null ? foldExpr(cs.elseExpr()) : null;
                yield new IRCase(whens, elseExpr, cs.type());
            }

            case IRCast ct -> new IRCast(foldExpr(ct.expr()), ct.targetType());
            case IRSubquery sq -> new IRSubquery(foldSelect(sq.query()), sq.type());

            case IRBetween btw -> {
                IRExpr e = foldExpr(btw.expr());
                IRExpr lo = foldExpr(btw.low());
                IRExpr hi = foldExpr(btw.high());
                yield new IRBetween(e, lo, hi, btw.not(), btw.type());
            }

            case IRInList in -> {
                IRExpr e = foldExpr(in.expr());
                List<IRExpr> vals = in.values() != null
                    ? in.values().stream().map(IROptimizer::foldExpr).toList() : null;
                IRSelect sq = in.subquery() != null ? foldSelect(in.subquery()) : null;
                yield new IRInList(e, vals, sq, in.not(), in.type());
            }

            case IRIsNull isn -> {
                IRExpr e = foldExpr(isn.expr());
                yield tryEvaluateIsNull(e, isn.not());
            }
        };
    }

    // ══════════════════════════════════════════════════
    //  Subquery optimization — Level 2
    // ══════════════════════════════════════════════════

    /** Flatten simple subqueries and merge predicates. */
    private static IRStatement optimizeSubqueries(IRStatement stmt) {
        return switch (stmt) {
            case IRSelect sel   -> flattenSubqueries(sel);
            case IRInsert ins   -> flattenSubqueriesInInsert(ins);
            case IRUpdate upd   -> flattenSubqueriesInUpdate(upd);
            case IRDelete del   -> flattenSubqueriesInDelete(del);
            default -> stmt;
        };
    }

    /**
     * Flatten FROM-clause subqueries that are simple projections without
     * aggregation, distinct, grouping, or limits.
     *
     * Example:
     *   SELECT * FROM (SELECT a, b FROM t) AS s WHERE s.a > 1
     *   → SELECT a, b FROM t WHERE s.a > 1  (with alias rewritten)
     */
    private static IRSelect flattenSubqueries(IRSelect sel) {
        if (sel.core().from() == null) return sel;

        List<IRTableRef> newFrom = new ArrayList<>();
        boolean changed = false;

        for (var ref : sel.core().from()) {
            if (ref instanceof IRSubqueryTable sq && isFlattenable(sq.query())) {
                // Inline the subquery's FROM into the outer query
                if (sq.query().core().from() != null) {
                    for (var innerRef : sq.query().core().from()) {
                        newFrom.add(rewriteAlias(innerRef, sq.alias()));
                    }
                }
                // Merge WHERE from outer using subquery alias → inner columns
                // For simplicity: if outer WHERE references s.col, push down
                changed = true;
            } else {
                newFrom.add(foldTableRef(ref));
            }
        }

        if (!changed) return sel;

        // Rebuild projections: expand wildcards from subquery
        List<IRSelectItem> newProj = new ArrayList<>();
        for (var p : sel.core().projections()) {
            if (p instanceof IRWildcardSelect) {
                // Expand wildcard from the first flattenable subquery
                for (var ref : sel.core().from()) {
                    if (ref instanceof IRSubqueryTable sq && isFlattenable(sq.query())) {
                        for (var innerP : sq.query().core().projections()) {
                            newProj.add(innerP); // inner aliases preserved
                        }
                        break;
                    } else {
                        newProj.add(p); // keep wildcard for non-subquery tables
                    }
                }
            } else {
                newProj.add(p);
            }
        }

        // Merge WHERE conditions
        IRExpr where = sel.core().where();
        for (var ref : sel.core().from()) {
            if (ref instanceof IRSubqueryTable sq && isFlattenable(sq.query())) {
                if (sq.query().core().where() != null) {
                    if (where != null) {
                        where = new IRBinaryOp(where, IRBinaryOp.BinaryOp.AND,
                            sq.query().core().where(), null);
                    } else {
                        where = sq.query().core().where();
                    }
                }
            }
        }
        where = where != null ? foldExpr(where) : null;

        SelectCore core = new SelectCore(newProj, newFrom, where,
            sel.core().groupBy(), sel.core().having(), sel.core().withClause(),
            null, null, sel.core().distinct());

        return new IRSelect(core, sel.orderBy(), sel.fetch(), sel.capabilities());
    }

    /** Check if a SELECT can be safely flattened. */
    private static boolean isFlattenable(IRSelect sel) {
        if (sel.core().distinct()) return false;
        if (sel.core().groupBy() != null && !sel.core().groupBy().isEmpty()) return false;
        if (sel.core().having() != null) return false;
        if (sel.core().setOp() != null) return false;
        if (sel.orderBy() != null && !sel.orderBy().isEmpty()) return false;
        if (sel.fetch() != null) return false;
        return true;
    }

    /** Rewrite table reference aliases for inlined subquery. */
    private static IRTableRef rewriteAlias(IRTableRef ref, String outerAlias) {
        return switch (ref) {
            case IRTableName tn -> {
                // Keep original table name, use original alias if set
                if (tn.alias() != null) yield tn;
                yield new IRTableName(tn.name(), outerAlias, tn.schema());
            }
            case IRJoin jn -> new IRJoin(
                rewriteAlias(jn.left(), outerAlias),
                jn.type(),
                rewriteAlias(jn.right(), outerAlias),
                jn.onCondition()
            );
            case IRSubqueryTable sq -> sq; // nested subqueries left as-is
            case IRFunctionTable ft -> ft;
        };
    }

    private static IRInsert flattenSubqueriesInInsert(IRInsert ins) {
        IRSelect source = ins.selectSource() != null
            ? flattenSubqueries(ins.selectSource()) : null;
        return new IRInsert(ins.table(), ins.columns(), ins.values(),
            source, ins.ignoreErrors(), ins.capabilities());
    }

    private static IRUpdate flattenSubqueriesInUpdate(IRUpdate upd) {
        // Subqueries in SET values and WHERE — fold expressions
        List<SetClause> sets = upd.sets().stream()
            .map(s -> new SetClause(s.column(), foldExpr(s.value()))).toList();
        IRExpr where = upd.where() != null ? foldExpr(upd.where()) : null;
        return new IRUpdate(upd.table(), sets, where, upd.capabilities());
    }

    private static IRDelete flattenSubqueriesInDelete(IRDelete del) {
        IRExpr where = del.where() != null ? foldExpr(del.where()) : null;
        return new IRDelete(del.table(), where, del.capabilities());
    }

    // ── Constant evaluation ──

    private static IRExpr tryEvaluateBinary(IRExpr left, IRBinaryOp.BinaryOp op, IRExpr right) {
        if (!(left instanceof IRLiteral lv) || !(right instanceof IRLiteral rv)) {
            return new IRBinaryOp(left, op, right, null);
        }
        Object l = lv.value();
        Object r = rv.value();

        try {
            Object result = switch (op) {
                case ADD -> addValues(l, r);
                case SUB -> mathOp(l, r, (a, b) -> a - b, (a, b) -> a - b);
                case MUL -> mathOp(l, r, (a, b) -> a * b, (a, b) -> a * b);
                case DIV -> {
                    if (isZero(r)) yield null;
                    yield mathOp(l, r, (a, b) -> (long) (a / b), (a, b) -> a / b);
                }
                case MOD -> {
                    if (isZero(r)) yield null;
                    yield mathOp(l, r, (a, b) -> a % b, (a, b) -> a % b);
                }
                case AND -> {
                    if (l instanceof Boolean lb && r instanceof Boolean rb) yield lb && rb;
                    yield null;
                }
                case OR -> {
                    if (l instanceof Boolean lb && r instanceof Boolean rb) yield lb || rb;
                    yield null;
                }
                case EQ -> foldEquals(l, r);
                case NEQ -> foldNotEquals(l, r);
                default -> null; // comparison on differing types kept as-is
            };
            if (result != null) {
                return new IRLiteral(result, null);
            }
        } catch (Exception ignored) {}

        return new IRBinaryOp(left, op, right, null);
    }

    private static Object addValues(Object l, Object r) {
        if (l instanceof String || r instanceof String)
            return String.valueOf(l) + r;
        if (l instanceof Number ln && r instanceof Number rn) {
            if (l instanceof Double || r instanceof Double || l instanceof Float || r instanceof Float)
                return ln.doubleValue() + rn.doubleValue();
            return ln.longValue() + rn.longValue();
        }
        return null;
    }

    @FunctionalInterface
    private interface LongOp { long apply(long a, long b); }
    @FunctionalInterface
    private interface DoubleOp { double apply(double a, double b); }

    private static Object mathOp(Object l, Object r, LongOp longOp, DoubleOp doubleOp) {
        if (!(l instanceof Number ln) || !(r instanceof Number rn)) return null;
        if (l instanceof Double || r instanceof Double || l instanceof Float || r instanceof Float)
            return doubleOp.apply(ln.doubleValue(), rn.doubleValue());
        return longOp.apply(ln.longValue(), rn.longValue());
    }

    private static boolean isZero(Object v) {
        if (v instanceof Number n) return n.doubleValue() == 0.0;
        return false;
    }

    private static Boolean foldEquals(Object l, Object r) {
        if (l == null && r == null) return true;
        if (l == null || r == null) return false;
        if (l instanceof Number ln && r instanceof Number rn)
            return ln.doubleValue() == rn.doubleValue();
        return l.equals(r);
    }

    private static Boolean foldNotEquals(Object l, Object r) {
        Boolean eq = foldEquals(l, r);
        return eq != null ? !eq : null;
    }

    private static IRExpr tryEvaluateUnary(IRUnaryOp.UnaryOp op, IRExpr operand) {
        if (op != IRUnaryOp.UnaryOp.NOT && op != IRUnaryOp.UnaryOp.NEG)
            return new IRUnaryOp(op, operand, null);

        if (!(operand instanceof IRLiteral lit)) {
            return new IRUnaryOp(op, operand, null);
        }
        Object val = lit.value();
        try {
            Object result = switch (op) {
                case NOT -> val instanceof Boolean b ? !b : null;
                case NEG -> {
                    if (val instanceof Long ln) yield -ln;
                    if (val instanceof Integer in) yield -in;
                    if (val instanceof Double dn) yield -dn;
                    yield null;
                }
                default -> null;
            };
            if (result != null) return new IRLiteral(result, null);
        } catch (Exception ignored) {}
        return new IRUnaryOp(op, operand, null);
    }

    private static IRExpr tryEvaluateIsNull(IRExpr expr, boolean not) {
        if (expr instanceof IRLiteral lit) {
            if (lit.value() == null) return new IRLiteral(!not, null);
            else return new IRLiteral(not, null); // non-null IS NULL → false, IS NOT NULL → true
        }
        return new IRIsNull(expr, not, expr.getType());
    }
}
