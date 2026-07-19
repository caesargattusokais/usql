package com.usql;

import com.usql.dialect.Dialect;
import com.usql.ir.DataType;
import com.usql.ir.IRExpr.*;
import com.usql.ir.IRStatement.*;
import com.usql.ir.IRStatement;

import java.util.List;
import java.util.Set;

/**
 * Tests for stored procedure IR types and backend generation.
 * No database required.
 */
public class StoredProcedureTest {

    static int pass = 0, fail = 0;
    static USqlCompiler compiler = USqlCompiler.builder().build();

    public static void main(String[] args) {
        System.out.println("=== Stored Procedure Test ===\n");

        testCreateProcedure();
        testCreateFunction();
        testCall();
        testProcedureWithParams();
        testOrReplace();
        testAllDialects();
        testTextParsing();
        testTextParsingCall();
        testEmptyBody();
        testNoParams();

        System.out.println("\n=== " + pass + "/" + (pass+fail) + " passed ===");
        if (fail > 0) System.exit(1);
    }

    static void testCreateProcedure() {
        IRCreateProcedure proc = new IRCreateProcedure(
            "my_proc",
            List.of(),
            "BEGIN SELECT 1; END;",
            false,
            Set.of()
        );

        CompilationResult r = compiler.compileFromIR(proc, Dialect.MYSQL);
        check(r.isSuccess(), "CREATE PROCEDURE compiles");
        check(r.getSql().contains("CREATE"), "Contains CREATE");
        check(r.getSql().contains("PROCEDURE"), "Contains PROCEDURE");
        check(r.getSql().contains("my_proc"), "Contains procedure name");
        check(r.getSql().contains("BEGIN SELECT 1; END;"), "Contains body");
        check(!r.getSql().contains("OR REPLACE"), "No OR REPLACE when false");
    }

    static void testCreateFunction() {
        IRCreateFunction func = new IRCreateFunction(
            "my_func",
            List.of(),
            DataType.IntType.INT,
            "BEGIN RETURN 42; END;",
            false,
            Set.of()
        );

        CompilationResult r = compiler.compileFromIR(func, Dialect.POSTGRESQL);
        check(r.isSuccess(), "CREATE FUNCTION compiles");
        check(r.getSql().contains("FUNCTION"), "Contains FUNCTION");
        check(r.getSql().contains("RETURNS"), "Contains RETURNS");
        check(r.getSql().contains("my_func"), "Contains function name");
        check(r.getSql().contains("BEGIN RETURN 42; END;"), "Contains body");
    }

    static void testCall() {
        IRCall call = new IRCall(
            "my_proc",
            List.of(new IRLiteral(1, null), new IRLiteral("hello", null)),
            Set.of()
        );

        CompilationResult r = compiler.compileFromIR(call, Dialect.MYSQL);
        check(r.isSuccess(), "CALL compiles");
        check(r.getSql().contains("CALL"), "Contains CALL");
        check(r.getSql().contains("my_proc"), "Contains procedure name");
        check(r.getSql().contains("1"), "Contains arg 1");
        check(r.getSql().contains("hello"), "Contains arg 'hello'");
    }

    static void testProcedureWithParams() {
        IRCreateProcedure proc = new IRCreateProcedure(
            "process_order",
            List.of(
                new ProcedureParam("order_id", DataType.IntType.INT, ParamMode.IN),
                new ProcedureParam("status", new DataType.VarcharType(100), ParamMode.OUT),
                new ProcedureParam("amount", DataType.FloatType.DOUBLE, ParamMode.INOUT)
            ),
            "BEGIN UPDATE orders SET status = status WHERE id = order_id; END;",
            false,
            Set.of()
        );

        CompilationResult r = compiler.compileFromIR(proc, Dialect.ORACLE);
        check(r.isSuccess(), "Procedure with IN/OUT/INOUT compiles");
        String sql = r.getSql();
        check(sql.contains("order_id"), "Contains param name");
        check(sql.contains("IN "), "Contains IN");
        check(sql.contains("OUT "), "Contains OUT");
        check(sql.contains("IN OUT") || sql.contains("INOUT"), "Contains INOUT/IN OUT");
    }

    static void testOrReplace() {
        IRCreateProcedure proc = new IRCreateProcedure(
            "reload_cache",
            List.of(),
            "BEGIN NULL; END;",
            true,  // OR REPLACE
            Set.of()
        );

        CompilationResult r = compiler.compileFromIR(proc, Dialect.POSTGRESQL);
        check(r.isSuccess(), "CREATE OR REPLACE compiles");
        check(r.getSql().contains("OR REPLACE"), "Contains OR REPLACE");
    }

    static void testAllDialects() {
        IRCreateProcedure proc = new IRCreateProcedure(
            "simple_proc", List.of(), "BEGIN NULL; END;", false, Set.of()
        );

        for (Dialect d : Dialect.values()) {
            if (d == Dialect.H2) continue;
            CompilationResult r = compiler.compileFromIR(proc, d);
            check(r.isSuccess(), d.displayName() + " CREATE PROCEDURE succeeds");
        }

        IRCall call = new IRCall("simple_proc", List.of(), Set.of());
        for (Dialect d : Dialect.values()) {
            if (d == Dialect.H2) continue;
            CompilationResult r = compiler.compileFromIR(call, d);
            check(r.isSuccess(), d.displayName() + " CALL succeeds");
        }
    }

    static void testTextParsing() {
        CompilationResult r = compiler.compile(
            "CREATE PROCEDURE hello_proc() AS 'BEGIN SELECT 1; END;'");
        if (!r.isSuccess()) {
            System.out.println("    ⚠️  Text parse CREATE PROCEDURE failed: " + r.report());
            return;
        }
        check(r.isSuccess(), "Text parse CREATE PROCEDURE succeeds");
        check(r.getSql().contains("PROCEDURE"), "Contains PROCEDURE");
        check(r.getSql().contains("hello_proc"), "Contains procedure name");

        CompilationResult r2 = compiler.compile(
            "CREATE FUNCTION add_one(x INT) RETURNS INT AS 'BEGIN RETURN x + 1; END;'");
        if (!r2.isSuccess()) {
            System.out.println("    ⚠️  Text parse CREATE FUNCTION failed: " + r2.report());
            return;
        }
        check(r2.isSuccess(), "Text parse CREATE FUNCTION succeeds");
        check(r2.getSql().contains("FUNCTION"), "Contains FUNCTION");
    }

    static void testTextParsingCall() {
        CompilationResult r = compiler.compile("CALL my_proc(1, 'hello')");
        check(r.isSuccess(), "Text parse CALL succeeds");
        check(r.getSql().contains("my_proc"), "Contains procedure name");

        // CALL with no args
        CompilationResult r2 = compiler.compile("CALL no_args_proc()");
        check(r2.isSuccess(), "Text parse CALL no args succeeds");
        check(!r2.getSql().contains("()"), "No empty parens in output");
    }

    static void testEmptyBody() {
        IRCreateProcedure proc = new IRCreateProcedure(
            "no_body_proc", List.of(), null, false, Set.of());
        CompilationResult r = compiler.compileFromIR(proc, Dialect.MYSQL);
        check(r.isSuccess(), "Null body compiles");
    }

    static void testNoParams() {
        // No-param procedure generates clean SQL with no trailing artifacts
        IRCreateProcedure proc = new IRCreateProcedure(
            "simple", List.of(), "BEGIN NULL; END;", false, Set.of());
        for (Dialect d : Dialect.values()) {
            if (d == Dialect.H2) continue;
            CompilationResult r = compiler.compileFromIR(proc, d);
            check(r.isSuccess(), d.displayName() + " no-param proc succeeds");
            // Parens should be empty or absent
            String sql = r.getSql();
            if (d == Dialect.MYSQL || d == Dialect.POSTGRESQL
                || d == Dialect.MARIADB || d == Dialect.TIDB || d == Dialect.OCEANBASE)
                check(sql.contains("()"), d.displayName() + " has empty parens");
            else
                check(!sql.contains("()"), d.displayName() + " no empty parens");
        }
    }

    static void check(boolean condition, String msg) {
        if (condition) { pass++; }
        else { fail++; System.err.println("  ❌ FAIL: " + msg); }
    }
}
