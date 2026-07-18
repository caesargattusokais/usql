package com.usql;

import com.usql.dialect.Dialect;
import com.usql.ir.*;
import com.usql.ir.IRExpr.*;
import com.usql.ir.IRStatement.*;
import java.util.*;

public class AllBackendsTest {
    static int pass = 0, fail = 0;
    static List<Dialect> ALL = Arrays.stream(Dialect.values()).filter(d->d!=Dialect.H2).toList();
    static IRTableName T = new IRTableName("t",null,null);

    public static void main(String[] args) {
        System.out.println("=== All Backends Test ===\n");
        testBasic(); testDDL(); testDrop(); testAlter(); testProc(); testTCL();
        testView(); testFunctions(); testJoin(); testGroupBy(); testWindow(); testCTE();
        System.out.println("\n=== " + pass + "/" + (pass+fail) + " ===");
        if (fail > 0) System.exit(1);
    }

    static CompilationResult gen(Dialect d, IRStatement s) {
        return USqlCompiler.builder().build().compileFromIR(s, d);
    }
    static void chk(boolean c, String m) { if(c) pass++; else { fail++; System.err.println("  ❌ "+m); } }
    static void all(String label, IRStatement stmt) {
        for(Dialect d : ALL) chk(gen(d, stmt).isSuccess(), d.displayName()+" "+label);
    }

    static IRSelect simpleSelect(IRExpr e) {
        return new IRSelect(new SelectCore(List.of(new IRExprSelect(e,null)),
            List.of(T), null, null, null, null, null, null, false), null, null, Set.of());
    }

    static void testBasic() {
        all("SELECT literal", simpleSelect(new IRLiteral(1, null)));
        all("INSERT", new IRInsert(T, List.of("a"), List.of(List.of(new IRLiteral(1,null))), null, false, Set.of()));
        all("UPDATE", new IRUpdate(T, List.of(new SetClause("a", new IRLiteral(1,null))), null, Set.of()));
        all("DELETE", new IRDelete(T, null, Set.of()));
    }

    static void testDDL() {
        var pk = List.<IRColumnConstraint>of(new ColPrimaryKey(false), new ColNotNull());
        var cols = List.of(
            new IRColumnDef("id", DataType.IntType.INT, pk, null),
            new IRColumnDef("name", new DataType.VarcharType(100), List.of(new ColNotNull()), null));
        all("CREATE TABLE", new IRCreateTable(T, false, cols, List.of(), null, Set.of()));
        all("CREATE INDEX", new IRCreateIndex("idx", T, List.of(new IndexColumn("name", OrderDir.ASC, null)), false, false, null, null, Set.of()));
    }

    static void testDrop() {
        all("DROP TABLE", new IRDropTable("x", false, false, Set.of()));
        all("DROP INDEX", new IRDropIndex("i", "x", false, Set.of()));
        all("DROP DATABASE", new IRDropDatabase("db", false, Set.of()));
    }

    static void testAlter() {
        var col = new IRColumnDef("c", DataType.IntType.INT, List.of(), null);
        all("ALTER ADD", new IRAlterTableAddColumn("x", col, false, Set.of()));
        all("ALTER DROP", new IRAlterTableDropColumn("x", "c", Set.of()));
        all("ALTER TYPE", new IRAlterColumnType("x", "c", DataType.IntType.BIGINT, Set.of()));
    }

    static void testProc() {
        all("PROCEDURE", new IRCreateProcedure("p", List.of(), "SELECT 1", false, Set.of()));
        all("CALL", new IRCall("p", List.of(), Set.of()));
    }

    static void testTCL() {
        all("TCL BEGIN", new IRTCL("BEGIN", Set.of()));
        all("TCL COMMIT", new IRTCL("COMMIT", Set.of()));
    }

    static void testView() {
        all("VIEW", new IRCreateView("v", simpleSelect(new IRLiteral(1,null)), Set.of()));
        all("SCHEMA", new IRCreateSchema("s", Set.of()));
    }

    static void testFunctions() {
        all("UPPER", simpleSelect(new IRFunctionCall("UPPER", List.of(new IRLiteral("x",null)), null, null, null)));
        all("COALESCE", simpleSelect(new IRFunctionCall("COALESCE", List.of(new IRLiteral(null,new DataType.NullType()), new IRLiteral(1,null)), null, null, null)));
        all("NOW", simpleSelect(new IRFunctionCall("NOW", List.of(), null, null, null)));
    }

    static void testJoin() {
        var join = new IRJoin(T, JoinType.LEFT, new IRTableName("s",null,null),
            new IRBinaryOp(new IRColumnRef("t","id",null), IRBinaryOp.BinaryOp.EQ, new IRColumnRef("s","id",null), null));
        all("LEFT JOIN", new IRSelect(new SelectCore(List.of(new IRWildcardSelect(new IRWildcard(null))),
            List.of(join), null, null, null, null, null, null, false), null, null, Set.of()));
    }

    static void testGroupBy() {
        var gb = List.of(new IRGroupBy(new IRColumnRef("x",null,null), GroupByKind.PLAIN));
        var proj = List.<IRSelectItem>of(new IRExprSelect(
            new IRFunctionCall("COUNT", List.of(new IRWildcard(null)), null, null, null), "cnt"));
        all("GROUP BY COUNT", new IRSelect(new SelectCore(proj, List.of(T), null, gb,
            null, null, null, null, false), null, null, Set.of()));
    }

    static void testWindow() {
        var over = new IRWindowOver(null, List.of(new OrderBy(new IRColumnRef("id",null,null), OrderDir.ASC, null)), null);
        all("ROW_NUMBER", simpleSelect(new IRFunctionCall("ROW_NUMBER", List.of(), null, over, null)));
    }

    static void testCTE() {
        var cte = List.of(new IRCommonTable("cte", List.of("id"), simpleSelect(new IRLiteral(1,null)), true));
        all("recursive CTE", new IRSelect(new SelectCore(
            List.of(new IRExprSelect(new IRColumnRef("id",null,null), null)),
            List.of(T), null, null, null, cte, null, null, false), null, null, Set.of()));
    }
}
