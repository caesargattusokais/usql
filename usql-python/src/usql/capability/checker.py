"""Capability checker — checks whether a Semantic IR statement's required capabilities
are supported by the target dialect.

Output drives polyfill (if available) or error (if fatal).
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum, auto
from typing import FrozenSet, List

from usql.dialect.capability import Capability
from usql.dialect.dialect import Dialect
from usql.ir.statement import IRStatement


class Severity(Enum):
    ERROR = auto()
    WARNING = auto()
    INFO = auto()


@dataclass(frozen=True)
class Finding:
    capability: Capability
    severity: Severity
    message: str


@dataclass(frozen=True)
class CapabilityReport:
    missing_capabilities: FrozenSet[Capability]
    polyfillable_capabilities: FrozenSet[Capability]
    findings: tuple[Finding, ...]
    all_supported: bool

    @property
    def has_missing(self) -> bool:
        return len(self.missing_capabilities) > 0

    @property
    def has_fatal(self) -> bool:
        return any(f.severity == Severity.ERROR for f in self.findings)


class CapabilityChecker:
    """Checks IR statement capabilities against target dialect."""

    # Severity mapping for each capability when missing
    _SEVERITY: dict[Capability, Severity] = {
        # ERROR: truly untranslatable
        Capability.RECURSIVE_CTE: Severity.ERROR,
        # WARNING: functional limitation
        Capability.WINDOW_FUNCTION: Severity.WARNING,
        Capability.ARRAY_TYPE: Severity.WARNING,
        Capability.DEFERRABLE_FK: Severity.WARNING,
        Capability.GENERATED_COLUMN: Severity.WARNING,
        Capability.FULL_OUTER_JOIN: Severity.WARNING,
        Capability.AGGREGATE: Severity.WARNING,
        Capability.DISTINCT: Severity.WARNING,
        Capability.GROUPING_SETS: Severity.WARNING,
        Capability.LATERAL_JOIN: Severity.WARNING,
        # INFO: clean polyfill available
        Capability.LIMIT_OFFSET: Severity.INFO,
        Capability.BOOLEAN_TYPE: Severity.INFO,
        Capability.AUTO_INCREMENT: Severity.INFO,
        Capability.CONCAT_WITH_NULL: Severity.INFO,
        Capability.PARTIAL_INDEX: Severity.INFO,
        Capability.ENUM_TYPE: Severity.INFO,
        Capability.RETURNING_CLAUSE: Severity.INFO,
        Capability.SELECT_WITHOUT_FROM: Severity.INFO,
        Capability.HAVING: Severity.INFO,
        Capability.TRUNCATE_TABLE: Severity.INFO,
        Capability.REPLACE_INTO: Severity.INFO,
        Capability.ON_DUPLICATE_KEY_UPDATE: Severity.INFO,
        Capability.INTERVAL_ARITHMETIC: Severity.INFO,
        Capability.MERGE_INTO: Severity.INFO,
        Capability.SEQUENCE: Severity.INFO,
        Capability.CTAS: Severity.INFO,
        Capability.TEMPORARY_TABLE: Severity.INFO,
        Capability.OBJECT_COMMENT: Severity.INFO,
        Capability.CHECK_CONSTRAINT: Severity.INFO,
    }

    def check(self, statement: IRStatement, dialect: Dialect) -> CapabilityReport:
        """Check a statement against a target dialect."""
        required = statement.capabilities
        missing: set[Capability] = set()
        polyfillable: set[Capability] = set()
        findings: list[Finding] = []

        for cap in required:
            if not dialect.supports(cap):
                missing.add(cap)
                severity = self._SEVERITY.get(cap, Severity.WARNING)
                if _can_polyfill(cap):
                    polyfillable.add(cap)
                    findings.append(Finding(
                        cap, Severity.WARNING,
                        f"Feature '{cap.name}' is not natively supported by "
                        f"{dialect.display_name} — applying polyfill",
                    ))
                else:
                    findings.append(Finding(
                        cap, severity,
                        f"Feature '{cap.name}' is required but "
                        f"{dialect.display_name} does not support it",
                    ))

        return CapabilityReport(
            missing_capabilities=frozenset(missing),
            polyfillable_capabilities=frozenset(polyfillable),
            findings=tuple(findings),
            all_supported=len(missing) == 0,
        )


def _can_polyfill(cap: Capability) -> bool:
    """Can a given capability be polyfilled?"""
    polyfillable = {
        Capability.LIMIT_OFFSET, Capability.BOOLEAN_TYPE, Capability.FULL_OUTER_JOIN,
        Capability.AUTO_INCREMENT, Capability.CONCAT_WITH_NULL, Capability.PARTIAL_INDEX,
        Capability.ENUM_TYPE, Capability.RETURNING_CLAUSE, Capability.SELECT_WITHOUT_FROM,
        Capability.HAVING, Capability.TRUNCATE_TABLE, Capability.REPLACE_INTO,
        Capability.ON_DUPLICATE_KEY_UPDATE, Capability.INTERVAL_ARITHMETIC,
        Capability.MERGE_INTO, Capability.WINDOW_FUNCTION,
    }
    return cap in polyfillable
