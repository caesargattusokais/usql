package com.usql;

import com.usql.dialect.Dialect;
import com.usql.ir.*;
import com.usql.ir.IRExpr.*;
import com.usql.ir.IRStatement.*;
import java.util.*;

/** Backend SQL generation tests — no database required. */
public class SqlGenerationTest {
    static int pass = 0, fail = 0;
    static USqlCompiler c = USqlCompiler.builder().build();

    public static void main(String[] args) {
        System.out.println("=== SQL Generation Test ===\n");
        testSelect(); testInsert(); testUpdate(); testDelete();
        testDDL(); testDrop(); testTCL(); testMerge();
        testWindow(); testCTE(); testCast(); testFunctions();
        System.out.println("\n=== " + pass + "/" + (pass+fail) + " ===");
        if (fail > 0) System.exit(1);
    }

    static void chk(boolean c, String m) { if(c) pass++; else { fail++; System.err.println("  ❌ "+m); } }

    static void testSelect() {
        for (Dialect d : Dialect.values()) { if(d==Dialect.H2)continue;
            CompilationResult r = c.compile("SELECT name FROM users WHERE id = 1", d);
            chk(r.isSuccess(), d+" SELECT WHERE");
            chk(r.getSql().toUpperCase().contains("NAME"), d+" contains column");
        }
    }

    static void testInsert() {
        for (Dialect d : Dialect.values()) { if(d==Dialect.H2)continue;
            CompilationResult r = c.compile("INSERT INTO t (a) VALUES (1)", d);
            chk(r.isSuccess(), d+" INSERT");
        }
    }

    static void testUpdate() {
        for (Dialect d : Dialect.values()) { if(d==Dialect.H2)continue;
            CompilationResult r = c.compile("UPDATE t SET a=1 WHERE b=2", d);
            chk(r.isSuccess(), d+" UPDATE");
        }
    }

    static void testDelete() {
        for (Dialect d : Dialect.values()) { if(d==Dialect.H2)continue;
            CompilationResult r = c.compile("DELETE FROM t WHERE a=1", d);
            chk(r.isSuccess(), d+" DELETE");
        }
    }

    static void testDDL() {
        for (Dialect d : Dialect.values()) { if(d==Dialect.H2)continue;
            CompilationResult r = c.compile("CREATE TABLE t (id INT PRIMARY KEY)", d);
            chk(r.isSuccess(), d+" CREATE TABLE");
            chk(r.getSql().toUpperCase().contains("CREATE"), d+" DDL contains CREATE");

            r = c.compile("ALTER TABLE t ADD c INT", d);
            chk(r.isSuccess(), d+" ALTER ADD");

            r = c.compile("ALTER TABLE t ADD COLUMN IF NOT EXISTS c VARCHAR(64)", d);
            chk(r.isSuccess(), d+" ALTER ADD COLUMN IF NOT EXISTS");

            r = c.compile("CREATE INDEX i ON t (id)", d);
            chk(r.isSuccess(), d+" CREATE INDEX");

            r = c.compile("CREATE VIEW v AS SELECT 1", d);
            chk(r.isSuccess(), d+" CREATE VIEW");
        }
    }

    static void testDrop() {
        for (Dialect d : Dialect.values()) { if(d==Dialect.H2)continue;
            CompilationResult r = c.compile("DROP TABLE t", d);
            chk(r.isSuccess(), d+" DROP TABLE");
            r = c.compile("TRUNCATE TABLE t", d);
            chk(r.isSuccess(), d+" TRUNCATE");
        }
    }

    static void testTCL() {
        for (Dialect d : Dialect.values()) { if(d==Dialect.H2)continue;
            chk(c.compile("BEGIN", d).isSuccess(), d+" BEGIN");
            chk(c.compile("COMMIT", d).isSuccess(), d+" COMMIT");
            chk(c.compile("ROLLBACK", d).isSuccess(), d+" ROLLBACK");
        }
    }

    static void testMerge() {
        for (Dialect d : new Dialect[]{Dialect.MYSQL, Dialect.POSTGRESQL, Dialect.ORACLE}) {
            CompilationResult r = c.compile("MERGE INTO a USING b ON a.id=b.id WHEN MATCHED THEN UPDATE SET a.x=b.x WHEN NOT MATCHED THEN INSERT (id,x) VALUES (b.id,b.x)", d);
            chk(r.isSuccess(), d+" MERGE");
        }
    }

    static void testWindow() {
        for (Dialect d : new Dialect[]{Dialect.MYSQL, Dialect.POSTGRESQL, Dialect.ORACLE}) {
            CompilationResult r = c.compile("SELECT ROW_NUMBER() OVER (ORDER BY id) FROM t", d);
            chk(r.isSuccess(), d+" ROW_NUMBER");
        }
    }

    static void testCTE() {
        for (Dialect d : new Dialect[]{Dialect.MYSQL, Dialect.POSTGRESQL}) {
            CompilationResult r = c.compile("WITH RECURSIVE n AS (SELECT 1 AS v UNION ALL SELECT v+1 FROM n WHERE v<5) SELECT v FROM n", d);
            chk(r.isSuccess(), d+" recursive CTE");
        }
    }

    static void testCast() {
        for (Dialect d : new Dialect[]{Dialect.MYSQL, Dialect.POSTGRESQL}) {
            CompilationResult r = c.compile("SELECT CAST(1 AS VARCHAR(10))", d);
            chk(r.isSuccess(), d+" CAST");
        }
    }

    static void testFunctions() {
        for (Dialect d : new Dialect[]{Dialect.MYSQL, Dialect.POSTGRESQL, Dialect.ORACLE}) {
            chk(c.compile("SELECT UPPER('x')", d).isSuccess(), d+" UPPER");
            chk(c.compile("SELECT LENGTH('x')", d).isSuccess(), d+" LENGTH");
            chk(c.compile("SELECT COALESCE(NULL,1)", d).isSuccess(), d+" COALESCE");
            chk(c.compile("SELECT NOW()", d).isSuccess(), d+" NOW");
            chk(c.compile("SELECT 1+1", d).isSuccess(), d+" arithmetic");
        }
    }
}
