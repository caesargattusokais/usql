import com.usql.ir.*;
import com.usql.ir.IRExpr.*;
import com.usql.ir.IRStatement.*;
import com.usql.optimizer.IROptimizer;
import java.util.*;

public class DebugPush {
    public static void main(String[] args) {
        IRSelect inner = new IRSelect(new SelectCore(
            List.of(new IRExprSelect(new IRColumnRef("a",null,null),"a")),
            List.of(new IRTableName("t",null,null)),null,null,null,null,null,null,true),
            null,null,Set.of());
        IRColumnRef col = new IRColumnRef("s","a",null);
        IRBinaryOp cond = new IRBinaryOp(col, IRBinaryOp.BinaryOp.GT, new IRLiteral(1,null), null);
        IRSelect outer = new IRSelect(new SelectCore(
            List.of(new IRWildcardSelect(new IRWildcard(null))),
            List.of(new IRSubqueryTable(inner,"s")),
            cond, null,null,null,null,null,false),null,null,Set.of());

        System.out.println("=== Before ===");
        System.out.println("Outer WHERE: " + outer.core().where());
        System.out.println("FROM count: " + outer.core().from().size());

        var result = IROptimizer.optimize(new SemanticIR(outer), 3);
        IRSelect r = (IRSelect) result.rootStatement();

        System.out.println("\n=== After Level 3 ===");
        System.out.println("Outer WHERE: " + r.core().where());
        System.out.println("FROM count: " + r.core().from().size());
        for (var ref : r.core().from()) {
            System.out.println("FROM ref: " + ref.getClass().getSimpleName());
            if (ref instanceof IRSubqueryTable sq) {
                System.out.println("  SQ alias: " + sq.alias());
                System.out.println("  SQ WHERE: " + sq.query().core().where());
                System.out.println("  SQ distinct: " + sq.query().core().distinct());
            }
        }
    }
}
