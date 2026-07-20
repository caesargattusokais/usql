"""Schema provider interface for semantic analysis."""
from __future__ import annotations

from dataclasses import dataclass
from typing import List, Optional, Protocol


@dataclass(frozen=True)
class ColumnInfo:
    name: str
    type_name: str
    nullable: bool = True


@dataclass(frozen=True)
class TableDef:
    name: str
    columns: tuple[ColumnInfo, ...] = ()


class SchemaProvider(Protocol):
    """Interface for providing table schema information."""

    def get_table(self, name: str) -> Optional[TableDef]: ...

    def table_names(self) -> List[str]: ...


class EmptySchemaProvider:
    """Schema provider that knows nothing — all names are unresolved."""

    def get_table(self, name: str) -> Optional[TableDef]:
        return None

    def table_names(self) -> List[str]:
        return []
