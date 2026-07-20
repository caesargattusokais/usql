"""OceanBase backend — MySQL-compatible dialect.

OceanBase is MySQL-protocol compatible and shares the same SQL generation
as MySQL with no additional overrides needed at this time.
"""
from __future__ import annotations

from usql.backend.mysql import MySqlBackend
from usql.dialect.dialect import Dialect


class OceanBaseBackend(MySqlBackend):
    """OceanBase dialect backend — MySQL-compatible."""

    def target_dialect(self) -> Dialect:
        return Dialect.OCEANBASE
