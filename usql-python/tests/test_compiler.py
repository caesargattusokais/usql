"""End-to-end compiler tests."""
import pytest
from usql.compiler import USqlCompiler
from usql.dialect.dialect import Dialect


class TestBasicCompile:
    """Test basic SQL compilation across dialects."""

    def setup_method(self):
        self.compiler = USqlCompiler()

    def test_select_star(self):
        result = self.compiler.compile("SELECT * FROM users", Dialect.MYSQL)
        assert result.success
        assert "SELECT" in result.sql
        assert "FROM" in result.sql

    def test_select_with_limit(self):
        result = self.compiler.compile("SELECT * FROM users LIMIT 10", Dialect.MYSQL)
        assert result.success
        assert "LIMIT" in result.sql

    def test_select_with_where(self):
        result = self.compiler.compile("SELECT name FROM users WHERE id = 1", Dialect.POSTGRESQL)
        assert result.success

    def test_insert_values(self):
        result = self.compiler.compile("INSERT INTO users (name, age) VALUES ('Alice', 30)", Dialect.MYSQL)
        assert result.success
        assert "INSERT" in result.sql

    def test_update(self):
        result = self.compiler.compile("UPDATE users SET name = 'Bob' WHERE id = 1", Dialect.MYSQL)
        assert result.success
        assert "UPDATE" in result.sql

    def test_delete(self):
        result = self.compiler.compile("DELETE FROM users WHERE id = 1", Dialect.MYSQL)
        assert result.success
        assert "DELETE" in result.sql

    def test_create_table(self):
        result = self.compiler.compile(
            "CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(100) NOT NULL)",
            Dialect.MYSQL,
        )
        assert result.success
        assert "CREATE TABLE" in result.sql

    def test_begin_transaction(self):
        result = self.compiler.compile("BEGIN", Dialect.MYSQL)
        assert result.success

    def test_commit(self):
        result = self.compiler.compile("COMMIT", Dialect.POSTGRESQL)
        assert result.success

    def test_rollback(self):
        result = self.compiler.compile("ROLLBACK", Dialect.ORACLE)
        assert result.success


class TestTclDialect:
    """Test TCL dialect-specific generation."""

    def setup_method(self):
        self.compiler = USqlCompiler()

    def test_sqlite_begin_transaction(self):
        result = self.compiler.compile("BEGIN", Dialect.SQLITE)
        assert result.success
        assert "BEGIN TRANSACTION" in result.sql

    def test_sqlserver_begin_transaction(self):
        result = self.compiler.compile("BEGIN", Dialect.SQLSERVER)
        assert result.success
        assert "BEGIN TRANSACTION" in result.sql

    def test_oracle_begin_noop(self):
        result = self.compiler.compile("BEGIN", Dialect.ORACLE)
        assert result.success
        assert "DUAL" in result.sql or "BEGIN" in result.sql

    def test_clickhouse_tcl_noop(self):
        result = self.compiler.compile("BEGIN", Dialect.CLICKHOUSE)
        assert result.success
        assert "not supported" in result.sql or "SELECT" in result.sql
