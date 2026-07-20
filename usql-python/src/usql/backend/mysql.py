"""MySQL backend — generates MySQL-compatible SQL from the Semantic IR."""
from __future__ import annotations

from usql.backend.base import AbstractDialectBackend
from usql.backend.generate_options import GenerateOptions
from usql.dialect.dialect import Dialect
from usql.ir.expr import IRExpr, IRLiteral, IRFunctionCall
from usql.ir.statement import (
    IRStatement, IRSelect, IRInsert, IRUpdate, IRDelete, IRMerge,
    IRCreateTable, IRCreateIndex, IRAlterTableAddColumn,
    IRTruncateTable,
    IRColumnDef, IRTableName,
    ColNotNull, ColPrimaryKey, IRAlterColumnType,
)
from usql.ir.types import (
    DataType, IntType, FloatType, DecimalType, BooleanType,
    CharType, VarcharType, TextType, DateType, TimeType,
    DatetimeType, TimestampType, JsonType, UuidType,
    BinaryType, VarbinaryType, BlobType, EnumType,
)


class MySqlBackend(AbstractDialectBackend):
    """MySQL dialect backend (also used as base for MariaDB, TiDB, OceanBase)."""

    def target_dialect(self) -> Dialect:
        return Dialect.MYSQL

    def quote_identifier(self, identifier: str) -> str:
        return f"`{identifier.replace('`', '``')}`"

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
                return "FLOAT" if b <= 32 else "DOUBLE"
            case CharType(length=l):
                return f"CHAR({l})"
            case VarcharType(length=l):
                return f"VARCHAR({l})"
            case VarcharType():
                return "VARCHAR(255)"
            case TextType():
                return "TEXT"
            case BooleanType():
                return "TINYINT(1)"
            case DateType():
                return "DATE"
            case TimeType(fractional_seconds=fs):
                return f"TIME({fs})" if fs else "TIME"
            case DatetimeType(fractional_seconds=fs):
                return f"DATETIME({fs})" if fs else "DATETIME"
            case TimestampType(fractional_seconds=fs):
                return f"TIMESTAMP({fs})" if fs else "TIMESTAMP"
            case JsonType():
                return "JSON"
            case UuidType():
                return "CHAR(36)"
            case BinaryType(length=l):
                return f"BINARY({l})"
            case VarbinaryType(length=l):
                return f"VARBINARY({l})"
            case VarbinaryType():
                return "VARBINARY(255)"
            case BlobType():
                return "BLOB"
            case EnumType(values=vals):
                if vals:
                    enum_vals = ", ".join(f"'{v}'" for v in vals)
                    return f"ENUM({enum_vals})"
                return "VARCHAR(255)"
            case _:
                return "VARCHAR(255)"

    # ═══════════════════════════════════════
    #  MySQL-specific overrides
    # ═══════════════════════════════════════

    def _generate_insert(self, ins: IRInsert, opt: GenerateOptions) -> str:
        sb = super()._generate_insert(ins, opt)
        # MySQL: INSERT IGNORE
        if ins.ignore_errors and not sb.startswith("INSERT OR IGNORE"):
            sb = sb.replace("INSERT INTO", "INSERT IGNORE INTO", 1)
        return sb

    def _generate_create_table(self, ct: IRCreateTable, opt: GenerateOptions) -> str:
        result = super()._generate_create_table(ct, opt)
        # MySQL: AUTO_INCREMENT on column, and table options
        if ct.options and ct.options.engine:
            result = result.rstrip(")") + f"\n) ENGINE={ct.options.engine}"
        return result

    def _generate_column_def(self, col: IRColumnDef, opt: GenerateOptions) -> str:
        # MySQL: AUTO_INCREMENT must come after PRIMARY KEY
        result = super()._generate_column_def(col, opt)
        # Already handled in base class via ColPrimaryKey
        return result

    def _generate_alter_column_type(self, act: IRAlterColumnType, opt: GenerateOptions) -> str:
        return (f"ALTER TABLE {self.quote_identifier(act.table_name)} "
                f"MODIFY COLUMN {self.quote_identifier(act.column)} {self.map_type(act.new_type)}")
