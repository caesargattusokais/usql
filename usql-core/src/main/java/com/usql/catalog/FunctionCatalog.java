package com.usql.catalog;

import com.usql.dialect.Dialect;
import com.usql.ir.DataType;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Central function registry.
 * Maps U-SQL function names to dialect-specific SQL generation rules.
 *
 * Loaded from functions.yaml (bundled) and optionally from external files.
 */
public class FunctionCatalog {

    private final Map<String, FunctionDef> functions = new LinkedHashMap<>();

    public FunctionCatalog() {
        registerCoreFunctions();
    }

    /**
     * Get a function definition by its U-SQL name.
     */
    public Optional<FunctionDef> get(String usqlName) {
        return Optional.ofNullable(functions.get(usqlName.toUpperCase()));
    }

    /**
     * Does the catalog know about this function?
     */
    public boolean contains(String usqlName) {
        return functions.containsKey(usqlName.toUpperCase());
    }

    /**
     * Get all registered U-SQL function names.
     */
    public Set<String> functionNames() {
        return Collections.unmodifiableSet(functions.keySet());
    }

    // ══════════════════════════════════════════════════
    //  Function definition
    // ══════════════════════════════════════════════════

    public static class FunctionDef {
        public final String uSqlName;
        public final String description;
        public final DataType returnType;           // null = depends on args
        public final Map<Dialect, DialectMapping> dialectMappings;
        public final PolyfillConfig polyfill;

        public FunctionDef(String uSqlName, String description, DataType returnType,
                           Map<Dialect, DialectMapping> dialectMappings, PolyfillConfig polyfill) {
            this.uSqlName = uSqlName;
            this.description = description;
            this.returnType = returnType;
            this.dialectMappings = Map.copyOf(dialectMappings);
            this.polyfill = polyfill;
        }

        /** Get the render function for a specific dialect */
        public Optional<DialectMapping> forDialect(Dialect dialect) {
            return Optional.ofNullable(dialectMappings.get(dialect));
        }
    }

    /**
     * How a function maps to a specific dialect.
     */
    public record DialectMapping(
        String nativeName,
        String renderTemplate,         // "CONCAT({0}, {1})" — positional args
        boolean argsReordered,         // true if arg order differs from U-SQL
        int[] argOrder                 // new order: [1, 0] means swap args
    ) {}

    /**
     * Polyfill configuration when a dialect lacks native support.
     */
    public record PolyfillConfig(
        PolyfillStrategy strategy,
        String template                 // SQL template with placeholders
    ) {}

    public enum PolyfillStrategy {
        /** Can be expressed as a simple SQL expression */
        EXPRESSION,
        /** Needs subquery wrapping */
        SUBQUERY,
        /** Not polyfillable — must error */
        NOT_SUPPORTED
    }

    // ── Builder helpers ──

    private static DialectMapping dm(String nativeName) {
        return new DialectMapping(nativeName, null, false, null);
    }

    /** Map where all 4 dialects use the same native function name */
    private static Map<Dialect, DialectMapping> allSame(String name) {
        Map<Dialect, DialectMapping> m = new EnumMap<>(Dialect.class);
        for (Dialect d : Dialect.values()) {
            if (d == Dialect.H2) continue;
            m.put(d, dm(name));
        }
        return m;
    }

    /** Map with per-dialect overrides */
    private static Map<Dialect, DialectMapping> dialectMap(
        String mysql, String pg, String oracle, String dm) {
        Map<Dialect, DialectMapping> m = new EnumMap<>(Dialect.class);
        m.put(Dialect.MYSQL, dm(mysql));
        m.put(Dialect.POSTGRESQL, dm(pg));
        m.put(Dialect.ORACLE, dm(oracle));
        m.put(Dialect.DM, dm(dm));
        return m;
    }

    /** Quick register: same name everywhere, no polyfill */
    private void reg(String uSqlName, String description, DataType returnType) {
        functions.put(uSqlName, new FunctionDef(uSqlName, description, returnType,
            allSame(uSqlName), null));
    }

    /** Quick register: same name everywhere, with polyfill */
    private void reg(String uSqlName, String description, DataType returnType, PolyfillConfig pf) {
        functions.put(uSqlName, new FunctionDef(uSqlName, description, returnType,
            allSame(uSqlName), pf));
    }

    /** Register with per-dialect names */
    private void regDialect(String uSqlName, String description, DataType returnType,
                            String mysql, String pg, String oracle, String dm) {
        functions.put(uSqlName, new FunctionDef(uSqlName, description, returnType,
            dialectMap(mysql, pg, oracle, dm), null));
    }

    /** Register with per-dialect names + polyfill */
    private void regDialect(String uSqlName, String description, DataType returnType,
                            String mysql, String pg, String oracle, String dm, PolyfillConfig pf) {
        functions.put(uSqlName, new FunctionDef(uSqlName, description, returnType,
            dialectMap(mysql, pg, oracle, dm), pf));
    }

    // ══════════════════════════════════════════════════
    //  Core function registration (35 functions)
    // ══════════════════════════════════════════════════

    private void registerCoreFunctions() {

        // ── String functions ──

        reg("LENGTH", "String length", DataType.IntType.INT);
        reg("UPPER", "Convert to uppercase", new DataType.VarcharType(0));
        reg("LOWER", "Convert to lowercase", new DataType.VarcharType(0));
        reg("TRIM", "Remove leading and trailing spaces", new DataType.VarcharType(0));

        regDialect("SUBSTR", "Extract substring", new DataType.VarcharType(0),
            "SUBSTR", "SUBSTR", "SUBSTR", "SUBSTR");

        reg("REPLACE", "Replace occurrences of substring", new DataType.VarcharType(0));

        regDialect("CONCAT", "Concatenate strings", new DataType.VarcharType(0),
            "CONCAT", "CONCAT", "CONCAT", "CONCAT",
            new PolyfillConfig(PolyfillStrategy.EXPRESSION,
                "COALESCE({0},'') || COALESCE({1},'')"));

        // INSTR: PG uses POSITION(sub IN str) — different syntax
        {
            Map<Dialect, DialectMapping> m = new EnumMap<>(Dialect.class);
            m.put(Dialect.MYSQL, new DialectMapping("INSTR", "INSTR({0}, {1})", false, null));
            m.put(Dialect.POSTGRESQL, new DialectMapping("POSITION", "POSITION({1} IN {0})", false, null));
            m.put(Dialect.ORACLE, dm("INSTR"));
            m.put(Dialect.DM, dm("INSTR"));
            functions.put("INSTR", new FunctionDef("INSTR", "Position of substring in string",
                DataType.IntType.INT, m,
                new PolyfillConfig(PolyfillStrategy.EXPRESSION, "POSITION({1} IN {0})")));
        }

        // LEFT: Oracle doesn't have it, polyfill with SUBSTR
        {
            Map<Dialect, DialectMapping> m = new EnumMap<>(Dialect.class);
            m.put(Dialect.MYSQL, dm("LEFT"));
            m.put(Dialect.POSTGRESQL, dm("LEFT"));
            m.put(Dialect.ORACLE, new DialectMapping("SUBSTR", "SUBSTR({0}, 1, {1})", false, null));
            m.put(Dialect.DM, dm("LEFT"));
            functions.put("LEFT", new FunctionDef("LEFT", "Leftmost n characters",
                new DataType.VarcharType(0), m,
                new PolyfillConfig(PolyfillStrategy.EXPRESSION, "SUBSTR({0}, 1, {1})")));
        }

        // RIGHT: Oracle doesn't have it, polyfill with SUBSTR
        {
            Map<Dialect, DialectMapping> m = new EnumMap<>(Dialect.class);
            m.put(Dialect.MYSQL, dm("RIGHT"));
            m.put(Dialect.POSTGRESQL, dm("RIGHT"));
            m.put(Dialect.ORACLE, new DialectMapping("SUBSTR", "SUBSTR({0}, LENGTH({0}) - {1} + 1)", false, null));
            m.put(Dialect.DM, dm("RIGHT"));
            functions.put("RIGHT", new FunctionDef("RIGHT", "Rightmost n characters",
                new DataType.VarcharType(0), m,
                new PolyfillConfig(PolyfillStrategy.EXPRESSION, "SUBSTR({0}, LENGTH({0}) - {1} + 1)")));
        }

        {
            Map<Dialect, DialectMapping> m = new EnumMap<>(Dialect.class);
            m.put(Dialect.MYSQL, dm("CONCAT_WS"));
            m.put(Dialect.POSTGRESQL, dm("CONCAT_WS"));
            m.put(Dialect.ORACLE, dm("CONCAT_WS"));
            m.put(Dialect.DM, dm("CONCAT_WS"));
            functions.put("CONCAT_WS", new FunctionDef("CONCAT_WS", "Concat with separator",
                new DataType.VarcharType(0), m,
                new PolyfillConfig(PolyfillStrategy.EXPRESSION,
                    "RTRIM(LISTAGG({1}, {0}) WITHIN GROUP (ORDER BY {1}), {0})")));
        }

        // ── Numeric functions ──

        reg("ABS", "Absolute value", DataType.IntType.INT);
        reg("ROUND", "Round to n decimal places", DataType.IntType.INT);

        regDialect("CEIL", "Ceiling (round up)", DataType.IntType.INT,
            "CEIL", "CEIL", "CEIL", "CEIL");

        regDialect("CEILING", "Ceiling (round up)", DataType.IntType.INT,
            "CEILING", "CEILING", "CEIL", "CEIL");

        reg("FLOOR", "Floor (round down)", DataType.IntType.INT);

        regDialect("MOD", "Modulo (remainder)", DataType.IntType.INT,
            "MOD", "MOD", "MOD", "MOD",
            new PolyfillConfig(PolyfillStrategy.EXPRESSION,
                "({0} - {1} * FLOOR({0} / {1}))"));

        reg("POWER", "Raise to power", DataType.FloatType.DOUBLE);
        reg("SQRT", "Square root", DataType.FloatType.DOUBLE);
        reg("SIGN", "Sign of number (-1, 0, 1)", DataType.IntType.INT);

        // ── Date/Time functions ──

        regDialect("CURRENT_TIMESTAMP", "Current date and time", new DataType.DatetimeType(3),
            "NOW", "NOW", "SYSDATE", "SYSDATE");

        regDialect("CURRENT_DATE", "Current date", new DataType.DateType(),
            "CURDATE", "CURRENT_DATE", "TRUNC(SYSDATE)", "CURDATE");

        regDialect("CURRENT_TIME", "Current time", new DataType.TimeType(0),
            "CURTIME", "CURRENT_TIME", "SYSTIMESTAMP", "CURTIME");

        reg("EXTRACT", "Extract date part (YEAR, MONTH, DAY, etc.)", DataType.IntType.INT);

        regDialect("DATE_FORMAT", "Format date to string", new DataType.VarcharType(0),
            "DATE_FORMAT", "TO_CHAR", "TO_CHAR", "TO_CHAR");

        regDialect("DATE_ADD", "Add interval to date", new DataType.DatetimeType(0),
            "DATE_ADD", "DATE_ADD", "DATE_ADD", "DATE_ADD");

        regDialect("DATE_DIFF", "Difference between two dates", DataType.IntType.BIGINT,
            "TIMESTAMPDIFF", "DATE_DIFF", "MONTHS_BETWEEN", "DATEDIFF");

        // ── Conditional / NULL handling ──

        reg("COALESCE", "Return first non-null value", null,
            new PolyfillConfig(PolyfillStrategy.EXPRESSION,
                "CASE WHEN {0} IS NOT NULL THEN {0} WHEN {1} IS NOT NULL THEN {1} ELSE NULL END"));

        reg("NULLIF", "Return NULL if args are equal", null,
            new PolyfillConfig(PolyfillStrategy.EXPRESSION,
                "CASE WHEN {0} = {1} THEN NULL ELSE {0} END"));

        regDialect("NVL", "Replace NULL with default value", null,
            "IFNULL", "COALESCE", "NVL", "NVL",
            new PolyfillConfig(PolyfillStrategy.EXPRESSION,
                "COALESCE({0}, {1})"));

        regDialect("IFNULL", "Replace NULL with default value", null,
            "IFNULL", "COALESCE", "NVL", "NVL",
            new PolyfillConfig(PolyfillStrategy.EXPRESSION,
                "COALESCE({0}, {1})"));

        regDialect("GREATEST", "Largest of values", null,
            "GREATEST", "GREATEST", "GREATEST", "GREATEST",
            new PolyfillConfig(PolyfillStrategy.EXPRESSION,
                "CASE WHEN {0} > {1} THEN {0} ELSE {1} END"));

        regDialect("LEAST", "Smallest of values", null,
            "LEAST", "LEAST", "LEAST", "LEAST",
            new PolyfillConfig(PolyfillStrategy.EXPRESSION,
                "CASE WHEN {0} < {1} THEN {0} ELSE {1} END"));

        // ── Aggregate functions ──

        reg("COUNT", "Row count", DataType.IntType.BIGINT);
        reg("SUM", "Sum of values", null);
        reg("AVG", "Average of values", null);
        reg("MIN", "Minimum value", null);
        reg("MAX", "Maximum value", null);

        // ── More string functions ──
        reg("LTRIM", "Remove leading spaces", new DataType.VarcharType(0));
        reg("RTRIM", "Remove trailing spaces", new DataType.VarcharType(0));
        regDialect("CHAR_LENGTH", "Character length", DataType.IntType.INT,
            "CHAR_LENGTH", "CHAR_LENGTH", "LENGTH", "CHAR_LENGTH");
        {
            Map<Dialect, DialectMapping> m = new EnumMap<>(Dialect.class);
            m.put(Dialect.MYSQL, dm("REPEAT"));
            m.put(Dialect.POSTGRESQL, dm("REPEAT"));
            m.put(Dialect.ORACLE, new DialectMapping("RPAD", "RPAD({0}, LENGTH({0}) * {1}, {0})", false, null));
            m.put(Dialect.DM, dm("REPEAT"));
            functions.put("REPEAT", new FunctionDef("REPEAT", "Repeat string n times",
                new DataType.VarcharType(0), m,
                new PolyfillConfig(PolyfillStrategy.EXPRESSION, "RPAD({0}, LENGTH({0}) * {1}, {0})")));
        }
        reg("REVERSE", "Reverse string", new DataType.VarcharType(0));
        regDialect("LPAD", "Left pad string", new DataType.VarcharType(0),
            "LPAD", "LPAD", "LPAD", "LPAD");
        regDialect("RPAD", "Right pad string", new DataType.VarcharType(0),
            "RPAD", "RPAD", "RPAD", "RPAD");
        regDialect("ASCII", "ASCII code of first char", DataType.IntType.INT,
            "ASCII", "ASCII", "ASCII", "ASCII");
        regDialect("LOCATE", "Locate substring position", DataType.IntType.INT,
            "LOCATE", "POSITION", "INSTR", "INSTR");
        regDialect("TRANSLATE", "Character translation", new DataType.VarcharType(0),
            "TRANSLATE", "TRANSLATE", "TRANSLATE", "TRANSLATE");

        // ── More numeric functions ──
        reg("EXP", "e raised to power n", DataType.FloatType.DOUBLE);
        regDialect("LN", "Natural logarithm", DataType.FloatType.DOUBLE,
            "LN", "LN", "LN", "LN");
        regDialect("LOG", "Logarithm base x of y", DataType.FloatType.DOUBLE,
            "LOG", "LOG", "LOG", "LOG");
        regDialect("TRUNC", "Truncate to n decimals", DataType.FloatType.DOUBLE,
            "TRUNCATE", "TRUNC", "TRUNC", "TRUNC");
        regDialect("RAND", "Random number 0-1", DataType.FloatType.DOUBLE,
            "RAND", "RANDOM", "DBMS_RANDOM.VALUE", "RAND");
        reg("COS", "Cosine", DataType.FloatType.DOUBLE);
        reg("SIN", "Sine", DataType.FloatType.DOUBLE);
        reg("TAN", "Tangent", DataType.FloatType.DOUBLE);
        reg("ACOS", "Arc cosine", DataType.FloatType.DOUBLE);
        reg("ASIN", "Arc sine", DataType.FloatType.DOUBLE);
        reg("ATAN", "Arc tangent", DataType.FloatType.DOUBLE);
        regDialect("ATAN2", "Arc tangent of y/x", DataType.FloatType.DOUBLE,
            "ATAN2", "ATAN2", "ATAN2", "ATAN2");
        {
            Map<Dialect, DialectMapping> m = new EnumMap<>(Dialect.class);
            m.put(Dialect.MYSQL, dm("PI"));
            m.put(Dialect.POSTGRESQL, dm("PI"));
            m.put(Dialect.ORACLE, new DialectMapping("ACOS", "ACOS(-1)", false, null));
            m.put(Dialect.DM, dm("PI"));
            functions.put("PI", new FunctionDef("PI", "Mathematical constant pi",
                DataType.FloatType.DOUBLE, m,
                new PolyfillConfig(PolyfillStrategy.EXPRESSION, "ACOS(-1)")));
        }
        regDialect("DEGREES", "Radians to degrees", DataType.FloatType.DOUBLE,
            "DEGREES", "DEGREES", "DEGREES", "DEGREES");
        regDialect("RADIANS", "Degrees to radians", DataType.FloatType.DOUBLE,
            "RADIANS", "RADIANS", "RADIANS", "RADIANS");
        regDialect("STDDEV", "Population standard deviation", DataType.FloatType.DOUBLE,
            "STDDEV", "STDDEV", "STDDEV", "STDDEV");
        regDialect("VARIANCE", "Population variance", DataType.FloatType.DOUBLE,
            "VARIANCE", "VARIANCE", "VARIANCE", "VARIANCE");

        // ── More date/time functions ──
        regDialect("DATE_SUB", "Subtract interval from date", new DataType.DatetimeType(0),
            "DATE_SUB", "DATE_SUB", "DATE_SUB", "DATE_SUB");
        regDialect("DAY", "Day of month", DataType.IntType.INT,
            "DAY", "EXTRACT(DAY FROM TIMESTAMP", "EXTRACT(DAY FROM", "DAY");
        regDialect("MONTH", "Month number", DataType.IntType.INT,
            "MONTH", "EXTRACT(MONTH FROM TIMESTAMP", "EXTRACT(MONTH FROM", "MONTH");
        regDialect("YEAR", "Year number", DataType.IntType.INT,
            "YEAR", "EXTRACT(YEAR FROM TIMESTAMP", "EXTRACT(YEAR FROM", "YEAR");
        regDialect("QUARTER", "Quarter (1-4)", DataType.IntType.INT,
            "QUARTER", "EXTRACT(QUARTER FROM TIMESTAMP", "EXTRACT(QUARTER FROM", "QUARTER");
        regDialect("HOUR", "Hour from time", DataType.IntType.INT,
            "HOUR", "EXTRACT(HOUR FROM TIMESTAMP", "EXTRACT(HOUR FROM", "HOUR");
        regDialect("MINUTE", "Minute from time", DataType.IntType.INT,
            "MINUTE", "EXTRACT(MINUTE FROM TIMESTAMP", "EXTRACT(MINUTE FROM", "MINUTE");
        regDialect("SECOND", "Second from time", DataType.IntType.INT,
            "SECOND", "EXTRACT(SECOND FROM TIMESTAMP", "EXTRACT(SECOND FROM", "SECOND");
        regDialect("DATEDIFF", "Alias for DATE_DIFF", DataType.IntType.BIGINT,
            "DATEDIFF", "DATEDIFF", "MONTHS_BETWEEN", "DATEDIFF");
        regDialect("DAYOFWEEK", "Day of week (1=Sunday)", DataType.IntType.INT,
            "DAYOFWEEK", "EXTRACT(DOW FROM TIMESTAMP", "TO_CHAR", "DAYOFWEEK");
        regDialect("DAYOFMONTH", "Day of month", DataType.IntType.INT,
            "DAYOFMONTH", "EXTRACT(DAY FROM TIMESTAMP", "EXTRACT(DAY FROM", "DAY");
        regDialect("DAYOFYEAR", "Day of year", DataType.IntType.INT,
            "DAYOFYEAR", "EXTRACT(DOY FROM TIMESTAMP", "TO_CHAR", "DAYOFYEAR");
        regDialect("WEEK", "Week number", DataType.IntType.INT,
            "WEEK", "EXTRACT(WEEK FROM TIMESTAMP", "TO_CHAR", "WEEK");
        regDialect("LAST_DAY", "Last day of month", new DataType.DateType(),
            "LAST_DAY", "LAST_DAY", "LAST_DAY", "LAST_DAY");
        regDialect("TO_DATE", "Convert string to date", new DataType.DateType(),
            "STR_TO_DATE", "TO_DATE", "TO_DATE", "TO_DATE");
        regDialect("UNIX_TIMESTAMP", "Seconds since 1970-01-01", DataType.IntType.BIGINT,
            "UNIX_TIMESTAMP", "EXTRACT(EPOCH FROM NOW())", "UNIX_TIMESTAMP", "UNIX_TIMESTAMP");

        // ── Conditional functions ──
        regDialect("IF", "IF(cond, trueVal, falseVal)", null,
            "IF", "CASE WHEN", "CASE WHEN", "IF");
        {
            Map<Dialect, DialectMapping> m = new EnumMap<>(Dialect.class);
            m.put(Dialect.MYSQL, dm("ISNULL"));
            m.put(Dialect.POSTGRESQL, new DialectMapping("ISNULL", "CASE WHEN {0} IS NULL THEN TRUE ELSE FALSE END", false, null));
            m.put(Dialect.ORACLE, new DialectMapping("CASE", "CASE WHEN {0} IS NULL THEN 1 ELSE 0 END", false, null));
            m.put(Dialect.DM, dm("ISNULL"));
            functions.put("ISNULL", new FunctionDef("ISNULL", "Check if value is NULL",
                new DataType.BooleanType(), m,
                new PolyfillConfig(PolyfillStrategy.EXPRESSION, "CASE WHEN {0} IS NULL THEN 1 ELSE 0 END")));
        }

        // ── Group aggregate ──
        regDialect("GROUP_CONCAT", "Concatenate group values", new DataType.VarcharType(0),
            "GROUP_CONCAT", "STRING_AGG", "LISTAGG", "LISTAGG");
        regDialect("STRING_AGG", "String aggregation with delimiter", new DataType.VarcharType(0),
            "GROUP_CONCAT", "STRING_AGG", "LISTAGG", "LISTAGG");
        {
            Map<Dialect, DialectMapping> m = new EnumMap<>(Dialect.class);
            m.put(Dialect.MYSQL, dm("MEDIAN")); // MySQL 8.0+ has MEDIAN via community extension
            m.put(Dialect.POSTGRESQL, new DialectMapping("PERCENTILE_CONT", "PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY {0})", false, null));
            m.put(Dialect.ORACLE, dm("MEDIAN"));
            m.put(Dialect.DM, dm("MEDIAN"));
            functions.put("MEDIAN", new FunctionDef("MEDIAN", "Median value",
                DataType.FloatType.DOUBLE, m, null));
        }

        // ── Window functions ──
        reg("ROW_NUMBER", "Row number in window", DataType.IntType.BIGINT);
        reg("RANK", "Rank in window (with gaps)", DataType.IntType.BIGINT);
        reg("DENSE_RANK", "Dense rank in window", DataType.IntType.BIGINT);
        reg("LAG", "Value from previous row", null);
        reg("LEAD", "Value from next row", null);
        reg("FIRST_VALUE", "First value in window", null);
        reg("LAST_VALUE", "Last value in window", null);
        reg("NTILE", "Bucket number (1-n)", DataType.IntType.INT);
        reg("PERCENT_RANK", "Percentile rank", DataType.FloatType.DOUBLE);
        reg("CUME_DIST", "Cumulative distribution", DataType.FloatType.DOUBLE);

        // ── More utility functions ──
        regDialect("MD5", "MD5 hash", new DataType.VarcharType(32),
            "MD5", "MD5", "DBMS_CRYPTO.HASH", "MD5");
        regDialect("RANDOM", "Random number", DataType.FloatType.DOUBLE,
            "RAND", "RANDOM", "DBMS_RANDOM.VALUE", "RAND");
        regDialect("NOW", "Alias for CURRENT_TIMESTAMP", new DataType.DatetimeType(3),
            "NOW", "NOW", "SYSDATE", "SYSDATE");
        regDialect("CURDATE", "Alias for CURRENT_DATE", new DataType.DateType(),
            "CURDATE", "CURRENT_DATE", "TRUNC(SYSDATE)", "CURDATE");
        regDialect("SYSDATE", "Server date+time", new DataType.DatetimeType(0),
            "NOW", "NOW", "SYSDATE", "SYSDATE");
        regDialect("IFNULL", "MySQL alias for NVL", null,
            "IFNULL", "COALESCE", "NVL", "NVL");
        reg("CHAR", "Character from ASCII code", new DataType.VarcharType(1));
        {
            Map<Dialect, DialectMapping> m = new EnumMap<>(Dialect.class);
            m.put(Dialect.MYSQL, dm("SPACE"));
            m.put(Dialect.POSTGRESQL, new DialectMapping("REPEAT", "REPEAT(' ', {0})", false, null));
            m.put(Dialect.ORACLE, new DialectMapping("RPAD", "RPAD(' ', {0}, ' ')", false, null));
            m.put(Dialect.DM, dm("SPACE"));
            functions.put("SPACE", new FunctionDef("SPACE", "String of n spaces",
                new DataType.VarcharType(0), m,
                new PolyfillConfig(PolyfillStrategy.EXPRESSION, "REPEAT(' ', {0})")));
        }
        {
            Map<Dialect, DialectMapping> m = new EnumMap<>(Dialect.class);
            m.put(Dialect.MYSQL, new DialectMapping("INITCAP", "CONCAT(UPPER(LEFT({0},1)), LOWER(SUBSTR({0},2)))", false, null));
            m.put(Dialect.POSTGRESQL, dm("INITCAP"));
            m.put(Dialect.ORACLE, dm("INITCAP"));
            m.put(Dialect.DM, dm("INITCAP"));
            functions.put("INITCAP", new FunctionDef("INITCAP", "Capitalize first letter",
                new DataType.VarcharType(0), m,
                new PolyfillConfig(PolyfillStrategy.EXPRESSION, "CONCAT(UPPER(LEFT({0},1)), LOWER(SUBSTR({0},2)))")));
        }
        regDialect("NVL2", "NVL2(expr, notNull, isNull)", null,
            "IF", "CASE WHEN", "NVL2", "NVL2");
    }
}
