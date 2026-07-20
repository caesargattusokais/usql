"""TiDB backend — MySQL-protocol with native CREATE INDEX IF NOT EXISTS.

TiDB supports CREATE INDEX IF NOT EXISTS natively (unlike MySQL 8.0).
TiDB does NOT support dynamic PREPARE inside stored procedures, so the
MySQL stored-procedure polyfill would fail — use the native IF NOT EXISTS
clause instead.
"""
from __future__ import annotations

from io import StringIO

from usql.backend.mysql import MySqlBackend
from usql.backend.generate_options import GenerateOptions
from usql.dialect.dialect import Dialect
from usql.ir.statement import IRCreateIndex, OrderDir


class TiDbBackend(MySqlBackend):
    """TiDB dialect backend — MySQL-compatible with native IF NOT EXISTS for indexes."""

    def target_dialect(self) -> Dialect:
        return Dialect.TIDB

    # ═══════════════════════════════════════
    #  CREATE INDEX — native IF NOT EXISTS
    # ═══════════════════════════════════════

    def _generate_create_index(self, idx: IRCreateIndex, opt: GenerateOptions) -> str:
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
        if idx.index_type and idx.index_type.name != "BTREE":
            sb.write(f" USING {idx.index_type.name}")
        return sb.getvalue()
