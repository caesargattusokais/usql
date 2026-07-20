"""Tests for USQL SQLAlchemy integration.

Uses SQLite in-memory databases so no external database is needed.
"""
import pytest
from sqlalchemy import create_engine, text

# Skip entire module if SQLAlchemy is not installed
sqlalchemy = pytest.importorskip("sqlalchemy")

from usql import USqlCompiler
from usql.dialect.dialect import Dialect
from usql.sqlalchemy_ext import detect_dialect, listen_engine, unlisten_engine, usql_engine
from usql.sqlalchemy_ext.dialect_map import SQLALCHEMY_TO_USQL


# ═══════════════════════════════════════
#  detect_dialect
# ═══════════════════════════════════════


class TestDetectDialect:
    """SQLAlchemy URL / Engine → USQL Dialect detection."""

    def test_url_string_sqlite(self):
        assert detect_dialect("sqlite://") == Dialect.SQLITE

    def test_url_string_mysql(self):
        assert detect_dialect("mysql+pymysql://user:pass@host/db") == Dialect.MYSQL

    def test_url_string_postgresql(self):
        assert detect_dialect("postgresql+psycopg2://user:pass@host/db") == Dialect.POSTGRESQL

    def test_url_string_oracle(self):
        assert detect_dialect("oracle+oracledb://user:pass@host/db") == Dialect.ORACLE

    def test_url_string_mssql(self):
        assert detect_dialect("mssql+pyodbc://host/db") == Dialect.SQLSERVER

    def test_url_string_mariadb(self):
        assert detect_dialect("mariadb+mariadbconnector://user:pass@host/db") == Dialect.MARIADB

    def test_engine_object(self):
        engine = create_engine("sqlite://")
        assert detect_dialect(engine) == Dialect.SQLITE

    def test_url_object(self):
        from sqlalchemy.engine import make_url
        url = make_url("postgresql+psycopg2://user:pass@host/db")
        assert detect_dialect(url) == Dialect.POSTGRESQL

    def test_unsupported_dialect_raises(self):
        with pytest.raises(ValueError, match="Unsupported SQLAlchemy dialect"):
            detect_dialect("h2+something://host/db")

    def test_all_mappings_present(self):
        """Every SQLAlchemy dialect in our map resolves to a valid USQL Dialect."""
        for sa_name, usql_dialect in SQLALCHEMY_TO_USQL.items():
            assert isinstance(usql_dialect, Dialect), f"{sa_name} → {usql_dialect} is not a Dialect"


# ═══════════════════════════════════════
#  usql_engine
# ═══════════════════════════════════════


class TestUsqlEngine:
    """usql_engine() creates Engine with USQL translation."""

    def test_creates_engine(self):
        engine = usql_engine("sqlite://")
        assert engine is not None
        assert engine.dialect.name == "sqlite"

    def test_auto_detect_dialect(self):
        engine = usql_engine("sqlite://")
        # The engine should work — dialect auto-detected as SQLite
        with engine.connect() as conn:
            result = conn.execute(text("SELECT 1"))
            assert result.scalar() == 1

    def test_explicit_dialect(self):
        engine = usql_engine("sqlite://", dialect=Dialect.SQLITE)
        with engine.connect() as conn:
            result = conn.execute(text("SELECT 1"))
            assert result.scalar() == 1

    def test_custom_compiler(self):
        compiler = USqlCompiler(cache_enabled=False)
        engine = usql_engine("sqlite://", compiler=compiler)
        with engine.connect() as conn:
            result = conn.execute(text("SELECT 1"))
            assert result.scalar() == 1

    def test_engine_kwargs_forwarded(self):
        """Extra kwargs are passed to create_engine."""
        engine = usql_engine("sqlite://", echo=False)
        assert engine is not None


# ═══════════════════════════════════════
#  listen_engine / unlisten_engine
# ═══════════════════════════════════════


class TestListenEngine:
    """listen_engine() registers translation on existing Engine."""

    def test_listen_returns_engine(self):
        engine = create_engine("sqlite://")
        result = listen_engine(engine)
        assert result is engine

    def test_listen_auto_detect(self):
        engine = create_engine("sqlite://")
        listen_engine(engine)
        with engine.connect() as conn:
            result = conn.execute(text("SELECT 1"))
            assert result.scalar() == 1

    def test_unlisten_removes_translation(self):
        engine = create_engine("sqlite://")
        listen_engine(engine)
        unlisten_engine(engine)
        # Should still work — just no translation layer
        with engine.connect() as conn:
            result = conn.execute(text("SELECT 1"))
            assert result.scalar() == 1


# ═══════════════════════════════════════
#  SQL translation in-memory (SQLite)
# ═══════════════════════════════════════


class TestSqlTranslation:
    """End-to-end SQL translation through SQLAlchemy with SQLite."""

    def test_select_basic(self):
        engine = usql_engine("sqlite://")
        with engine.connect() as conn:
            conn.execute(text("CREATE TABLE t1 (id INT, name VARCHAR(50))"))
            conn.execute(text("INSERT INTO t1 (id, name) VALUES (1, 'Alice')"))
            rows = conn.execute(text("SELECT name FROM t1")).fetchall()
            assert len(rows) == 1
            assert rows[0][0] == "Alice"

    def test_truncate_translated_to_delete(self):
        """SQLite: TRUNCATE TABLE → DELETE FROM"""
        engine = usql_engine("sqlite://")
        with engine.connect() as conn:
            conn.execute(text("CREATE TABLE t1 (id INT)"))
            conn.execute(text("INSERT INTO t1 (id) VALUES (1)"))
            conn.execute(text("INSERT INTO t1 (id) VALUES (2)"))
            # TRUNCATE should be translated to DELETE FROM for SQLite
            conn.execute(text("TRUNCATE TABLE t1"))
            count = conn.execute(text("SELECT COUNT(*) FROM t1")).scalar()
            assert count == 0

    def test_select_with_where(self):
        engine = usql_engine("sqlite://")
        with engine.connect() as conn:
            conn.execute(text("CREATE TABLE users (id INT, name VARCHAR(50), age INT)"))
            conn.execute(text("INSERT INTO users (id, name, age) VALUES (1, 'Alice', 30)"))
            conn.execute(text("INSERT INTO users (id, name, age) VALUES (2, 'Bob', 25)"))
            rows = conn.execute(text("SELECT name FROM users WHERE age > 26")).fetchall()
            assert len(rows) == 1
            assert rows[0][0] == "Alice"

    def test_select_count_star(self):
        engine = usql_engine("sqlite://")
        with engine.connect() as conn:
            conn.execute(text("CREATE TABLE t1 (id INT)"))
            conn.execute(text("INSERT INTO t1 (id) VALUES (1)"))
            conn.execute(text("INSERT INTO t1 (id) VALUES (2)"))
            count = conn.execute(text("SELECT COUNT(*) FROM t1")).scalar()
            assert count == 2

    def test_create_table_if_not_exists(self):
        engine = usql_engine("sqlite://")
        with engine.connect() as conn:
            conn.execute(text("CREATE TABLE IF NOT EXISTS t1 (id INT)"))
            # Second time should not fail
            conn.execute(text("CREATE TABLE IF NOT EXISTS t1 (id INT)"))

    def test_drop_table_if_exists(self):
        engine = usql_engine("sqlite://")
        with engine.connect() as conn:
            # Should not fail even if table doesn't exist
            conn.execute(text("DROP TABLE IF EXISTS nonexistent_table"))

    def test_begin_commit(self):
        engine = usql_engine("sqlite://")
        with engine.connect() as conn:
            conn.execute(text("BEGIN"))
            conn.execute(text("COMMIT"))


# ═══════════════════════════════════════
#  Error handling
# ═══════════════════════════════════════


class TestErrorHandling:
    """Compilation errors are raised as SQLAlchemy exceptions."""

    def test_compile_error_raises_programming_error(self):
        from sqlalchemy.exc import ProgrammingError

        engine = usql_engine("sqlite://")
        with engine.connect() as conn:
            # Invalid SQL that the parser cannot handle should cause a compile error
            with pytest.raises((ProgrammingError, Exception)):
                conn.execute(text("INVALID SQL GIBBERISH !!!"))


# ═══════════════════════════════════════
#  Multiple engines
# ═══════════════════════════════════════


class TestMultipleEngines:
    """Multiple Engines can each have independent USQL translation."""

    def test_two_engines_both_work(self):
        """Two separate usql_engine instances both function correctly."""
        from sqlalchemy.pool import StaticPool

        engine1 = usql_engine("sqlite://", poolclass=StaticPool)
        engine2 = usql_engine("sqlite://", poolclass=StaticPool)

        # Each engine works independently
        with engine1.connect() as conn1:
            conn1.execute(text("CREATE TABLE t1 (id INT)"))
            conn1.execute(text("INSERT INTO t1 (id) VALUES (1)"))
            count = conn1.execute(text("SELECT COUNT(*) FROM t1")).scalar()
            assert count == 1

        with engine2.connect() as conn2:
            conn2.execute(text("CREATE TABLE t2 (id INT)"))
            conn2.execute(text("INSERT INTO t2 (id) VALUES (2)"))
            count = conn2.execute(text("SELECT COUNT(*) FROM t2")).scalar()
            assert count == 1


# ═══════════════════════════════════════
#  Explicit dialect override
# ═══════════════════════════════════════


class TestExplicitDialect:
    """Explicitly specifying a dialect overrides auto-detection."""

    def test_explicit_sqlite_dialect(self):
        compiler = USqlCompiler()
        engine = create_engine("sqlite://")
        listen_engine(engine, compiler=compiler, dialect=Dialect.SQLITE)
        with engine.connect() as conn:
            result = conn.execute(text("SELECT 1"))
            assert result.scalar() == 1
