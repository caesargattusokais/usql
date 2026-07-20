"""MariaDB backend — extends MySQL with native IF NOT EXISTS support.

MariaDB 10.0+ supports ALTER TABLE ADD COLUMN IF NOT EXISTS natively,
and MariaDB 10.5+ supports CREATE INDEX IF NOT EXISTS natively,
avoiding the MySQL stored-procedure polyfill.
"""
from __future__ import annotations

from io import StringIO

from usql.backend.mysql import MySqlBackend
from usql.backend.generate_options import GenerateOptions
from usql.dialect.dialect import Dialect
from usql.ir.statement import (
    IRAlterTableAddColumn, IRCreateIndex, OrderDir,
    ColNotNull, ColPrimaryKey, ColUnique,
)


class MariaDbBackend(MySqlBackend):
    """MariaDB dialect backend — MySQL-compatible with native IF NOT EXISTS."""

    def target_dialect(self) -> Dialect:
        return Dialect.MARIADB

    # ═══════════════════════════════════════
    #  ALTER TABLE ADD COLUMN — native IF NOT EXISTS
    # ═══════════════════════════════════════

    def _generate_alter_add_column(self, aa: IRAlterTableAddColumn, opt: GenerateOptions) -> str:
        col = aa.column
        sb = StringIO()
        sb.write(f"ALTER TABLE {self.quote_identifier(aa.table_name)} ADD ")
        if aa.if_not_exists:
            sb.write("IF NOT EXISTS ")
        sb.write(f"{self.quote_identifier(col.name)} {self.map_type(col.type)}")
        if col.constraints:
            for c in col.constraints:
                match c:
                    case ColNotNull():
                        sb.write(" NOT NULL")
                    case ColPrimaryKey():
                        sb.write(" PRIMARY KEY")
                    case ColUnique():
                        sb.write(" UNIQUE")
        return sb.getvalue()

    # ═══════════════════════════════════════
    #  CREATE INDEX — native IF NOT EXISTS (MariaDB 10.5+)
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
