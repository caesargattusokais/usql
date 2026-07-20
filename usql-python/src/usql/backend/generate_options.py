"""Options controlling SQL generation output."""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum, auto


class QuoteStyle(Enum):
    """Identifier quoting style."""
    DEFAULT = auto()       # Use the dialect's default quoting rule
    ALWAYS = auto()        # Always quote all identifiers
    RESERVED_ONLY = auto() # Only quote reserved words
    NEVER = auto()         # Never quote — caller takes responsibility


@dataclass(frozen=True)
class GenerateOptions:
    """Options controlling SQL generation output."""

    quote_style: QuoteStyle = QuoteStyle.DEFAULT
    pretty_print: bool = True
    indent: str = "  "
    emit_comments: bool = False

    DEFAULTS: GenerateOptions = None  # type: ignore
    MINIMAL: GenerateOptions = None  # type: ignore


# Class-level constants
GenerateOptions.DEFAULTS = GenerateOptions(QuoteStyle.DEFAULT, True, "  ", False)
GenerateOptions.MINIMAL = GenerateOptions(QuoteStyle.DEFAULT, False, "", False)
