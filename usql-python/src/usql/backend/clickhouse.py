"""ClickHouse backend — columnar analytical database.

Extends MySqlBackend for similar LIMIT/OFFSET and general SQL patterns.
Key ClickHouse differences from MySQL:
  - UNION requires explicit ALL or DISTINCT
  - UPDATE/DELETE don't support qualified column references (table.col)
  - CREATE INDEX requires TYPE clause
  - ALTER COLUMN uses MODIFY COLUMN syntax
  - No CASCADE on DROP TABLE
  - No MERGE, stored procedures, or functions
  - Transactions not supported
"""
from __future__ import annotations

from io import StringIO

from usql.backend.mysql import MySqlBackend
from usql.backend.generate_options import GenerateOptions
from usql.dialect.dialect import Dialect
from usql.ir.expr import IRExpr, IRColumnRef, IRBinaryOp, IRUnaryOp, IRBetween, IRInList, IRIsNull, IRFunctionCall, IRLiteral
from usql.ir.statement import (
    IRStatement, IRSelect, IRUpdate, IRDelete, IRCreateTable, IRCreateIndex,
    IRDropTable, IRMerge, IRCreateProcedure, IRCreateFunction, IRCall,
    IRAlterTableAddColumn, IRAlterColumnType, IRAlterColumnSetDefault,
    IRAlterColumnDropDefault, IRTCL, IRColumnDef, SetClause,
    ColNotNull, ColPrimaryKey, TclType,
)
from usql.ir.types import (
    DataType, IntType, FloatType, DecimalType, CharType, VarcharType,
    TextType, BooleanType, DateType, DatetimeType, TimestampType,
    JsonType, UuidType, BinaryType, BlobType, EnumType,
)


class ClickHouseBackend(MySqlBackend):
    """ClickHouse dialect backend — MySQL-compatible with CH-specific overrides."""

    def target_dialect(self) -> Dialect:
        return Dialect.CLICKHOUSE

    def quote_identifier(self, identifier: str) -> str:
        return f"`{identifier.replace('`', '``')}`"

    def map_type(self, dtype: DataType) -> str:
        match dtype:
            case IntType(bits=8):
                return "Int8"
            case IntType(bits=16):
                return "Int16"
            case IntType(bits=32):
                return "Int32"
            case IntType(bits=64):
                return "Int64"
            case IntType():
                return "Int64"
            case DecimalType(precision=p, scale=s):
                return f"Decimal({p},{s})"
            case FloatType(bits=b):
                return "Float32" if b <= 32 else "Float64"
            case VarcharType():
                return "String"
            case CharType(length=l):
                return f"FixedString({l})"
            case TextType():
                return "String"
            case BooleanType():
                return "UInt8"
            case DateType():
                return "Date"
            case DatetimeType():
                return "DateTime"
            case TimestampType():
                return "DateTime64(3)"
            case JsonType():
                return "String"
            case UuidType():
                return "UUID"
            case BinaryType():
                return "String"
            case BlobType():
                return "String"
            case EnumType(values=vals):
                enum_vals = ", ".join(f"'{v.replace("'", "''")}'" for v in vals)
                return f"Enum8({enum_vals})"
            case _:
                return "String"

    # ═══════════════════════════════════════
    #  Dispatch override
    # ═══════════════════════════════════════

    def _dispatch(self, stmt: IRStatement, opt: GenerateOptions) -> str:
        match stmt:
            case IRCreateTable():
                return self._ch_create_table(stmt, opt)
            case IRCreateIndex():
                return self._ch_create_index(stmt, opt)
            case IRSelect():
                return self._ch_select(stmt, opt)
            case IRUpdate():
                return self._ch_update(stmt, opt)
            case IRDelete():
                return self._ch_delete(stmt, opt)
            case IRDropTable():
                return self._ch_drop_table(stmt, opt)
            case IRMerge():
                return "SELECT 1 /* ClickHouse: MERGE not supported */"
            case IRCreateProcedure():
                return "SELECT 1 /* ClickHouse: stored procedures not supported */"
            case IRCreateFunction():
                return "SELECT 1 /* ClickHouse: stored functions not supported */"
            case IRCall():
                return "SELECT 1 /* ClickHouse: CALL not supported */"
            case IRAlterColumnSetDefault():
                return self._ch_alter_set_default(stmt, opt)
            case IRAlterColumnDropDefault():
                return self._ch_alter_drop_default(stmt, opt)
            case _:
                return super()._dispatch(stmt, opt)

    # ═══════════════════════════════════════
    #  CREATE TABLE — ENGINE = MergeTree() with ORDER BY
    # ═══════════════════════════════════════

    def _ch_create_table(self, ct: IRCreateTable, opt: GenerateOptions) -> str:
        sb = StringIO()
        sb.write("CREATE TABLE ")
        if ct.if_not_exists:
            sb.write("IF NOT EXISTS ")
        sb.write(self.quote_identifier(ct.name.name))
        sb.write(" (\n")
        sb.write(",\n".join(self._ch_column_def(c, opt) for c in ct.columns))
        sb.write("\n) ENGINE = MergeTree()")

        # ORDER BY first column (or PK)
        order_col = ct.columns[0].name if ct.columns else "id"
        for col in ct.columns:
            if col.constraints:
                for c in col.constraints:
                    if isinstance(c, ColPrimaryKey):
                        order_col = col.name
                        break
        sb.write(f" ORDER BY {self.quote_identifier(order_col)}")
        return sb.getvalue()

    def _ch_column_def(self, col: IRColumnDef, opt: GenerateOptions) -> str:
        sb = StringIO()
        sb.write(f"  {self.quote_identifier(col.name)} {self.map_type(col.type)}")
        if col.default_value is not None:
            sb.write(f" DEFAULT {self.generate_expr(col.default_value, opt)}")
        if col.constraints:
            for c in col.constraints:
                match c:
                    case ColNotNull():
                        sb.write(" NOT NULL")
                    case ColPrimaryKey():
                        sb.write(" PRIMARY KEY")
        return sb.getvalue()

    # ═══════════════════════════════════════
    #  CREATE INDEX — ClickHouse requires TYPE
    # ═══════════════════════════════════════

    def _ch_create_index(self, ci: IRCreateIndex, opt: GenerateOptions) -> str:
        if ci.unique:
            # ClickHouse does not support UNIQUE index; emit a harmless no-op
            return "SELECT 1 /* ClickHouse: CREATE UNIQUE INDEX not supported */"
        sb = StringIO()
        sb.write("CREATE INDEX ")
        if ci.if_not_exists:
            sb.write("IF NOT EXISTS ")
        sb.write(self.quote_identifier(ci.name))
        sb.write(f" ON {self.quote_identifier(ci.table.name)} (")
        sb.write(", ".join(self.quote_identifier(c.name) for c in ci.columns))
        sb.write(") TYPE minmax GRANULARITY 1")
        return sb.getvalue()

    # ═══════════════════════════════════════
    #  SELECT — ClickHouse requires UNION ALL / UNION DISTINCT
    # ═══════════════════════════════════════

    def _ch_select(self, sel: IRSelect, opt: GenerateOptions) -> str:
        sql = super()._generate_select(sel, opt)
        # ClickHouse requires explicit ALL or DISTINCT after UNION
        sql = sql.replace(" UNION SELECT", " UNION DISTINCT SELECT")
        # Guard against double-replacement
        sql = sql.replace(" UNION DISTINCT DISTINCT SELECT", " UNION DISTINCT SELECT")
        return sql

    # ═══════════════════════════════════════
    #  UPDATE / DELETE — strip table qualifier from column references
    # ═══════════════════════════════════════

    def _ch_update(self, upd: IRUpdate, opt: GenerateOptions) -> str:
        # ClickHouse UPDATE doesn't support qualified column references (table.col)
        stripped = self._strip_update_qualifiers(upd)
        return super()._generate_update(stripped, opt)

    def _ch_delete(self, del_: IRDelete, opt: GenerateOptions) -> str:
        stripped = self._strip_delete_qualifiers(del_)
        return super()._generate_delete(stripped, opt)

    def _strip_update_qualifiers(self, upd: IRUpdate) -> IRUpdate:
        sets = tuple(
            SetClause(column=s.column, value=self._strip_qual(s.value))
            for s in upd.sets
        )
        where = self._strip_qual(upd.where) if upd.where is not None else None
        return IRUpdate(table=upd.table, sets=sets, where=where, capabilities=upd.capabilities)

    def _strip_delete_qualifiers(self, del_: IRDelete) -> IRDelete:
        where = self._strip_qual(del_.where) if del_.where is not None else None
        return IRDelete(table=del_.table, where=where, capabilities=del_.capabilities)

    def _strip_qual(self, expr: IRExpr) -> IRExpr:
        """Recursively strip qualifiers from column references in an expression."""
        match expr:
            case IRColumnRef(name=name, qualifier=q) if q is not None:
                return IRColumnRef(name=name, qualifier=None, type=getattr(expr, 'type', None))
            case IRBinaryOp(left=l, op=op, right=r):
                return IRBinaryOp(left=self._strip_qual(l), op=op, right=self._strip_qual(r),
                                  type=getattr(expr, 'type', None))
            case IRUnaryOp(op=op, operand=operand):
                return IRUnaryOp(op=op, operand=self._strip_qual(operand),
                                 type=getattr(expr, 'type', None))
            case IRBetween(expr=e, low=lo, high=hi, not_=n):
                return IRBetween(expr=self._strip_qual(e), low=self._strip_qual(lo),
                                 high=self._strip_qual(hi), not_=n,
                                 type=getattr(expr, 'type', None))
            case IRInList(expr=e, values=vals, subquery=sq, not_=n):
                stripped_vals = tuple(self._strip_qual(v) for v in vals) if vals else None
                return IRInList(expr=self._strip_qual(e), values=stripped_vals,
                                subquery=sq, not_=n, type=getattr(expr, 'type', None))
            case IRIsNull(expr=e, not_=n):
                return IRIsNull(expr=self._strip_qual(e), not_=n,
                                type=getattr(expr, 'type', None))
            case IRFunctionCall(func_name=fn, args=args):
                stripped_args = tuple(self._strip_qual(a) for a in args)
                return IRFunctionCall(func_name=fn, args=stripped_args,
                                      type=getattr(expr, 'type', None),
                                      over=getattr(expr, 'over', None),
                                      keep=getattr(expr, 'keep', None))
            case IRLiteral():
                return expr
            case _:
                return expr

    # ═══════════════════════════════════════
    #  ALTER COLUMN DEFAULT — ClickHouse uses MODIFY COLUMN
    # ═══════════════════════════════════════

    def _ch_alter_set_default(self, ad: IRAlterColumnSetDefault, opt: GenerateOptions) -> str:
        return (f"ALTER TABLE {self.quote_identifier(ad.table_name)} "
                f"MODIFY COLUMN {self.quote_identifier(ad.column)} "
                f"DEFAULT {self.generate_expr(ad.value, opt)}")

    def _ch_alter_drop_default(self, dd: IRAlterColumnDropDefault, opt: GenerateOptions) -> str:
        return (f"ALTER TABLE {self.quote_identifier(dd.table_name)} "
                f"MODIFY COLUMN {self.quote_identifier(dd.column)} REMOVE DEFAULT")

    def _generate_alter_column_type(self, act, opt: GenerateOptions) -> str:
        return (f"ALTER TABLE {self.quote_identifier(act.table_name)} "
                f"MODIFY COLUMN {self.quote_identifier(act.column)} {self.map_type(act.new_type)}")

    def _generate_alter_add_column(self, aa: IRAlterTableAddColumn, opt: GenerateOptions) -> str:
        col = aa.column
        sb = StringIO()
        sb.write(f"ALTER TABLE {self.quote_identifier(aa.table_name)} ADD COLUMN ")
        if aa.if_not_exists:
            sb.write("IF NOT EXISTS ")
        sb.write(f"{self.quote_identifier(col.name)} {self.map_type(col.type)}")
        return sb.getvalue()

    # ═══════════════════════════════════════
    #  DROP TABLE — ClickHouse doesn't support CASCADE
    # ═══════════════════════════════════════

    def _ch_drop_table(self, dt: IRDropTable, opt: GenerateOptions) -> str:
        result = "DROP TABLE "
        if dt.if_exists:
            result += "IF EXISTS "
        result += self.quote_identifier(dt.name)
        # ClickHouse doesn't support CASCADE — omit it
        return result

    # ═══════════════════════════════════════
    #  TCL — ClickHouse: transactions not supported
    # ═══════════════════════════════════════

    def _generate_tcl(self, tcl: IRTCL, opt: GenerateOptions) -> str:
        match tcl.type:
            case TclType.BEGIN:
                return "SELECT 1 /* ClickHouse: BEGIN TRANSACTION not supported */"
            case TclType.COMMIT:
                return "SELECT 1 /* ClickHouse: COMMIT not supported */"
            case TclType.ROLLBACK:
                return "SELECT 1 /* ClickHouse: ROLLBACK not supported */"
            case _:
                return "SELECT 1 /* ClickHouse: TCL not supported */"
