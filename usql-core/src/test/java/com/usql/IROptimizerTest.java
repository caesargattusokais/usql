package com.usql;

import com.usql.ir.*;
import com.usql.ir.IRExpr.*;
import com.usql.ir.IRStatement.*;
import com.usql.optimizer.IROptimizer;

import java.util.List;
import java.util.Set;

public class IROptimizerTest {
    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        System.out.println("=== IROptimizer Test ===\n");
        foldArithmetic();
        foldBoolean();
        foldUnary();
        foldString();
        foldIsNull();
        simplifyWhere();
        simplifyFetch();
        foldNested();
        flattenSubquery();
        noFlattenWhenAggregate();
        foldInUpdate();
        foldInDelete();
        foldInMerge();
        levelZero();
        System.out.println("\n=== " + pass + "/" + (pass+fail) + " passed ===");
        if (fail > 0) System.exit(1);
    }

    static void foldArithmetic() {
        // 2+3→5, 3*4→12, 10-7→3, 20/5→4, 15%10→5
        Object[][] cases = {
            {2L, IRBinaryOp.BinOp.ADD, 3L, 5L},
            {3L, IRBinaryOp.BinOp.MUL, 4L, 12L},
            {10L, IRBinaryOp.BinOp.SUB, 7L, 3L},
            {20L, IRBinaryOp.BinOp.DIV, 5L, 4L},
            {15L, IRBinaryOp.BinOp.MOD, 10L, 5L},
        };
        for (Object[] c : cases) {
            IRExpr expr = fold1(new IRBinaryOp(new IRLiteral(c[0], null),
                (IRBinaryOp.BinOp)c[1], new IRLiteral(c[2], null), null));
            check(expr instanceof IRLiteral l && l.value().equals(c[3]),
                c[0] + " " + c[1] + " " + c[2] + " → " + c[3]);
        }
    }

    static void foldBoolean() {
        // TRUE AND FALSE → FALSE
        check(fold1(new IRBinaryOp(new IRLiteral(true,null), IRBinaryOp.BinOp.AND,
            new IRLiteral(false,null), null)) instanceof IRLiteral l && l.value().equals(false),
            "TRUE AND FALSE → FALSE");
        // TRUE OR FALSE → TRUE
        check(fold1(new IRBinaryOp(new IRLiteral(true,null), IRBinaryOp.BinOp.OR,
            new IRLiteral(false,null), null)) instanceof IRLiteral l && l.value().equals(true),
            "TRUE OR FALSE → TRUE");
        // 1 = 1 → true
        check(fold1(new IRBinaryOp(new IRLiteral(1,null), IRBinaryOp.BinOp.EQ,
            new IRLiteral(1,null), null)) instanceof IRLiteral l && l.value().equals(true),
            "1 = 1 → true");
        // 1 = 2 → false
        check(fold1(new IRBinaryOp(new IRLiteral(1,null), IRBinaryOp.BinOp.EQ,
            new IRLiteral(2,null), null)) instanceof IRLiteral l && l.value().equals(false),
            "1 = 2 → false");
        // 1 <> 1 → false
        check(fold1(new IRBinaryOp(new IRLiteral(1,null), IRBinaryOp.BinOp.NEQ,
            new IRLiteral(1,null), null)) instanceof IRLiteral l && l.value().equals(false),
            "1 <> 1 → false");
    }

    static void foldUnary() {
        check(fold1(new IRUnaryOp(IRUnaryOp.UnaryOp.NOT, new IRLiteral(true,null), null))
            instanceof IRLiteral l && l.value().equals(false), "NOT true → false");
        check(fold1(new IRUnaryOp(IRUnaryOp.UnaryOp.NOT, new IRLiteral(false,null), null))
            instanceof IRLiteral l && l.value().equals(true), "NOT false → true");
        check(fold1(new IRUnaryOp(IRUnaryOp.UnaryOp.NEG, new IRLiteral(5L,null), null))
            instanceof IRLiteral l && l.value().equals(-5L), "-5 → -5");
    }

    static void foldString() {
        check(fold1(new IRBinaryOp(new IRLiteral("hello",null), IRBinaryOp.BinOp.ADD,
            new IRLiteral("world",null), null))
            instanceof IRLiteral l && "helloworld".equals(l.value()), "'hello'+'world' → 'helloworld'");
        check(fold1(new IRBinaryOp(new IRLiteral(1,null), IRBinaryOp.BinOp.ADD,
            new IRLiteral("x",null), null))
            instanceof IRLiteral l && "1x".equals(l.value()), "1+'x' → '1x'");
    }

    static void foldIsNull() {
        check(fold1(new IRIsNull(new IRLiteral(null,null), false, null))
            instanceof IRLiteral l && l.value().equals(true), "NULL IS NULL → true");
        check(fold1(new IRIsNull(new IRLiteral(null,null), true, null))
            instanceof IRLiteral l && l.value().equals(false), "NULL IS NOT NULL → false");
        // Non-null IS NULL → false
        check(fold1(new IRIsNull(new IRLiteral(1,null), false, null))
            instanceof IRLiteral l && l.value().equals(false), "1 IS NULL → false");
    }

    static void simplifyWhere() {
        IRSelect q = new IRSelect(new SelectCore(
            List.of(new IRExprSelect(new IRColumnRef("id",null,null), null)),
            List.of(new IRTableName("t",null,null)),
            new IRLiteral(true, null), null, null, null, null, null, false),
            null, null, Set.of());
        check(((IRSelect)IROptimizer.optimize(new SemanticIR(q),1).rootStatement())
            .core().where() == null, "WHERE TRUE → removed");
    }

    static void simplifyFetch() {
        IRSelect q = new IRSelect(new SelectCore(
            List.of(new IRExprSelect(new IRColumnRef("id",null,null), null)),
            List.of(new IRTableName("t",null,null)),
            null, null, null, null, null, null, false),
            null, new FetchClause(new IRLiteral(10,null), new IRLiteral(0,null)), Set.of());
        check(((IRSelect)IROptimizer.optimize(new SemanticIR(q),1).rootStatement())
            .fetch().offset() == null, "OFFSET 0 → removed");
    }

    static void foldNested() {
        // (2+3)*4 → 20
        IRExpr inner = new IRBinaryOp(new IRLiteral(2,null), IRBinaryOp.BinOp.ADD,
            new IRLiteral(3,null), null);
        IRExpr expr = fold1(new IRBinaryOp(inner, IRBinaryOp.BinOp.MUL,
            new IRLiteral(4,null), null));
        check(expr instanceof IRLiteral l && l.value().equals(20L), "(2+3)*4 → 20");
    }

    static void flattenSubquery() {
        IRSelect inner = new IRSelect(new SelectCore(
            List.of(new IRExprSelect(new IRColumnRef("a",null,null), "x")),
            List.of(new IRTableName("t",null,null)),
            null, null, null, null, null, null, false), null, null, Set.of());
        IRSelect outer = new IRSelect(new SelectCore(
            List.of(new IRWildcardSelect(new IRWildcard(null))),
            List.of(new IRSubqueryTable(inner, "s")),
            null, null, null, null, null, null, false), null, null, Set.of());
        IRSelect r = (IRSelect)IROptimizer.optimize(new SemanticIR(outer),2).rootStatement();
        check(r.core().from().get(0) instanceof IRTableName, "Flattened to table ref");
    }

    static void noFlattenWhenAggregate() {
        IRSelect inner = new IRSelect(new SelectCore(
            List.of(new IRExprSelect(new IRColumnRef("a",null,null), null)),
            List.of(new IRTableName("t",null,null)),
            null, List.of(new IRGroupBy(new IRColumnRef("b",null,null), GroupByKind.PLAIN)),
            null, null, null, null, false), null, null, Set.of());
        IRSelect outer = new IRSelect(new SelectCore(
            List.of(new IRWildcardSelect(new IRWildcard(null))),
            List.of(new IRSubqueryTable(inner, "s")),
            null, null, null, null, null, null, false), null, null, Set.of());
        IRSelect r = (IRSelect)IROptimizer.optimize(new SemanticIR(outer),2).rootStatement();
        check(r.core().from().get(0) instanceof IRSubqueryTable, "GROUP BY subquery NOT flattened");
    }

    static void foldInUpdate() {
        IRUpdate upd = new IRUpdate(new IRTableName("t",null,null),
            List.of(new SetClause("x", new IRBinaryOp(new IRLiteral(1,null),
                IRBinaryOp.BinOp.ADD, new IRLiteral(2,null), null))),
            new IRLiteral(true,null), Set.of());
        check(IROptimizer.optimize(new SemanticIR(upd),1).rootStatement() instanceof IRUpdate,
            "UPDATE with constant folding succeeds");
    }

    static void foldInDelete() {
        IRDelete del = new IRDelete(new IRTableName("t",null,null),
            new IRLiteral(false,null), Set.of());
        check(IROptimizer.optimize(new SemanticIR(del),1).rootStatement() instanceof IRDelete,
            "DELETE with constant folding succeeds");
    }

    static void foldInMerge() {
        IRMerge merge = new IRMerge(new IRTableName("t",null,null),
            new IRTableName("s",null,null),
            new IRBinaryOp(new IRLiteral(1,null), IRBinaryOp.BinOp.EQ, new IRLiteral(1,null), null),
            List.of(new MergeUpdate(List.of(new SetClause("x", new IRLiteral(42,null))))),
            Set.of());
        check(IROptimizer.optimize(new SemanticIR(merge),1).rootStatement() instanceof IRMerge,
            "MERGE with constant folding succeeds");
    }

    static void levelZero() {
        SemanticIR ir = new SemanticIR(new IRSelect(new SelectCore(
            List.of(new IRExprSelect(new IRLiteral(1,null), null)),
            List.of(new IRTableName("t",null,null)),
            null, null, null, null, null, null, false), null, null, Set.of()));
        check(IROptimizer.optimize(ir,0) == ir, "Level 0 returns same object");
    }

    static IRExpr fold1(IRExpr e) {
        return ((IRExprSelect)((IRSelect)IROptimizer.optimize(new SemanticIR(
            simpleQuery(e)),1).rootStatement()).core().projections().get(0)).expr();
    }
    static IRSelect simpleQuery(IRExpr p) {
        return new IRSelect(new SelectCore(List.of(new IRExprSelect(p,null)),
            List.of(new IRTableName("t",null,null)),
            null, null, null, null, null, null, false), null, null, Set.of());
    }
    static void check(boolean c, String m) { if(c) pass++; else { fail++; System.err.println("  ❌ "+m); } }
}
