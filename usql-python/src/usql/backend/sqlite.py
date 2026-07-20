"""SQLite backend — generates SQLite-compatible SQL from the Semantic IR.

SQLite is lightweight and permissive — types are suggestions.
Key differences from standard SQL:
  - No TRUNCATE TABLE (use DELETE FROM instead)
  - No ALTER COLUMN SET/DROP DEFAULT or ALTER COLUMN TYPE
  - INSERT OR IGNORE instead of INSERT IGNORE
  - Uses BEGIN TRANSACTION (not plain BEGIN) for TCL
"""
from __future__ import annotations

from io import StringIO

from usql.backend.base import AbstractDialectBackend
from usql.backend.generate_options import GenerateOptions
from usql.dialect.dialect import Dialect
from usql.ir.statement import (
    IRStatement, IRSelect, IRInsert, IRUpdate, IRDelete, IRMerge,
    IRCreateTable, IRCreateIndex, IRDropTable, IRDropIndex,
    IRTruncateTable, IRAlterTableAddColumn, IRAlterTableDropColumn,
    IRRenameColumn, IRDropDatabase, IRCreateView, IRCreateSchema, IRTCL,
    IRCreateProcedure, IRCreateFunction, IRCall,
    IRAlterColumnType, IRAlterColumnSetDefault, IRAlterColumnDropDefault,
    ColNotNull, ColPrimaryKey, ColUnique, ColCheck, OrderDir, TclType,
)
from usql.ir.types import (
    DataType, IntType, FloatType, DecimalType, CharType, VarcharType,
    TextType, BooleanType, DateType, TimeType, DatetimeType, TimestampType,
    JsonType, UuidType, BinaryType, VarbinaryType, BlobType, EnumType, NullType,
)


class SqliteBackend(AbstractDialectBackend):
    """Generates SQLite-compatible SQL from the Semantic IR."""

    def target_dialect(self) -> Dialect:
        return Dialect.SQLITE

    def quote_identifier(self, identifier: str) -> str:
        escaped = identifier.replace('"', '""')
        return f'"{escaped}"'

    def map_type(self, dtype: DataType) -> str:
        match dtype:
            case IntType():
                return "INTEGER"
            case FloatType():
                return "REAL"
            case DecimalType():
                return "REAL"
            case CharType():
                return "TEXT"
            case VarcharType():
                return "TEXT"
            case TextType():
                return "TEXT"
            case BooleanType():
                return "INTEGER"
            case DateType():
                return "TEXT"
            case TimeType():
                return "TEXT"
            case DatetimeType():
                return "TEXT"
            case TimestampType():
                return "TEXT"
            case JsonType():
                return "TEXT"
            case UuidType():
                return "TEXT"
            case BinaryType():
                return "BLOB"
            case VarbinaryType():
                return "BLOB"
            case BlobType():
                return "BLOB"
            case EnumType():
                return "TEXT"
            case NullType():
                return "NULL"
            case _:
                return "TEXT"

    # ═══════════════════════════════════════
    #  Dispatch override — unsupported ALTER ops return no-op comments
    # ═══════════════════════════════════════

    def _dispatch(self, stmt: IRStatement, opt: GenerateOptions) -> str:
        match stmt:
            case IRAlterColumnSetDefault():
                return "SELECT 1 /* SQLite: ALTER COLUMN SET DEFAULT not supported */"
            case IRAlterColumnDropDefault():
                return "SELECT 1 /* SQLite: ALTER COLUMN DROP DEFAULT not supported */"
            case IRAlterColumnType():
                return "SELECT 1 /* SQLite: ALTER COLUMN TYPE not supported */"
            case _:
                return super()._dispatch(stmt, opt)

    # ═══════════════════════════════════════
    #  INSERT — SQLite uses INSERT OR IGNORE
    # ═══════════════════════════════════════

    def _generate_insert(self, ins: IRInsert, opt: GenerateOptions) -> str:
        sb = StringIO()
        sb.write("INSERT")
        if ins.ignore_errors:
            sb.write(" OR IGNORE")
        sb.write(" INTO ")
        sb.write(self._generate_table_ref(ins.table, opt))
        if ins.columns:
            cols = ", ".join(self.quote_identifier(c) for c in ins.columns)
            sb.write(f" ({cols})")
        if ins.select_source:
            sb.write(" ")
            sb.write(self._generate_select(ins.select_source, opt))
        elif ins.values:
            rows = ", ".join(
                f"({', '.join(self.generate_expr(v, opt) for v in row)})"
                for row in ins.values
            )
            sb.write(f" VALUES {rows}")
        return sb.getvalue()

    # ═══════════════════════════════════════
    #  TRUNCATE — SQLite has no TRUNCATE, use DELETE FROM
    # ═══════════════════════════════════════

    def _generate_truncate(self, tt: IRTruncateTable, opt: GenerateOptions) -> str:
        return f"DELETE FROM {self.quote_identifier(tt.name)}"

    # ═══════════════════════════════════════
    #  DDL overrides
    # ═══════════════════════════════════════

    def _generate_create_table(self, ct: IRCreateTable, opt: GenerateOptions) -> str:
        sb = StringIO()
        sb.write("CREATE TABLE ")
        if ct.if_not_exists:
            sb.write("IF NOT EXISTS ")
        sb.write(self._generate_table_ref(ct.name, opt))
        sb.write(" (\n")
        items: list[str] = [self._generate_column_def(c, opt) for c in ct.columns]
        items.extend(self._generate_table_constraint(c, opt) for c in ct.constraints)
        sb.write(",\n".join(items))
        sb.write("\n)")
        return sb.getvalue()

    def _generate_column_def(self, col, opt: GenerateOptions) -> str:
        sb = StringIO()
        sb.write(f"  {self.quote_identifier(col.name)} {self.map_type(col.type)}")
        if col.constraints:
            is_pk = False
            is_ai = False
            for c in col.constraints:
                match c:
                    case ColPrimaryKey(auto_increment=ai):
                        is_pk = True
                        is_ai = ai
                    case ColNotNull():
                        sb.write(" NOT NULL")
                    case ColUnique():
                        sb.write(" UNIQUE")
                    case ColCheck(condition=cond):
                        sb.write(f" CHECK ({self.generate_expr(cond, opt)})")
            if is_pk:
                sb.write(" PRIMARY KEY")
                if is_ai:
                    sb.write(" AUTOINCREMENT")
        if col.default_value is not None:
            sb.write(f" DEFAULT {self.generate_expr(col.default_value, opt)}")
        return sb.getvalue()

    def _generate_create_index(self, idx, opt: GenerateOptions) -> str:
        sb = StringIO()
        sb.write("CREATE ")
        if idx.unique:
            sb.write("UNIQUE ")
        sb.write("INDEX ")
        if idx.if_not_exists:
            sb.write("IF NOT EXISTS ")
        sb.write(self.quote_identifier(idx.name))
        sb.write(f" ON {self.quote_identifier(idx.table.name)}")
        cols = ", ".join(
            f"{self.quote_identifier(c.name)}{' DESC' if c.dir == OrderDir.DESC else ''}"
            for c in idx.columns
        )
        sb.write(f" ({cols})")
        return sb.getvalue()

    def _generate_drop_table(self, dt, opt: GenerateOptions) -> str:
        result = "DROP TABLE "
        if dt.if_exists:
            result += "IF EXISTS "
        return result + self.quote_identifier(dt.name)

    def _generate_drop_index(self, di, opt: GenerateOptions) -> str:
        result = "DROP INDEX "
        if di.if_exists:
            result += "IF EXISTS "
        return result + self.quote_identifier(di.index_name)

    def _generate_alter_add_column(self, aa, opt: GenerateOptions) -> str:
        col = aa.column
        return (f"ALTER TABLE {self.quote_identifier(aa.table_name)} ADD COLUMN "
                f"{self.quote_identifier(col.name)} {self.map_type(col.type)}")

    def _generate_rename_column(self, rc, opt: GenerateOptions) -> str:
        return (f"ALTER TABLE {self.quote_identifier(rc.table_name)} "
                f"RENAME COLUMN {self.quote_identifier(rc.old_name)} TO {self.quote_identifier(rc.new_name)}")

    # ═══════════════════════════════════════
    #  MERGE — not fully supported in SQLite
    # ═══════════════════════════════════════

    def _generate_merge(self, merge, opt: GenerateOptions) -> str:
        return "-- MERGE not fully supported in SQLite, use INSERT OR REPLACE"

    # ═══════════════════════════════════════
    #  TCL — SQLite uses BEGIN TRANSACTION (not plain BEGIN)
    # ═══════════════════════════════════════

    def _generate_tcl(self, tcl: IRTCL, opt: GenerateOptions) -> str:
        match tcl.type:
            case TclType.BEGIN:
                return "BEGIN TRANSACTION"
            case TclType.COMMIT:
                return "COMMIT"
            case TclType.ROLLBACK:
                return "ROLLBACK"
            case TclType.SAVEPOINT:
                return f"SAVEPOINT {tcl.savepoint_name}"
            case TclType.RELEASE_SAVEPOINT:
                return f"RELEASE SAVEPOINT {tcl.savepoint_name}"
            case TclType.SET_TRANSACTION:
                return "SELECT 1 /* SQLite: SET TRANSACTION not supported */"
