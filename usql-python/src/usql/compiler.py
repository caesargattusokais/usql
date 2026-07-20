"""Main entry point for the USQL compiler.

Full pipeline: Text → Lexer → Parser → AST → Semantic Analysis → IR → Backend → SQL
"""
from __future__ import annotations

from collections import OrderedDict
from typing import Dict, List, Optional

from usql.backend.base import AbstractDialectBackend
from usql.backend.generate_options import GenerateOptions
from usql.capability.checker import CapabilityChecker, Severity
from usql.capability.polyfill import PolyfillEngine
from usql.catalog.function import FunctionCatalog
from usql.dialect.dialect import Dialect
from usql.ir.statement import IRStatement
from usql.ir.semantic import SemanticIR
from usql.optimizer.optimizer import optimize
from usql.result import CompilationResult, Error, Warning
from usql.schema import SchemaProvider, EmptySchemaProvider


class USqlCompiler:
    """Main entry point for the USQL compiler.

    Usage:
        compiler = USqlCompiler()
        result = compiler.compile(
            "SELECT name, COUNT(*) AS cnt FROM users GROUP BY name LIMIT 10",
            Dialect.ORACLE
        )
        if result.success:
            print(result.sql)
    """

    def __init__(
        self,
        *,
        schema: SchemaProvider | None = None,
        verify: bool = False,
        optimize_level: int = 1,
        default_dialect: Dialect = Dialect.MYSQL,
        cache_enabled: bool = True,
        cache_size: int = 256,
    ):
        self._schema = schema or EmptySchemaProvider()
        self._verify = verify
        self._optimize_level = optimize_level
        self._default_dialect = default_dialect
        self._function_catalog = FunctionCatalog()

        # Initialize backends (lazy imports to avoid circular deps)
        from usql.backend.mysql import MySqlBackend
        from usql.backend.postgresql import PgBackend
        from usql.backend.oracle import OracleBackend
        from usql.backend.dm import DmBackend
        from usql.backend.sqlserver import SqlServerBackend
        from usql.backend.mariadb import MariaDbBackend
        from usql.backend.tidb import TiDbBackend
        from usql.backend.sqlite import SqliteBackend
        from usql.backend.oceanbase import OceanBaseBackend
        from usql.backend.clickhouse import ClickHouseBackend
        from usql.backend.duckdb import DuckDbBackend

        self._backends: dict[Dialect, AbstractDialectBackend] = {
            Dialect.MYSQL:      MySqlBackend(),
            Dialect.POSTGRESQL: PgBackend(),
            Dialect.ORACLE:     OracleBackend(),
            Dialect.DM:         DmBackend(),
            Dialect.SQLSERVER:  SqlServerBackend(),
            Dialect.MARIADB:    MariaDbBackend(),
            Dialect.TIDB:       TiDbBackend(),
            Dialect.SQLITE:     SqliteBackend(),
            Dialect.OCEANBASE:  OceanBaseBackend(),
            Dialect.CLICKHOUSE: ClickHouseBackend(),
            Dialect.DUCKDB:     DuckDbBackend(),
        }

        # Inject function catalog
        for backend in self._backends.values():
            backend.set_function_catalog(self._function_catalog)

        self._capability_checker = CapabilityChecker()
        self._polyfill_engine = PolyfillEngine()

        # Plan cache
        self._cache_enabled = cache_enabled
        self._cache_size = cache_size
        self._cache: OrderedDict[str, SemanticIR] = OrderedDict() if cache_enabled else OrderedDict()

    # ═══════════════════════════════════════
    #  Public API — from U-SQL text
    # ═══════════════════════════════════════

    def compile(
        self,
        usql: str,
        target: Dialect,
        options: GenerateOptions | None = None,
    ) -> CompilationResult:
        """Compile U-SQL text to the target dialect.

        Full pipeline: Text → Lexer → Parser → AST → Semantic Analysis → IR → Backend → SQL
        """
        # 1. Cache check
        if self._cache_enabled and usql in self._cache:
            return self._compile_from_ir(self._cache[usql].root, target, options)

        # 2. Parse
        try:
            ir = self._parse_to_ir(usql)
        except Exception as e:
            return CompilationResult.failed([Error.of(0, 0, f"Parse error: {e}")])

        # 3. Semantic analysis (AST → IR) — for now, parse directly produces IR
        # Future: SemanticAnalyzer.analyze(ast) → IR

        # 4. IR optimization
        if self._optimize_level > 0:
            ir = optimize(ir, self._optimize_level)

        # 5. Cache
        if self._cache_enabled:
            self._cache[usql] = SemanticIR(root=ir)
            if len(self._cache) > self._cache_size:
                self._cache.popitem(last=False)

        # 6. Capability check + polyfill + generate
        return self._compile_from_ir(ir, target, options)

    def _parse_to_ir(self, sql: str) -> IRStatement:
        """Parse SQL text to USQL IR via Lexer → Parser → SemanticAnalyzer."""
        from usql.parser.lexer import Lexer
        from usql.parser.parser import Parser
        from usql.analyzer.semantic import SemanticAnalyzer

        tokens = Lexer(sql).tokenize()
        ast_nodes = Parser(tokens).parse_program()
        if not ast_nodes:
            raise ValueError("Empty input — no statements found")

        analyzer = SemanticAnalyzer(self._schema, self._function_catalog)
        return analyzer.analyze(ast_nodes[0])

    # ═══════════════════════════════════════
    #  Public API — from IR directly
    # ═══════════════════════════════════════

    def compile_from_ir(
        self,
        ir: IRStatement,
        target: Dialect,
        options: GenerateOptions | None = None,
    ) -> CompilationResult:
        """Compile from IR directly (skip parse + analyze)."""
        return self._compile_from_ir(ir, target, options)

    # ═══════════════════════════════════════
    #  Internal: IR → SQL
    # ═══════════════════════════════════════

    def _compile_from_ir(
        self,
        ir: IRStatement,
        target: Dialect,
        options: GenerateOptions | None = None,
    ) -> CompilationResult:
        """Capability check → polyfill → generate."""
        options = options or GenerateOptions.DEFAULTS

        # Capability check
        report = self._capability_checker.check(ir, target)
        if report.has_fatal:
            fatal_errors = [
                Error.of(0, 0, f.message)
                for f in report.findings
                if f.severity == Severity.ERROR
            ]
            return CompilationResult.failed(fatal_errors)

        # Polyfill
        if report.has_missing:
            ir = self._polyfill_engine.apply(ir, report, target)

        # Generate
        backend = self._backends.get(target)
        if not backend:
            return CompilationResult.failed([Error.of(0, 0, f"No backend registered for dialect: {target}")])

        sql = backend.generate(ir, options)

        # Verification (optional — generates H2 reference SQL)
        ref_sql = None
        if self._verify:
            ref_backend = self._backends.get(Dialect.H2)
            if ref_backend:
                ref_sql = ref_backend.generate(ir, GenerateOptions.MINIMAL)

        # Warnings
        warnings = [
            Warning.of(0, 0, f"[{target.display_name}] {f.message}")
            for f in report.findings
        ]

        if ref_sql:
            return CompilationResult.ok_with_reference(sql, ref_sql, warnings)
        return CompilationResult.ok(sql, warnings)

    # ═══════════════════════════════════════
    #  Cache management
    # ═══════════════════════════════════════

    def clear_cache(self) -> None:
        """Clear the compiled plan cache."""
        self._cache.clear()

    @property
    def cache_size(self) -> int:
        """Number of cached plans."""
        return len(self._cache)
