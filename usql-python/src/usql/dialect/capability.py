"""Features that a database dialect may or may not support.

When a capability is missing, the polyfill engine generates equivalent SQL.
"""
from enum import Enum, auto


class Capability(Enum):
    """Database capability flags."""

    # Pagination
    LIMIT_OFFSET = auto()

    # Window functions: ROW_NUMBER(), RANK(), LAG(), LEAD()
    WINDOW_FUNCTION = auto()

    # Recursive CTE: WITH RECURSIVE ...
    RECURSIVE_CTE = auto()

    # MERGE INTO / UPSERT
    MERGE_INTO = auto()

    # Native BOOLEAN type in SQL context
    BOOLEAN_TYPE = auto()

    # AUTO_INCREMENT / IDENTITY columns
    AUTO_INCREMENT = auto()

    # CONCAT treats NULL as empty string (MySQL behavior) vs returning NULL (standard)
    CONCAT_WITH_NULL = auto()

    # INTERVAL arithmetic
    INTERVAL_ARITHMETIC = auto()

    # LATERAL joins
    LATERAL_JOIN = auto()

    # FULL OUTER JOIN
    FULL_OUTER_JOIN = auto()

    # Partial / filtered indexes (WHERE clause on CREATE INDEX)
    PARTIAL_INDEX = auto()

    # Array column type
    ARRAY_TYPE = auto()

    # Deferrable foreign keys
    DEFERRABLE_FK = auto()

    # ENUM column type
    ENUM_TYPE = auto()

    # CHECK constraints
    CHECK_CONSTRAINT = auto()

    # HAVING clause without GROUP BY
    HAVING = auto()

    # DISTINCT keyword
    DISTINCT = auto()

    # Aggregate functions (COUNT/SUM/AVG/MIN/MAX)
    AGGREGATE = auto()

    # CUBE / ROLLUP / GROUPING SETS
    GROUPING_SETS = auto()

    # GENERATED ALWAYS AS columns
    GENERATED_COLUMN = auto()

    # Sequence objects (vs AUTO_INCREMENT)
    SEQUENCE = auto()

    # SELECT without FROM (for expressions like SELECT 1)
    SELECT_WITHOUT_FROM = auto()

    # COMMENT ON TABLE/COLUMN
    OBJECT_COMMENT = auto()

    # TRUNCATE TABLE
    TRUNCATE_TABLE = auto()

    # REPLACE INTO (MySQL-specific, but useful for upsert polyfill)
    REPLACE_INTO = auto()

    # ON DUPLICATE KEY UPDATE (MySQL-specific)
    ON_DUPLICATE_KEY_UPDATE = auto()

    # RETURNING clause (PG/Oracle)
    RETURNING_CLAUSE = auto()

    # CREATE TABLE AS SELECT
    CTAS = auto()

    # Temporary tables
    TEMPORARY_TABLE = auto()
