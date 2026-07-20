"""U-SQL canonical type system.

Each database dialect maps its native types to/from these.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import List, Union


@dataclass(frozen=True)
class IntType:
    bits: int = 32

    TINYINT: IntType = None  # type: ignore  # class-level constants set below
    SMALLINT: IntType = None  # type: ignore
    INT: IntType = None  # type: ignore
    BIGINT: IntType = None  # type: ignore

    @property
    def type_name(self) -> str:
        return {8: "TINYINT", 16: "SMALLINT", 32: "INT", 64: "BIGINT"}.get(self.bits, f"INT({self.bits})")

    def __post_init__(self):
        # sanitize: default to INT for invalid values
        if self.bits <= 0:
            object.__setattr__(self, 'bits', 32)


# Class-level constants
IntType.TINYINT = IntType(8)
IntType.SMALLINT = IntType(16)
IntType.INT = IntType(32)
IntType.BIGINT = IntType(64)


@dataclass(frozen=True)
class DecimalType:
    precision: int = 10
    scale: int = 0

    @property
    def type_name(self) -> str:
        return f"DECIMAL({self.precision},{self.scale})"

    def __post_init__(self):
        if self.precision <= 0:
            object.__setattr__(self, 'precision', 10)
        if self.scale < 0:
            object.__setattr__(self, 'scale', 0)
        if self.scale > self.precision:
            object.__setattr__(self, 'scale', self.precision)


@dataclass(frozen=True)
class FloatType:
    bits: int = 32

    FLOAT: FloatType = None  # type: ignore
    DOUBLE: FloatType = None  # type: ignore

    @property
    def type_name(self) -> str:
        return "FLOAT" if self.bits <= 32 else "DOUBLE"


FloatType.FLOAT = FloatType(32)
FloatType.DOUBLE = FloatType(64)


@dataclass(frozen=True)
class CharType:
    length: int

    @property
    def type_name(self) -> str:
        return f"CHAR({self.length})"


@dataclass(frozen=True)
class VarcharType:
    length: int

    @property
    def type_name(self) -> str:
        return f"VARCHAR({self.length})"


@dataclass(frozen=True)
class TextType:
    @property
    def type_name(self) -> str:
        return "TEXT"


@dataclass(frozen=True)
class BinaryType:
    length: int

    @property
    def type_name(self) -> str:
        return f"BINARY({self.length})"


@dataclass(frozen=True)
class VarbinaryType:
    length: int

    @property
    def type_name(self) -> str:
        return f"VARBINARY({self.length})"


@dataclass(frozen=True)
class BlobType:
    @property
    def type_name(self) -> str:
        return "BLOB"


@dataclass(frozen=True)
class BooleanType:
    @property
    def type_name(self) -> str:
        return "BOOLEAN"


@dataclass(frozen=True)
class DateType:
    @property
    def type_name(self) -> str:
        return "DATE"


@dataclass(frozen=True)
class TimeType:
    fractional_seconds: int = 0

    @property
    def type_name(self) -> str:
        return f"TIME({self.fractional_seconds})"


@dataclass(frozen=True)
class DatetimeType:
    fractional_seconds: int = 0

    @property
    def type_name(self) -> str:
        return f"DATETIME({self.fractional_seconds})"


@dataclass(frozen=True)
class TimestampType:
    fractional_seconds: int = 0

    @property
    def type_name(self) -> str:
        return f"TIMESTAMP({self.fractional_seconds})"


@dataclass(frozen=True)
class IntervalYearMonth:
    @property
    def type_name(self) -> str:
        return "INTERVAL YEAR TO MONTH"


@dataclass(frozen=True)
class IntervalDaySecond:
    fractional_seconds: int = 0

    @property
    def type_name(self) -> str:
        return f"INTERVAL DAY TO SECOND({self.fractional_seconds})"


@dataclass(frozen=True)
class JsonType:
    @property
    def type_name(self) -> str:
        return "JSON"


@dataclass(frozen=True)
class XmlType:
    @property
    def type_name(self) -> str:
        return "XML"


@dataclass(frozen=True)
class UuidType:
    @property
    def type_name(self) -> str:
        return "UUID"


@dataclass(frozen=True)
class ArrayType:
    element_type: DataType

    @property
    def type_name(self) -> str:
        return f"ARRAY<{self.element_type.type_name}>"


@dataclass(frozen=True)
class NullType:
    @property
    def type_name(self) -> str:
        return "NULL"


@dataclass(frozen=True)
class EnumType:
    values: tuple[str, ...]

    @property
    def type_name(self) -> str:
        return f"ENUM({','.join(self.values)})"


# Union of all data types
DataType = Union[
    IntType, DecimalType, FloatType, CharType, VarcharType, TextType,
    BinaryType, VarbinaryType, BlobType, BooleanType,
    DateType, TimeType, DatetimeType, TimestampType,
    IntervalYearMonth, IntervalDaySecond,
    JsonType, XmlType, UuidType, ArrayType, NullType, EnumType,
]


def is_numeric(dtype: DataType) -> bool:
    return isinstance(dtype, (IntType, DecimalType, FloatType))


def is_string(dtype: DataType) -> bool:
    return isinstance(dtype, (CharType, VarcharType, TextType))


def is_temporal(dtype: DataType) -> bool:
    return isinstance(dtype, (DateType, TimeType, DatetimeType, TimestampType))
