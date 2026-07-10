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

        IRStatement optimized = switch (level) {
            case 1 -> foldConstants(ir.rootStatement());
            default -> ir.rootStatement(); // higher levels TBD
        };
        return new SemanticIR(optimized);
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
            case IRCreateTable ct -> foldCreateTable(ct);
            case IRCreateIndex ci  -> foldCreateIndex(ci);
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

    // ── Constant evaluation ──

    private static IRExpr tryEvaluateBinary(IRExpr left, IRBinaryOp.BinOp op, IRExpr right) {
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
        if (expr instanceof IRLiteral lit && lit.value() == null) {
            return new IRLiteral(!not, null);
        }
        return new IRIsNull(expr, not, expr.getType());
    }
}
