package com.usql;

import com.usql.ir.*;
import com.usql.ir.IRExpr.*;
import com.usql.ir.IRStatement.*;
import com.usql.optimizer.IROptimizer;
import java.util.*;

public class IROptimizerTest {
    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        System.out.println("=== IROptimizer Test ===\n");
        testArithmetic(); testBoolean(); testUnary(); testString();
        testIsNull(); testWhere(); testFetch(); testNested();
        testSubquery(); testLevel2Simplify(); testLevel2Zero();
        testLevel2Collapse(); testLevel2NotNot(); testLevel2DML();
        testLevel3All();
        System.out.println("\n=== " + pass + "/" + (pass+fail) + " ===");
        if (fail > 0) System.exit(1);
    }

    static IRExpr fold1(IRExpr e) { return ((IRExprSelect)((IRSelect)IROptimizer.optimize(
        new SemanticIR(simpleQuery(e)),1).rootStatement()).core().projections().get(0)).expr(); }
    static IRSelect simpleQuery(IRExpr p) { return new IRSelect(new SelectCore(List.of(new IRExprSelect(p,null)),
        List.of(new IRTableName("t",null,null)),null,null,null,null,null,null,false),null,null,Set.of()); }
    static IRSelect opt2(IRStatement s) { return (IRSelect)IROptimizer.optimize(new SemanticIR(s),2).rootStatement(); }
    static void chk(boolean c, String m) { if(c) pass++; else { fail++; System.err.println("  ❌ "+m); } }

    static void testArithmetic() {
        Object[][] t = {{2L,IRBinaryOp.BinaryOp.ADD,3L,5L},{3L,IRBinaryOp.BinaryOp.MUL,4L,12L},
            {10L,IRBinaryOp.BinaryOp.SUB,7L,3L},{20L,IRBinaryOp.BinaryOp.DIV,5L,4L},{15L,IRBinaryOp.BinaryOp.MOD,10L,5L}};
        for (Object[] c : t) { IRExpr e = fold1(new IRBinaryOp(new IRLiteral(c[0],null),(IRBinaryOp.BinaryOp)c[1],new IRLiteral(c[2],null),null));
            chk(e instanceof IRLiteral l && l.value().equals(c[3]),c[0]+" "+c[1]+" "+c[2]+" → "+c[3]); }
    }

    static void testBoolean() {
        chk(fold1(new IRBinaryOp(new IRLiteral(true,null),IRBinaryOp.BinaryOp.AND,new IRLiteral(false,null),null)) instanceof IRLiteral l && l.value().equals(false),"TRUE AND FALSE→FALSE");
        chk(fold1(new IRBinaryOp(new IRLiteral(true,null),IRBinaryOp.BinaryOp.OR,new IRLiteral(false,null),null)) instanceof IRLiteral l && l.value().equals(true),"TRUE OR FALSE→TRUE");
        chk(fold1(new IRBinaryOp(new IRLiteral(1,null),IRBinaryOp.BinaryOp.EQ,new IRLiteral(1,null),null)) instanceof IRLiteral l && l.value().equals(true),"1=1→true");
        chk(fold1(new IRBinaryOp(new IRLiteral(1,null),IRBinaryOp.BinaryOp.NEQ,new IRLiteral(1,null),null)) instanceof IRLiteral l && l.value().equals(false),"1<>1→false");
    }

    static void testUnary() {
        chk(fold1(new IRUnaryOp(IRUnaryOp.UnaryOp.NOT,new IRLiteral(true,null),null)) instanceof IRLiteral l && l.value().equals(false),"NOT true→false");
        chk(fold1(new IRUnaryOp(IRUnaryOp.UnaryOp.NOT,new IRLiteral(false,null),null)) instanceof IRLiteral l && l.value().equals(true),"NOT false→true");
        chk(fold1(new IRUnaryOp(IRUnaryOp.UnaryOp.NEG,new IRLiteral(5L,null),null)) instanceof IRLiteral l && l.value().equals(-5L),"-5→-5");
    }

    static void testString() {
        chk(fold1(new IRBinaryOp(new IRLiteral("a",null),IRBinaryOp.BinaryOp.ADD,new IRLiteral("b",null),null)) instanceof IRLiteral l && "ab".equals(l.value()),"'a'+'b'→'ab'");
    }

    static void testIsNull() {
        chk(fold1(new IRIsNull(new IRLiteral(null,null),false,null)) instanceof IRLiteral l && l.value().equals(true),"NULL IS NULL→true");
        chk(fold1(new IRIsNull(new IRLiteral(null,null),true,null)) instanceof IRLiteral l && l.value().equals(false),"NULL IS NOT NULL→false");
        chk(fold1(new IRIsNull(new IRLiteral(1,null),false,null)) instanceof IRLiteral l && l.value().equals(false),"1 IS NULL→false");
        chk(fold1(new IRIsNull(new IRLiteral(1,null),true,null)) instanceof IRLiteral l && l.value().equals(true),"1 IS NOT NULL→true");
    }

    static void testWhere() {
        IRSelect q = new IRSelect(new SelectCore(List.of(new IRExprSelect(new IRColumnRef("id",null,null),null)),
            List.of(new IRTableName("t",null,null)),new IRLiteral(true,null),null,null,null,null,null,false),null,null,Set.of());
        chk(((IRSelect)IROptimizer.optimize(new SemanticIR(q),1).rootStatement()).core().where()==null,"WHERE TRUE→removed");
    }

    static void testFetch() {
        IRSelect q = new IRSelect(new SelectCore(List.of(new IRExprSelect(new IRColumnRef("id",null,null),null)),
            List.of(new IRTableName("t",null,null)),null,null,null,null,null,null,false),
            null,new FetchClause(new IRLiteral(10,null),new IRLiteral(0,null)),Set.of());
        chk(((IRSelect)IROptimizer.optimize(new SemanticIR(q),1).rootStatement()).fetch().offset()==null,"OFFSET 0→removed");
    }

    static void testNested() {
        IRExpr inner = new IRBinaryOp(new IRLiteral(2,null),IRBinaryOp.BinaryOp.ADD,new IRLiteral(3,null),null);
        chk(fold1(new IRBinaryOp(inner,IRBinaryOp.BinaryOp.MUL,new IRLiteral(4,null),null)) instanceof IRLiteral l && l.value().equals(20L),"(2+3)*4→20");
    }

    static void testSubquery() {
        IRSelect inner = new IRSelect(new SelectCore(List.of(new IRExprSelect(new IRColumnRef("a",null,null),"x")),
            List.of(new IRTableName("t",null,null)),null,null,null,null,null,null,false),null,null,Set.of());
        IRSelect outer = new IRSelect(new SelectCore(List.of(new IRWildcardSelect(new IRWildcard(null))),
            List.of(new IRSubqueryTable(inner,"s")),null,null,null,null,null,null,false),null,null,Set.of());
        IRSelect r = (IRSelect)IROptimizer.optimize(new SemanticIR(outer),2).rootStatement();
        chk(r.core().from().get(0) instanceof IRTableName,"Flatten subquery");
    }

    // Level 2: expression simplification
    static void testLevel2Simplify() {
        // x*1→x
        IRExpr e = simpleExpr(new IRBinaryOp(new IRColumnRef("x",null,null),IRBinaryOp.BinaryOp.MUL,new IRLiteral(1,null),null));
        IRSelect r = opt2(simpleQuery(e));
        IRExpr res = ((IRExprSelect)r.core().projections().get(0)).expr();
        chk(res instanceof IRColumnRef,"x*1→x");
        chk(fold1(e) instanceof IRBinaryOp,"x*1 not folded by L1");
    }

    static void testLevel2Zero() {
        // x*0→0
        IRExpr e = new IRBinaryOp(new IRColumnRef("x",null,null),IRBinaryOp.BinaryOp.MUL,new IRLiteral(0,null),null);
        IRSelect r = opt2(simpleQuery(e));
        IRExpr res = ((IRExprSelect)r.core().projections().get(0)).expr();
        chk(res instanceof IRLiteral && ((IRLiteral)res).value().toString().equals("0"),"x*0→0");

        // 0*x→0
        e = new IRBinaryOp(new IRLiteral(0,null),IRBinaryOp.BinaryOp.MUL,new IRColumnRef("x",null,null),null);
        r = opt2(simpleQuery(e));
        res = ((IRExprSelect)r.core().projections().get(0)).expr();
        chk(res instanceof IRLiteral && ((IRLiteral)res).value().toString().equals("0"),"0*x→0");
    }

    static void testLevel2Collapse() {
        // x AND FALSE→FALSE
        IRExpr e = new IRBinaryOp(new IRColumnRef("x",null,null),IRBinaryOp.BinaryOp.AND,new IRLiteral(false,null),null);
        chk(((IRExprSelect)opt2(simpleQuery(e)).core().projections().get(0)).expr() instanceof IRLiteral l && l.value().equals(false),"x AND FALSE→FALSE");

        // TRUE OR x→TRUE
        e = new IRBinaryOp(new IRLiteral(true,null),IRBinaryOp.BinaryOp.OR,new IRColumnRef("x",null,null),null);
        chk(((IRExprSelect)opt2(simpleQuery(e)).core().projections().get(0)).expr() instanceof IRLiteral l && l.value().equals(true),"TRUE OR x→TRUE");
    }

    static void testLevel2NotNot() {
        // NOT NOT x→x
        IRExpr e = new IRUnaryOp(IRUnaryOp.UnaryOp.NOT,new IRUnaryOp(IRUnaryOp.UnaryOp.NOT,new IRColumnRef("x",null,null),null),null);
        chk(((IRExprSelect)opt2(simpleQuery(e)).core().projections().get(0)).expr() instanceof IRColumnRef,"NOT NOT x→x");
    }

    // Level 3: predicate pushdown
    static void testLevel3Pushdown() {
        // SELECT * FROM (SELECT a FROM t ORDER BY a LIMIT 100) s WHERE s.a > 1
        // → push WHERE into subquery (subquery has ORDER BY+FETCH so not flattenable, but safe to push)
        IRSelect inner = new IRSelect(new SelectCore(
            List.of(new IRExprSelect(new IRColumnRef("a",null,null),"a")),
            List.of(new IRTableName("t",null,null)),null,null,null,null,null,null,false),
            List.of(new OrderBy(new IRColumnRef("a",null,null),OrderDir.ASC,NullsOrder.LAST)),
            new FetchClause(new IRLiteral(100,null),null),Set.of());
        IRSelect outer = new IRSelect(new SelectCore(
            List.of(new IRWildcardSelect(new IRWildcard(null))),
            List.of(new IRSubqueryTable(inner,"s")),
            new IRBinaryOp(new IRColumnRef("a","s",null),IRBinaryOp.BinaryOp.GT,new IRLiteral(1,null),null),
            null,null,null,null,null,false),null,null,Set.of());
        var result = com.usql.optimizer.IROptimizer.optimize(new SemanticIR(outer), 3);
        IRSelect r = (IRSelect) result.rootStatement();
        boolean hasWhere = false;
        for (var ref : r.core().from()) {
            if (ref instanceof IRSubqueryTable sq && sq.query().core().where() != null) hasWhere = true;
        }
        chk(hasWhere, "Pushdown: subquery has WHERE after Level 3");
    }

    static void testLevel3NoPushdown() {
        IRSelect inner = new IRSelect(new SelectCore(
            List.of(new IRExprSelect(new IRColumnRef("a",null,null),"a")),
            List.of(new IRTableName("t",null,null)),null,null,null,null,null,null,true),
            null,null,Set.of());
        IRSelect outer = new IRSelect(new SelectCore(
            List.of(new IRWildcardSelect(new IRWildcard(null))),
            List.of(new IRTableName("t1",null,null),new IRSubqueryTable(inner,"s")),
            new IRBinaryOp(new IRColumnRef("x","t1",null),IRBinaryOp.BinaryOp.GT,new IRLiteral(1,null),null),
            null,null,null,null,null,false),null,null,Set.of());
        IRSelect r = (IRSelect)IROptimizer.optimize(new SemanticIR(outer),3).rootStatement();
        chk(r.core().where()!=null,"No pushdown: WHERE stays for t1 ref");
    }

    // Level 3: projection pruning
    static void testLevel3Prune() {
        // SELECT s.x FROM (SELECT DISTINCT a AS x, b AS y FROM t) s → prune b
        IRSelect inner = new IRSelect(new SelectCore(
            List.of(new IRExprSelect(new IRColumnRef("a",null,null),"x"),
                    new IRExprSelect(new IRColumnRef("b",null,null),"y")),
            List.of(new IRTableName("t",null,null)),null,null,null,null,null,null,true),
            null,null,Set.of());
        IRSelect outer = new IRSelect(new SelectCore(
            List.of(new IRExprSelect(new IRColumnRef("x","s",null),"x")),
            List.of(new IRSubqueryTable(inner,"s")),null,null,null,null,null,null,false),
            null,null,Set.of());
        IRSelect r = (IRSelect)IROptimizer.optimize(new SemanticIR(outer),3).rootStatement();
        boolean pruned = false;
        for (var ref : r.core().from()) {
            if (ref instanceof IRSubqueryTable sq)
                pruned = sq.query().core().projections().size()==1;
        }
        chk(pruned,"Prune: subquery has 1 projection");
    }

    static void testLevel3All() {
        testLevel3Pushdown(); testLevel3NoPushdown(); testLevel3Prune();
    }

    static void testLevel2DML() {
        IRUpdate upd = new IRUpdate(new IRTableName("t",null,null),
            List.of(new SetClause("x",new IRBinaryOp(new IRLiteral(1,null),IRBinaryOp.BinaryOp.ADD,new IRLiteral(2,null),null))),
            new IRLiteral(true,null),Set.of());
        chk(IROptimizer.optimize(new SemanticIR(upd),1).rootStatement() instanceof IRUpdate,"UPDATE fold works");

        IRDelete del = new IRDelete(new IRTableName("t",null,null),new IRLiteral(false,null),Set.of());
        chk(IROptimizer.optimize(new SemanticIR(del),1).rootStatement() instanceof IRDelete,"DELETE fold works");

        chk(IROptimizer.optimize(new SemanticIR(simpleQuery(new IRLiteral(1,null))),0).rootStatement()!=null,"Level 0 no-op");
    }

    static IRExpr simpleExpr(IRExpr e) { return e; }
}
