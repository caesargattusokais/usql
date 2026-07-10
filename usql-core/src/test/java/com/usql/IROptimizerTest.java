package com.usql;

import com.usql.ir.*;
import com.usql.ir.IRExpr.*;
import com.usql.ir.IRStatement.*;
import com.usql.optimizer.IROptimizer;

import java.util.List;
import java.util.Set;

/**
 * Tests for IROptimizer constant folding and subquery flattening.
 * No database required.
 */
public class IROptimizerTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        System.out.println("=== IROptimizer Test ===\n");

        testConstantFolding();
        testSubqueryFlattening();

        System.out.println("\n=== Result: " + pass + "/" + (pass + fail) + " passed ===");
        if (fail > 0) System.exit(1);
    }

    // ══════════════════════════════════════════════════
    //  Constant folding (Level 1)
    // ══════════════════════════════════════════════════

    static void testConstantFolding() {
        // 1. 2 + 3 → 5
        {
            IRSelect q = simpleQuery(new IRBinaryOp(
                new IRLiteral(2, null), IRBinaryOp.BinOp.ADD,
                new IRLiteral(3, null), null));
            SemanticIR ir = new SemanticIR(q);
            SemanticIR opt = IROptimizer.optimize(ir, 1);
            IRExpr expr = ((IRExprSelect)((IRSelect)opt.rootStatement())
                .core().projections().get(0)).expr();
            check(expr instanceof IRLiteral lit && lit.value().equals(5L),
                "2 + 3 → 5");
        }

        // 2. TRUE AND FALSE → FALSE
        {
            IRSelect q = simpleQuery(new IRBinaryOp(
                new IRLiteral(true, null), IRBinaryOp.BinOp.AND,
                new IRLiteral(false, null), null));
            SemanticIR opt = IROptimizer.optimize(new SemanticIR(q), 1);
            IRExpr expr = ((IRExprSelect)((IRSelect)opt.rootStatement())
                .core().projections().get(0)).expr();
            check(expr instanceof IRLiteral lit && lit.value().equals(false),
                "TRUE AND FALSE → FALSE");
        }

        // 3. NOT TRUE → FALSE
        {
            IRSelect q = simpleQuery(new IRUnaryOp(IRUnaryOp.UnaryOp.NOT,
                new IRLiteral(true, null), null));
            SemanticIR opt = IROptimizer.optimize(new SemanticIR(q), 1);
            IRExpr expr = ((IRExprSelect)((IRSelect)opt.rootStatement())
                .core().projections().get(0)).expr();
            check(expr instanceof IRLiteral lit && lit.value().equals(false),
                "NOT TRUE → FALSE");
        }

        // 4. WHERE TRUE → removed
        {
            IRSelect q = new IRSelect(
                new SelectCore(List.of(new IRExprSelect(new IRColumnRef("id", null, null), null)),
                    List.of(new IRTableName("t", null, null)),
                    new IRLiteral(true, null), null, null, null, null, null, false),
                null, null, Set.of());
            SemanticIR opt = IROptimizer.optimize(new SemanticIR(q), 1);
            check(((IRSelect)opt.rootStatement()).core().where() == null,
                "WHERE TRUE → removed");
        }

        // 5. OFFSET 0 → removed
        {
            IRSelect q = new IRSelect(
                new SelectCore(List.of(new IRExprSelect(new IRColumnRef("id", null, null), null)),
                    List.of(new IRTableName("t", null, null)),
                    null, null, null, null, null, null, false),
                null, new FetchClause(new IRLiteral(10, null), new IRLiteral(0, null)),
                Set.of());
            SemanticIR opt = IROptimizer.optimize(new SemanticIR(q), 1);
            check(((IRSelect)opt.rootStatement()).fetch().offset() == null,
                "OFFSET 0 → removed");
        }

        // 6. String concat → 'ab'
        {
            IRSelect q = simpleQuery(new IRBinaryOp(
                new IRLiteral("a", null), IRBinaryOp.BinOp.ADD,
                new IRLiteral("b", null), null));
            SemanticIR opt = IROptimizer.optimize(new SemanticIR(q), 1);
            IRExpr expr = ((IRExprSelect)((IRSelect)opt.rootStatement())
                .core().projections().get(0)).expr();
            check(expr instanceof IRLiteral lit && "ab".equals(lit.value()),
                "'a' || 'b' → 'ab'");
        }

        // 7. IS NULL on null literal → TRUE
        {
            IRSelect q = simpleQuery(new IRIsNull(
                new IRLiteral(null, null), false, null));
            SemanticIR opt = IROptimizer.optimize(new SemanticIR(q), 1);
            IRExpr expr = ((IRExprSelect)((IRSelect)opt.rootStatement())
                .core().projections().get(0)).expr();
            check(expr instanceof IRLiteral lit && lit.value().equals(true),
                "NULL IS NULL → TRUE");
        }

        // 8. 3 * 4 → 12
        {
            IRSelect q = simpleQuery(new IRBinaryOp(
                new IRLiteral(3, null), IRBinaryOp.BinOp.MUL,
                new IRLiteral(4, null), null));
            SemanticIR opt = IROptimizer.optimize(new SemanticIR(q), 1);
            IRExpr expr = ((IRExprSelect)((IRSelect)opt.rootStatement())
                .core().projections().get(0)).expr();
            check(expr instanceof IRLiteral lit && lit.value().equals(12L),
                "3 * 4 → 12");
        }

        System.out.println("  ✅ Constant folding: " + (pass + fail) + "/8");
    }

    // ══════════════════════════════════════════════════
    //  Subquery flattening (Level 2)
    // ══════════════════════════════════════════════════

    static void testSubqueryFlattening() {
        // 9. Flatten simple subquery
        {
            IRSelect inner = new IRSelect(
                new SelectCore(
                    List.of(new IRExprSelect(new IRColumnRef("a", null, null), "x"),
                            new IRExprSelect(new IRColumnRef("b", null, null), "y")),
                    List.of(new IRTableName("t", null, null)),
                    null, null, null, null, null, null, false),
                null, null, Set.of());
            IRSelect outer = new IRSelect(
                new SelectCore(
                    List.of(new IRWildcardSelect(new IRWildcard(null))),
                    List.of(new IRSubqueryTable(inner, "s")),
                    null, null, null, null, null, null, false),
                null, null, Set.of());
            SemanticIR opt = IROptimizer.optimize(new SemanticIR(outer), 2);
            IRSelect sel = (IRSelect) opt.rootStatement();
            check(sel.core().from().size() == 1, "Flattened to 1 FROM entry");
            check(sel.core().from().get(0) instanceof IRTableName,
                "FROM entry is table (not subquery)");
        }

        // 10. Don't flatten subquery with DISTINCT
        {
            IRSelect inner = new IRSelect(
                new SelectCore(
                    List.of(new IRExprSelect(new IRColumnRef("a", null, null), null)),
                    List.of(new IRTableName("t", null, null)),
                    null, null, null, null, null, null, true), // DISTINCT
                null, null, Set.of());
            IRSelect outer = new IRSelect(
                new SelectCore(
                    List.of(new IRWildcardSelect(new IRWildcard(null))),
                    List.of(new IRSubqueryTable(inner, "s")),
                    null, null, null, null, null, null, false),
                null, null, Set.of());
            SemanticIR opt = IROptimizer.optimize(new SemanticIR(outer), 2);
            IRSelect sel = (IRSelect) opt.rootStatement();
            check(sel.core().from().get(0) instanceof IRSubqueryTable,
                "DISTINCT subquery NOT flattened");
        }

        // 11. Don't flatten subquery with GROUP BY
        {
            IRSelect inner = new IRSelect(
                new SelectCore(
                    List.of(new IRExprSelect(new IRColumnRef("a", null, null), null)),
                    List.of(new IRTableName("t", null, null)),
                    null,
                    List.of(new IRGroupBy(new IRColumnRef("b", null, null), GroupByKind.PLAIN)),
                    null, null, null, null, false),
                null, null, Set.of());
            IRSelect outer = new IRSelect(
                new SelectCore(
                    List.of(new IRWildcardSelect(new IRWildcard(null))),
                    List.of(new IRSubqueryTable(inner, "s")),
                    null, null, null, null, null, null, false),
                null, null, Set.of());
            SemanticIR opt = IROptimizer.optimize(new SemanticIR(outer), 2);
            IRSelect sel = (IRSelect) opt.rootStatement();
            check(sel.core().from().get(0) instanceof IRSubqueryTable,
                "GROUP BY subquery NOT flattened");
        }

        System.out.println("  ✅ Subquery flattening: 3/3");
    }

    // ══════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════

    static IRSelect simpleQuery(IRExpr projection) {
        return new IRSelect(
            new SelectCore(List.of(new IRExprSelect(projection, null)),
                List.of(new IRTableName("t", null, null)),
                null, null, null, null, null, null, false),
            null, null, Set.of());
    }

    static void check(boolean condition, String msg) {
        if (condition) { pass++; }
        else { fail++; System.err.println("  ❌ FAIL: " + msg); }
    }
}
