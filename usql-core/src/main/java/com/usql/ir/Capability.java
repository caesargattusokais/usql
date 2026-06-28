package com.usql.ir;

/**
 * Features that a database dialect may or may not support.
 * When a capability is missing, the polyfill engine generates equivalent SQL.
 */
public enum Capability {

    /** LIMIT / OFFSET pagination */
    LIMIT_OFFSET,

    /** Window functions: ROW_NUMBER(), RANK(), LAG(), LEAD() */
    WINDOW_FUNCTION,

    /** Recursive CTE: WITH RECURSIVE ... */
    RECURSIVE_CTE,

    /** MERGE INTO / UPSERT */
    MERGE_INTO,

    /** Native BOOLEAN type in SQL context */
    BOOLEAN_TYPE,

    /** AUTO_INCREMENT / IDENTITY columns */
    AUTO_INCREMENT,

    /** CONCAT treats NULL as empty string (MySQL behavior) vs returning NULL (standard) */
    CONCAT_WITH_NULL,

    /** INTERVAL arithmetic */
    INTERVAL_ARITHMETIC,

    /** LATERAL joins */
    LATERAL_JOIN,

    /** FULL OUTER JOIN */
    FULL_OUTER_JOIN,

    /** Partial / filtered indexes (WHERE clause on CREATE INDEX) */
    PARTIAL_INDEX,

    /** Array column type */
    ARRAY_TYPE,

    /** Deferrable foreign keys */
    DEFERRABLE_FK,

    /** ENUM column type */
    ENUM_TYPE,

    /** CHECK constraints */
    CHECK_CONSTRAINT,

    /** HAVING clause without GROUP BY */
    HAVING,

    /** DISTINCT keyword */
    DISTINCT,

    /** Aggregate functions (COUNT/SUM/AVG/MIN/MAX) */
    AGGREGATE,

    /** CUBE / ROLLUP / GROUPING SETS */
    GROUPING_SETS,

    /** GENERATED ALWAYS AS columns */
    GENERATED_COLUMN,

    /** Sequence objects (vs AUTO_INCREMENT) */
    SEQUENCE,

    /** SELECT without FROM (for expressions like SELECT 1) */
    SELECT_WITHOUT_FROM,

    /** COMMENT ON TABLE/COLUMN */
    OBJECT_COMMENT,

    /** TRUNCATE TABLE */
    TRUNCATE_TABLE,

    /** REPLACE INTO (MySQL-specific, but useful for upsert polyfill) */
    REPLACE_INTO,

    /** ON DUPLICATE KEY UPDATE (MySQL-specific) */
    ON_DUPLICATE_KEY_UPDATE,

    /** RETURNING clause (PG/Oracle) */
    RETURNING_CLAUSE,

    /** CREATE TABLE AS SELECT */
    CTAS,

    /** Temporary tables */
    TEMPORARY_TABLE
}
