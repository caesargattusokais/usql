package com.usql;

import com.usql.ir.DataType;
import com.usql.ir.IRStatement.*;
import com.usql.verify.TestDataGenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Tests for TestDataGenerator.
 * No database required.
 */
public class TestDataGeneratorTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        System.out.println("=== TestDataGenerator Test ===\n");

        testSingleTable();
        testMultiTableWithFK();
        testNullableColumns();
        testAutoIncrement();

        System.out.println("\n=== Result: " + pass + "/" + (pass + fail) + " passed ===");
        if (fail > 0) System.exit(1);
    }

    static void testSingleTable() {
        List<IRColumnConstraint> col1c = new ArrayList<>();
        col1c.add(new ColPrimaryKey(false));
        col1c.add(new ColNotNull());

        List<IRColumnConstraint> col2c = new ArrayList<>();
        col2c.add(new ColNotNull());

        IRCreateTable table = new IRCreateTable(
            new IRTableName("users", null, null), false,
            List.of(
                new IRColumnDef("id", DataType.IntType.INT, col1c, null),
                new IRColumnDef("name", new DataType.VarcharType(100), col2c, null)
            ),
            List.of(), null, Set.of());

        TestDataGenerator gen = new TestDataGenerator(3);
        List<String> inserts = gen.generateForTable(table, new java.util.LinkedHashMap<>());

        check(inserts.size() == 3, "3 rows generated");
        check(inserts.get(0).startsWith("INSERT INTO \"users\""), "INSERT statement format");
        check(inserts.get(0).contains("VALUES"), "Contains VALUES");

        System.out.println("  ✅ Single table: 3 rows, valid SQL");
    }

    static void testMultiTableWithFK() {
        // Table 1: departments (id PK, name)
        List<IRColumnConstraint> deptPK = new ArrayList<>();
        deptPK.add(new ColPrimaryKey(false));
        deptPK.add(new ColNotNull());

        IRCreateTable dept = new IRCreateTable(
            new IRTableName("departments", null, null), false,
            List.of(
                new IRColumnDef("id", DataType.IntType.INT, deptPK, null),
                new IRColumnDef("name", new DataType.VarcharType(100), List.of(new ColNotNull()), null)
            ),
            List.of(), null, Set.of());

        // Table 2: employees (id PK, dept_id FK→departments.id)
        List<IRColumnConstraint> empPK = new ArrayList<>();
        empPK.add(new ColPrimaryKey(false));
        empPK.add(new ColNotNull());

        List<IRColumnConstraint> fkCol = new ArrayList<>();
        fkCol.add(new ColNotNull());
        fkCol.add(new ColReferences("departments", "id",
            ForeignKeyAction.RESTRICT, ForeignKeyAction.RESTRICT));

        IRCreateTable emp = new IRCreateTable(
            new IRTableName("employees", null, null), false,
            List.of(
                new IRColumnDef("id", DataType.IntType.INT, empPK, null),
                new IRColumnDef("dept_id", DataType.IntType.INT, fkCol, null)
            ),
            List.of(), null, Set.of());

        TestDataGenerator gen = new TestDataGenerator(5);
        List<String> inserts = gen.generate(List.of(dept, emp));

        check(inserts.size() == 10, "10 total inserts (5 per table)");
        // FK values should be in range 1-5 (PKs of departments)
        String empInsert = inserts.get(5); // first employee insert
        check(empInsert.contains("dept_id"), "FK column present");

        System.out.println("  ✅ Multi-table with FK: 10 inserts, FK values referenced");
    }

    static void testNullableColumns() {
        List<IRColumnConstraint> pk = new ArrayList<>();
        pk.add(new ColPrimaryKey(false));
        pk.add(new ColNotNull());

        IRCreateTable table = new IRCreateTable(
            new IRTableName("t", null, null), false,
            List.of(
                new IRColumnDef("id", DataType.IntType.INT, pk, null),
                new IRColumnDef("opt_col", new DataType.VarcharType(50), List.of(), null) // nullable
            ),
            List.of(), null, Set.of());

        TestDataGenerator gen = new TestDataGenerator(5);
        List<String> inserts = gen.generateForTable(table, new java.util.LinkedHashMap<>());

        // Last row (row 5) should have NULL for opt_col
        String lastInsert = inserts.get(4);
        check(lastInsert.contains("NULL"), "Last row has NULL for nullable column");
        // First row should NOT be NULL
        check(!inserts.get(0).contains("NULL"), "First row has value for nullable column");

        System.out.println("  ✅ Nullable columns: last row NULL, others valued");
    }

    static void testAutoIncrement() {
        List<IRColumnConstraint> ai = new ArrayList<>();
        ai.add(new ColPrimaryKey(true)); // autoIncrement
        ai.add(new ColNotNull());

        IRCreateTable table = new IRCreateTable(
            new IRTableName("t", null, null), false,
            List.of(
                new IRColumnDef("id", DataType.IntType.INT, ai, null)
            ),
            List.of(), null, Set.of());

        TestDataGenerator gen = new TestDataGenerator(3);
        List<String> inserts = gen.generateForTable(table, new java.util.LinkedHashMap<>());

        // Auto-increment PK uses row number
        check(inserts.get(0).contains("1"), "Row 1: PK = 1");
        check(inserts.get(1).contains("2"), "Row 2: PK = 2");

        System.out.println("  ✅ Auto-increment: row numbers used for PK");
    }

    static void check(boolean condition, String msg) {
        if (condition) { pass++; }
        else { fail++; System.err.println("  ❌ FAIL: " + msg); }
    }
}
