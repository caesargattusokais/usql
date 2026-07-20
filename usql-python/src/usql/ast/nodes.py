"""AST node definitions for USQL — mirrors Java USqlAst exactly."""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Union


# ══════════════════════════════════════════════════
#  Enums
# ══════════════════════════════════════════════════


class BinOp(Enum):
    ADD = "ADD"
    SUB = "SUB"
    MUL = "MUL"
    DIV = "DIV"
    MOD = "MOD"
    EQ = "EQ"
    NEQ = "NEQ"
    LT = "LT"
    GT = "GT"
    LTE = "LTE"
    GTE = "GTE"
    AND = "AND"
    OR = "OR"
    CONCAT = "CONCAT"
    LIKE = "LIKE"
    NOT_LIKE = "NOT_LIKE"


class UnOp(Enum):
    NEG = "NEG"
    NOT = "NOT"
    IS_NULL = "IS_NULL"
    IS_NOT_NULL = "IS_NOT_NULL"
    IS_TRUE = "IS_TRUE"
    IS_NOT_TRUE = "IS_NOT_TRUE"
    IS_FALSE = "IS_FALSE"
    IS_NOT_FALSE = "IS_NOT_FALSE"
    EXISTS = "EXISTS"


class JoinType(Enum):
    INNER = "INNER"
    LEFT = "LEFT"
    RIGHT = "RIGHT"
    CROSS = "CROSS"
    FULL = "FULL"


class SetOp(Enum):
    UNION = "UNION"
    UNION_ALL = "UNION_ALL"
    INTERSECT = "INTERSECT"
    EXCEPT = "EXCEPT"


class GroupByKind(Enum):
    PLAIN = "PLAIN"
    ROLLUP = "ROLLUP"
    CUBE = "CUBE"
    GROUPING_SETS = "GROUPING_SETS"


class ParamDir(Enum):
    IN = "IN"
    OUT = "OUT"
    INOUT = "INOUT"


class WindowFrameUnit(Enum):
    ROWS = "ROWS"
    RANGE = "RANGE"


class WindowFrameBound(Enum):
    UNBOUNDED_PRECEDING = "UNBOUNDED_PRECEDING"
    CURRENT_ROW = "CURRENT_ROW"
    UNBOUNDED_FOLLOWING = "UNBOUNDED_FOLLOWING"


# ══════════════════════════════════════════════════
#  Top-level Statement
# ══════════════════════════════════════════════════


@dataclass
class Statement:
    """Base class for all statements."""
    pass


# ══════════════════════════════════════════════════
#  SELECT statement & components
# ══════════════════════════════════════════════════


@dataclass
class SelectStmt(Statement):
    with_clause: list[CommonTable] | None
    distinct: bool
    projections: list[SelectItem]
    from_: list[TableRef] | None
    where: Expression | None
    group_by: list[GroupByItem] | None
    having: Expression | None
    order_by: list[OrderByItem] | None
    fetch: FetchClause | None
    set_op: SetOp | None
    set_operand: SelectStmt | None


@dataclass
class CommonTable:
    name: str
    columns: list[str] | None
    query: SelectStmt
    recursive: bool


@dataclass
class SelectItem:
    """Base class for select items."""
    pass


@dataclass
class ExprItem(SelectItem):
    expr: Expression
    alias: str | None


@dataclass
class StarItem(SelectItem):
    qualifier: str | None


# ══════════════════════════════════════════════════
#  Table references
# ══════════════════════════════════════════════════


@dataclass
class TableRef:
    """Base class for table references."""
    pass


@dataclass
class SimpleTable(TableRef):
    name: str
    alias: str | None


@dataclass
class SubqueryTable(TableRef):
    query: SelectStmt
    alias: str | None


@dataclass
class JoinTable(TableRef):
    left: TableRef
    type: JoinType
    right: TableRef
    condition: Expression | None


@dataclass
class FunctionTable(TableRef):
    lateral: bool
    func_name: str
    args: list[Expression]
    alias: str | None


# ══════════════════════════════════════════════════
#  GROUP BY / ORDER BY / FETCH
# ══════════════════════════════════════════════════


@dataclass
class GroupByItem:
    expr: Expression
    kind: GroupByKind


@dataclass
class OrderByItem:
    expr: Expression
    desc: bool
    nulls_first: bool


@dataclass
class FetchClause:
    limit: Expression
    offset: Expression | None


# ══════════════════════════════════════════════════
#  INSERT / UPDATE / DELETE / MERGE
# ══════════════════════════════════════════════════


@dataclass
class InsertStmt(Statement):
    ignore: bool
    table: TableRef
    columns: list[str] | None
    values: list[list[Expression]] | None
    select_source: SelectStmt | None


@dataclass
class UpdateStmt(Statement):
    table: TableRef
    sets: list[SetClause]
    where: Expression | None


@dataclass
class DeleteStmt(Statement):
    table: TableRef
    where: Expression | None


@dataclass
class MergeStmt(Statement):
    target: TableRef
    target_alias: str | None
    source: TableRef
    on_condition: Expression
    actions: list[MergeAction]


@dataclass
class SetClause:
    column: str
    value: Expression


@dataclass
class MergeAction:
    """Base class for merge actions."""
    pass


@dataclass
class MergeInsert(MergeAction):
    columns: list[str] | None
    values: list[Expression]


@dataclass
class MergeUpdate(MergeAction):
    sets: list[SetClause]


@dataclass
class MergeDelete(MergeAction):
    pass


# ══════════════════════════════════════════════════
#  DDL — CREATE TABLE
# ══════════════════════════════════════════════════


@dataclass
class CreateTableStmt(Statement):
    if_not_exists: bool
    table_name: str
    columns: list[ColumnDef]
    constraints: list[TableConstraint]
    options: TableOptions


@dataclass
class ColumnDef:
    name: str
    type_name: str
    type_precision: int
    type_scale: int
    enum_values: list[str] | None
    constraints: list[ColumnConstraint]
    default_value: Expression | None


@dataclass
class ColumnConstraint:
    """Base class for column constraints."""
    pass


@dataclass
class NotNullConstraint(ColumnConstraint):
    pass


@dataclass
class NullConstraint(ColumnConstraint):
    pass


@dataclass
class PrimaryKeyConstraint(ColumnConstraint):
    auto_increment: bool


@dataclass
class UniqueConstraint(ColumnConstraint):
    pass


@dataclass
class CheckConstraint(ColumnConstraint):
    condition: Expression


@dataclass
class ReferencesConstraint(ColumnConstraint):
    target_table: str
    target_column: str
    on_update: str | None
    on_delete: str | None


@dataclass
class GeneratedConstraint(ColumnConstraint):
    always: bool
    virtual: bool
    expression: Expression | None


@dataclass
class TableConstraint:
    """Base class for table-level constraints."""
    pass


@dataclass
class TbPrimaryKey(TableConstraint):
    columns: list[str]
    name: str | None


@dataclass
class TbUnique(TableConstraint):
    columns: list[str]
    name: str | None


@dataclass
class TbForeignKey(TableConstraint):
    columns: list[str]
    target_table: str
    target_columns: list[str]
    name: str | None
    on_update: str | None
    on_delete: str | None


@dataclass
class TbCheck(TableConstraint):
    condition: Expression
    name: str | None


@dataclass
class TableOptions:
    engine: str | None
    tablespace: str | None
    character_set: str | None
    collation: str | None
    comment: str | None


# ══════════════════════════════════════════════════
#  DDL — CREATE INDEX
# ══════════════════════════════════════════════════


@dataclass
class CreateIndexStmt(Statement):
    unique: bool
    if_not_exists: bool
    name: str
    table_name: str
    columns: list[IndexColumn]
    where: Expression | None


@dataclass
class IndexColumn:
    name: str
    desc: bool
    nulls_first: bool


# ══════════════════════════════════════════════════
#  DDL — CREATE VIEW / SCHEMA / PROCEDURE / FUNCTION
# ══════════════════════════════════════════════════


@dataclass
class CreateViewStmt(Statement):
    view_name: str
    query: SelectStmt


@dataclass
class CreateSchemaStmt(Statement):
    schema_name: str


@dataclass
class CreateProcedureStmt(Statement):
    name: str
    params: list[ParamDef]
    or_replace: bool
    body: str | None


@dataclass
class CreateFunctionStmt(Statement):
    name: str
    params: list[ParamDef]
    return_type: DataTypeDecl
    or_replace: bool
    body: str | None


@dataclass
class CallStmt(Statement):
    name: str
    args: list[Expression]


@dataclass
class ParamDef:
    name: str
    type: DataTypeDecl
    direction: ParamDir


@dataclass
class DataTypeDecl:
    name: str
    precision: int
    scale: int


# ══════════════════════════════════════════════════
#  DDL — DROP / TRUNCATE / ALTER
# ══════════════════════════════════════════════════


@dataclass
class DropTableStmt(Statement):
    table_name: str
    if_exists: bool
    cascade: bool


@dataclass
class DropIndexStmt(Statement):
    index_name: str
    table_name: str | None
    if_exists: bool


@dataclass
class DropDatabaseStmt(Statement):
    db_name: str
    if_exists: bool


@dataclass
class TruncateStmt(Statement):
    table_name: str


@dataclass
class AlterTableStmt(Statement):
    table_name: str
    action: AlterAction


@dataclass
class TCLStmt(Statement):
    sql: str


@dataclass
class AlterAction:
    """Base class for ALTER TABLE actions."""
    pass


@dataclass
class AddColumn(AlterAction):
    name: str
    type: DataTypeDecl
    constraints: list[ColumnConstraint]
    default_val: Expression | None
    if_not_exists: bool


@dataclass
class DropColumn(AlterAction):
    name: str


@dataclass
class AlterColumnType(AlterAction):
    column: str
    new_type: DataTypeDecl


@dataclass
class AlterColumnSetDefault(AlterAction):
    column: str
    default_val: Expression


@dataclass
class AlterColumnDropDefault(AlterAction):
    column: str


@dataclass
class RenameColumn(AlterAction):
    old_name: str
    new_name: str


# ══════════════════════════════════════════════════
#  Expressions
# ══════════════════════════════════════════════════


@dataclass
class Expression:
    """Base class for all expressions."""
    pass


# -- Literals --


@dataclass
class IntLiteral(Expression):
    value: int


@dataclass
class FloatLiteral(Expression):
    value: float


@dataclass
class StringLiteral(Expression):
    value: str


@dataclass
class BoolLiteral(Expression):
    value: bool


@dataclass
class NullLiteral(Expression):
    pass


@dataclass
class DateLiteral(Expression):
    value: str


@dataclass
class TimestampLiteral(Expression):
    value: str


@dataclass
class IntervalLiteral(Expression):
    value: str
    unit: str | None


# -- References --


@dataclass
class ColumnRef(Expression):
    qualifier: list[str]
    name: str

    def qualifier_str(self) -> str | None:
        return ".".join(self.qualifier) if self.qualifier else None


@dataclass
class StarExpr(Expression):
    qualifier: str | None


# -- Parameter --


@dataclass
class ParamRef(Expression):
    name: str


# -- Operators --


@dataclass
class BinaryOp(Expression):
    left: Expression
    op: BinOp
    right: Expression


@dataclass
class UnaryOp(Expression):
    op: UnOp
    operand: Expression


# -- Function call --


@dataclass
class FunctionCall(Expression):
    name: str
    args: list[Expression]
    star: bool
    keep: KeepClause | None
    over: WindowOver | None


@dataclass
class KeepClause:
    last: bool
    order_by: list[OrderByItem]


@dataclass
class WindowOver:
    partition_by: list[Expression] | None
    order_by: list[OrderByItem] | None
    frame: WindowFrame | None


@dataclass
class WindowFrame:
    """Base class for window frame specifications."""
    pass


@dataclass
class WindowFrameBetween(WindowFrame):
    unit: WindowFrameUnit
    start: WindowFrameBound
    end: WindowFrameBound


@dataclass
class WindowFrameSingle(WindowFrame):
    unit: WindowFrameUnit
    bound: WindowFrameBound


# -- CASE --


@dataclass
class CaseExpr(Expression):
    operand: Expression | None
    whens: list[WhenClause]
    else_expr: Expression | None


@dataclass
class WhenClause:
    condition: Expression
    result: Expression


# -- Other --


@dataclass
class BetweenExpr(Expression):
    expr: Expression
    low: Expression
    high: Expression
    not_: bool


@dataclass
class InListExpr(Expression):
    expr: Expression
    values: list[Expression] | None
    subquery: SelectStmt | None
    not_: bool


@dataclass
class CastExpr(Expression):
    expr: Expression
    type_name: str
    precision: int
    scale: int


@dataclass
class SubqueryExpr(Expression):
    query: SelectStmt


@dataclass
class IsNullExpr(Expression):
    expr: Expression
    not_: bool
