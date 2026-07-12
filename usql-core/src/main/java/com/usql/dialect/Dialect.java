package com.usql.dialect;

import com.usql.ir.Capability;
import java.util.EnumSet;
import java.util.Set;

/**
 * Supported database dialects.
 * Each holds its native capabilities — the compiler queries this
 * to decide whether to polyfill or not.
 */
public enum Dialect {

    MYSQL("MySQL", true,
        // Capabilities
        Capability.LIMIT_OFFSET,
        Capability.WINDOW_FUNCTION,          // 8.0+
        Capability.RECURSIVE_CTE,            // 8.0+
        Capability.AGGREGATE,
        Capability.HAVING,
        Capability.DISTINCT,
        Capability.AUTO_INCREMENT,
        Capability.CONCAT_WITH_NULL,         // MySQL CONCAT treats NULL as ''
        Capability.GENERATED_COLUMN,         // 5.7+
        Capability.TEMPORARY_TABLE,
        Capability.CHECK_CONSTRAINT,         // 8.0.16+
        Capability.CTAS,
        Capability.REPLACE_INTO,
        Capability.ON_DUPLICATE_KEY_UPDATE,
        Capability.TRUNCATE_TABLE,
        Capability.OBJECT_COMMENT
        // Missing: BOOLEAN_TYPE, FULL_OUTER_JOIN, LATERAL_JOIN, ARRAY_TYPE
        // Missing: DEFERRABLE_FK, PARTIAL_INDEX, ENUM_TYPE, SEQUENCE, RETURNING_CLAUSE
    ),

    POSTGRESQL("PostgreSQL", false,
        // Capabilities — the most complete dialect
        Capability.LIMIT_OFFSET,
        Capability.WINDOW_FUNCTION,
        Capability.RECURSIVE_CTE,
        Capability.AGGREGATE,
        Capability.HAVING,
        Capability.DISTINCT,
        Capability.BOOLEAN_TYPE,
        Capability.FULL_OUTER_JOIN,
        Capability.LATERAL_JOIN,
        Capability.ARRAY_TYPE,
        Capability.DEFERRABLE_FK,
        Capability.PARTIAL_INDEX,
        Capability.ENUM_TYPE,               // CREATE TYPE AS ENUM
        Capability.GENERATED_COLUMN,        // stored only
        Capability.GROUPING_SETS,
        Capability.CHECK_CONSTRAINT,
        Capability.CTAS,
        Capability.TEMPORARY_TABLE,
        Capability.INTERVAL_ARITHMETIC,
        Capability.SEQUENCE,
        Capability.RETURNING_CLAUSE,
        Capability.TRUNCATE_TABLE,
        Capability.SELECT_WITHOUT_FROM,
        Capability.OBJECT_COMMENT
        // Missing: CONCAT_WITH_NULL, AUTO_INCREMENT (uses SEQUENCE),
        // REPLACE_INTO, ON_DUPLICATE_KEY_UPDATE
    ),

    ORACLE("Oracle", false,
        // Capabilities
        Capability.AGGREGATE,
        Capability.HAVING,
        Capability.DISTINCT,
        Capability.WINDOW_FUNCTION,
        Capability.RECURSIVE_CTE,            // 11gR2+
        Capability.MERGE_INTO,
        Capability.FULL_OUTER_JOIN,
        Capability.LATERAL_JOIN,             // 12c+ CROSS APPLY
        Capability.DEFERRABLE_FK,
        Capability.INTERVAL_ARITHMETIC,
        Capability.GROUPING_SETS,
        Capability.CHECK_CONSTRAINT,
        Capability.CTAS,
        Capability.GENERATED_COLUMN,         // virtual only
        Capability.SEQUENCE,
        Capability.RETURNING_CLAUSE,
        Capability.TRUNCATE_TABLE,
        Capability.SELECT_WITHOUT_FROM,      // FROM DUAL
        Capability.OBJECT_COMMENT
        // Missing: LIMIT_OFFSET (polyfill via ROWNUM),
        // BOOLEAN_TYPE (uses NUMBER(1)), CONCAT_WITH_NULL,
        // AUTO_INCREMENT (uses SEQUENCE+TRIGGER), ARRAY_TYPE (VARRAY)
        // PARTIAL_INDEX (uses function-based), ENUM_TYPE,
        // REPLACE_INTO, ON_DUPLICATE_KEY_UPDATE
    ),

    DM("达梦DM", false,
        // 达梦 is Oracle-compatible but with some unique features
        Capability.AGGREGATE,
        Capability.HAVING,
        Capability.DISTINCT,
        Capability.WINDOW_FUNCTION,
        Capability.RECURSIVE_CTE,            // DM 8+
        Capability.MERGE_INTO,
        Capability.FULL_OUTER_JOIN,
        Capability.AUTO_INCREMENT,           // DM supports both auto-increment and sequences
        Capability.SEQUENCE,
        Capability.INTERVAL_ARITHMETIC,
        Capability.GROUPING_SETS,
        Capability.CHECK_CONSTRAINT,
        Capability.CTAS,
        Capability.GENERATED_COLUMN,
        Capability.TRUNCATE_TABLE,
        Capability.SELECT_WITHOUT_FROM,      // FROM DUAL
        Capability.OBJECT_COMMENT
        // Missing: LIMIT_OFFSET (polyfill via ROWNUM or FETCH),
        // BOOLEAN_TYPE (uses BIT), CONCAT_WITH_NULL,
        // LATERAL_JOIN, ARRAY_TYPE, PARTIAL_INDEX, ENUM_TYPE,
        // DEFERRABLE_FK, REPLACE_INTO, ON_DUPLICATE_KEY_UPDATE,
        // RETURNING_CLAUSE, TEMPORARY_TABLE
    ),

    SQLSERVER("SQL Server", false,
        // SQL Server 2017+
        Capability.LIMIT_OFFSET,             // 2012+ OFFSET/FETCH
        Capability.WINDOW_FUNCTION,
        Capability.RECURSIVE_CTE,
        Capability.AGGREGATE,
        Capability.HAVING,
        Capability.DISTINCT,
        Capability.FULL_OUTER_JOIN,
        Capability.LATERAL_JOIN,             // CROSS/OUTER APPLY
        Capability.CHECK_CONSTRAINT,
        Capability.CTAS,                     // SELECT ... INTO
        Capability.TEMPORARY_TABLE,          // #temp tables
        Capability.TRUNCATE_TABLE,
        Capability.RETURNING_CLAUSE,         // OUTPUT clause
        Capability.SELECT_WITHOUT_FROM,
        Capability.OBJECT_COMMENT
        // Missing: BOOLEAN_TYPE (BIT), AUTO_INCREMENT (IDENTITY),
        // CONCAT_WITH_NULL, INTERVAL_ARITHMETIC, MERGE_INTO (2008+),
        // ARRAY_TYPE, PARTIAL_INDEX, ENUM_TYPE,
        // DEFERRABLE_FK, SEQUENCE (2012+),
        // REPLACE_INTO, ON_DUPLICATE_KEY_UPDATE (use MERGE)
    ),

    /** MySQL-compatible — same capabilities as MYSQL */
    MARIADB("MariaDB", true,
        Capability.LIMIT_OFFSET,
        Capability.WINDOW_FUNCTION,
        Capability.RECURSIVE_CTE,
        Capability.AGGREGATE,
        Capability.HAVING,
        Capability.DISTINCT,
        Capability.AUTO_INCREMENT,
        Capability.CONCAT_WITH_NULL,
        Capability.GENERATED_COLUMN,
        Capability.TEMPORARY_TABLE,
        Capability.CHECK_CONSTRAINT,
        Capability.CTAS,
        Capability.REPLACE_INTO,
        Capability.ON_DUPLICATE_KEY_UPDATE,
        Capability.TRUNCATE_TABLE,
        Capability.OBJECT_COMMENT
    ),

    /** TiDB — MySQL protocol compatible */
    TIDB("TiDB", true,
        Capability.LIMIT_OFFSET,
        Capability.WINDOW_FUNCTION,
        Capability.RECURSIVE_CTE,
        Capability.AGGREGATE,
        Capability.HAVING,
        Capability.DISTINCT,
        Capability.AUTO_INCREMENT,
        Capability.CONCAT_WITH_NULL,
        Capability.GENERATED_COLUMN,
        Capability.TEMPORARY_TABLE,
        Capability.CHECK_CONSTRAINT,
        Capability.CTAS,
        Capability.REPLACE_INTO,
        Capability.ON_DUPLICATE_KEY_UPDATE,
        Capability.TRUNCATE_TABLE,
        Capability.OBJECT_COMMENT
    ),

    /** SQLite — lightweight, permissive */
    SQLITE("SQLite", false,
        Capability.LIMIT_OFFSET,
        Capability.WINDOW_FUNCTION,          // 3.25+
        Capability.RECURSIVE_CTE,            // 3.8.3+
        Capability.AGGREGATE,
        Capability.HAVING,
        Capability.DISTINCT,
        Capability.CHECK_CONSTRAINT,
        Capability.TRUNCATE_TABLE            // DELETE FROM polyfill
        // Missing: AUTO_INCREMENT (uses AUTOINCREMENT via INTEGER PK),
        // BOOLEAN_TYPE, FULL_OUTER_JOIN, LATERAL_JOIN, ARRAY_TYPE,
        // ENUM_TYPE, SEQUENCE, GENERATED_COLUMN, etc.
    ),

    /** DuckDB — PostgreSQL-compatible embedded analytics */
    DUCKDB("DuckDB", false,
        Capability.LIMIT_OFFSET,
        Capability.WINDOW_FUNCTION,
        Capability.RECURSIVE_CTE,
        Capability.AGGREGATE,
        Capability.HAVING,
        Capability.DISTINCT,
        Capability.BOOLEAN_TYPE,
        Capability.FULL_OUTER_JOIN,
        Capability.CHECK_CONSTRAINT,
        Capability.CTAS,
        Capability.TEMPORARY_TABLE,
        Capability.INTERVAL_ARITHMETIC,
        Capability.SEQUENCE,
        Capability.TRUNCATE_TABLE,
        Capability.SELECT_WITHOUT_FROM,
        Capability.OBJECT_COMMENT
    ),

    /** OceanBase — MySQL-compatible distributed database */
    OCEANBASE("OceanBase", true,
        Capability.LIMIT_OFFSET,
        Capability.WINDOW_FUNCTION,
        Capability.RECURSIVE_CTE,
        Capability.AGGREGATE,
        Capability.HAVING,
        Capability.DISTINCT,
        Capability.AUTO_INCREMENT,
        Capability.CONCAT_WITH_NULL,
        Capability.GENERATED_COLUMN,
        Capability.TEMPORARY_TABLE,
        Capability.CHECK_CONSTRAINT,
        Capability.CTAS,
        Capability.REPLACE_INTO,
        Capability.ON_DUPLICATE_KEY_UPDATE,
        Capability.TRUNCATE_TABLE,
        Capability.OBJECT_COMMENT
    ),

    /** Reference dialect for semantic verification */
    H2("H2", false,
        // H2 — used as reference implementation; very standards-compliant
        Capability.LIMIT_OFFSET,
        Capability.WINDOW_FUNCTION,
        Capability.RECURSIVE_CTE,
        Capability.AGGREGATE,
        Capability.HAVING,
        Capability.DISTINCT,
        Capability.BOOLEAN_TYPE,
        Capability.FULL_OUTER_JOIN,
        Capability.LATERAL_JOIN,
        Capability.ARRAY_TYPE,
        Capability.INTERVAL_ARITHMETIC,
        Capability.GROUPING_SETS,
        Capability.GENERATED_COLUMN,
        Capability.CHECK_CONSTRAINT,
        Capability.CTAS,
        Capability.TEMPORARY_TABLE,
        Capability.SEQUENCE,
        Capability.TRUNCATE_TABLE,
        Capability.SELECT_WITHOUT_FROM,
        Capability.OBJECT_COMMENT
    );

    private final String displayName;
    private final boolean caseSensitive;     // MySQL is case-sensitive for identifiers?
    private final Set<Capability> capabilities;

    Dialect(String displayName, boolean caseSensitive, Capability... capabilities) {
        this.displayName = displayName;
        this.caseSensitive = caseSensitive;
        this.capabilities = EnumSet.noneOf(Capability.class);
        for (Capability c : capabilities) {
            this.capabilities.add(c);
        }
    }

    public String displayName() { return displayName; }
    public boolean isCaseSensitive() { return caseSensitive; }

    public boolean supports(Capability cap) {
        return capabilities.contains(cap);
    }

    public Set<Capability> capabilities() {
        return EnumSet.copyOf(capabilities);
    }

    /** Missing capabilities that need polyfill or error */
    public Set<Capability> missingCapabilities(Set<Capability> required) {
        Set<Capability> missing = EnumSet.noneOf(Capability.class);
        for (Capability c : required) {
            if (!supports(c)) {
                missing.add(c);
            }
        }
        return missing;
    }
}
