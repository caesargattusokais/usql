package com.usql.analyzer;

import com.usql.ast.USqlAst.BinOp;
import com.usql.ir.DataType;
import com.usql.ir.IRExpr;

import java.util.List;

/**
 * Type deduction for expressions.
 * Extracted from SemanticAnalyzer — pure logic, no scope/state dependencies.
 */
public final class TypeInferrer {

    private TypeInferrer() {} // utility

    // ══════════════════════════════════════════════════
    //  Binary operation result types
    // ══════════════════════════════════════════════════

    public static DataType inferBinaryResultType(DataType left, DataType right, BinOp op) {
        return switch (op) {
            case EQ, NEQ, LT, GT, LTE, GTE, AND, OR, LIKE, NOT_LIKE -> new DataType.BooleanType();
            case CONCAT -> new DataType.VarcharType(255);
            case ADD, SUB, MUL, DIV, MOD -> {
                if (left instanceof DataType.FloatType || right instanceof DataType.FloatType)
                    yield DataType.FloatType.DOUBLE;
                if (left instanceof DataType.DecimalType || right instanceof DataType.DecimalType)
                    yield new DataType.DecimalType(20, 4);
                if (left instanceof DataType.IntType li && right instanceof DataType.IntType ri)
                    yield (li.bits() >= 64 || ri.bits() >= 64)
                        ? DataType.IntType.BIGINT : DataType.IntType.INT;
                yield DataType.IntType.INT;
            }
        };
    }

    // ══════════════════════════════════════════════════
    //  Function return type (when catalog says null)
    // ══════════════════════════════════════════════════

    public static DataType inferFunctionReturnType(String funcName, List<IRExpr> args) {
        DataType argType = args.isEmpty() ? new DataType.NullType() : args.get(0).getType();
        return switch (funcName.toUpperCase()) {
            case "SUM" -> {
                if (argType instanceof DataType.FloatType) yield DataType.FloatType.DOUBLE;
                yield DataType.IntType.BIGINT;
            }
            case "AVG", "STDDEV", "VARIANCE" -> DataType.FloatType.DOUBLE;
            case "COUNT" -> DataType.IntType.BIGINT;
            case "MIN", "MAX" -> argType instanceof DataType.NullType
                ? new DataType.NullType() : argType;
            case "UPPER", "LOWER", "TRIM", "LTRIM", "RTRIM", "REVERSE",
                 "LPAD", "RPAD", "REPEAT", "INITCAP", "TRANSLATE" ->
                new DataType.VarcharType(255);
            case "CONCAT", "CONCAT_WS", "SUBSTR", "REPLACE", "SPACE" ->
                new DataType.VarcharType(255);
            case "COALESCE", "IFNULL", "NVL", "NULLIF", "IF",
                 "NVL2", "GREATEST", "LEAST" -> {
                // Find first non-null argument type (not just the first argument)
                DataType found = null;
                for (var a : args) {
                    if (a.getType() != null && !(a.getType() instanceof DataType.NullType)) {
                        found = a.getType();
                        break;
                    }
                }
                yield found != null ? found : new DataType.NullType();
            }
            default -> new DataType.NullType();
        };
    }

    // ══════════════════════════════════════════════════
    //  Simple expression type guessing (for schema-less)
    // ══════════════════════════════════════════════════

    public static DataType inferExpressionType(com.usql.ast.USqlAst.Expression expr) {
        return switch (expr) {
            case com.usql.ast.USqlAst.IntLiteral i      -> DataType.IntType.INT;
            case com.usql.ast.USqlAst.FloatLiteral f    -> DataType.FloatType.DOUBLE;
            case com.usql.ast.USqlAst.StringLiteral s   -> new DataType.VarcharType(s.value().length());
            case com.usql.ast.USqlAst.BoolLiteral b     -> new DataType.BooleanType();
            case com.usql.ast.USqlAst.NullLiteral n     -> new DataType.NullType();
            case com.usql.ast.USqlAst.ColumnRef c       -> new DataType.NullType();
            case com.usql.ast.USqlAst.FunctionCall fc   -> new DataType.NullType();
            default -> new DataType.NullType();
        };
    }

    // ══════════════════════════════════════════════════
    //  Type name → DataType
    // ══════════════════════════════════════════════════

    public static DataType parseTypeName(String name, int precision, int scale) {
        return switch (name.toUpperCase()) {
            case "TINYINT"   -> DataType.IntType.TINYINT;
            case "SMALLINT"  -> DataType.IntType.SMALLINT;
            case "INT", "INTEGER" -> DataType.IntType.INT;
            case "BIGINT"    -> DataType.IntType.BIGINT;
            case "DECIMAL", "NUMERIC" -> new DataType.DecimalType(precision > 0 ? precision : 10, Math.max(scale, 0));
            case "FLOAT"     -> DataType.FloatType.FLOAT;
            case "REAL"      -> DataType.FloatType.FLOAT;
            case "DOUBLE"    -> DataType.FloatType.DOUBLE;
            case "CHAR"      -> new DataType.CharType(precision > 0 ? precision : 1);
            case "VARCHAR"   -> new DataType.VarcharType(precision > 0 ? precision : 255);
            case "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT", "CLOB" -> new DataType.TextType();
            case "BOOLEAN"   -> new DataType.BooleanType();
            case "DATE"      -> new DataType.DateType();
            case "TIME"      -> new DataType.TimeType(Math.max(scale, 0));
            case "DATETIME"  -> new DataType.DatetimeType(Math.max(scale, 0));
            case "TIMESTAMP" -> new DataType.TimestampType(Math.max(scale, 0));
            case "JSON"      -> new DataType.JsonType();
            case "UUID"      -> new DataType.UuidType();
            case "BINARY"    -> new DataType.BinaryType(precision > 0 ? precision : 1);
            case "VARBINARY" -> new DataType.VarbinaryType(precision > 0 ? precision : 255);
            case "BLOB"      -> new DataType.BlobType();
            case "BIT"       -> new DataType.BooleanType();
            default -> new DataType.VarcharType(255);
        };
    }
}
