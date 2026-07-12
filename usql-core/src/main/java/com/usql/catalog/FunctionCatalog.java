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
 * Loaded from functions.yaml (bundled) — YAML is parsed once and shared.
 */
public class FunctionCatalog {

    /** Singleton loaded YAML data — shared across all catalog instances. */
    private static final Map<String, FunctionDef> YAML_DATA = loadYamlData();

    private final Map<String, FunctionDef> functions;

    public FunctionCatalog() {
        functions = new LinkedHashMap<>(YAML_DATA);
    }

    /** Get a function definition by its U-SQL name. */
    public Optional<FunctionDef> get(String usqlName) {
        return Optional.ofNullable(functions.get(usqlName.toUpperCase()));
    }

    public boolean contains(String usqlName) {
        return functions.containsKey(usqlName.toUpperCase());
    }

    public Set<String> functionNames() {
        return Collections.unmodifiableSet(functions.keySet());
    }

    // ══════════════════════════════════════════════════
    //  Function definition
    // ══════════════════════════════════════════════════

    public static class FunctionDef {
        public final String uSqlName;
        public final String description;
        public final DataType returnType;
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

        public Optional<DialectMapping> forDialect(Dialect dialect) {
            return Optional.ofNullable(dialectMappings.get(dialect));
        }
    }

    public record DialectMapping(String nativeName, String renderTemplate,
                                  boolean argsReordered, int[] argOrder) {}

    public record PolyfillConfig(PolyfillStrategy strategy, String template) {}

    public enum PolyfillStrategy { EXPRESSION, SUBQUERY, NOT_SUPPORTED }

    // ══════════════════════════════════════════════════
    //  YAML loading (once, at class load time)
    // ══════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private static Map<String, FunctionDef> loadYamlData() {
        Yaml yaml = new Yaml();
        InputStream in = FunctionCatalog.class.getClassLoader()
            .getResourceAsStream("functions.yaml");
        if (in == null)
            throw new IllegalStateException("functions.yaml not found on classpath");

        Map<String, Object> root = yaml.load(in);
        List<Map<String, Object>> list = (List<Map<String, Object>>) root.get("functions");
        if (list == null)
            throw new IllegalStateException("functions.yaml missing 'functions' key");

        Map<String, FunctionDef> data = new LinkedHashMap<>();
        for (Map<String, Object> entry : list) {
            FunctionDef def = parseFunction(entry);
            data.put(def.uSqlName.toUpperCase(), def);
        }
        return data;
    }

    private static FunctionDef parseFunction(Map<String, Object> entry) {
        String name = (String) entry.get("name");
        String desc = (String) entry.get("desc");
        String returns = (String) entry.get("returns");
        Object dialects = entry.get("dialects");
        Map<String, Object> polyfill = cast(entry.get("polyfill"));

        if (name == null) throw new IllegalStateException("functions.yaml: missing 'name'");
        if (dialects == null) throw new IllegalStateException("functions.yaml: '" + name + "' missing 'dialects'");

        DataType returnType = returns != null ? parseReturnType(returns) : null;
        Map<Dialect, DialectMapping> dialectMap = parseDialects(dialects);
        PolyfillConfig pf = null;
        if (polyfill != null) {
            pf = new PolyfillConfig(PolyfillStrategy.valueOf((String) polyfill.get("strategy")),
                (String) polyfill.get("template"));
        }
        return new FunctionDef(name, desc != null ? desc : "", returnType, dialectMap, pf);
    }

    @SuppressWarnings("unchecked")
    private static Map<Dialect, DialectMapping> parseDialects(Object dialects) {
        Map<Dialect, DialectMapping> result = new EnumMap<>(Dialect.class);
        if (dialects instanceof String s) {
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
                if (nativeName == null)
                    throw new IllegalStateException("functions.yaml: dialect mapping missing 'name'");
                result.put(dialect, new DialectMapping(nativeName, template, false, null));
            }
        }
        return result;
    }

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
                if (s.startsWith("VARCHAR("))
                    yield new DataType.VarcharType(Integer.parseInt(s.substring(8, s.length() - 1)));
                if (s.startsWith("TIME("))
                    yield new DataType.TimeType(Integer.parseInt(s.substring(5, s.length() - 1)));
                if (s.startsWith("DATETIME("))
                    yield new DataType.DatetimeType(Integer.parseInt(s.substring(9, s.length() - 1)));
                throw new IllegalArgumentException("Unknown return type: " + s);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object o) { return (T) o; }
}
