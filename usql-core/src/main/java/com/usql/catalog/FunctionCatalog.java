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

        regDialect("INSTR", "Position of substring in string", DataType.IntType.INT,
            "INSTR", "POSITION", "INSTR", "INSTR");

        regDialect("LEFT", "Leftmost n characters", new DataType.VarcharType(0),
            "LEFT", "LEFT", "LEFT", "LEFT",
            new PolyfillConfig(PolyfillStrategy.EXPRESSION,
                "SUBSTR({0}, 1, {1})"));

        regDialect("RIGHT", "Rightmost n characters", new DataType.VarcharType(0),
            "RIGHT", "RIGHT", "RIGHT", "RIGHT",
            new PolyfillConfig(PolyfillStrategy.EXPRESSION,
                "SUBSTR({0}, LENGTH({0}) - {1} + 1)"));

        regDialect("CONCAT_WS", "Concatenate with separator", new DataType.VarcharType(0),
            "CONCAT_WS", "CONCAT_WS", "CONCAT_WS", "CONCAT_WS");

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
        reg("SUM", "Sum of values", null);  // return type depends on argument
        reg("AVG", "Average of values", null);
        reg("MIN", "Minimum value", null);
        reg("MAX", "Maximum value", null);
    }
}
