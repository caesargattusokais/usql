"""Typed expression nodes in the Semantic IR.

Every expression node carries its resolved DataType.
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum, auto
from typing import List, Optional, Union

from usql.ir.types import DataType, NullType


# ═══════════════════════════════════════
#  Enums
# ═══════════════════════════════════════

class BinaryOp(Enum):
    """Binary operators."""
    # Arithmetic
    ADD = auto(); SUB = auto(); MUL = auto(); DIV = auto(); MOD = auto()
    # Comparison
    EQ = auto(); NEQ = auto(); LT = auto(); GT = auto(); LTE = auto(); GTE = auto()
    # Logical
    AND = auto(); OR = auto()
    # String
    CONCAT = auto()
    # SQL-specific
    LIKE = auto(); NOT_LIKE = auto(); IS_DISTINCT_FROM = auto()


class UnaryOp(Enum):
    """Unary operators."""
    NEG = auto(); NOT = auto()
    IS_NULL = auto(); IS_NOT_NULL = auto()
    IS_TRUE = auto(); IS_NOT_TRUE = auto()
    IS_FALSE = auto(); IS_NOT_FALSE = auto()
    EXISTS = auto(); UNIQUE = auto()


# ═══════════════════════════════════════
#  Window / KEEP support
# ═══════════════════════════════════════

@dataclass(frozen=True)
class OrderBy:
    """Order-by item with direction and nulls ordering."""
    expr: IRExpr
    dir: str = "ASC"  # "ASC" | "DESC"
    nulls: str | None = None  # "FIRST" | "LAST" | None


@dataclass(frozen=True)
class IRWindowOver:
    """OVER clause for window functions."""
    partition_by: tuple[IRExpr, ...] = ()
    order_by: tuple[OrderBy, ...] = ()
    frame: WindowFrame | None = None


class WindowFrame:
    """Base for window frame clauses."""
    pass


@dataclass(frozen=True)
class WindowFrameBetween(WindowFrame):
    """ROWS/RANGE BETWEEN start AND end."""
    unit: str  # "ROWS" | "RANGE"
    start: str  # "UNBOUNDED PRECEDING" | "CURRENT ROW" | "UNBOUNDED FOLLOWING"
    end: str


@dataclass(frozen=True)
class WindowFrameSingle(WindowFrame):
    """ROWS/RANGE bound (no BETWEEN)."""
    unit: str
    bound: str


class KeepSpec:
    """KEEP (DENSE_RANK FIRST|LAST ORDER BY ...) — Oracle aggregate extension."""
    pass


@dataclass(frozen=True)
class KeepFirst(KeepSpec):
    order_by: tuple[OrderBy, ...]


@dataclass(frozen=True)
class KeepLast(KeepSpec):
    order_by: tuple[OrderBy, ...]


# ═══════════════════════════════════════
#  CASE support
# ═══════════════════════════════════════

@dataclass(frozen=True)
class WhenClause:
    condition: IRExpr
    result: IRExpr


# ═══════════════════════════════════════
#  Expression nodes
# ═══════════════════════════════════════

@dataclass(frozen=True)
class IRLiteral:
    """A literal value: 42, 'hello', TRUE, NULL."""
    value: object
    type: DataType

    def get_type(self) -> DataType:
        return self.type


@dataclass(frozen=True)
class IRColumnRef:
    """A column reference: name, d.name, d."name"."""
    name: str
    qualifier: str | None = None
    type: DataType | None = None

    def get_type(self) -> DataType:
        return self.type or NullType()

    @property
    def full_name(self) -> str:
        return f"{self.qualifier}.{self.name}" if self.qualifier else self.name


@dataclass(frozen=True)
class IRWildcard:
    """Wildcard: * or d.*."""
    qualifier: str | None = None

    def get_type(self) -> DataType:
        return NullType()


@dataclass(frozen=True)
class IRParameter:
    """Named parameter placeholder: :param or ?."""
    name: str
    type: DataType | None = None

    def get_type(self) -> DataType:
        return self.type or NullType()


@dataclass(frozen=True)
class IRBinaryOp:
    """Binary operation: a + b, a = b, a AND b."""
    left: IRExpr
    op: BinaryOp
    right: IRExpr
    type: DataType | None = None

    def get_type(self) -> DataType:
        return self.type or NullType()


@dataclass(frozen=True)
class IRUnaryOp:
    """Unary operation: -x, NOT x, EXISTS."""
    op: UnaryOp
    operand: IRExpr
    type: DataType | None = None

    def get_type(self) -> DataType:
        return self.type or NullType()


@dataclass(frozen=True)
class IRFunctionCall:
    """Function call: COUNT(*), UPPER(name), COALESCE(a, b)."""
    func_name: str
    args: tuple[IRExpr, ...]
    type: DataType | None = None
    over: IRWindowOver | None = None
    keep: KeepSpec | None = None
    star: bool = False  # True for COUNT(*) etc.

    def get_type(self) -> DataType:
        return self.type or NullType()


@dataclass(frozen=True)
class IRCase:
    """CASE WHEN cond THEN val ... ELSE default END."""
    whens: tuple[WhenClause, ...]
    else_expr: IRExpr | None = None
    type: DataType | None = None

    def get_type(self) -> DataType:
        return self.type or NullType()


@dataclass(frozen=True)
class IRCast:
    """CAST(expr AS targetType)."""
    expr: IRExpr
    target_type: DataType

    def get_type(self) -> DataType:
        return self.target_type


@dataclass(frozen=True)
class IRSubquery:
    """Subquery expression: (SELECT ...)."""
    query: IRSelect  # forward ref resolved at runtime
    type: DataType | None = None

    def get_type(self) -> DataType:
        return self.type or NullType()


@dataclass(frozen=True)
class IRBetween:
    """BETWEEN low AND high."""
    expr: IRExpr
    low: IRExpr
    high: IRExpr
    not_: bool = False
    type: DataType | None = None

    def get_type(self) -> DataType:
        return self.type or NullType()


@dataclass(frozen=True)
class IRInList:
    """expr IN (val1, val2, ...) or expr IN (subquery)."""
    expr: IRExpr
    values: tuple[IRExpr, ...] | None = None
    subquery: object | None = None  # IRSelect forward ref
    not_: bool = False
    type: DataType | None = None

    def get_type(self) -> DataType:
        return self.type or NullType()


@dataclass(frozen=True)
class IRIsNull:
    """x IS NULL or x IS NOT NULL."""
    expr: IRExpr
    not_: bool = False
    type: DataType | None = None

    def get_type(self) -> DataType:
        return self.type or NullType()


# Union of all expression types
IRExpr = Union[
    IRLiteral, IRColumnRef, IRWildcard, IRParameter,
    IRBinaryOp, IRUnaryOp, IRFunctionCall, IRCase,
    IRCast, IRSubquery, IRBetween, IRInList, IRIsNull,
]
