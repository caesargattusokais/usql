"""SQLAlchemy dialect name → USQL Dialect mapping."""
from __future__ import annotations

from usql.dialect.dialect import Dialect

# SQLAlchemy backend name (engine.dialect.name / url.get_backend_name())
# → USQL Dialect enum
SQLALCHEMY_TO_USQL: dict[str, Dialect] = {
    "mysql":        Dialect.MYSQL,
    "mariadb":      Dialect.MARIADB,
    "postgresql":   Dialect.POSTGRESQL,
    "oracle":       Dialect.ORACLE,
    "dm":           Dialect.DM,
    "mssql":        Dialect.SQLSERVER,
    "sqlite":       Dialect.SQLITE,
    "duckdb":       Dialect.DUCKDB,
    "clickhouse":   Dialect.CLICKHOUSE,
    "oceanbase":    Dialect.OCEANBASE,
    "tidb":         Dialect.TIDB,
}


def detect_dialect(engine_or_url) -> Dialect:
    """Detect USQL Dialect from a SQLAlchemy Engine, URL, or connection string.

    Args:
        engine_or_url: A SQLAlchemy Engine, URL object, or connection string.

    Returns:
        The corresponding USQL Dialect enum value.

    Raises:
        ValueError: If the dialect cannot be detected or is unsupported.
    """
    backend: str | None = None

    # str → parse URL
    if isinstance(engine_or_url, str):
        from sqlalchemy.engine import make_url
        url = make_url(engine_or_url)
        backend = url.get_backend_name()
    # Engine → engine.dialect.name
    elif hasattr(engine_or_url, "dialect"):
        backend = engine_or_url.dialect.name
    # URL → url.get_backend_name()
    elif hasattr(engine_or_url, "get_backend_name"):
        backend = engine_or_url.get_backend_name()

    if backend is None:
        raise ValueError(f"Cannot detect dialect from: {engine_or_url!r}")

    dialect = SQLALCHEMY_TO_USQL.get(backend)
    if dialect is None:
        raise ValueError(
            f"Unsupported SQLAlchemy dialect: {backend!r}. "
            f"Supported: {', '.join(sorted(SQLALCHEMY_TO_USQL))}"
        )
    return dialect
