"""Top-level statement nodes in the Semantic IR.

This is the 'logical plan' layer — it describes intent, not syntax.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum, auto
from typing import FrozenSet, List, Optional, Union

from usql.dialect.capability import Capability
from usql.ir.expr import (
    IRExpr, IRWildcard, OrderBy, IRWindowOver, KeepSpec, WhenClause,
)
from usql.ir.types import DataType


# ═══════════════════════════════════════
#  Shared enums
# ═══════════════════════════════════════

class JoinType(Enum):
    INNER = auto(); LEFT = auto(); RIGHT = auto(); CROSS = auto(); FULL = auto()


class OrderDir(Enum):
    ASC = auto(); DESC = auto()


class NullsOrder(Enum):
    FIRST = auto(); LAST = auto()


class SetOp(Enum):
    UNION = auto(); UNION_ALL = auto(); INTERSECT = auto(); EXCEPT = auto()


class GroupByKind(Enum):
    PLAIN = auto(); ROLLUP = auto(); CUBE = auto(); GROUPING_SETS = auto()


class TclType(Enum):
    BEGIN = auto(); COMMIT = auto(); ROLLBACK = auto()
    SAVEPOINT = auto(); RELEASE_SAVEPOINT = auto(); SET_TRANSACTION = auto()


class ForeignKeyAction(Enum):
    CASCADE = auto(); SET_NULL = auto(); RESTRICT = auto(); NO_ACTION = auto()


class GeneratedStrategy(Enum):
    ALWAYS = auto(); BY_DEFAULT = auto()


class ParamMode(Enum):
    IN = auto(); OUT = auto(); INOUT = auto()


class IndexType(Enum):
    BTREE = auto(); HASH = auto(); GIST = auto(); GIN = auto(); BRIN = auto()


# ═══════════════════════════════════════
#  SELECT support types
# ═══════════════════════════════════════

@dataclass(frozen=True)
class IRSelectItem:
    """Base for SELECT list items."""
    pass


@dataclass(frozen=True)
class IRExprSelect(IRSelectItem):
    expr: IRExpr
    alias: str | None = None


@dataclass(frozen=True)
class IRWildcardSelect(IRSelectItem):
    wildcard: IRWildcard


@dataclass(frozen=True)
class IRTableRef:
    """Base for table references."""
    pass


@dataclass(frozen=True)
class IRTableName(IRTableRef):
    name: str
    alias: str | None = None
    schema: str | None = None


@dataclass(frozen=True)
class IRJoin(IRTableRef):
    left: IRTableRef
    type: JoinType
    right: IRTableRef
    on_condition: IRExpr | None = None


@dataclass(frozen=True)
class IRSubqueryTable(IRTableRef):
    query: IRSelect
    alias: str | None = None


@dataclass(frozen=True)
class IRFunctionTable(IRTableRef):
    func_name: str
    args: tuple[IRExpr, ...]
    alias: str | None = None
    lateral: bool = False


@dataclass(frozen=True)
class IRGroupBy:
    expr: IRExpr
    kind: GroupByKind = GroupByKind.PLAIN


@dataclass(frozen=True)
class FetchClause:
    """Semantic pagination — stored as intent, not syntax."""
    limit: IRExpr | None = None
    offset: IRExpr | None = None


@dataclass(frozen=True)
class IRCommonTable:
    name: str
    columns: tuple[str, ...] | None = None
    query: IRSelect = None  # type: ignore  # forward ref
    recursive: bool = False


@dataclass(frozen=True)
class SelectCore:
    projections: tuple[IRSelectItem, ...]
    from_clause: tuple[IRTableRef, ...] | None = None
    where: IRExpr | None = None
    group_by: tuple[IRGroupBy, ...] | None = None
    having: IRExpr | None = None
    with_clause: tuple[IRCommonTable, ...] | None = None
    set_op: SetOp | None = None
    set_operand: IRSelect | None = None
    distinct: bool = False


# ═══════════════════════════════════════
#  DDL support types
# ═══════════════════════════════════════

@dataclass(frozen=True)
class IRColumnConstraint:
    """Base for column-level constraints."""
    pass


@dataclass(frozen=True)
class ColNotNull(IRColumnConstraint):
    pass


@dataclass(frozen=True)
class ColUnique(IRColumnConstraint):
    clustered: bool = False


@dataclass(frozen=True)
class ColPrimaryKey(IRColumnConstraint):
    auto_increment: bool = False


@dataclass(frozen=True)
class ColCheck(IRColumnConstraint):
    condition: IRExpr


@dataclass(frozen=True)
class ColReferences(IRColumnConstraint):
    target_table: str
    target_column: str
    on_update: ForeignKeyAction | None = None
    on_delete: ForeignKeyAction | None = None


@dataclass(frozen=True)
class ColGenerated(IRColumnConstraint):
    strategy: GeneratedStrategy
    virtual: bool
    expression: IRExpr | None = None


@dataclass(frozen=True)
class IRColumnDef:
    name: str
    type: DataType
    constraints: tuple[IRColumnConstraint, ...] = ()
    default_value: IRExpr | None = None


@dataclass(frozen=True)
class IRTableConstraint:
    """Base for table-level constraints."""
    pass


@dataclass(frozen=True)
class TBUnique(IRTableConstraint):
    columns: tuple[str, ...]
    constraint_name: str | None = None


@dataclass(frozen=True)
class TBPrimaryKey(IRTableConstraint):
    columns: tuple[str, ...]
    constraint_name: str | None = None


@dataclass(frozen=True)
class TBForeignKey(IRTableConstraint):
    columns: tuple[str, ...]
    target_table: str
    target_columns: tuple[str, ...]
    constraint_name: str | None = None
    on_update: ForeignKeyAction | None = None
    on_delete: ForeignKeyAction | None = None
    deferrable: bool = False


@dataclass(frozen=True)
class TBCheck(IRTableConstraint):
    condition: IRExpr
    constraint_name: str | None = None


@dataclass(frozen=True)
class TableOptions:
    engine: str | None = None
    tablespace: str | None = None
    character_set: str | None = None
    collation: str | None = None
    comment: str | None = None


@dataclass(frozen=True)
class IndexColumn:
    name: str
    dir: OrderDir = OrderDir.ASC
    nulls: NullsOrder | None = None


@dataclass(frozen=True)
class SetClause:
    column: str
    value: IRExpr


@dataclass(frozen=True)
class ProcedureParam:
    name: str
    type: DataType
    mode: ParamMode = ParamMode.IN


# ═══════════════════════════════════════
#  MERGE support
# ═══════════════════════════════════════

@dataclass(frozen=True)
class IRMergeAction:
    """Base for MERGE actions."""
    pass


@dataclass(frozen=True)
class MergeInsert(IRMergeAction):
    columns: tuple[str, ...] | None = None
    values: tuple[IRExpr, ...] = ()


@dataclass(frozen=True)
class MergeUpdate(IRMergeAction):
    sets: tuple[SetClause, ...] = ()


@dataclass(frozen=True)
class MergeDelete(IRMergeAction):
    pass


# ═══════════════════════════════════════
#  Statement nodes
# ═══════════════════════════════════════

@dataclass(frozen=True)
class IRSelect:
    """SELECT statement."""
    core: SelectCore
    order_by: tuple[OrderBy, ...] | None = None
    fetch: FetchClause | None = None
    capabilities: frozenset[Capability] = frozenset()


@dataclass(frozen=True)
class IRInsert:
    """INSERT statement."""
    table: IRTableRef
    columns: tuple[str, ...] | None = None
    values: tuple[tuple[IRExpr, ...], ...] | None = None
    select_source: IRSelect | None = None
    ignore_errors: bool = False
    capabilities: frozenset[Capability] = frozenset()


@dataclass(frozen=True)
class IRUpdate:
    """UPDATE statement."""
    table: IRTableRef
    sets: tuple[SetClause, ...]
    where: IRExpr | None = None
    capabilities: frozenset[Capability] = frozenset()


@dataclass(frozen=True)
class IRDelete:
    """DELETE statement."""
    table: IRTableRef
    where: IRExpr | None = None
    capabilities: frozenset[Capability] = frozenset()


@dataclass(frozen=True)
class IRMerge:
    """MERGE (UPSERT) statement."""
    target: IRTableRef
    source: IRTableRef
    on_condition: IRExpr
    actions: tuple[IRMergeAction, ...] = ()
    capabilities: frozenset[Capability] = frozenset()


@dataclass(frozen=True)
class IRCreateTable:
    """CREATE TABLE statement."""
    name: IRTableName
    if_not_exists: bool = False
    columns: tuple[IRColumnDef, ...] = ()
    constraints: tuple[IRTableConstraint, ...] = ()
    options: TableOptions | None = None
    capabilities: frozenset[Capability] = frozenset()


@dataclass(frozen=True)
class IRCreateIndex:
    """CREATE INDEX statement."""
    name: str
    table: IRTableName
    columns: tuple[IndexColumn, ...] = ()
    unique: bool = False
    if_not_exists: bool = False
    index_type: IndexType | None = None
    where_clause: IRExpr | None = None
    capabilities: frozenset[Capability] = frozenset()


@dataclass(frozen=True)
class IRDropTable:
    name: str
    if_exists: bool = False
    cascade: bool = False
    capabilities: frozenset[Capability] = frozenset()


@dataclass(frozen=True)
class IRDropIndex:
    index_name: str
    table_name: str | None = None
    if_exists: bool = False
    capabilities: frozenset[Capability] = frozenset()


@dataclass(frozen=True)
class IRDropDatabase:
    name: str
    if_exists: bool = False
    capabilities: frozenset[Capability] = frozenset()


@dataclass(frozen=True)
class IRCreateView:
    name: str
    query: IRSelect
    capabilities: frozenset[Capability] = frozenset()


@dataclass(frozen=True)
class IRCreateSchema:
    name: str
    capabilities: frozenset[Capability] = frozenset()


@dataclass(frozen=True)
class IRTCL:
    """TCL (Transaction Control Language) statement."""
    type: TclType
    savepoint_name: str | None = None
    capabilities: frozenset[Capability] = frozenset()


@dataclass(frozen=True)
class IRTruncateTable:
    name: str
    capabilities: frozenset[Capability] = frozenset()


@dataclass(frozen=True)
class IRAlterTableAddColumn:
    table_name: str
    column: IRColumnDef
    if_not_exists: bool = False
    capabilities: frozenset[Capability] = frozenset()


@dataclass(frozen=True)
class IRAlterTableDropColumn:
    table_name: str
    column_name: str
    capabilities: frozenset[Capability] = frozenset()


@dataclass(frozen=True)
class IRAlterColumnType:
    table_name: str
    column: str
    new_type: DataType
    capabilities: frozenset[Capability] = frozenset()


@dataclass(frozen=True)
class IRAlterColumnSetDefault:
    table_name: str
    column: str
    value: IRExpr
    capabilities: frozenset[Capability] = frozenset()


@dataclass(frozen=True)
class IRAlterColumnDropDefault:
    table_name: str
    column: str
    capabilities: frozenset[Capability] = frozenset()


@dataclass(frozen=True)
class IRRenameColumn:
    table_name: str
    old_name: str
    new_name: str
    capabilities: frozenset[Capability] = frozenset()


@dataclass(frozen=True)
class IRCreateProcedure:
    """CREATE PROCEDURE — body is raw dialect-specific SQL."""
    name: str
    params: tuple[ProcedureParam, ...] = ()
    body: str | None = None
    or_replace: bool = False
    capabilities: frozenset[Capability] = frozenset()


@dataclass(frozen=True)
class IRCreateFunction:
    """CREATE FUNCTION — returns a scalar value."""
    name: str
    params: tuple[ProcedureParam, ...] = ()
    return_type: DataType | None = None
    body: str | None = None
    or_replace: bool = False
    capabilities: frozenset[Capability] = frozenset()


@dataclass(frozen=True)
class IRCall:
    """CALL procedure(args)."""
    procedure_name: str
    args: tuple[IRExpr, ...] = ()
    capabilities: frozenset[Capability] = frozenset()


# Union of all statement types
IRStatement = Union[
    IRSelect, IRInsert, IRUpdate, IRDelete, IRMerge,
    IRCreateTable, IRCreateIndex, IRDropTable, IRDropIndex,
    IRTruncateTable, IRAlterTableAddColumn, IRAlterTableDropColumn,
    IRRenameColumn, IRAlterColumnType, IRAlterColumnSetDefault,
    IRAlterColumnDropDefault, IRDropDatabase, IRCreateView,
    IRCreateSchema, IRTCL, IRCreateProcedure, IRCreateFunction, IRCall,
]
