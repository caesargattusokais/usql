"""Factory function for creating SQLAlchemy Engines with USQL translation."""
from __future__ import annotations

from sqlalchemy import create_engine

from usql.compiler import USqlCompiler
from usql.dialect.dialect import Dialect as UsqlDialect


def usql_engine(
    url: str,
    *,
    compiler: USqlCompiler | None = None,
    dialect: UsqlDialect | None = None,
    **engine_kwargs,
):
    """Create a SQLAlchemy Engine with transparent USQL SQL translation.

    This is a convenience wrapper around :func:`sqlalchemy.create_engine`
    + :func:`listen_engine`.  Every SQL statement executed through the
    returned engine is automatically compiled from U-SQL to the target
    dialect before reaching the database driver.

    Args:
        url: SQLAlchemy connection string (e.g.
             ``"postgresql+psycopg2://user:pass@host/db"``).
        compiler: USqlCompiler instance (created if omitted).
        dialect: Target USQL Dialect (auto-detected from *url* if omitted).
        **engine_kwargs: Additional keyword arguments forwarded to
                         :func:`sqlalchemy.create_engine`.

    Returns:
        A SQLAlchemy Engine with USQL translation registered.

    Example::

        from usql.sqlalchemy_ext import usql_engine
        from sqlalchemy import text

        engine = usql_engine("sqlite://")
        with engine.connect() as conn:
            conn.execute(text("SELECT 1"))
    """
    engine = create_engine(url, **engine_kwargs)

    if compiler is None:
        compiler = USqlCompiler()
    if dialect is None:
        from usql.sqlalchemy_ext.dialect_map import detect_dialect
        dialect = detect_dialect(url)

    from usql.sqlalchemy_ext.listener import listen_engine
    return listen_engine(engine, compiler, dialect)
