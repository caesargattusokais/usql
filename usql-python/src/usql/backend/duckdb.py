"""DuckDB backend — generates DuckDB-compatible SQL from the Semantic IR.

DuckDB is PostgreSQL-compatible except:
  - No GENERATED AS IDENTITY (use sequences + DEFAULT nextval for auto-increment)
  - No stored procedures or functions
  - Uses double-quote identifier quoting like PostgreSQL
"""
from __future__ import annotations

from io import StringIO

from usql.backend.base import AbstractDialectBackend
from usql.backend.generate_options import GenerateOptions
from usql.dialect.dialect import Dialect
from usql.ir.statement import (
    IRStatement, IRCreateTable, IRCreateProcedure, IRCreateFunction, IRCall,
    ColNotNull, ColPrimaryKey, ColUnique, ColCheck, ColReferences,
    IRColumnDef, IRTCL, TclType,
)
from usql.ir.types import (
    DataType, IntType, FloatType, DecimalType, CharType, VarcharType,
    TextType, BooleanType, DateType, TimeType, DatetimeType, TimestampType,
    JsonType, UuidType, BinaryType, VarbinaryType, BlobType, EnumType,
)


class DuckDbBackend(AbstractDialectBackend):
    """DuckDB dialect backend — PostgreSQL-compatible with DuckDB-specific overrides."""

    def target_dialect(self) -> Dialect:
        return Dialect.DUCKDB

    def quote_identifier(self, identifier: str) -> str:
        escaped = identifier.replace('"', '""')
        return f'"{escaped}"'

    def map_type(self, dtype: DataType) -> str:
        match dtype:
            case IntType(bits=8):
                return "TINYINT"
            case IntType(bits=16):
                return "SMALLINT"
            case IntType(bits=32):
                return "INTEGER"
            case IntType(bits=64):
                return "BIGINT"
            case IntType():
                return "INTEGER"
            case DecimalType(precision=p, scale=s):
                return f"DECIMAL({p},{s})"
            case FloatType(bits=b):
                return "REAL" if b <= 32 else "DOUBLE"
            case CharType(length=l):
                return f"CHAR({l})"
            case VarcharType(length=l):
                return f"VARCHAR({l})"
            case VarcharType():
                return "VARCHAR"
            case TextType():
                return "VARCHAR"
            case BooleanType():
                return "BOOLEAN"
            case DateType():
                return "DATE"
            case TimeType(fractional_seconds=fs):
                return f"TIME({fs})"
            case DatetimeType(fractional_seconds=fs):
                return f"TIMESTAMP({fs})"
            case TimestampType(fractional_seconds=fs):
                return f"TIMESTAMP({fs})"
            case JsonType():
                return "JSON"
            case UuidType():
                return "UUID"
            case BinaryType(length=l):
                return f"BLOB({l})"
            case VarbinaryType(length=l):
                return f"BLOB({l})"
            case BlobType():
                return "BLOB"
            case EnumType():
                return "VARCHAR(255)"
            case _:
                return getattr(dtype, 'type_name', type(dtype).__name__)

    # ═══════════════════════════════════════
    #  Dispatch override — unsupported features
    # ═══════════════════════════════════════

    def _dispatch(self, stmt: IRStatement, opt: GenerateOptions) -> str:
        match stmt:
            case IRCreateProcedure():
                return "-- DuckDB: stored procedures not supported"
            case IRCreateFunction():
                return "-- DuckDB: stored functions not supported"
            case IRCall():
                return "-- DuckDB: CALL not supported"
            case _:
                return super()._dispatch(stmt, opt)

    # ═══════════════════════════════════════
    #  CREATE TABLE — sequences for auto-increment
    # ═══════════════════════════════════════

    def _generate_create_table(self, ct: IRCreateTable, opt: GenerateOptions) -> str:
        # Generate sequences for auto-increment columns
        pre_seq = StringIO()
        for col in ct.columns:
            if col.constraints:
                for c in col.constraints:
                    if isinstance(c, ColPrimaryKey) and c.auto_increment:
                        seq_name = f"{col.name}_seq"
                        pre_seq.write(f"CREATE SEQUENCE IF NOT EXISTS {self.quote_identifier(seq_name)};\n")

        sb = StringIO()
        sb.write(pre_seq.getvalue())
        sb.write("CREATE TABLE ")
        if ct.if_not_exists:
            sb.write("IF NOT EXISTS ")
        sb.write(self.quote_identifier(ct.name.name))
        sb.write(" (\n")
        items: list[str] = [self._duck_column_def(c, opt) for c in ct.columns]
        items.extend(self._generate_table_constraint(c, opt) for c in ct.constraints)
        sb.write(",\n".join(items))
        sb.write("\n)")
        return sb.getvalue()

    def _duck_column_def(self, col: IRColumnDef, opt: GenerateOptions) -> str:
        sb = StringIO()
        sb.write(f"  {self.quote_identifier(col.name)} {self.map_type(col.type)}")
        if col.default_value is not None:
            sb.write(f" DEFAULT {self.generate_expr(col.default_value, opt)}")
        if col.constraints:
            is_pk = False
            for c in col.constraints:
                match c:
                    case ColPrimaryKey(auto_increment=True):
                        is_pk = True
                        seq_name = f"{col.name}_seq"
                        sb.write(f" DEFAULT nextval('{self.quote_identifier(seq_name)}')")
                    case ColPrimaryKey(auto_increment=False):
                        is_pk = True
                    case ColNotNull() if not is_pk:
                        sb.write(" NOT NULL")
                    case ColUnique():
                        sb.write(" UNIQUE")
                    case ColCheck(condition=cond):
                        sb.write(f" CHECK ({self.generate_expr(cond, opt)})")
                    case ColReferences(target_table=tt, target_column=tc):
                        sb.write(f" REFERENCES {self.quote_identifier(tt)}({self.quote_identifier(tc)})")
            if is_pk:
                sb.write(" PRIMARY KEY")
        return sb.getvalue()

    # ═══════════════════════════════════════
    #  TCL — DuckDB-specific
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
                return "SET TRANSACTION"
