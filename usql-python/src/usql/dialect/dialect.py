"""Supported database dialects.

Each holds its native capabilities — the compiler queries this
to decide whether to polyfill or not.
"""
from __future__ import annotations

from enum import Enum
from typing import FrozenSet

from usql.dialect.capability import Capability


class Dialect(Enum):
    """Database dialect enum with capability metadata."""

    MYSQL = ("MySQL", True, frozenset({
        Capability.LIMIT_OFFSET, Capability.WINDOW_FUNCTION,
        Capability.RECURSIVE_CTE, Capability.AGGREGATE,
        Capability.HAVING, Capability.DISTINCT,
        Capability.AUTO_INCREMENT, Capability.CONCAT_WITH_NULL,
        Capability.GENERATED_COLUMN, Capability.TEMPORARY_TABLE,
        Capability.CHECK_CONSTRAINT, Capability.CTAS,
        Capability.REPLACE_INTO, Capability.ON_DUPLICATE_KEY_UPDATE,
        Capability.TRUNCATE_TABLE, Capability.OBJECT_COMMENT,
    }))

    POSTGRESQL = ("PostgreSQL", False, frozenset({
        Capability.LIMIT_OFFSET, Capability.WINDOW_FUNCTION,
        Capability.RECURSIVE_CTE, Capability.AGGREGATE,
        Capability.HAVING, Capability.DISTINCT,
        Capability.BOOLEAN_TYPE, Capability.FULL_OUTER_JOIN,
        Capability.LATERAL_JOIN, Capability.ARRAY_TYPE,
        Capability.DEFERRABLE_FK, Capability.PARTIAL_INDEX,
        Capability.ENUM_TYPE, Capability.GENERATED_COLUMN,
        Capability.GROUPING_SETS, Capability.CHECK_CONSTRAINT,
        Capability.CTAS, Capability.TEMPORARY_TABLE,
        Capability.INTERVAL_ARITHMETIC, Capability.SEQUENCE,
        Capability.RETURNING_CLAUSE, Capability.TRUNCATE_TABLE,
        Capability.SELECT_WITHOUT_FROM, Capability.OBJECT_COMMENT,
    }))

    ORACLE = ("Oracle", False, frozenset({
        Capability.AGGREGATE, Capability.HAVING, Capability.DISTINCT,
        Capability.WINDOW_FUNCTION, Capability.RECURSIVE_CTE,
        Capability.MERGE_INTO, Capability.FULL_OUTER_JOIN,
        Capability.LATERAL_JOIN, Capability.DEFERRABLE_FK,
        Capability.INTERVAL_ARITHMETIC, Capability.GROUPING_SETS,
        Capability.CHECK_CONSTRAINT, Capability.CTAS,
        Capability.GENERATED_COLUMN, Capability.SEQUENCE,
        Capability.RETURNING_CLAUSE, Capability.TRUNCATE_TABLE,
        Capability.SELECT_WITHOUT_FROM, Capability.OBJECT_COMMENT,
    }))

    DM = ("达梦DM", False, frozenset({
        Capability.AGGREGATE, Capability.HAVING, Capability.DISTINCT,
        Capability.WINDOW_FUNCTION, Capability.RECURSIVE_CTE,
        Capability.MERGE_INTO, Capability.FULL_OUTER_JOIN,
        Capability.AUTO_INCREMENT, Capability.SEQUENCE,
        Capability.INTERVAL_ARITHMETIC, Capability.GROUPING_SETS,
        Capability.CHECK_CONSTRAINT, Capability.CTAS,
        Capability.GENERATED_COLUMN, Capability.TRUNCATE_TABLE,
        Capability.SELECT_WITHOUT_FROM, Capability.OBJECT_COMMENT,
    }))

    SQLSERVER = ("SQL Server", False, frozenset({
        Capability.LIMIT_OFFSET, Capability.WINDOW_FUNCTION,
        Capability.RECURSIVE_CTE, Capability.AGGREGATE,
        Capability.HAVING, Capability.DISTINCT,
        Capability.FULL_OUTER_JOIN, Capability.LATERAL_JOIN,
        Capability.CHECK_CONSTRAINT, Capability.CTAS,
        Capability.TEMPORARY_TABLE, Capability.TRUNCATE_TABLE,
        Capability.RETURNING_CLAUSE, Capability.SELECT_WITHOUT_FROM,
        Capability.OBJECT_COMMENT,
    }))

    MARIADB = ("MariaDB", True, frozenset({
        Capability.LIMIT_OFFSET, Capability.WINDOW_FUNCTION,
        Capability.RECURSIVE_CTE, Capability.AGGREGATE,
        Capability.HAVING, Capability.DISTINCT,
        Capability.AUTO_INCREMENT, Capability.CONCAT_WITH_NULL,
        Capability.GENERATED_COLUMN, Capability.TEMPORARY_TABLE,
        Capability.CHECK_CONSTRAINT, Capability.CTAS,
        Capability.REPLACE_INTO, Capability.ON_DUPLICATE_KEY_UPDATE,
        Capability.TRUNCATE_TABLE, Capability.OBJECT_COMMENT,
    }))

    TIDB = ("TiDB", True, frozenset({
        Capability.LIMIT_OFFSET, Capability.WINDOW_FUNCTION,
        Capability.RECURSIVE_CTE, Capability.AGGREGATE,
        Capability.HAVING, Capability.DISTINCT,
        Capability.AUTO_INCREMENT, Capability.CONCAT_WITH_NULL,
        Capability.GENERATED_COLUMN, Capability.TEMPORARY_TABLE,
        Capability.CHECK_CONSTRAINT, Capability.CTAS,
        Capability.REPLACE_INTO, Capability.ON_DUPLICATE_KEY_UPDATE,
        Capability.TRUNCATE_TABLE, Capability.OBJECT_COMMENT,
    }))

    SQLITE = ("SQLite", False, frozenset({
        Capability.LIMIT_OFFSET, Capability.WINDOW_FUNCTION,
        Capability.RECURSIVE_CTE, Capability.AGGREGATE,
        Capability.HAVING, Capability.DISTINCT,
        Capability.CHECK_CONSTRAINT, Capability.TRUNCATE_TABLE,
        Capability.FULL_OUTER_JOIN,
    }))

    DUCKDB = ("DuckDB", False, frozenset({
        Capability.LIMIT_OFFSET, Capability.WINDOW_FUNCTION,
        Capability.RECURSIVE_CTE, Capability.AGGREGATE,
        Capability.HAVING, Capability.DISTINCT,
        Capability.BOOLEAN_TYPE, Capability.FULL_OUTER_JOIN,
        Capability.CHECK_CONSTRAINT, Capability.CTAS,
        Capability.TEMPORARY_TABLE, Capability.INTERVAL_ARITHMETIC,
        Capability.SEQUENCE, Capability.TRUNCATE_TABLE,
        Capability.SELECT_WITHOUT_FROM, Capability.OBJECT_COMMENT,
    }))

    OCEANBASE = ("OceanBase", True, frozenset({
        Capability.LIMIT_OFFSET, Capability.WINDOW_FUNCTION,
        Capability.RECURSIVE_CTE, Capability.AGGREGATE,
        Capability.HAVING, Capability.DISTINCT,
        Capability.AUTO_INCREMENT, Capability.CONCAT_WITH_NULL,
        Capability.GENERATED_COLUMN, Capability.TEMPORARY_TABLE,
        Capability.CHECK_CONSTRAINT, Capability.CTAS,
        Capability.REPLACE_INTO, Capability.ON_DUPLICATE_KEY_UPDATE,
        Capability.TRUNCATE_TABLE, Capability.OBJECT_COMMENT,
    }))

    CLICKHOUSE = ("ClickHouse", True, frozenset({
        Capability.LIMIT_OFFSET, Capability.WINDOW_FUNCTION,
        Capability.RECURSIVE_CTE, Capability.AGGREGATE,
        Capability.HAVING, Capability.DISTINCT,
        Capability.CHECK_CONSTRAINT, Capability.TRUNCATE_TABLE,
    }))

    H2 = ("H2", False, frozenset({
        Capability.LIMIT_OFFSET, Capability.WINDOW_FUNCTION,
        Capability.RECURSIVE_CTE, Capability.AGGREGATE,
        Capability.HAVING, Capability.DISTINCT,
        Capability.BOOLEAN_TYPE, Capability.FULL_OUTER_JOIN,
        Capability.LATERAL_JOIN, Capability.ARRAY_TYPE,
        Capability.INTERVAL_ARITHMETIC, Capability.GROUPING_SETS,
        Capability.GENERATED_COLUMN, Capability.CHECK_CONSTRAINT,
        Capability.CTAS, Capability.TEMPORARY_TABLE,
        Capability.SEQUENCE, Capability.TRUNCATE_TABLE,
        Capability.SELECT_WITHOUT_FROM, Capability.OBJECT_COMMENT,
    }))

    def __init__(self, display_name: str, case_sensitive: bool, capabilities: FrozenSet[Capability]):
        self._display_name = display_name
        self._case_sensitive = case_sensitive
        self._capabilities = capabilities

    @property
    def display_name(self) -> str:
        return self._display_name

    @property
    def capabilities(self) -> FrozenSet[Capability]:
        return self._capabilities

    def supports(self, cap: Capability) -> bool:
        return cap in self._capabilities

    def missing_capabilities(self, required: FrozenSet[Capability]) -> FrozenSet[Capability]:
        return required - self._capabilities
