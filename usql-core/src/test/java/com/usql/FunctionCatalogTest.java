package com.usql;

import com.usql.catalog.FunctionCatalog;
import com.usql.catalog.FunctionCatalog.FunctionDef;
import com.usql.dialect.Dialect;
import com.usql.ir.DataType;

import java.util.Optional;
import java.util.Set;

/**
 * Tests for FunctionCatalog YAML loading and function lookups.
 * No database required.
 */
public class FunctionCatalogTest {

    private static final FunctionCatalog catalog = new FunctionCatalog();

    public static void main(String[] args) {
        System.out.println("=== FunctionCatalog Test ===\n");

        int pass = 0, fail = 0;

        // ── 1. YAML loaded with enough functions ──
        {
            Set<String> names = catalog.functionNames();
            check(names.size() >= 100, "Function count >= 100 (actual: " + names.size() + ")");
            System.out.println("  ✅ 1. Loaded " + names.size() + " functions from functions.yaml");
            pass++;
        }

        // ── 2. Simple all-same-dialect function ──
        {
            var def = catalog.get("UPPER").orElse(null);
            check(def != null, "UPPER exists");
            check(def.description.equals("Convert to uppercase"), "UPPER description");
            check(def.returnType != null, "UPPER has return type");
            check(def.polyfill == null, "UPPER has no polyfill");
            // All 6 dialects mapped
            check(def.forDialect(Dialect.MYSQL).isPresent(), "UPPER → MySQL");
            check(def.forDialect(Dialect.POSTGRESQL).isPresent(), "UPPER → PostgreSQL");
            check(def.forDialect(Dialect.ORACLE).isPresent(), "UPPER → Oracle");
            check(def.forDialect(Dialect.DM).isPresent(), "UPPER → DM");
            check(def.forDialect(Dialect.SQLSERVER).isPresent(), "UPPER → SQL Server");
            System.out.println("  ✅ 2. UPPER: all dialects mapped, no polyfill");
            pass++;
        }

        // ── 3. Non-existent function ──
        {
            Optional<FunctionDef> def = catalog.get("NONEXIST");
            check(def.isEmpty(), "NONEXIST returns empty");
            System.out.println("  ✅ 3. NONEXIST: Optional.empty()");
            pass++;
        }

        // ── 4. Function with polyfill ──
        {
            var def = catalog.get("COALESCE").orElse(null);
            check(def != null, "COALESCE exists");
            check(def.polyfill != null, "COALESCE has polyfill");
            check(def.polyfill.strategy() == FunctionCatalog.PolyfillStrategy.EXPRESSION,
                "COALESCE polyfill strategy = EXPRESSION");
            check(def.polyfill.template() != null && def.polyfill.template().contains("CASE WHEN"),
                "COALESCE polyfill template contains CASE WHEN");
            System.out.println("  ✅ 4. COALESCE: has EXPRESSION polyfill");
            pass++;
        }

        // ── 5. Function with dialect-specific names ──
        {
            var def = catalog.get("LENGTH").orElse(null);
            check(def != null, "LENGTH exists");
            // MySQL uses LENGTH
            check(def.forDialect(Dialect.MYSQL).get().nativeName().equals("LENGTH"),
                "LENGTH → MySQL native name = LENGTH");
            // SQL Server uses LEN
            check(def.forDialect(Dialect.SQLSERVER).get().nativeName().equals("LEN"),
                "LENGTH → SQL Server native name = LEN");
            System.out.println("  ✅ 5. LENGTH: MySQL=LENGTH, SQLServer=LEN");
            pass++;
        }

        // ── 6. Function with template ──
        {
            var def = catalog.get("INSTR").orElse(null);
            check(def != null, "INSTR exists");
            // PostgreSQL has template POSITION({1} IN {0})
            var pg = def.forDialect(Dialect.POSTGRESQL).get();
            check(pg.renderTemplate() != null, "INSTR → PostgreSQL has template");
            check(pg.renderTemplate().contains("POSITION"), "INSTR → PostgreSQL template uses POSITION");
            // MySQL has template INSTR({0}, {1})
            var mysql = def.forDialect(Dialect.MYSQL).get();
            check(mysql.renderTemplate() != null, "INSTR → MySQL has template");
            System.out.println("  ✅ 6. INSTR: PostgreSQL/MYSQL have templates");
            pass++;
        }

        // ── 7. Function without polyfill ──
        {
            var def = catalog.get("ABS").orElse(null);
            check(def != null, "ABS exists");
            check(def.polyfill == null, "ABS has no polyfill");
            System.out.println("  ✅ 7. ABS: no polyfill");
            pass++;
        }

        // ── 8. Return type: VARCHAR ──
        {
            var def = catalog.get("UPPER").orElse(null);
            check(def.returnType instanceof DataType.VarcharType, "UPPER returns VARCHAR");
            System.out.println("  ✅ 8. UPPER return type = VARCHAR");
            pass++;
        }

        // ── 9. Return type: INT ──
        {
            var def = catalog.get("LENGTH").orElse(null);
            check(def.returnType instanceof DataType.IntType, "LENGTH returns INT");
            System.out.println("  ✅ 9. LENGTH return type = INT");
            pass++;
        }

        // ── 10. Return type: null (depends on args) ──
        {
            var def = catalog.get("SUM").orElse(null);
            check(def != null && def.returnType == null, "SUM return type = null (depends on args)");
            System.out.println("  ✅ 10. SUM return type = null");
            pass++;
        }

        // ── 11. Aggregate functions ──
        {
            for (String fn : new String[]{"COUNT", "SUM", "AVG", "MIN", "MAX"}) {
                check(catalog.get(fn).isPresent(), fn + " is registered");
            }
            System.out.println("  ✅ 11. Aggregate functions: COUNT/SUM/AVG/MIN/MAX");
            pass++;
        }

        // ── 12. Window functions ──
        {
            for (String fn : new String[]{"ROW_NUMBER", "RANK", "DENSE_RANK", "LAG", "LEAD",
                                           "FIRST_VALUE", "LAST_VALUE", "NTILE"}) {
                check(catalog.get(fn).isPresent(), fn + " is registered");
            }
            System.out.println("  ✅ 12. Window functions: 8/8 registered");
            pass++;
        }

        // ── 13. NVL with SQL Server override ──
        {
            var def = catalog.get("NVL").orElse(null);
            check(def != null, "NVL exists");
            check(def.forDialect(Dialect.SQLSERVER).get().nativeName().equals("ISNULL"),
                "NVL → SQL Server = ISNULL");
            check(def.polyfill != null, "NVL has polyfill");
            System.out.println("  ✅ 13. NVL: SQLServer=ISNULL, has polyfill");
            pass++;
        }

        // ── 14. SQL Server specific DATE functions ──
        {
            var now = catalog.get("NOW").orElse(null);
            check(now != null, "NOW exists");
            check(now.forDialect(Dialect.SQLSERVER).get().nativeName().equals("GETDATE"),
                "NOW → SQL Server = GETDATE");
            System.out.println("  ✅ 14. NOW: SQLServer=GETDATE");
            pass++;
        }

        System.out.println("\n=== Result: " + pass + "/14 passed ===");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            System.err.println("  ❌ FAIL: " + message);
            System.exit(1);
        }
    }
}
