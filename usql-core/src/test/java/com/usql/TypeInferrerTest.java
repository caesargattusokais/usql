package com.usql;

import com.usql.analyzer.TypeInferrer;
import com.usql.ir.DataType;
import com.usql.ir.IRExpr.*;

import java.util.List;

/**
 * Tests for TypeInferrer utility.
 * No database required.
 */
public class TypeInferrerTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        System.out.println("=== TypeInferrer Test ===\n");

        testBinaryTypes();
        testFunctionReturnTypes();
        testParseTypeName();

        System.out.println("\n=== Result: " + pass + "/" + (pass + fail) + " passed ===");
        if (fail > 0) System.exit(1);
    }

    static void testBinaryTypes() {
        // INT + INT → INT
        check(TypeInferrer.inferBinaryResultType(
            DataType.IntType.INT, DataType.IntType.INT,
            com.usql.ast.USqlAst.BinOp.ADD)
            .equals(DataType.IntType.INT), "INT + INT → INT");

        // BIGINT + INT → BIGINT
        check(TypeInferrer.inferBinaryResultType(
            DataType.IntType.BIGINT, DataType.IntType.INT,
            com.usql.ast.USqlAst.BinOp.ADD)
            .equals(DataType.IntType.BIGINT), "BIGINT + INT → BIGINT");

        // DOUBLE + INT → DOUBLE
        check(TypeInferrer.inferBinaryResultType(
            DataType.FloatType.DOUBLE, DataType.IntType.INT,
            com.usql.ast.USqlAst.BinOp.ADD)
            .equals(DataType.FloatType.DOUBLE), "DOUBLE + INT → DOUBLE");

        // Comparison → BOOLEAN
        check(TypeInferrer.inferBinaryResultType(
            DataType.IntType.INT, DataType.IntType.INT,
            com.usql.ast.USqlAst.BinOp.EQ)
            instanceof DataType.BooleanType, "EQ → BOOLEAN");

        // AND → BOOLEAN
        check(TypeInferrer.inferBinaryResultType(
            new DataType.BooleanType(), new DataType.BooleanType(),
            com.usql.ast.USqlAst.BinOp.AND)
            instanceof DataType.BooleanType, "AND → BOOLEAN");

        // CONCAT → VARCHAR
        check(TypeInferrer.inferBinaryResultType(
            new DataType.VarcharType(0), new DataType.VarcharType(0),
            com.usql.ast.USqlAst.BinOp.CONCAT)
            instanceof DataType.VarcharType, "CONCAT → VARCHAR");

        System.out.println("  ✅ Binary types: 6/6");
    }

    static void testFunctionReturnTypes() {
        // COUNT → BIGINT
        check(TypeInferrer.inferFunctionReturnType("COUNT", List.of())
            .equals(DataType.IntType.BIGINT), "COUNT → BIGINT");

        // SUM(INT) → BIGINT
        check(TypeInferrer.inferFunctionReturnType("SUM",
            List.of(new IRLiteral(1, DataType.IntType.INT)))
            .equals(DataType.IntType.BIGINT), "SUM(INT) → BIGINT");

        // SUM(DOUBLE) → DOUBLE
        check(TypeInferrer.inferFunctionReturnType("SUM",
            List.of(new IRLiteral(1.0, DataType.FloatType.DOUBLE)))
            .equals(DataType.FloatType.DOUBLE), "SUM(DOUBLE) → DOUBLE");

        // AVG → DOUBLE
        check(TypeInferrer.inferFunctionReturnType("AVG",
            List.of(new IRLiteral(1, null)))
            .equals(DataType.FloatType.DOUBLE), "AVG → DOUBLE");

        // UPPER → VARCHAR
        check(TypeInferrer.inferFunctionReturnType("UPPER", List.of())
            instanceof DataType.VarcharType, "UPPER → VARCHAR");

        // COALESCE → first arg type
        check(TypeInferrer.inferFunctionReturnType("COALESCE",
            List.of(new IRLiteral("x", new DataType.VarcharType(10))))
            instanceof DataType.VarcharType, "COALESCE → VARCHAR");

        // Unknown → NullType
        check(TypeInferrer.inferFunctionReturnType("UNKNOWN_FUNC", List.of())
            instanceof DataType.NullType, "Unknown → NullType");

        System.out.println("  ✅ Function return types: 7/7");
    }

    static void testParseTypeName() {
        check(TypeInferrer.parseTypeName("INT", 0, 0).equals(DataType.IntType.INT),
            "INT → IntType.INT");
        check(TypeInferrer.parseTypeName("BIGINT", 0, 0).equals(DataType.IntType.BIGINT),
            "BIGINT → IntType.BIGINT");
        check(TypeInferrer.parseTypeName("VARCHAR", 255, 0) instanceof DataType.VarcharType,
            "VARCHAR → VarcharType");
        check(TypeInferrer.parseTypeName("BOOLEAN", 0, 0) instanceof DataType.BooleanType,
            "BOOLEAN → BooleanType");
        check(TypeInferrer.parseTypeName("DATE", 0, 0) instanceof DataType.DateType,
            "DATE → DateType");
        check(TypeInferrer.parseTypeName("TEXT", 0, 0) instanceof DataType.TextType,
            "TEXT → TextType");
        check(TypeInferrer.parseTypeName("DECIMAL", 10, 2) instanceof DataType.DecimalType,
            "DECIMAL → DecimalType");
        check(TypeInferrer.parseTypeName("DOUBLE", 0, 0).equals(DataType.FloatType.DOUBLE),
            "DOUBLE → FloatType.DOUBLE");

        System.out.println("  ✅ Parse type name: 8/8");
    }

    static void check(boolean condition, String msg) {
        if (condition) { pass++; }
        else { fail++; System.err.println("  ❌ FAIL: " + msg); }
    }
}
