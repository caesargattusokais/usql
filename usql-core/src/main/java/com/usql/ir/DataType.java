package com.usql.ir;

import java.util.List;

/**
 * U-SQL canonical type system.
 * Each database dialect maps its native types to/from these.
 */
public sealed interface DataType {

    /** Type name for SQL generation */
    String typeName();

    // ── Integer family ──
    record IntType(int bits) implements DataType {
        public static final IntType TINYINT  = new IntType(8);
        public static final IntType SMALLINT = new IntType(16);
        public static final IntType INT       = new IntType(32);
        public static final IntType BIGINT    = new IntType(64);

        public String typeName() {
            return switch (bits) {
                case 8  -> "TINYINT";
                case 16 -> "SMALLINT";
                case 32 -> "INT";
                case 64 -> "BIGINT";
                default -> "INT(" + bits + ")";
            };
        }
    }

    // ── Fixed-point ──
    record DecimalType(int precision, int scale) implements DataType {
        public String typeName() { return "DECIMAL(" + precision + "," + scale + ")"; }
    }

    // ── Float family ──
    record FloatType(int bits) implements DataType {
        public static final FloatType FLOAT  = new FloatType(32);
        public static final FloatType DOUBLE = new FloatType(64);

        public String typeName() { return bits <= 32 ? "FLOAT" : "DOUBLE"; }
    }

    // ── Character family ──
    record CharType(int length) implements DataType {
        public String typeName() { return "CHAR(" + length + ")"; }
    }

    record VarcharType(int length) implements DataType {
        public String typeName() { return "VARCHAR(" + length + ")"; }
    }

    record TextType() implements DataType {
        public String typeName() { return "TEXT"; }
    }

    // ── Binary family ──
    record BinaryType(int length) implements DataType {
        public String typeName() { return "BINARY(" + length + ")"; }
    }

    record VarbinaryType(int length) implements DataType {
        public String typeName() { return "VARBINARY(" + length + ")"; }
    }

    record BlobType() implements DataType {
        public String typeName() { return "BLOB"; }
    }

    // ── Boolean ──
    record BooleanType() implements DataType {
        public String typeName() { return "BOOLEAN"; }
    }

    // ── DateTime family ──
    record DateType() implements DataType {
        public String typeName() { return "DATE"; }
    }

    record TimeType(int fractionalSeconds) implements DataType {
        public String typeName() { return "TIME(" + fractionalSeconds + ")"; }
    }

    record DatetimeType(int fractionalSeconds) implements DataType {
        public String typeName() { return "DATETIME(" + fractionalSeconds + ")"; }
    }

    record TimestampType(int fractionalSeconds) implements DataType {
        public String typeName() { return "TIMESTAMP(" + fractionalSeconds + ")"; }
    }

    // ── Interval ──
    record IntervalYearMonth() implements DataType {
        public String typeName() { return "INTERVAL YEAR TO MONTH"; }
    }

    record IntervalDaySecond(int fractionalSeconds) implements DataType {
        public String typeName() { return "INTERVAL DAY TO SECOND(" + fractionalSeconds + ")"; }
    }

    // ── Special ──
    record JsonType() implements DataType {
        public String typeName() { return "JSON"; }
    }

    record XmlType() implements DataType {
        public String typeName() { return "XML"; }
    }

    record UuidType() implements DataType {
        public String typeName() { return "UUID"; }
    }

    record ArrayType(DataType elementType) implements DataType {
        public String typeName() { return "ARRAY<" + elementType.typeName() + ">"; }
    }

    record NullType() implements DataType {
        public String typeName() { return "NULL"; }
    }

    /** ENUM type with allowed values */
    record EnumType(List<String> values) implements DataType {
        public String typeName() { return "ENUM(" + String.join(",", values) + ")"; }
    }

    // ── Helpers ──

    /** Is this type integer-like (for arithmetic compatibility checks) */
    default boolean isNumeric() {
        return this instanceof IntType
            || this instanceof DecimalType
            || this instanceof FloatType;
    }

    /** Is this type character-like */
    default boolean isString() {
        return this instanceof CharType
            || this instanceof VarcharType
            || this instanceof TextType;
    }

    /** Is this temporal */
    default boolean isTemporal() {
        return this instanceof DateType
            || this instanceof TimeType
            || this instanceof DatetimeType
            || this instanceof TimestampType;
    }
}
