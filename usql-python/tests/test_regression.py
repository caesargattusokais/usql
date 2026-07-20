"""Comprehensive end-to-end tests for the USQL Python compiler.

Ported from Java RegressionTest.java — verifies compilation
(not execution) across all 11 dialects.
"""
import pytest
from usql import USqlCompiler
from usql.dialect.dialect import Dialect


@pytest.fixture
def compiler():
    return USqlCompiler()


# ═══════════════════════════════════════
#  All dialects
# ═══════════════════════════════════════

ALL_DIALECTS = [
    Dialect.MYSQL, Dialect.POSTGRESQL, Dialect.ORACLE, Dialect.DM,
    Dialect.SQLSERVER, Dialect.MARIADB, Dialect.TIDB, Dialect.SQLITE,
    Dialect.OCEANBASE, Dialect.CLICKHOUSE, Dialect.DUCKDB,
]


class TestDDL:
    """CREATE TABLE, CREATE INDEX, DROP TABLE, TRUNCATE, ALTER TABLE."""

    def test_create_table_basic(self, compiler):
        sql = "CREATE TABLE t1 (id INT PRIMARY KEY, name VARCHAR(100) NOT NULL)"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"
            assert "CREATE TABLE" in result.sql

    def test_create_table_if_not_exists(self, compiler):
        sql = "CREATE TABLE IF NOT EXISTS t1 (id INT PRIMARY KEY)"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"

    def test_create_table_all_types(self, compiler):
        sql = ("CREATE TABLE t1 (id INT PRIMARY KEY, name VARCHAR(100) NOT NULL, "
               "score DECIMAL(10,2), active BOOLEAN, created DATE, bio TEXT)")
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"

    def test_create_table_auto_increment(self, compiler):
        sql = "CREATE TABLE t1 (id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(50))"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"

    def test_create_index(self, compiler):
        sql = "CREATE INDEX idx_name ON t1 (name)"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"

    def test_create_unique_index(self, compiler):
        sql = "CREATE UNIQUE INDEX idx_name ON t1 (name)"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"

    def test_drop_table(self, compiler):
        sql = "DROP TABLE t1"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"

    def test_drop_table_if_exists(self, compiler):
        sql = "DROP TABLE IF EXISTS t1"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"

    def test_truncate(self, compiler):
        sql = "TRUNCATE TABLE t1"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"

    def test_alter_add_column(self, compiler):
        sql = "ALTER TABLE t1 ADD COLUMN email VARCHAR(255)"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"

    def test_alter_drop_column(self, compiler):
        sql = "ALTER TABLE t1 DROP COLUMN email"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"

    def test_alter_rename_column(self, compiler):
        sql = "ALTER TABLE t1 RENAME COLUMN name TO full_name"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"


class TestDML:
    """INSERT, UPDATE, DELETE."""

    def test_insert_values(self, compiler):
        sql = "INSERT INTO t1 (id, name) VALUES (1, 'Alice')"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"
            assert "INSERT" in result.sql

    def test_insert_multi_values(self, compiler):
        sql = "INSERT INTO t1 (id, name) VALUES (1, 'Alice'), (2, 'Bob')"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"

    def test_update(self, compiler):
        sql = "UPDATE t1 SET name = 'Bob' WHERE id = 1"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"
            assert "UPDATE" in result.sql

    def test_delete(self, compiler):
        sql = "DELETE FROM t1 WHERE id = 1"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"
            assert "DELETE" in result.sql


class TestQuery:
    """SELECT with various features."""

    def test_select_star(self, compiler):
        sql = "SELECT * FROM t1"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"

    def test_select_where(self, compiler):
        sql = "SELECT name FROM t1 WHERE id = 1"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"

    def test_select_distinct(self, compiler):
        sql = "SELECT DISTINCT name FROM t1"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"

    def test_select_group_by(self, compiler):
        sql = "SELECT dept_id, COUNT(*) AS cnt FROM t1 GROUP BY dept_id"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"

    def test_select_having(self, compiler):
        sql = "SELECT dept_id, COUNT(*) AS cnt FROM t1 GROUP BY dept_id HAVING COUNT(*) > 2"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"

    def test_select_order_by(self, compiler):
        sql = "SELECT name FROM t1 ORDER BY name"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"

    def test_select_order_by_desc(self, compiler):
        sql = "SELECT name FROM t1 ORDER BY name DESC"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"

    def test_select_limit(self, compiler):
        sql = "SELECT name FROM t1 LIMIT 10"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"

    def test_select_limit_offset(self, compiler):
        sql = "SELECT name FROM t1 LIMIT 10 OFFSET 5"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"

    def test_count_star(self, compiler):
        sql = "SELECT COUNT(*) FROM t1"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"
            assert "COUNT(*)" in result.sql

    def test_select_like(self, compiler):
        sql = "SELECT name FROM t1 WHERE name LIKE 'A%'"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"

    def test_select_between(self, compiler):
        sql = "SELECT name FROM t1 WHERE salary BETWEEN 60000 AND 80000"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"

    def test_select_in(self, compiler):
        sql = "SELECT name FROM t1 WHERE dept_id IN (1, 2)"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"

    def test_select_is_not_null(self, compiler):
        sql = "SELECT name FROM t1 WHERE name IS NOT NULL"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"

    def test_select_join(self, compiler):
        sql = "SELECT e.name, d.label FROM t1 e JOIN t2 d ON e.dept_id = d.id"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"

    def test_select_left_join(self, compiler):
        sql = "SELECT e.name, d.label FROM t1 e LEFT JOIN t2 d ON e.dept_id = d.id"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"

    def test_select_case(self, compiler):
        sql = "SELECT CASE WHEN salary > 70000 THEN 'High' ELSE 'Low' END AS lvl FROM t1"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"

    def test_select_upper(self, compiler):
        sql = "SELECT UPPER(name) FROM t1"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"

    def test_select_expression(self, compiler):
        sql = "SELECT 1 + 1 AS two"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"

    def test_select_cast(self, compiler):
        sql = "SELECT CAST(salary AS VARCHAR(10)) FROM t1"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"


class TestTCL:
    """Transaction control: BEGIN, COMMIT, ROLLBACK, SAVEPOINT."""

    def test_begin(self, compiler):
        sql = "BEGIN"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"

    def test_commit(self, compiler):
        sql = "COMMIT"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"

    def test_rollback(self, compiler):
        sql = "ROLLBACK"
        for d in ALL_DIALECTS:
            result = compiler.compile(sql, d)
            assert result.success, f"{d.display_name}: {result.errors}"


class TestDialectSpecific:
    """Dialect-specific output verification."""

    def test_oracle_select_no_from(self, compiler):
        """Oracle adds FROM DUAL for SELECT without FROM."""
        result = compiler.compile("SELECT 1", Dialect.ORACLE)
        assert result.success
        assert "DUAL" in result.sql

    def test_oracle_limit_to_rownum(self, compiler):
        """Oracle converts LIMIT to ROWNUM."""
        result = compiler.compile("SELECT * FROM t1 LIMIT 10", Dialect.ORACLE)
        assert result.success
        assert "ROWNUM" in result.sql

    def test_sqlserver_limit_to_offset_fetch(self, compiler):
        """SQL Server uses OFFSET/FETCH for LIMIT."""
        result = compiler.compile("SELECT * FROM t1 LIMIT 10", Dialect.SQLSERVER)
        assert result.success
        assert "FETCH" in result.sql

    def test_sqlite_truncate_to_delete(self, compiler):
        """SQLite replaces TRUNCATE with DELETE FROM."""
        result = compiler.compile("TRUNCATE TABLE t1", Dialect.SQLITE)
        assert result.success
        assert "DELETE FROM" in result.sql
        assert "TRUNCATE" not in result.sql

    def test_clickhouse_create_table_engine(self, compiler):
        """ClickHouse adds ENGINE = MergeTree()."""
        result = compiler.compile("CREATE TABLE t1 (id INT PRIMARY KEY)", Dialect.CLICKHOUSE)
        assert result.success
        assert "MergeTree" in result.sql

    def test_mysql_backtick_quoting(self, compiler):
        """MySQL uses backtick quoting."""
        result = compiler.compile("SELECT name FROM t1", Dialect.MYSQL)
        assert result.success
        assert "`name`" in result.sql
        assert "`t1`" in result.sql

    def test_postgresql_double_quote_quoting(self, compiler):
        """PostgreSQL uses double-quote quoting."""
        result = compiler.compile("SELECT name FROM t1", Dialect.POSTGRESQL)
        assert result.success
        assert '"name"' in result.sql
        assert '"t1"' in result.sql

    def test_sqlserver_bracket_quoting(self, compiler):
        """SQL Server uses bracket quoting."""
        result = compiler.compile("SELECT name FROM t1", Dialect.SQLSERVER)
        assert result.success
        assert "[name]" in result.sql
        assert "[t1]" in result.sql

    def test_oracle_number_type(self, compiler):
        """Oracle maps INT to NUMBER."""
        result = compiler.compile("CREATE TABLE t1 (id INT)", Dialect.ORACLE)
        assert result.success
        assert "NUMBER" in result.sql

    def test_clickhouse_int32_type(self, compiler):
        """ClickHouse maps INT to Int32."""
        result = compiler.compile("CREATE TABLE t1 (id INT)", Dialect.CLICKHOUSE)
        assert result.success
        assert "Int32" in result.sql

    def test_sqlite_integer_type(self, compiler):
        """SQLite maps INT to INTEGER."""
        result = compiler.compile("CREATE TABLE t1 (id INT)", Dialect.SQLITE)
        assert result.success
        assert "INTEGER" in result.sql

    def test_duckdb_double_quote_quoting(self, compiler):
        """DuckDB uses double-quote quoting like PostgreSQL."""
        result = compiler.compile("SELECT name FROM t1", Dialect.DUCKDB)
        assert result.success
        assert '"name"' in result.sql


class TestParser:
    """Test the parser directly."""

    def test_lexer_tokenize(self):
        from usql.parser.lexer import Lexer, TokenType
        tokens = Lexer("SELECT 1").tokenize()
        assert tokens[0].type == TokenType.SELECT
        assert tokens[1].type == TokenType.INT_LITERAL

    def test_parser_select(self):
        from usql.parser.lexer import Lexer
        from usql.parser.parser import Parser
        from usql.ast.nodes import SelectStmt
        tokens = Lexer("SELECT name FROM users WHERE id = 1").tokenize()
        stmts = Parser(tokens).parse_program()
        assert len(stmts) == 1
        assert isinstance(stmts[0], SelectStmt)

    def test_parser_create_table(self):
        from usql.parser.lexer import Lexer
        from usql.parser.parser import Parser
        from usql.ast.nodes import CreateTableStmt
        tokens = Lexer("CREATE TABLE t1 (id INT PRIMARY KEY)").tokenize()
        stmts = Parser(tokens).parse_program()
        assert len(stmts) == 1
        assert isinstance(stmts[0], CreateTableStmt)

    def test_parser_insert(self):
        from usql.parser.lexer import Lexer
        from usql.parser.parser import Parser
        from usql.ast.nodes import InsertStmt
        tokens = Lexer("INSERT INTO t1 (id) VALUES (1)").tokenize()
        stmts = Parser(tokens).parse_program()
        assert len(stmts) == 1
        assert isinstance(stmts[0], InsertStmt)


class TestCompilerAPI:
    """Test the compiler public API."""

    def test_compile_success(self, compiler):
        result = compiler.compile("SELECT 1", Dialect.MYSQL)
        assert result.success is True
        assert result.sql is not None
        assert result.errors == ()

    def test_compile_from_ir(self, compiler):
        from usql.ir.statement import IRSelect, SelectCore, IRExprSelect
        from usql.ir.expr import IRLiteral
        from usql.ir.types import IntType
        ir = IRSelect(core=SelectCore(projections=(IRExprSelect(expr=IRLiteral(value=1, type=IntType(32))),)))
        result = compiler.compile_from_ir(ir, Dialect.MYSQL)
        assert result.success

    def test_cache(self, compiler):
        compiler.clear_cache()
        r1 = compiler.compile("SELECT 1", Dialect.MYSQL)
        r2 = compiler.compile("SELECT 1", Dialect.MYSQL)
        assert r1.success and r2.success
        assert compiler.cache_size > 0

    def test_all_dialects_listed(self):
        from usql.dialect.dialect import Dialect
        expected = {"MYSQL", "POSTGRESQL", "ORACLE", "DM", "SQLSERVER",
                    "MARIADB", "TIDB", "SQLITE", "OCEANBASE", "CLICKHOUSE", "DUCKDB"}
        actual = {d.name for d in Dialect if d != Dialect.H2}
        assert expected == actual
