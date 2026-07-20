"""SQLAlchemy event listener for USQL SQL translation.

Registers a ``before_cursor_execute`` hook on a SQLAlchemy Engine
that compiles every SQL statement through the USQL compiler before
it reaches the database driver.
"""
from __future__ import annotations

from typing import Callable

from sqlalchemy import event

from usql.compiler import USqlCompiler
from usql.dialect.dialect import Dialect as UsqlDialect

# Attribute name used to store the listener reference on the Engine
_LISTENER_ATTR = "_usql_before_cursor_execute_listener"


def _make_listener(compiler: USqlCompiler, target_dialect: UsqlDialect) -> Callable:
    """Create a ``before_cursor_execute`` event handler.

    The handler compiles the SQL statement through USQL and returns
    the translated SQL + original parameters.  If compilation fails,
    a :class:`sqlalchemy.exc.ProgrammingError` is raised.
    """

    def before_cursor_execute(conn, cursor, statement, parameters, context, executemany):
        # Skip non-string or empty statements (e.g. SQLAlchemy internals)
        if not isinstance(statement, str) or not statement.strip():
            return statement, parameters

        result = compiler.compile(statement, target_dialect)
        if result.success:
            return result.sql, parameters

        # Compilation failed — raise as SQLAlchemy error
        from sqlalchemy.exc import ProgrammingError

        raise ProgrammingError(
            statement,
            parameters,
            orig=RuntimeError(f"USQL compile error: {result.report()}"),
        )

    return before_cursor_execute


def listen_engine(
    engine,
    compiler: USqlCompiler | None = None,
    dialect: UsqlDialect | None = None,
):
    """Register USQL translation on an existing SQLAlchemy Engine.

    Args:
        engine: A SQLAlchemy Engine instance.
        compiler: USqlCompiler instance (created if omitted).
        dialect: Target USQL Dialect (auto-detected from engine if omitted).

    Returns:
        The same engine, for chaining.

    Example::

        from sqlalchemy import create_engine
        from usql.sqlalchemy_ext import listen_engine

        engine = create_engine("postgresql+psycopg2://user:pass@host/db")
        listen_engine(engine)
    """
    if compiler is None:
        compiler = USqlCompiler()
    if dialect is None:
        from usql.sqlalchemy_ext.dialect_map import detect_dialect
        dialect = detect_dialect(engine)

    listener = _make_listener(compiler, dialect)
    event.listen(engine, "before_cursor_execute", listener, retval=True)

    # Store reference so unlisten_engine can remove it
    setattr(engine, _LISTENER_ATTR, listener)

    return engine


def unlisten_engine(engine):
    """Remove the USQL ``before_cursor_execute`` listener from an Engine.

    After calling this, SQL statements pass through to the driver
    without USQL translation.

    Args:
        engine: A SQLAlchemy Engine that was previously registered
                with :func:`listen_engine`.
    """
    listener = getattr(engine, _LISTENER_ATTR, None)
    if listener is not None:
        event.remove(engine, "before_cursor_execute", listener)
        delattr(engine, _LISTENER_ATTR)
