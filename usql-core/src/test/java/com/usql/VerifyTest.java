package com.usql;

import com.usql.verify.TestDataGenerator;
import com.usql.ir.DataType;
import com.usql.ir.IRStatement.*;
import java.util.*;

/** Tests for verify package — no database required. */
public class VerifyTest {
    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        System.out.println("=== Verify Test ===\n");
        testDataGenerator(); testDataType();
        System.out.println("\n=== " + pass + "/" + (pass+fail) + " ===");
        if (fail > 0) System.exit(1);
    }

    static void testDataGenerator() {
        TestDataGenerator gen = new TestDataGenerator(3);

        var cols = new ArrayList<IRColumnDef>();
        var pk = new ArrayList<IRColumnConstraint>(); pk.add(new ColPrimaryKey(false)); pk.add(new ColNotNull());
        cols.add(new IRColumnDef("id", DataType.IntType.INT, pk, null));
        cols.add(new IRColumnDef("name", new DataType.VarcharType(100), List.of(new ColNotNull()), null));
        cols.add(new IRColumnDef("score", DataType.FloatType.DOUBLE, List.of(), null));
        IRCreateTable table = new IRCreateTable(new IRTableName("t",null,null), false, cols, List.of(), null, Set.of());

        List<String> inserts = gen.generateForTable(table, new LinkedHashMap<>());
        chk(inserts.size() == 3, "3 rows");
        chk(inserts.get(0).contains("INSERT INTO"), "INSERT format");
        chk(inserts.get(0).contains("id"), "contains id column");
        chk(inserts.get(0).contains("name"), "contains name column");

        // FK test
        var deptCols = new ArrayList<IRColumnDef>();
        var deptPk = new ArrayList<IRColumnConstraint>(); deptPk.add(new ColPrimaryKey(false)); deptPk.add(new ColNotNull());
        deptCols.add(new IRColumnDef("id", DataType.IntType.INT, deptPk, null));
        deptCols.add(new IRColumnDef("name", new DataType.VarcharType(50), List.of(new ColNotNull()), null));
        IRCreateTable dept = new IRCreateTable(new IRTableName("dept",null,null), false, deptCols, List.of(), null, Set.of());

        var empCols = new ArrayList<IRColumnDef>();
        empCols.add(new IRColumnDef("id", DataType.IntType.INT, deptPk, null));
        var fkCol = new ArrayList<IRColumnConstraint>(); fkCol.add(new ColNotNull()); fkCol.add(new ColReferences("dept","id",ForeignKeyAction.RESTRICT,ForeignKeyAction.RESTRICT));
        empCols.add(new IRColumnDef("dept_id", DataType.IntType.INT, fkCol, null));
        IRCreateTable emp = new IRCreateTable(new IRTableName("emp",null,null), false, empCols, List.of(), null, Set.of());

        List<String> multi = gen.generate(List.of(dept, emp));
        chk(multi.size() == 6, "2 tables x 3 rows = 6 inserts");
        chk(multi.get(3).contains("dept_id"), "FK column in employee insert");
    }

    static void testDataType() {
        chk(DataType.IntType.INT.bits() == 32, "INT bits=32");
        chk(DataType.IntType.BIGINT.bits() == 64, "BIGINT bits=64");
        chk(new DataType.VarcharType(100).length() == 100, "VARCHAR(100) length");
        chk(new DataType.BooleanType() instanceof DataType.BooleanType, "BooleanType");
        chk(new DataType.DateType() instanceof DataType.DateType, "DateType");
        chk(DataType.FloatType.DOUBLE.bits() == 64, "DOUBLE bits=64");

        // Enum
        var e = new DataType.EnumType(List.of("a","b","c"));
        chk(e.values().size() == 3, "Enum 3 values");
        chk(e.values().get(0).equals("a"), "Enum first=a");
    }

    static void chk(boolean c, String m) { if(c) pass++; else { fail++; System.err.println("  ❌ "+m); } }
}
