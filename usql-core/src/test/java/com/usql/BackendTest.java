package com.usql;

import com.usql.dialect.Dialect;
import com.usql.ir.*;
import com.usql.ir.IRExpr.*;
import com.usql.ir.IRStatement.*;
import java.util.*;

/**
 * Tests for backend SQL generation (no database required).
 * Verifies that IR → SQL translation produces expected patterns.
 */
public class BackendTest {
    static int pass = 0, fail = 0;
    static USqlCompiler compiler = USqlCompiler.builder().build();

    public static void main(String[] args) {
        System.out.println("=== Backend Test ===\n");
        testDropTable(); testTruncate(); testDropIndex(); testAlter();
        testCreateProc(); testCallProc(); testSelectTypes();
        System.out.println("\n=== " + pass + "/" + (pass+fail) + " ===");
        if (fail > 0) System.exit(1);
    }

    static void chk(boolean c, String m) { if(c) pass++; else { fail++; System.err.println("  ❌ "+m); } }

    static void testDropTable() {
        for (Dialect d : Dialect.values()) { if(d==Dialect.H2)continue;
            CompilationResult r = compiler.compileFromIR(new IRDropTable("t",false,false,Set.of()),d);
            chk(r.isSuccess() && r.getSql().toUpperCase().contains("DROP TABLE"),d.displayName()+" DROP TABLE");
            r = compiler.compileFromIR(new IRDropTable("t",true,false,Set.of()),d);
            chk(r.isSuccess(),d.displayName()+" DROP IF EXISTS compiles");
        }
    }

    static void testTruncate() {
        for (Dialect d : Dialect.values()) { if(d==Dialect.H2)continue;
            CompilationResult r = compiler.compileFromIR(new IRTruncateTable("t",Set.of()),d);
            // SQLite has no TRUNCATE — polyfilled to DELETE FROM. Accept either.
            String sql = r.getSql().toUpperCase();
            chk(r.isSuccess() && (sql.contains("TRUNCATE") || sql.contains("DELETE FROM")),
                d.displayName()+" TRUNCATE");
        }
    }

    static void testDropIndex() {
        for (Dialect d : Dialect.values()) { if(d==Dialect.H2)continue;
            CompilationResult r = compiler.compileFromIR(new IRDropIndex("ix","t",true,Set.of()),d);
            chk(r.isSuccess(),d.displayName()+" DROP INDEX compiles");
        }
    }

    static void testAlter() {
        for (Dialect d : Dialect.values()) { if(d==Dialect.H2)continue;
            IRColumnDef col = new IRColumnDef("score",new DataType.VarcharType(100),List.of(new ColNotNull()),null);
            CompilationResult r = compiler.compileFromIR(new IRAlterTableAddColumn("t",col,false,Set.of()),d);
            chk(r.isSuccess() && r.getSql().contains("ADD"),d.displayName()+" ALTER ADD COLUMN compiles");

            r = compiler.compileFromIR(new IRAlterTableDropColumn("t","col",Set.of()),d);
            chk(r.isSuccess() && r.getSql().contains("DROP"),d.displayName()+" ALTER DROP COLUMN compiles");
        }
    }

    static void testCreateProc() {
        for (Dialect d : Dialect.values()) { if(d==Dialect.H2)continue;
            IRCreateProcedure cp = new IRCreateProcedure("p",List.of(),"SELECT 1",false,Set.of());
            CompilationResult r = compiler.compileFromIR(cp,d);
            // DuckDB/ClickHouse have no stored procedures — the backend emits a
            // "not supported" comment rather than a PROCEDURE keyword. The test
            // name is "compiles", so require success; native dialects also emit
            // the PROCEDURE keyword, which we check where applicable.
            boolean nativeProc = d != Dialect.DUCKDB && d != Dialect.CLICKHOUSE;
            chk(r.isSuccess() && (!nativeProc || r.getSql().contains("PROCEDURE")),
                d.displayName()+" CREATE PROCEDURE compiles");

            IRCreateFunction cf = new IRCreateFunction("f",List.of(),DataType.IntType.INT,"RETURN 1",false,Set.of());
            r = compiler.compileFromIR(cf,d);
            boolean nativeFn = d != Dialect.DUCKDB && d != Dialect.CLICKHOUSE;
            chk(r.isSuccess() && (!nativeFn || r.getSql().contains("FUNCTION")),
                d.displayName()+" CREATE FUNCTION compiles");
        }
    }

    static void testCallProc() {
        for (Dialect d : Dialect.values()) { if(d==Dialect.H2)continue;
            CompilationResult r = compiler.compileFromIR(new IRCall("p",List.of(),Set.of()),d);
            chk(r.isSuccess(),d.displayName()+" CALL compiles");
        }
    }

    static void testSelectTypes() {
        for (Dialect d : Dialect.values()) { if(d==Dialect.H2)continue;
            // Simple SELECT
            CompilationResult r = compiler.compile("SELECT 1",d);
            chk(r.isSuccess(),d.displayName()+" SELECT 1");

            // CAST expression
            r = compiler.compile("SELECT CAST(1 AS INT)",d);
            chk(r.isSuccess(),d.displayName()+" CAST compiles");

            // COALESCE
            r = compiler.compile("SELECT COALESCE(NULL, 42)",d);
            chk(r.isSuccess(),d.displayName()+" COALESCE compiles");

            // CASE
            r = compiler.compile("SELECT CASE WHEN 1>0 THEN 'yes' ELSE 'no' END",d);
            chk(r.isSuccess(),d.displayName()+" CASE compiles");

            // Window function
            r = compiler.compile("SELECT ROW_NUMBER() OVER (ORDER BY 1)",d);
            chk(r.isSuccess(),d.displayName()+" ROW_NUMBER compiles");
        }
    }
}
