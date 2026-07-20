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
     * Handles parameterized types like VARCHAR(255), DECIMAL(10,2), NUMBER(10), etc.
     */
    public Optional<DataType> fromNative(String nativeType, Dialect dialect) {
        Map<String, DataType> mapping = dialectToUsql.get(dialect);
        if (mapping == null) return Optional.empty();

        String upper = nativeType.toUpperCase().trim();

        // Exact match first (e.g. "TINYINT(1)", "DOUBLE PRECISION")
        DataType exact = mapping.get(upper);
        if (exact != null) return Optional.of(exact);

        // Strip parameters and try base type (e.g. "VARCHAR(255)" → "VARCHAR")
        int paren = upper.indexOf('(');
        if (paren > 0) {
            String base = upper.substring(0, paren).trim();
            DataType baseMatch = mapping.get(base);
            if (baseMatch != null) {
                // For parameterized types, create a new instance with the parameters
                return Optional.of(withParameters(baseMatch, upper.substring(paren + 1, upper.length() - 1)));
            }
        }

        return Optional.empty();
    }

    /** Create a parameterized version of a base DataType from the parenthesized parameters. */
    private static DataType withParameters(DataType base, String params) {
        String[] parts = params.split(",");
        try {
            if (base instanceof DataType.VarcharType && parts.length >= 1) {
                return new DataType.VarcharType(Integer.parseInt(parts[0].trim()));
            }
            if (base instanceof DataType.CharType && parts.length >= 1) {
                return new DataType.CharType(Integer.parseInt(parts[0].trim()));
            }
            if (base instanceof DataType.DecimalType && parts.length >= 1) {
                int precision = Integer.parseInt(parts[0].trim());
                int scale = parts.length >= 2 ? Integer.parseInt(parts[1].trim()) : 0;
                return new DataType.DecimalType(precision, scale);
            }
            if (base instanceof DataType.IntType && parts.length >= 1) {
                // NUMBER(10) → IntType with matching bits
                int precision = Integer.parseInt(parts[0].trim());
                if (precision <= 3) return DataType.IntType.TINYINT;
                if (precision <= 5) return DataType.IntType.SMALLINT;
                if (precision <= 10) return DataType.IntType.INT;
                return DataType.IntType.BIGINT;
            }
            if (base instanceof DataType.BinaryType && parts.length >= 1) {
                return new DataType.BinaryType(Integer.parseInt(parts[0].trim()));
            }
            if (base instanceof DataType.VarbinaryType && parts.length >= 1) {
                return new DataType.VarbinaryType(Integer.parseInt(parts[0].trim()));
            }
        } catch (NumberFormatException ignored) {
            // Fall through to base type
        }
        return base;
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
        registerSqlServer();
        registerMariaDB();
        registerSQLite();
        registerDuckDB();
        registerClickHouse();
        // TiDB/OceanBase reuse MySQL mapping
        registerTidbAndOceanBase();
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

    private void registerSqlServer() {
        var native2usql = new java.util.HashMap<String, DataType>();
        var usql2native = new java.util.HashMap<Class<? extends DataType>, String>();

        native2usql.put("TINYINT",       DataType.IntType.TINYINT);
        native2usql.put("SMALLINT",      DataType.IntType.SMALLINT);
        native2usql.put("INT",           DataType.IntType.INT);
        native2usql.put("BIGINT",        DataType.IntType.BIGINT);
        native2usql.put("REAL",          DataType.FloatType.FLOAT);
        native2usql.put("FLOAT",         DataType.FloatType.DOUBLE);
        native2usql.put("DECIMAL",       new DataType.DecimalType(10, 0));
        native2usql.put("NUMERIC",       new DataType.DecimalType(10, 0));
        native2usql.put("CHAR",          new DataType.CharType(1));
        native2usql.put("VARCHAR",       new DataType.VarcharType(255));
        native2usql.put("TEXT",          new DataType.TextType());
        native2usql.put("NTEXT",         new DataType.TextType());
        native2usql.put("NVARCHAR",      new DataType.VarcharType(255));
        native2usql.put("NCHAR",         new DataType.CharType(1));
        native2usql.put("BIT",           new DataType.BooleanType());
        native2usql.put("DATE",          new DataType.DateType());
        native2usql.put("TIME",          new DataType.TimeType(7));
        native2usql.put("DATETIME",      new DataType.DatetimeType(3));
        native2usql.put("DATETIME2",     new DataType.DatetimeType(7));
        native2usql.put("DATETIMEOFFSET", new DataType.TimestampType(7));
        native2usql.put("UNIQUEIDENTIFIER", new DataType.UuidType());
        native2usql.put("BINARY",        new DataType.BinaryType(1));
        native2usql.put("VARBINARY",     new DataType.VarbinaryType(255));
        native2usql.put("IMAGE",         new DataType.BlobType());

        usql2native.put(DataType.IntType.class,        "INT");
        usql2native.put(DataType.DecimalType.class,     "DECIMAL");
        usql2native.put(DataType.FloatType.class,       "FLOAT");
        usql2native.put(DataType.CharType.class,        "NCHAR");
        usql2native.put(DataType.VarcharType.class,     "NVARCHAR");
        usql2native.put(DataType.TextType.class,        "NVARCHAR(MAX)");
        usql2native.put(DataType.BooleanType.class,     "BIT");
        usql2native.put(DataType.DateType.class,        "DATE");
        usql2native.put(DataType.TimeType.class,        "TIME");
        usql2native.put(DataType.DatetimeType.class,    "DATETIME2");
        usql2native.put(DataType.TimestampType.class,   "DATETIMEOFFSET");
        usql2native.put(DataType.UuidType.class,        "UNIQUEIDENTIFIER");
        usql2native.put(DataType.BinaryType.class,      "BINARY");
        usql2native.put(DataType.VarbinaryType.class,   "VARBINARY");
        usql2native.put(DataType.BlobType.class,        "VARBINARY(MAX)");
        usql2native.put(DataType.JsonType.class,        "NVARCHAR(MAX)");

        dialectToUsql.put(Dialect.SQLSERVER, native2usql);
        usqlToNative.put(Dialect.SQLSERVER, usql2native);
    }

    private void registerMariaDB() {
        // MariaDB is MySQL-compatible — reuse MySQL mapping with MariaDB-specific additions
        var native2usql = new java.util.HashMap<String, DataType>();
        var usql2native = new java.util.HashMap<Class<? extends DataType>, String>();

        // Copy MySQL base types
        native2usql.put("TINYINT",   DataType.IntType.TINYINT);
        native2usql.put("SMALLINT",  DataType.IntType.SMALLINT);
        native2usql.put("INT",       DataType.IntType.INT);
        native2usql.put("INTEGER",   DataType.IntType.INT);
        native2usql.put("BIGINT",    DataType.IntType.BIGINT);
        native2usql.put("FLOAT",     DataType.FloatType.FLOAT);
        native2usql.put("DOUBLE",    DataType.FloatType.DOUBLE);
        native2usql.put("DECIMAL",   new DataType.DecimalType(10, 0));
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
        native2usql.put("BLOB",      new DataType.BlobType());
        native2usql.put("LONGBLOB",  new DataType.BlobType());
        native2usql.put("UUID",      new DataType.UuidType());

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
        usql2native.put(DataType.UuidType.class,        "UUID");
        usql2native.put(DataType.BinaryType.class,      "BINARY");
        usql2native.put(DataType.VarbinaryType.class,   "VARBINARY");
        usql2native.put(DataType.BlobType.class,        "LONGBLOB");

        dialectToUsql.put(Dialect.MARIADB, native2usql);
        usqlToNative.put(Dialect.MARIADB, usql2native);
    }

    private void registerSQLite() {
        var native2usql = new java.util.HashMap<String, DataType>();
        var usql2native = new java.util.HashMap<Class<? extends DataType>, String>();

        // SQLite uses dynamic typing; these are type affinities
        native2usql.put("INTEGER",   DataType.IntType.INT);
        native2usql.put("INT",       DataType.IntType.INT);
        native2usql.put("BIGINT",    DataType.IntType.BIGINT);
        native2usql.put("REAL",      DataType.FloatType.DOUBLE);
        native2usql.put("FLOAT",     DataType.FloatType.FLOAT);
        native2usql.put("DOUBLE",    DataType.FloatType.DOUBLE);
        native2usql.put("NUMERIC",   new DataType.DecimalType(10, 0));
        native2usql.put("TEXT",      new DataType.TextType());
        native2usql.put("VARCHAR",   new DataType.VarcharType(255));
        native2usql.put("CHAR",      new DataType.CharType(1));
        native2usql.put("BLOB",      new DataType.BlobType());
        native2usql.put("BOOLEAN",   new DataType.BooleanType());
        native2usql.put("DATE",      new DataType.DateType());
        native2usql.put("DATETIME",  new DataType.DatetimeType(0));

        usql2native.put(DataType.IntType.class,        "INTEGER");
        usql2native.put(DataType.DecimalType.class,     "REAL");
        usql2native.put(DataType.FloatType.class,       "REAL");
        usql2native.put(DataType.CharType.class,        "TEXT");
        usql2native.put(DataType.VarcharType.class,     "TEXT");
        usql2native.put(DataType.TextType.class,        "TEXT");
        usql2native.put(DataType.BooleanType.class,     "INTEGER");
        usql2native.put(DataType.DateType.class,        "TEXT");
        usql2native.put(DataType.TimeType.class,        "TEXT");
        usql2native.put(DataType.DatetimeType.class,    "TEXT");
        usql2native.put(DataType.TimestampType.class,   "TEXT");
        usql2native.put(DataType.BlobType.class,        "BLOB");
        usql2native.put(DataType.UuidType.class,        "TEXT");
        usql2native.put(DataType.JsonType.class,        "TEXT");

        dialectToUsql.put(Dialect.SQLITE, native2usql);
        usqlToNative.put(Dialect.SQLITE, usql2native);
    }

    private void registerDuckDB() {
        var native2usql = new java.util.HashMap<String, DataType>();
        var usql2native = new java.util.HashMap<Class<? extends DataType>, String>();

        // DuckDB is PG-compatible
        native2usql.put("SMALLINT",         DataType.IntType.SMALLINT);
        native2usql.put("INTEGER",          DataType.IntType.INT);
        native2usql.put("INT",              DataType.IntType.INT);
        native2usql.put("BIGINT",           DataType.IntType.BIGINT);
        native2usql.put("REAL",             DataType.FloatType.FLOAT);
        native2usql.put("DOUBLE",           DataType.FloatType.DOUBLE);
        native2usql.put("DECIMAL",          new DataType.DecimalType(10, 0));
        native2usql.put("CHAR",             new DataType.CharType(1));
        native2usql.put("VARCHAR",          new DataType.VarcharType(255));
        native2usql.put("TEXT",             new DataType.TextType());
        native2usql.put("BOOLEAN",          new DataType.BooleanType());
        native2usql.put("DATE",             new DataType.DateType());
        native2usql.put("TIME",             new DataType.TimeType(6));
        native2usql.put("TIMESTAMP",        new DataType.DatetimeType(6));
        native2usql.put("TIMESTAMPTZ",      new DataType.TimestampType(6));
        native2usql.put("JSON",             new DataType.JsonType());
        native2usql.put("UUID",             new DataType.UuidType());
        native2usql.put("BLOB",             new DataType.BlobType());

        usql2native.put(DataType.IntType.class,        "INTEGER");
        usql2native.put(DataType.DecimalType.class,     "DECIMAL");
        usql2native.put(DataType.FloatType.class,       "DOUBLE");
        usql2native.put(DataType.CharType.class,        "VARCHAR");
        usql2native.put(DataType.VarcharType.class,     "VARCHAR");
        usql2native.put(DataType.TextType.class,        "VARCHAR");
        usql2native.put(DataType.BooleanType.class,     "BOOLEAN");
        usql2native.put(DataType.DateType.class,        "DATE");
        usql2native.put(DataType.TimeType.class,        "TIME");
        usql2native.put(DataType.DatetimeType.class,    "TIMESTAMP");
        usql2native.put(DataType.TimestampType.class,   "TIMESTAMPTZ");
        usql2native.put(DataType.JsonType.class,        "JSON");
        usql2native.put(DataType.UuidType.class,        "UUID");
        usql2native.put(DataType.BinaryType.class,      "BLOB");
        usql2native.put(DataType.VarbinaryType.class,   "BLOB");
        usql2native.put(DataType.BlobType.class,        "BLOB");

        dialectToUsql.put(Dialect.DUCKDB, native2usql);
        usqlToNative.put(Dialect.DUCKDB, usql2native);
    }

    private void registerClickHouse() {
        var native2usql = new java.util.HashMap<String, DataType>();
        var usql2native = new java.util.HashMap<Class<? extends DataType>, String>();

        native2usql.put("Int8",        DataType.IntType.TINYINT);
        native2usql.put("Int16",       DataType.IntType.SMALLINT);
        native2usql.put("Int32",       DataType.IntType.INT);
        native2usql.put("Int64",       DataType.IntType.BIGINT);
        native2usql.put("UInt8",       DataType.IntType.TINYINT);
        native2usql.put("UInt16",      DataType.IntType.SMALLINT);
        native2usql.put("UInt32",      DataType.IntType.INT);
        native2usql.put("UInt64",      DataType.IntType.BIGINT);
        native2usql.put("Float32",     DataType.FloatType.FLOAT);
        native2usql.put("Float64",     DataType.FloatType.DOUBLE);
        native2usql.put("Decimal",     new DataType.DecimalType(10, 0));
        native2usql.put("String",      new DataType.VarcharType(255));
        native2usql.put("FixedString", new DataType.CharType(1));
        native2usql.put("Date",        new DataType.DateType());
        native2usql.put("DateTime",    new DataType.DatetimeType(0));
        native2usql.put("UUID",        new DataType.UuidType());
        native2usql.put("Nullable",    new DataType.NullType()); // wrapper

        usql2native.put(DataType.IntType.class,        "Int32");
        usql2native.put(DataType.DecimalType.class,     "Decimal");
        usql2native.put(DataType.FloatType.class,       "Float64");
        usql2native.put(DataType.CharType.class,        "FixedString");
        usql2native.put(DataType.VarcharType.class,     "String");
        usql2native.put(DataType.TextType.class,        "String");
        usql2native.put(DataType.BooleanType.class,     "UInt8");
        usql2native.put(DataType.DateType.class,        "Date");
        usql2native.put(DataType.TimeType.class,        "String");
        usql2native.put(DataType.DatetimeType.class,    "DateTime");
        usql2native.put(DataType.TimestampType.class,   "DateTime");
        usql2native.put(DataType.UuidType.class,        "UUID");
        usql2native.put(DataType.BlobType.class,        "String");
        usql2native.put(DataType.JsonType.class,        "String");

        dialectToUsql.put(Dialect.CLICKHOUSE, native2usql);
        usqlToNative.put(Dialect.CLICKHOUSE, usql2native);
    }

    private void registerTidbAndOceanBase() {
        // TiDB and OceanBase are MySQL-compatible — reuse MySQL mapping
        var mysqlNative = dialectToUsql.get(Dialect.MYSQL);
        var mysqlUsql = usqlToNative.get(Dialect.MYSQL);
        if (mysqlNative != null) {
            dialectToUsql.put(Dialect.TIDB, new java.util.HashMap<>(mysqlNative));
            dialectToUsql.put(Dialect.OCEANBASE, new java.util.HashMap<>(mysqlNative));
        }
        if (mysqlUsql != null) {
            usqlToNative.put(Dialect.TIDB, new java.util.HashMap<>(mysqlUsql));
            usqlToNative.put(Dialect.OCEANBASE, new java.util.HashMap<>(mysqlUsql));
        }
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
