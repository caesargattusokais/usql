"""Central function registry.

Maps U-SQL function names to dialect-specific SQL generation rules.
Loaded from functions.yaml (bundled).
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Dict, List, Optional

import yaml

from usql.dialect.dialect import Dialect
from usql.ir.types import DataType, IntType, VarcharType


@dataclass(frozen=True)
class DialectMapping:
    """Maps a U-SQL function to a dialect-specific name/template."""
    name: str
    template: str | None = None


@dataclass(frozen=True)
class PolyfillConfig:
    """Polyfill configuration for a function."""
    strategy: str  # "EXPRESSION" | "SUBQUERY"
    template: str


@dataclass
class FunctionDef:
    """Definition of a U-SQL function with dialect mappings."""
    u_sql_name: str
    description: str
    return_type: DataType
    dialect_mappings: Dict[Dialect, DialectMapping]
    polyfill: PolyfillConfig | None = None


class FunctionCatalog:
    """Central function registry loaded from functions.yaml."""

    _yaml_data: Dict[str, FunctionDef] | None = None

    def __init__(self):
        self._functions: Dict[str, FunctionDef] = {}
        self._load_yaml()

    def _load_yaml(self) -> None:
        """Load function definitions from functions.yaml."""
        if FunctionCatalog._yaml_data is not None:
            self._functions = dict(FunctionCatalog._yaml_data)
            return

        yaml_path = os.path.join(
            os.path.dirname(__file__), '..', '..', 'resources', 'functions.yaml'
        )
        if not os.path.exists(yaml_path):
            # Try relative to package
            yaml_path = os.path.join(os.path.dirname(__file__), 'resources', 'functions.yaml')

        if not os.path.exists(yaml_path):
            FunctionCatalog._yaml_data = {}
            return

        with open(yaml_path, 'r', encoding='utf-8') as f:
            data = yaml.safe_load(f)

        functions = {}
        for func_data in (data or {}).get('functions', []):
            name = func_data.get('name', '').upper()
            desc = func_data.get('desc', '')
            returns = func_data.get('returns', 'VARCHAR')

            # Parse return type
            return_type = self._parse_type(returns)

            # Parse dialect mappings
            dialect_mappings: Dict[Dialect, DialectMapping] = {}
            dialects = func_data.get('dialects', {})
            if isinstance(dialects, str):
                # All dialects use the same name
                for d in Dialect:
                    dialect_mappings[d] = DialectMapping(name=dialects)
            elif isinstance(dialects, dict):
                for dialect_name, mapping in dialects.items():
                    try:
                        dialect = Dialect[dialect_name.upper()]
                    except KeyError:
                        continue
                    if isinstance(mapping, str):
                        dialect_mappings[dialect] = DialectMapping(name=mapping)
                    elif isinstance(mapping, dict):
                        dialect_mappings[dialect] = DialectMapping(
                            name=mapping.get('name', name),
                            template=mapping.get('template'),
                        )

            # Parse polyfill
            polyfill = None
            pf_data = func_data.get('polyfill')
            if pf_data:
                polyfill = PolyfillConfig(
                    strategy=pf_data.get('strategy', 'EXPRESSION'),
                    template=pf_data.get('template', ''),
                )

            functions[name] = FunctionDef(
                u_sql_name=name,
                description=desc,
                return_type=return_type,
                dialect_mappings=dialect_mappings,
                polyfill=polyfill,
            )

        FunctionCatalog._yaml_data = functions
        self._functions = dict(functions)

    def _parse_type(self, type_name: str) -> DataType:
        """Parse a type name string into a DataType."""
        match type_name.upper():
            case 'INT' | 'INTEGER':
                return IntType(32)
            case 'VARCHAR':
                return VarcharType(255)
            case _:
                return VarcharType(255)

    def get(self, u_sql_name: str) -> Optional[FunctionDef]:
        """Get a function definition by its U-SQL name."""
        return self._functions.get(u_sql_name.upper())

    def get_dialect_name(self, u_sql_name: str, dialect: Dialect) -> str | None:
        """Get the dialect-specific function name."""
        func = self._functions.get(u_sql_name.upper())
        if not func:
            return None
        mapping = func.dialect_mappings.get(dialect)
        if mapping:
            return mapping.name
        # Fallback: use the U-SQL name
        return u_sql_name.upper()

    def contains(self, u_sql_name: str) -> bool:
        return u_sql_name.upper() in self._functions

    def function_names(self) -> set[str]:
        return set(self._functions.keys())
