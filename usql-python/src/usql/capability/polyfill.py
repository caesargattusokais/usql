"""Polyfill engine — rewrites IR to compensate for missing capabilities.

When a target dialect lacks a feature (e.g., Oracle → LIMIT_OFFSET),
the polyfill engine modifies the IR so that the Backend generates
equivalent SQL using features the dialect does support.
"""
from __future__ import annotations

from usql.dialect.capability import Capability
from usql.dialect.dialect import Dialect
from usql.ir.statement import (
    IRStatement, IRSelect, IRTableRef, IRJoin,
    SelectCore,
    JoinType, SetOp,
)
from usql.capability.checker import CapabilityReport, _can_polyfill


class PolyfillEngine:
    """Rewrites IR to compensate for missing capabilities."""

    def apply(self, statement: IRStatement, report: CapabilityReport,
              target: Dialect) -> IRStatement:
        """Apply polyfill transformations for the target dialect."""
        if report.all_supported:
            return statement

        result = statement
        for cap in report.polyfillable_capabilities:
            result = self._apply_one(result, cap, target)

        return result

    def _apply_one(self, statement: IRStatement, cap: Capability,
                   target: Dialect) -> IRStatement:
        match cap:
            case Capability.FULL_OUTER_JOIN:
                return self._polyfill_full_outer_join(statement)
            case Capability.BOOLEAN_TYPE:
                return statement  # handled at backend level
            case Capability.SELECT_WITHOUT_FROM:
                return statement  # handled at backend level
            case Capability.CONCAT_WITH_NULL:
                return statement  # handled at backend level
            case _:
                return statement

    def _polyfill_full_outer_join(self, statement: IRStatement) -> IRStatement:
        """Convert FULL OUTER JOIN to LEFT JOIN UNION RIGHT JOIN."""
        if not isinstance(statement, IRSelect):
            return statement
        if statement.core.from_clause is None:
            return statement

        # Check if any join is FULL
        has_full_join = any(
            isinstance(ref, IRJoin) and ref.type == JoinType.FULL
            for ref in statement.core.from_clause
        )
        if not has_full_join:
            return statement

        # Build LEFT JOIN side
        left_side = self._replace_join_type(statement, JoinType.FULL, JoinType.LEFT)
        right_side = self._replace_join_type(statement, JoinType.FULL, JoinType.RIGHT)

        # Combine with UNION
        union_core = SelectCore(
            projections=left_side.core.projections,
            from_clause=left_side.core.from_clause,
            where=left_side.core.where,
            group_by=left_side.core.group_by,
            having=left_side.core.having,
            with_clause=left_side.core.with_clause,
            set_op=SetOp.UNION,
            set_operand=right_side,
            distinct=left_side.core.distinct,
        )
        return IRSelect(
            core=union_core,
            order_by=left_side.order_by,
            fetch=left_side.fetch,
            capabilities=statement.capabilities,
        )

    def _replace_join_type(self, sel: IRSelect, from_type: JoinType,
                           to_type: JoinType) -> IRSelect:
        """Replace all occurrences of a JOIN type in the FROM tree."""
        new_from = None
        if sel.core.from_clause:
            new_from = tuple(
                self._replace_join_type_in_ref(ref, from_type, to_type)
                for ref in sel.core.from_clause
            )
        new_core = SelectCore(
            projections=sel.core.projections,
            from_clause=new_from,
            where=sel.core.where,
            group_by=sel.core.group_by,
            having=sel.core.having,
            with_clause=sel.core.with_clause,
            set_op=None,
            set_operand=None,
            distinct=sel.core.distinct,
        )
        return IRSelect(
            core=new_core,
            order_by=sel.order_by,
            fetch=sel.fetch,
            capabilities=sel.capabilities,
        )

    def _replace_join_type_in_ref(self, ref: IRTableRef, from_type: JoinType,
                                   to_type: JoinType) -> IRTableRef:
        if isinstance(ref, IRJoin):
            left = self._replace_join_type_in_ref(ref.left, from_type, to_type)
            right = self._replace_join_type_in_ref(ref.right, from_type, to_type)
            new_type = to_type if ref.type == from_type else ref.type
            return IRJoin(left=left, type=new_type, right=right,
                         on_condition=ref.on_condition)
        return ref
