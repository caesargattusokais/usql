"""Result of compiling a U-SQL statement.

Contains either the generated SQL + metadata, or a list of errors.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import List


@dataclass(frozen=True)
class Error:
    line: int
    col: int
    message: str
    hint: str | None = None

    @classmethod
    def of(cls, line: int, col: int, message: str, hint: str | None = None) -> Error:
        return cls(line, col, message, hint)


@dataclass(frozen=True)
class Warning:
    line: int
    col: int
    message: str
    hint: str | None = None

    @classmethod
    def of(cls, line: int, col: int, message: str, hint: str | None = None) -> Warning:
        return cls(line, col, message, hint)


class CompilationResult:
    """Result of compiling a U-SQL statement."""

    __slots__ = ('_sql', '_reference_sql', '_warnings', '_errors', '_success', '_source_location')

    def __init__(
        self,
        sql: str | None,
        reference_sql: str | None,
        warnings: list[Warning],
        errors: list[Error],
        success: bool,
        source_location: str | None = None,
    ):
        self._sql = sql
        self._reference_sql = reference_sql
        self._warnings = tuple(warnings)
        self._errors = tuple(errors)
        self._success = success
        self._source_location = source_location

    @classmethod
    def ok(cls, sql: str, warnings: list[Warning] | None = None) -> CompilationResult:
        return cls(sql, None, warnings or [], [], True)

    @classmethod
    def ok_with_reference(
        cls, sql: str, reference_sql: str, warnings: list[Warning] | None = None
    ) -> CompilationResult:
        return cls(sql, reference_sql, warnings or [], [], True)

    @classmethod
    def failed(cls, errors: list[Error], source: str | None = None) -> CompilationResult:
        return cls(None, None, [], errors, False, source)

    @property
    def success(self) -> bool:
        return self._success

    @property
    def sql(self) -> str | None:
        return self._sql

    @property
    def reference_sql(self) -> str | None:
        return self._reference_sql

    @property
    def warnings(self) -> tuple[Warning, ...]:
        return self._warnings

    @property
    def errors(self) -> tuple[Error, ...]:
        return self._errors

    def report(self) -> str:
        """Combine warnings and errors into a human-readable report."""
        lines: list[str] = []
        status = "✅ SUCCESS" if self._success else "❌ FAILED"
        lines.append(f"── USQL Compilation {status} ──")

        if self._source_location:
            lines.append(f"Source: {self._source_location}")

        for err in self._errors:
            lines.append(f"  Error [{err.line}:{err.col}] {err.message}")
            if err.hint:
                lines.append(f"    → Hint: {err.hint}")

        for warn in self._warnings:
            lines.append(f"  Warning [{warn.line}:{warn.col}] {warn.message}")
            if warn.hint:
                lines.append(f"    → Hint: {warn.hint}")

        if self._success and self._sql:
            lines.append(f"\nGenerated SQL:\n{self._sql}")
            if self._reference_sql:
                lines.append(f"\n[H2 Reference]:\n{self._reference_sql}")

        return "\n".join(lines)
