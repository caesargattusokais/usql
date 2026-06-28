package com.usql.catalog;

import com.usql.dialect.Dialect;
import com.usql.ir.DataType;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Central type mapping registry.
 *
 * Maps between U-SQL canonical types and each dialect's native type.
 * This is the "hub" design: each dialect maps to/from the U-SQL canonical type,
 * avoiding N×(N-1) direct mappings.
 */
public class TypeCatalog {

    private final Map<Dialect, Map<String, DataType>> dialectToUsql;   // native → U-SQL
    private final Map<Dialect, Map<Class<? extends DataType>, String>> usqlToNative; // U-SQL → native
    private final Map<Dialect, TypeMappingRules> rules;

    public TypeCatalog() {
        this.dialectToUsql = new EnumMap<>(Dialect.class);
        this.usqlToNative = new EnumMap<>(Dialect.class);
        this.rules = new EnumMap<>(Dialect.class);
        registerAll();
    }

    /**
     * Map a U-SQL type to the dialect's native type string (for DDL generation).
     */
    public String toNative(DataType usqlType, Dialect dialect) {
        Map<Class<? extends DataType>, String> mapping = usqlToNative.get(dialect);
        if (mapping == null) return usqlType.typeName(); // fallback

        return mapping.getOrDefault(usqlType.getClass(), usqlType.typeName());
    }

    /**
     * Map a native type string to the canonical U-SQL type.
     */
    public Optional<DataType> fromNative(String nativeType, Dialect dialect) {
        Map<String, DataType> mapping = dialectToUsql.get(dialect);
        if (mapping == null) return Optional.empty();

        String upper = nativeType.toUpperCase().trim();
        return Optional.ofNullable(mapping.get(upper));
    }

    /**
     * Get special conversion rules for a dialect.
     */
    public Optional<TypeMappingRules> getRules(Dialect dialect) {
        return Optional.ofNullable(rules.get(dialect));
    }

    // ── Registration ──

    private void registerAll() {
        registerMySQL();
        registerPostgreSQL();
        registerOracle();
        registerDM();
    }

    private void registerMySQL() {
        var native2usql = new java.util.HashMap<String, DataType>();
        var usql2native = new java.util.HashMap<Class<? extends DataType>, String>();

        // Native → U-SQL
        native2usql.put("TINYINT",   DataType.IntType.TINYINT);
        native2usql.put("SMALLINT",  DataType.IntType.SMALLINT);
        native2usql.put("INT",       DataType.IntType.INT);
        native2usql.put("INTEGER",   DataType.IntType.INT);
        native2usql.put("BIGINT",    DataType.IntType.BIGINT);
        native2usql.put("FLOAT",     DataType.FloatType.FLOAT);
        native2usql.put("DOUBLE",    DataType.FloatType.DOUBLE);
        native2usql.put("DECIMAL",   new DataType.DecimalType(10, 0)); // placeholder
        native2usql.put("CHAR",      new DataType.CharType(1));
        native2usql.put("VARCHAR",   new DataType.VarcharType(255));
        native2usql.put("TEXT",      new DataType.TextType());
        native2usql.put("LONGTEXT",  new DataType.TextType());
        native2usql.put("TINYINT(1)", new DataType.BooleanType());
        native2usql.put("DATE",      new DataType.DateType());
        native2usql.put("TIME",      new DataType.TimeType(0));
        native2usql.put("DATETIME",  new DataType.DatetimeType(0));
        native2usql.put("TIMESTAMP", new DataType.TimestampType(0));
        native2usql.put("JSON",      new DataType.JsonType());
        native2usql.put("BINARY",    new DataType.BinaryType(1));
        native2usql.put("VARBINARY", new DataType.VarbinaryType(255));
        native2usql.put("LONGBLOB",  new DataType.BlobType());
        native2usql.put("BLOB",      new DataType.BlobType());

        // U-SQL → Native
        usql2native.put(DataType.IntType.class,        "INT");
        usql2native.put(DataType.DecimalType.class,     "DECIMAL");
        usql2native.put(DataType.FloatType.class,       "DOUBLE");
        usql2native.put(DataType.CharType.class,        "CHAR");
        usql2native.put(DataType.VarcharType.class,     "VARCHAR");
        usql2native.put(DataType.TextType.class,        "LONGTEXT");
        usql2native.put(DataType.BooleanType.class,     "TINYINT(1)");
        usql2native.put(DataType.DateType.class,        "DATE");
        usql2native.put(DataType.TimeType.class,        "TIME");
        usql2native.put(DataType.DatetimeType.class,    "DATETIME");
        usql2native.put(DataType.TimestampType.class,   "TIMESTAMP");
        usql2native.put(DataType.JsonType.class,        "JSON");
        usql2native.put(DataType.BinaryType.class,      "BINARY");
        usql2native.put(DataType.VarbinaryType.class,   "VARBINARY");
        usql2native.put(DataType.BlobType.class,        "LONGBLOB");
        usql2native.put(DataType.UuidType.class,        "CHAR(36)");

        dialectToUsql.put(Dialect.MYSQL, native2usql);
        usqlToNative.put(Dialect.MYSQL, usql2native);
    }

    private void registerPostgreSQL() {
        var native2usql = new java.util.HashMap<String, DataType>();
        var usql2native = new java.util.HashMap<Class<? extends DataType>, String>();

        native2usql.put("SMALLINT",         DataType.IntType.SMALLINT);
        native2usql.put("INTEGER",          DataType.IntType.INT);
        native2usql.put("INT",              DataType.IntType.INT);
        native2usql.put("BIGINT",           DataType.IntType.BIGINT);
        native2usql.put("REAL",             DataType.FloatType.FLOAT);
        native2usql.put("DOUBLE PRECISION", DataType.FloatType.DOUBLE);
        native2usql.put("NUMERIC",          new DataType.DecimalType(10, 0));
        native2usql.put("CHAR",             new DataType.CharType(1));
        native2usql.put("VARCHAR",          new DataType.VarcharType(255));
        native2usql.put("TEXT",             new DataType.TextType());
        native2usql.put("BOOLEAN",          new DataType.BooleanType());
        native2usql.put("DATE",             new DataType.DateType());
        native2usql.put("TIME",             new DataType.TimeType(6));
        native2usql.put("TIMESTAMP",        new DataType.DatetimeType(6));
        native2usql.put("TIMESTAMPTZ",      new DataType.TimestampType(6));
        native2usql.put("JSON",             new DataType.JsonType());
        native2usql.put("JSONB",            new DataType.JsonType());
        native2usql.put("UUID",             new DataType.UuidType());
        native2usql.put("BYTEA",            new DataType.BlobType());

        usql2native.put(DataType.IntType.class,        "INTEGER");
        usql2native.put(DataType.DecimalType.class,     "NUMERIC");
        usql2native.put(DataType.FloatType.class,       "DOUBLE PRECISION");
        usql2native.put(DataType.CharType.class,        "CHAR");
        usql2native.put(DataType.VarcharType.class,     "VARCHAR");
        usql2native.put(DataType.TextType.class,        "TEXT");
        usql2native.put(DataType.BooleanType.class,     "BOOLEAN");
        usql2native.put(DataType.DateType.class,        "DATE");
        usql2native.put(DataType.TimeType.class,        "TIME");
        usql2native.put(DataType.DatetimeType.class,    "TIMESTAMP");
        usql2native.put(DataType.TimestampType.class,   "TIMESTAMPTZ");
        usql2native.put(DataType.JsonType.class,        "JSONB");
        usql2native.put(DataType.UuidType.class,        "UUID");
        usql2native.put(DataType.BinaryType.class,      "BYTEA");
        usql2native.put(DataType.VarbinaryType.class,   "BYTEA");
        usql2native.put(DataType.BlobType.class,        "BYTEA");

        dialectToUsql.put(Dialect.POSTGRESQL, native2usql);
        usqlToNative.put(Dialect.POSTGRESQL, usql2native);
    }

    private void registerOracle() {
        var native2usql = new java.util.HashMap<String, DataType>();
        var usql2native = new java.util.HashMap<Class<? extends DataType>, String>();

        native2usql.put("NUMBER(3)",   DataType.IntType.TINYINT);
        native2usql.put("NUMBER(5)",   DataType.IntType.SMALLINT);
        native2usql.put("NUMBER(10)",  DataType.IntType.INT);
        native2usql.put("NUMBER(19)",  DataType.IntType.BIGINT);
        native2usql.put("NUMBER",      new DataType.DecimalType(38, 0));
        native2usql.put("BINARY_FLOAT", DataType.FloatType.FLOAT);
        native2usql.put("BINARY_DOUBLE", DataType.FloatType.DOUBLE);
        native2usql.put("CHAR",        new DataType.CharType(1));
        native2usql.put("VARCHAR2",    new DataType.VarcharType(255));
        native2usql.put("CLOB",        new DataType.TextType());
        native2usql.put("NUMBER(1)",   new DataType.BooleanType());
        native2usql.put("DATE",        new DataType.DatetimeType(0)); // Oracle DATE = datetime!
        native2usql.put("TIMESTAMP",   new DataType.DatetimeType(6));
        native2usql.put("TIMESTAMP(6)", new DataType.DatetimeType(6));
        native2usql.put("TIMESTAMP WITH TIME ZONE", new DataType.TimestampType(6));
        native2usql.put("RAW",         new DataType.BinaryType(1));
        native2usql.put("BLOB",        new DataType.BlobType());
        native2usql.put("INTERVAL YEAR TO MONTH", new DataType.IntervalYearMonth());
        native2usql.put("INTERVAL DAY TO SECOND", new DataType.IntervalDaySecond(6));

        usql2native.put(DataType.IntType.class,        "NUMBER");
        usql2native.put(DataType.DecimalType.class,     "NUMBER");
        usql2native.put(DataType.FloatType.class,       "BINARY_DOUBLE");
        usql2native.put(DataType.CharType.class,        "CHAR");
        usql2native.put(DataType.VarcharType.class,     "VARCHAR2");
        usql2native.put(DataType.TextType.class,        "CLOB");
        usql2native.put(DataType.BooleanType.class,     "NUMBER(1)");
        usql2native.put(DataType.DateType.class,        "DATE");
        usql2native.put(DataType.TimeType.class,        "INTERVAL DAY(0) TO SECOND");
        usql2native.put(DataType.DatetimeType.class,    "TIMESTAMP");
        usql2native.put(DataType.TimestampType.class,   "TIMESTAMP WITH TIME ZONE");
        usql2native.put(DataType.IntervalYearMonth.class, "INTERVAL YEAR TO MONTH");
        usql2native.put(DataType.IntervalDaySecond.class, "INTERVAL DAY TO SECOND");
        usql2native.put(DataType.JsonType.class,        "CLOB");
        usql2native.put(DataType.UuidType.class,        "RAW(16)");
        usql2native.put(DataType.BinaryType.class,      "RAW");
        usql2native.put(DataType.VarbinaryType.class,   "RAW");
        usql2native.put(DataType.BlobType.class,        "BLOB");

        dialectToUsql.put(Dialect.ORACLE, native2usql);
        usqlToNative.put(Dialect.ORACLE, usql2native);
    }

    private void registerDM() {
        var native2usql = new java.util.HashMap<String, DataType>();
        var usql2native = new java.util.HashMap<Class<? extends DataType>, String>();

        // 达梦 — largely Oracle-compatible with some MySQL-like additions
        native2usql.put("TINYINT",   DataType.IntType.TINYINT);
        native2usql.put("SMALLINT",  DataType.IntType.SMALLINT);
        native2usql.put("INT",       DataType.IntType.INT);
        native2usql.put("BIGINT",    DataType.IntType.BIGINT);
        native2usql.put("REAL",      DataType.FloatType.FLOAT);
        native2usql.put("DOUBLE",    DataType.FloatType.DOUBLE);
        native2usql.put("DECIMAL",   new DataType.DecimalType(10, 0));
        native2usql.put("CHAR",      new DataType.CharType(1));
        native2usql.put("VARCHAR",   new DataType.VarcharType(255));
        native2usql.put("TEXT",      new DataType.TextType());
        native2usql.put("CLOB",      new DataType.TextType());
        native2usql.put("BIT",       new DataType.BooleanType());
        native2usql.put("DATE",      new DataType.DatetimeType(0)); // 达梦 DATE = datetime
        native2usql.put("TIME",      new DataType.TimeType(0));
        native2usql.put("TIMESTAMP", new DataType.DatetimeType(6));
        native2usql.put("TIMESTAMP WITH TIME ZONE", new DataType.TimestampType(6));
        native2usql.put("BINARY",    new DataType.BinaryType(1));
        native2usql.put("VARBINARY", new DataType.VarbinaryType(255));
        native2usql.put("BLOB",      new DataType.BlobType());
        native2usql.put("IMAGE",     new DataType.BlobType());
        native2usql.put("INTERVAL YEAR TO MONTH", new DataType.IntervalYearMonth());
        native2usql.put("INTERVAL DAY TO SECOND", new DataType.IntervalDaySecond(6));

        usql2native.put(DataType.IntType.class,        "INT");
        usql2native.put(DataType.DecimalType.class,     "DECIMAL");
        usql2native.put(DataType.FloatType.class,       "DOUBLE");
        usql2native.put(DataType.CharType.class,        "CHAR");
        usql2native.put(DataType.VarcharType.class,     "VARCHAR");
        usql2native.put(DataType.TextType.class,        "CLOB");
        usql2native.put(DataType.BooleanType.class,     "BIT");
        usql2native.put(DataType.DateType.class,        "DATE");
        usql2native.put(DataType.TimeType.class,        "TIME");
        usql2native.put(DataType.DatetimeType.class,    "TIMESTAMP");
        usql2native.put(DataType.TimestampType.class,   "TIMESTAMP WITH TIME ZONE");
        usql2native.put(DataType.IntervalYearMonth.class, "INTERVAL YEAR TO MONTH");
        usql2native.put(DataType.IntervalDaySecond.class, "INTERVAL DAY TO SECOND");
        usql2native.put(DataType.JsonType.class,        "CLOB");
        usql2native.put(DataType.UuidType.class,        "VARCHAR(36)");
        usql2native.put(DataType.BinaryType.class,      "BINARY");
        usql2native.put(DataType.VarbinaryType.class,   "VARBINARY");
        usql2native.put(DataType.BlobType.class,        "BLOB");

        dialectToUsql.put(Dialect.DM, native2usql);
        usqlToNative.put(Dialect.DM, usql2native);
    }

    // ══════════════════════════════════════════════════
    //  Mapping rules
    // ══════════════════════════════════════════════════

    /**
     * Additional type conversion rules for a dialect.
     */
    public record TypeMappingRules(
        Dialect dialect,
        boolean booleansRequireCast,     // MySQL: CAST(col AS SIGNED) to read BOOLEAN
        boolean dateContainsTime,        // Oracle/达梦: DATE has time portion
        boolean charPadsWithSpaces,      // Oracle CHAR is blank-padded
        boolean varcharUsesChars,        // Oracle: VARCHAR2(n CHAR) not bytes
        boolean needsFromDual,           // Oracle/达梦: SELECT expr FROM DUAL
        int maxIdentifierLength,
        Set<String> reservedWords
    ) {}
}
