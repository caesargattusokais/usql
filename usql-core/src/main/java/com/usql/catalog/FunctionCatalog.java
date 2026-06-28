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

    private static DialectMapping dm(String nativeName, String template) {
        return new DialectMapping(nativeName, template, false, null);
    }

    private static DialectMapping dm(String nativeName) {
        return new DialectMapping(nativeName, null, false, null);
    }

    // ══════════════════════════════════════════════════
    //  Core function registration
    // ══════════════════════════════════════════════════

    private void registerCoreFunctions() {
        Map<Dialect, DialectMapping> concatMap = new EnumMap<>(Dialect.class);
        concatMap.put(Dialect.MYSQL,      dm("CONCAT"));
        concatMap.put(Dialect.POSTGRESQL, dm("CONCAT"));
        concatMap.put(Dialect.ORACLE,     dm("CONCAT"));
        concatMap.put(Dialect.DM,         dm("CONCAT"));

        functions.put("STRING_CONCAT", new FunctionDef(
            "STRING_CONCAT", "Concatenate strings",
            new DataType.VarcharType(0),   // length depends on args
            concatMap,
            new PolyfillConfig(PolyfillStrategy.EXPRESSION, "COALESCE({0},'') || COALESCE({1},'')")
        ));

        // ── DATE_ADD ──
        Map<Dialect, DialectMapping> dateAddMap = new EnumMap<>(Dialect.class);
        dateAddMap.put(Dialect.MYSQL,      dm("DATE_ADD"));
        dateAddMap.put(Dialect.POSTGRESQL, dm("DATE_ADD"));
        dateAddMap.put(Dialect.ORACLE,     dm("DATE_ADD"));
        dateAddMap.put(Dialect.DM,         dm("DATE_ADD"));

        functions.put("DATE_ADD", new FunctionDef(
            "DATE_ADD", "Add an interval to a date",
            new DataType.DatetimeType(0),
            dateAddMap,
            null  // not easily polyfillable — use backend-specific logic
        ));

        // ── DATE_DIFF ──
        Map<Dialect, DialectMapping> dateDiffMap = new EnumMap<>(Dialect.class);
        dateDiffMap.put(Dialect.MYSQL,      dm("TIMESTAMPDIFF"));
        dateDiffMap.put(Dialect.POSTGRESQL, dm("DATE_DIFF"));
        dateDiffMap.put(Dialect.DM,         dm("DATEDIFF"));

        functions.put("DATE_DIFF", new FunctionDef(
            "DATE_DIFF", "Compute difference between two dates",
            new DataType.IntType(64),
            dateDiffMap,
            new PolyfillConfig(PolyfillStrategy.EXPRESSION,
                "EXTRACT(epoch FROM {0}::timestamp - {1}::timestamp) / CASE {2} " +
                "WHEN 'DAY' THEN 86400 WHEN 'HOUR' THEN 3600 WHEN 'MINUTE' THEN 60 ELSE 1 END")
        ));

        // ── COALESCE ──
        Map<Dialect, DialectMapping> coalesceMap = new EnumMap<>(Dialect.class);
        coalesceMap.put(Dialect.MYSQL,      dm("COALESCE"));
        coalesceMap.put(Dialect.POSTGRESQL, dm("COALESCE"));
        coalesceMap.put(Dialect.ORACLE,     dm("COALESCE")); // NVL is less general but COALESCE works
        coalesceMap.put(Dialect.DM,         dm("COALESCE"));

        functions.put("COALESCE", new FunctionDef(
            "COALESCE", "Return the first non-null value",
            null,  // depends on args
            coalesceMap,
            new PolyfillConfig(PolyfillStrategy.EXPRESSION,
                "CASE WHEN {0} IS NOT NULL THEN {0} WHEN {1} IS NOT NULL THEN {1} ELSE NULL END")
        ));

        // ── NULLIF ──
        Map<Dialect, DialectMapping> nullifMap = new EnumMap<>(Dialect.class);
        nullifMap.put(Dialect.MYSQL,      dm("NULLIF"));
        nullifMap.put(Dialect.POSTGRESQL, dm("NULLIF"));
        nullifMap.put(Dialect.ORACLE,     dm("NULLIF"));
        nullifMap.put(Dialect.DM,         dm("NULLIF"));

        functions.put("NULLIF", new FunctionDef(
            "NULLIF", "Return NULL if the two arguments are equal",
            null,
            nullifMap,
            new PolyfillConfig(PolyfillStrategy.EXPRESSION,
                "CASE WHEN {0} = {1} THEN NULL ELSE {0} END")
        ));

        // ── NOW ──
        Map<Dialect, DialectMapping> nowMap = new EnumMap<>(Dialect.class);
        nowMap.put(Dialect.MYSQL,      dm("NOW"));
        nowMap.put(Dialect.POSTGRESQL, dm("NOW"));
        nowMap.put(Dialect.ORACLE,     dm("SYSDATE"));
        nowMap.put(Dialect.DM,         dm("SYSDATE"));

        functions.put("CURRENT_TIMESTAMP", new FunctionDef(
            "CURRENT_TIMESTAMP", "Current date and time",
            new DataType.DatetimeType(3),
            nowMap,
            null
        ));
    }

    // ── TODO: Add remaining ~30 core functions ──
    // LENGTH, UPPER, LOWER, TRIM, SUBSTR, REPLACE,
    // ABS, ROUND, CEIL, FLOOR, MOD, POWER, SQRT,
    // GREATEST, LEAST,
    // CAST,
    // COUNT, SUM, AVG, MIN, MAX,
    // ROW_NUMBER, RANK, DENSE_RANK, LAG, LEAD
}
