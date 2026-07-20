"""SQL Server (T-SQL) backend — generates SQL Server-compatible SQL from the Semantic IR."""
from __future__ import annotations

from io import StringIO

from usql.backend.base import AbstractDialectBackend
from usql.backend.generate_options import GenerateOptions
from usql.dialect.dialect import Dialect
from usql.ir.expr import IRExpr, BinaryOp
from usql.ir.statement import (
    IRStatement, IRSelect, IRCreateTable, IRCreateIndex, IRDropIndex,
    IRAlterColumnType, IRRenameColumn, IRAlterColumnSetDefault,
    IRCreateProcedure, IRCreateFunction, IRCall, IRTCL,
    ColNotNull, ColPrimaryKey, ColUnique, ColCheck, ColReferences,
    OrderDir, ParamMode, ProcedureParam, TclType,
)
from usql.ir.types import (
    DataType, IntType, FloatType, DecimalType, CharType, VarcharType,
    TextType, BooleanType, DateType, TimeType, DatetimeType, TimestampType,
    JsonType, UuidType, BinaryType, VarbinaryType, BlobType, EnumType,
)


class SqlServerBackend(AbstractDialectBackend):
    """Generates SQL Server (T-SQL) compatible SQL from the Semantic IR."""

    def target_dialect(self) -> Dialect:
        return Dialect.SQLSERVER

    def quote_identifier(self, identifier: str) -> str:
        return f"[{identifier.replace(']', ']]')}]"

    def map_type(self, dtype: DataType) -> str:
        match dtype:
            case IntType(bits=8):
                return "TINYINT"
            case IntType(bits=16):
                return "SMALLINT"
            case IntType(bits=32):
                return "INT"
            case IntType(bits=64):
                return "BIGINT"
            case IntType():
                return "INT"
            case DecimalType(precision=p, scale=s):
                return f"DECIMAL({p},{s})"
            case FloatType(bits=b):
                return "REAL" if b <= 32 else "FLOAT(53)"
            case CharType(length=l):
                return f"CHAR({l})"
            case VarcharType(length=l):
                return f"VARCHAR({l})"
            case TextType():
                return "NVARCHAR(MAX)"
            case BooleanType():
                return "BIT"
            case DateType():
                return "DATE"
            case TimeType(fractional_seconds=fs):
                return f"TIME({fs})"
            case DatetimeType(fractional_seconds=fs):
                return f"DATETIME2({fs})"
            case TimestampType(fractional_seconds=fs):
                return f"DATETIME2({fs})"
            case JsonType():
                return "NVARCHAR(MAX)"
            case UuidType():
                return "UNIQUEIDENTIFIER"
            case BinaryType(length=l):
                return f"BINARY({l})"
            case VarbinaryType(length=l):
                return f"VARBINARY({l})"
            case BlobType():
                return "VARBINARY(MAX)"
            case EnumType():
                return "VARCHAR(255)"
            case _:
                return getattr(dtype, 'type_name', type(dtype).__name__)

    # ═══════════════════════════════════════
    #  SELECT — OFFSET/FETCH pagination
    # ═══════════════════════════════════════

    def _generate_select(self, sel: IRSelect, opt: GenerateOptions) -> str:
        sb = StringIO()

        # WITH clause (SQL Server uses WITH for both recursive and non-recursive CTEs)
        core = sel.core
        if core.with_clause:
            sb.write("WITH ")
            sb.write(", ".join(
                f"{self.quote_identifier(cte.name)} AS ({self._generate_select(cte.query, opt)})"
                for cte in core.with_clause
            ))
            sb.write(" ")

        # SELECT
        sb.write("SELECT ")
        if core.distinct:
            sb.write("DISTINCT ")
        sb.write(", ".join(self._generate_select_item(p, opt) for p in core.projections))

        # FROM
        if core.from_clause:
            sb.write(" FROM ")
            sb.write(", ".join(self._generate_table_ref(f, opt) for f in core.from_clause))

        # WHERE
        if core.where is not None:
            sb.write(" WHERE ")
            sb.write(self.generate_expr(core.where, opt))

        # GROUP BY
        if core.group_by:
            sb.write(" GROUP BY ")
            sb.write(", ".join(self._generate_group_by(g, opt) for g in core.group_by))

        # HAVING
        if core.having is not None:
            sb.write(" HAVING ")
            sb.write(self.generate_expr(core.having, opt))

        # ORDER BY
        if sel.order_by:
            sb.write(" ORDER BY ")
            sb.write(", ".join(self._generate_order_by(o, opt) for o in sel.order_by))

        # SQL Server 2012+: OFFSET ... FETCH (requires ORDER BY)
        if sel.fetch:
            has_order_by = bool(sel.order_by)
            if not has_order_by:
                sb.write(" ORDER BY (SELECT NULL)")
            if sel.fetch.offset is not None:
                sb.write(" OFFSET ")
                sb.write(self.generate_expr(sel.fetch.offset, opt))
                sb.write(" ROWS")
            if sel.fetch.limit is not None:
                if sel.fetch.offset is None:
                    sb.write(" OFFSET 0 ROWS")
                sb.write(" FETCH NEXT ")
                sb.write(self.generate_expr(sel.fetch.limit, opt))
                sb.write(" ROWS ONLY")

        # Set operations
        if core.set_op and core.set_operand:
            op_name = core.set_op.name.replace("_", " ")
            sb.write(f" {op_name} ")
            sb.write(self._generate_select(core.set_operand, opt))

        return sb.getvalue()

    # ═══════════════════════════════════════
    #  Expression overrides — SQL Server uses + for string concat
    # ═══════════════════════════════════════

    def _gen_binary_op(self, bo, opt: GenerateOptions) -> str:
        left = self.generate_expr(bo.left, opt)
        right = self.generate_expr(bo.right, opt)
        op = {
            BinaryOp.ADD: " + ", BinaryOp.SUB: " - ", BinaryOp.MUL: " * ",
            BinaryOp.DIV: " / ", BinaryOp.MOD: " % ",
            BinaryOp.EQ: " = ", BinaryOp.NEQ: " <> ",
            BinaryOp.LT: " < ", BinaryOp.GT: " > ",
            BinaryOp.LTE: " <= ", BinaryOp.GTE: " >= ",
            BinaryOp.AND: " AND ", BinaryOp.OR: " OR ",
            BinaryOp.CONCAT: " + ",  # SQL Server string concat uses +
            BinaryOp.LIKE: " LIKE ",
            BinaryOp.NOT_LIKE: " NOT LIKE ",
            BinaryOp.IS_DISTINCT_FROM: " IS DISTINCT FROM ",
        }.get(bo.op, f" {bo.op.name} ")
        return f"({left}{op}{right})"

    # ═══════════════════════════════════════
    #  DDL overrides
    # ═══════════════════════════════════════

    def _generate_create_table(self, ct: IRCreateTable, opt: GenerateOptions) -> str:
        sb = StringIO()
        sb.write("CREATE TABLE ")
        sb.write(self._generate_table_ref(ct.name, opt))
        sb.write(" (\n")
        items: list[str] = [self._generate_column_def(c, opt) for c in ct.columns]
        # Enum CHECK constraints
        for col in ct.columns:
            if isinstance(col.type, EnumType):
                values = ", ".join(f"'{v.replace("'", "''")}'" for v in col.type.values)
                items.append(f"  CHECK ({self.quote_identifier(col.name)} IN ({values}))")
        items.extend(self._generate_table_constraint(c, opt) for c in ct.constraints)
        sb.write(",\n".join(items))
        sb.write("\n)")

        if ct.if_not_exists:
            raw_name = ct.name.name
            if ct.name.schema:
                raw_name = f"{ct.name.schema}.{ct.name.name}"
            escaped = raw_name.replace("'", "''")
            return f"IF OBJECT_ID(N'{escaped}', N'U') IS NULL\nBEGIN\n{sb.getvalue()}\nEND"
        return sb.getvalue()

    def _generate_column_def(self, col, opt: GenerateOptions) -> str:
        sb = StringIO()
        sb.write(f"  {self.quote_identifier(col.name)} {self.map_type(col.type)}")
        # IDENTITY for auto-increment
        has_identity = False
        if col.constraints:
            for c in col.constraints:
                match c:
                    case ColPrimaryKey(auto_increment=True):
                        sb.write(" IDENTITY(1,1)")
                        has_identity = True
        if col.default_value is not None:
            sb.write(f" DEFAULT {self.generate_expr(col.default_value, opt)}")
        if col.constraints:
            for c in col.constraints:
                match c:
                    case ColNotNull():
                        sb.write(" NOT NULL")
                    case ColPrimaryKey(auto_increment=False):
                        sb.write(" PRIMARY KEY")
                    case ColPrimaryKey(auto_increment=True):
                        pass  # already handled above
                    case ColUnique():
                        sb.write(" UNIQUE")
                    case ColCheck(condition=cond):
                        sb.write(f" CHECK ({self.generate_expr(cond, opt)})")
                    case ColReferences(target_table=tt, target_column=tc):
                        sb.write(f" REFERENCES {self.quote_identifier(tt)}({self.quote_identifier(tc)})")
        return sb.getvalue()

    def _generate_create_index(self, idx, opt: GenerateOptions) -> str:
        sb = StringIO()
        sb.write("CREATE ")
        if idx.unique:
            sb.write("UNIQUE ")
        sb.write("INDEX ")
        sb.write(self.quote_identifier(idx.name))
        sb.write(f" ON {self.quote_identifier(idx.table.name)}")
        cols = ", ".join(
            f"{self.quote_identifier(c.name)}{' DESC' if c.dir == OrderDir.DESC else ''}"
            for c in idx.columns
        )
        sb.write(f" ({cols})")
        if idx.where_clause is not None:
            sb.write(f" WHERE {self.generate_expr(idx.where_clause, opt)}")

        if idx.if_not_exists:
            escaped = idx.name.replace("'", "''")
            return f"IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'{escaped}') {sb.getvalue()}"
        return sb.getvalue()

    def _generate_alter_column_type(self, act, opt: GenerateOptions) -> str:
        return (f"ALTER TABLE {self.quote_identifier(act.table_name)} "
                f"ALTER COLUMN {self.quote_identifier(act.column)} {self.map_type(act.new_type)}")

    def _generate_rename_column(self, rc, opt: GenerateOptions) -> str:
        # SQL Server uses sp_rename; strip brackets from table name for the first arg
        raw_table = rc.table_name.replace("[", "").replace("]", "")
        return f"EXEC sp_rename '{raw_table}.{rc.old_name}', '{rc.new_name}', 'COLUMN'"

    def _generate_alter_set_default(self, ad, opt: GenerateOptions) -> str:
        return (f"ALTER TABLE {self.quote_identifier(ad.table_name)} "
                f"ADD CONSTRAINT DF_{ad.column} DEFAULT {self.generate_expr(ad.value, opt)} "
                f"FOR {self.quote_identifier(ad.column)}")

    def _generate_drop_index(self, di, opt: GenerateOptions) -> str:
        result = "DROP INDEX "
        if di.if_exists:
            result += "IF EXISTS "
        if di.table_name:
            result += f"{self.quote_identifier(di.table_name)}."
        result += self.quote_identifier(di.index_name)
        return result

    # ═══════════════════════════════════════
    #  Stored procedures — T-SQL syntax
    # ═══════════════════════════════════════

    def _generate_create_procedure(self, cp, opt: GenerateOptions) -> str:
        sb = StringIO()
        sb.write("CREATE ")
        if cp.or_replace:
            sb.write("OR ALTER ")
        sb.write(f"PROCEDURE {self.quote_identifier(cp.name)}")
        if cp.params:
            params = ",\n  ".join(self._tsql_param(p) for p in cp.params)
            sb.write(f"\n  {params}\n")
        sb.write(f" AS\nBEGIN\n{cp.body}\nEND;")
        return sb.getvalue()

    def _generate_create_function(self, cf, opt: GenerateOptions) -> str:
        sb = StringIO()
        sb.write("CREATE ")
        if cf.or_replace:
            sb.write("OR ALTER ")
        sb.write(f"FUNCTION {self.quote_identifier(cf.name)}")
        if cf.params:
            params = ",\n  ".join(self._tsql_param(p) for p in cf.params)
            sb.write(f"\n  {params}\n")
        if cf.return_type:
            sb.write(f" RETURNS {self.map_type(cf.return_type)}")
        sb.write(f" AS\nBEGIN\nRETURN (\n{cf.body}\n)\nEND;")
        return sb.getvalue()

    def _generate_call(self, call, opt: GenerateOptions) -> str:
        args = ", ".join(self.generate_expr(a, opt) for a in call.args)
        return f"EXEC {self.quote_identifier(call.procedure_name)}{(' ' + args) if args else ''}"

    def _tsql_param(self, p: ProcedureParam) -> str:
        dir_suffix = {ParamMode.IN: "", ParamMode.OUT: " OUTPUT", ParamMode.INOUT: " OUTPUT"}.get(p.mode, "")
        return f"@{p.name} {self.map_type(p.type)}{dir_suffix}"

    # ═══════════════════════════════════════
    #  TCL — SQL Server uses BEGIN/COMMIT/ROLLBACK TRANSACTION
    # ═══════════════════════════════════════

    def _generate_tcl(self, tcl: IRTCL, opt: GenerateOptions) -> str:
        match tcl.type:
            case TclType.BEGIN:
                return "BEGIN TRANSACTION"
            case TclType.COMMIT:
                return "COMMIT TRANSACTION"
            case TclType.ROLLBACK:
                return "ROLLBACK TRANSACTION"
            case TclType.SAVEPOINT:
                return f"SAVE TRANSACTION {tcl.savepoint_name}"
            case TclType.RELEASE_SAVEPOINT:
                # SQL Server has no RELEASE SAVEPOINT; use SAVE TRANSACTION as equivalent
                return f"SAVE TRANSACTION {tcl.savepoint_name}"
            case TclType.SET_TRANSACTION:
                return "SET TRANSACTION ISOLATION LEVEL READ COMMITTED"
