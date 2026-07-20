"""USQL SQLAlchemy integration — transparent SQL translation.

Provides two main entry points:

- :func:`usql_engine` — one-call Engine creation with USQL translation
- :func:`listen_engine` — add USQL translation to an existing Engine

Example::

    from usql.sqlalchemy_ext import usql_engine
    from sqlalchemy import text

    engine = usql_engine("sqlite://")
    with engine.connect() as conn:
        conn.execute(text("SELECT 1"))
"""
from usql.sqlalchemy_ext.dialect_map import detect_dialect
from usql.sqlalchemy_ext.engine import usql_engine
from usql.sqlalchemy_ext.listener import listen_engine, unlisten_engine

__all__ = ["usql_engine", "listen_engine", "unlisten_engine", "detect_dialect"]
