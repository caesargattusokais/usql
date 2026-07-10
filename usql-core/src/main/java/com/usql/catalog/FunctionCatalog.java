package com.usql.catalog;

import com.usql.dialect.Dialect;
import com.usql.ir.DataType;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.*;

/**
 * Central function registry.
 * Maps U-SQL function names to dialect-specific SQL generation rules.
 *
 * Loaded from functions.yaml (bundled) and optionally from external files.
 */
public class FunctionCatalog {

    private final Map<String, FunctionDef> functions = new LinkedHashMap<>();

    public FunctionCatalog() {
        loadFromYaml();
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

    // ══════════════════════════════════════════════════
    //  YAML loading
    // ══════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private void loadFromYaml() {
        Yaml yaml = new Yaml();
        InputStream in = getClass().getClassLoader()
            .getResourceAsStream("functions.yaml");
        if (in == null) {
            throw new IllegalStateException("functions.yaml not found on classpath");
        }

        Map<String, Object> root = yaml.load(in);
        List<Map<String, Object>> list = (List<Map<String, Object>>) root.get("functions");
        if (list == null) {
            throw new IllegalStateException("functions.yaml missing 'functions' key");
        }

        for (Map<String, Object> entry : list) {
            FunctionDef def = parseFunction(entry);
            functions.put(def.uSqlName.toUpperCase(), def);
        }
    }

    private FunctionDef parseFunction(Map<String, Object> entry) {
        String name = (String) entry.get("name");
        String desc = (String) entry.get("desc");
        String returns = (String) entry.get("returns");
        Object dialects = entry.get("dialects");
        Map<String, Object> polyfill = cast(entry.get("polyfill"));

        if (name == null) throw new IllegalStateException(
            "functions.yaml: function entry missing required 'name' field");
        if (dialects == null) throw new IllegalStateException(
            "functions.yaml: function '" + name + "' missing required 'dialects' mapping");

        DataType returnType = returns != null ? parseReturnType(returns) : null;

        Map<Dialect, DialectMapping> dialectMap = parseDialects(dialects);

        PolyfillConfig pf = null;
        if (polyfill != null) {
            String strategy = (String) polyfill.get("strategy");
            String template = (String) polyfill.get("template");
            pf = new PolyfillConfig(
                PolyfillStrategy.valueOf(strategy),
                template
            );
        }

        return new FunctionDef(name, desc != null ? desc : "", returnType, dialectMap, pf);
    }

    @SuppressWarnings("unchecked")
    private Map<Dialect, DialectMapping> parseDialects(Object dialects) {
        Map<Dialect, DialectMapping> result = new EnumMap<>(Dialect.class);

        if (dialects instanceof String s) {
            // Shorthand: "UPPER" → all dialects use this name
            for (Dialect d : Dialect.values()) {
                if (d == Dialect.H2) continue;
                result.put(d, new DialectMapping(s, null, false, null));
            }
            return result;
        }

        Map<String, Object> map = (Map<String, Object>) dialects;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            Dialect dialect = Dialect.valueOf(e.getKey().toUpperCase());
            Object val = e.getValue();

            if (val instanceof String s) {
                result.put(dialect, new DialectMapping(s, null, false, null));
            } else if (val instanceof Map m) {
                String nativeName = (String) m.get("name");
                String template = (String) m.get("template");
                if (nativeName == null) {
                    throw new IllegalStateException(
                        "functions.yaml: dialect mapping missing required 'name' field");
                }
                result.put(dialect, new DialectMapping(nativeName, template, false, null));
            }
        }

        // Fill in missing dialects with the function's own name as default
        // (snakeyaml returns empty for dialects not in the map)
        return result;
    }

    // ══════════════════════════════════════════════════
    //  Return type parsing
    // ══════════════════════════════════════════════════

    static DataType parseReturnType(String s) {
        return switch (s) {
            case "INT"     -> DataType.IntType.INT;
            case "BIGINT"  -> DataType.IntType.BIGINT;
            case "DOUBLE"  -> DataType.FloatType.DOUBLE;
            case "VARCHAR" -> new DataType.VarcharType(0);
            case "DATE"    -> new DataType.DateType();
            case "DATETIME"-> new DataType.DatetimeType(3);
            case "BOOLEAN" -> new DataType.BooleanType();
            default -> {
                // VARCHAR(N), TIME(N), DATETIME(N)
                if (s.startsWith("VARCHAR(")) {
                    int n = Integer.parseInt(s.substring(8, s.length() - 1));
                    yield new DataType.VarcharType(n);
                }
                if (s.startsWith("TIME(")) {
                    int n = Integer.parseInt(s.substring(5, s.length() - 1));
                    yield new DataType.TimeType(n);
                }
                if (s.startsWith("DATETIME(")) {
                    int n = Integer.parseInt(s.substring(9, s.length() - 1));
                    yield new DataType.DatetimeType(n);
                }
                throw new IllegalArgumentException("Unknown return type: " + s);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object o) {
        return (T) o;
    }
}
