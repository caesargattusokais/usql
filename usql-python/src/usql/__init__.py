"""USQL — Universal SQL Compiler. Write once, run on 11 databases."""

__version__ = "4.0.0"

from usql.compiler import USqlCompiler
from usql.dialect.dialect import Dialect
from usql.result import CompilationResult

__all__ = ["USqlCompiler", "Dialect", "CompilationResult"]
