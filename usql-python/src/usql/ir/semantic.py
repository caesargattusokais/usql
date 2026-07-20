"""Semantic IR wrapper — holds the root statement and metadata."""
from __future__ import annotations

from dataclasses import dataclass

from usql.ir.statement import IRStatement


@dataclass(frozen=True)
class SemanticIR:
    """Wrapper around a root IR statement."""
    root: IRStatement
