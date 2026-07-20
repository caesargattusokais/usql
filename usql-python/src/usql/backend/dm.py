"""DM (DaMeng) backend -- generates DM-compatible SQL from the Semantic IR.

DaMeng is primarily Oracle-compatible but with key differences:
  - Uses VARCHAR (not VARCHAR2)
  - Uses BIT for boolean (not NUMBER(1))
  - Supports both AUTO_INCREMENT (like MySQL) and SEQUENCE (like Oracle)
  - Uses LIMIT/OFFSET natively (DM 8+)
  - DATE also contains time portion (like Oracle)
"""
from __future__ import annotations

from io import StringIO

from usql.backend.oracle import OracleBackend
from usql.backend.generate_options import GenerateOptions
from usql.dialect.dialect import Dialect
from usql.ir.statement import (
    IRSelect, IRCreateTable, IRCreateIndex, IRDropTable,
    IRAlterColumnType, IRTCL,
    TclType, ParamMode,
    ColNotNull, ColPrimaryKey, ColUnique, ColCheck, ColReferences, ColGenerated,
)
from usql.ir.types import (
    DataType, IntType, DecimalType, FloatType, CharType, VarcharType,
    TextType, BooleanType, DateType, TimeType, DatetimeType, TimestampType,
    IntervalYearMonth, IntervalDaySecond, JsonType, UuidType,
    BinaryType, VarbinaryType, BlobType, EnumType,
)


class DmBackend(OracleBackend):
    """DM (DaMeng) dialect backend.

    Extends OracleBackend since DM is Oracle-compatible.
    Overrides type mapping and pagination where DM differs.
    """

    def target_dialect(self) -> Dialect:
        return Dialect.DM

    # DM uses the same double-quote quoting as Oracle
    # (inherited from OracleBackend)

    def map_type(self, dtype: DataType) -> str:
        """DM-specific type mapping.

        Key differences from Oracle:
          - IntType uses TINYINT/SMALLINT/INT/BIGINT (not NUMBER)
          - BooleanType uses BIT (not NUMBER(1))
          - VarcharType uses VARCHAR (not VARCHAR2)
          - DecimalType uses DECIMAL (not NUMBER)
          - FloatType uses REAL/DOUBLE (not BINARY_FLOAT/BINARY_DOUBLE)
          - UuidType uses VARCHAR(36) (not RAW(16))
          - BinaryType uses BINARY (not RAW)
          - VarbinaryType uses VARBINARY (not RAW)
        """
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
                return "REAL" if b <= 32 else "DOUBLE"
            case CharType(length=l):
                return f"CHAR({l})"
            case VarcharType(length=l):
                return f"VARCHAR({l})"
            case TextType():
                return "CLOB"
            case BooleanType():
                return "BIT"
            case DateType():
                return "DATE"
            case TimeType(fractional_seconds=fs):
                return f"TIME({fs})"
            case DatetimeType(fractional_seconds=fs):
                return f"TIMESTAMP({fs})"
            case TimestampType(fractional_seconds=fs):
                return f"TIMESTAMP({fs}) WITH TIME ZONE"
            case IntervalYearMonth():
                return "INTERVAL YEAR TO MONTH"
            case IntervalDaySecond(fractional_seconds=fs):
                return f"INTERVAL DAY TO SECOND({fs})"
            case JsonType():
                return "CLOB"
            case UuidType():
                return "VARCHAR(36)"
            case BinaryType(length=l):
                return f"BINARY({l})"
            case VarbinaryType(length=l):
                return f"VARBINARY({l})"
            case BlobType():
                return "BLOB"
            case EnumType():
                return "VARCHAR(255)"
            case _:
                return getattr(dtype, "type_name", type(dtype).__name__)

    # ======================================================================
    #  SELECT -- DM 8+ supports LIMIT/OFFSET natively (no ROWNUM wrapping)
    # ======================================================================

    def _generate_select(self, sel: IRSelect, opt: GenerateOptions) -> str:
        """Override Oracle's ROWNUM wrapping with DM's native LIMIT/OFFSET."""
        sb = StringIO()
        core = sel.core

        # WITH clause
        if core.with_clause:
            sb.write("WITH ")
            if core.with_clause[0].recursive:
                sb.write("RECURSIVE ")
            sb.write(", ".join(
                f'{self.quote_identifier(cte.name)} AS ({self._generate_select(cte.query, opt)})'
                for cte in core.with_clause
            ))
            sb.write(" ")

        # SELECT
        sb.write("SELECT ")
        if core.distinct:
            sb.write("DISTINCT ")
        sb.write(", ".join(self._generate_select_item(p, opt) for p in core.projections))

        # FROM -- DM also uses DUAL for SELECT without FROM
        if core.from_clause:
            sb.write(" FROM ")
            sb.write(", ".join(self._generate_table_ref(f, opt) for f in core.from_clause))
        else:
            sb.write(" FROM DUAL")

        # WHERE
        if core.where is not None:
            sb.write(" WHERE ")
            sb.write(self.generate_expr(core.where, opt))

        # GROUP BY
        if core.group_by:
            sb.write(" GROUP BY ")
            sb.write(", ".join(self._generate_group_by(g, opt) for g in core.group_by))

        # HAVING
        if core.having is not None:
            sb.write(" HAVING ")
            sb.write(self.generate_expr(core.having, opt))

        # ORDER BY
        if sel.order_by:
            sb.write(" ORDER BY ")
            sb.write(", ".join(self._generate_order_by(o, opt) for o in sel.order_by))

        # DM 8+ supports native LIMIT/OFFSET
        if sel.fetch:
            if sel.fetch.limit is not None:
                sb.write(" LIMIT ")
                sb.write(self.generate_expr(sel.fetch.limit, opt))
            if sel.fetch.offset is not None:
                sb.write(" OFFSET ")
                sb.write(self.generate_expr(sel.fetch.offset, opt))

        # Set operations
        if core.set_op and core.set_operand:
            op_name = core.set_op.name.replace("_", " ")
            sb.write(f" {op_name} ")
            sb.write(self._generate_select(core.set_operand, opt))

        return sb.getvalue()

    # ======================================================================
    #  CREATE TABLE -- DM-specific DDL (IDENTITY instead of Oracle's AS IDENTITY)
    # ======================================================================

    def _generate_create_table(self, ct: IRCreateTable, opt: GenerateOptions) -> str:
        sb = StringIO()
        sb.write("CREATE TABLE ")
        sb.write(self._generate_table_ref(ct.name, opt))
        sb.write(" (\n")

        items: list[str] = [self._generate_column_def(c, opt) for c in ct.columns]

        # ENUM CHECK constraints
        for col in ct.columns:
            if isinstance(col.type, EnumType) and col.type.values:
                values = ", ".join(
                    f"'{v.replace(chr(39), chr(39) + chr(39))}'" for v in col.type.values
                )
                items.append(f"  CHECK ({self.quote_identifier(col.name)} IN ({values}))")

        if ct.constraints:
            items.extend(self._generate_table_constraint(c, opt) for c in ct.constraints)

        sb.write(",\n".join(items))
        sb.write("\n)")

        if not ct.if_not_exists:
            return sb.getvalue()

        # DM doesn't support IF NOT EXISTS -- PL/SQL wrapper (catch all duplicate errors)
        ddl = sb.getvalue().replace("'", "''")
        return (f"BEGIN EXECUTE IMMEDIATE '{ddl}'; "
                f"EXCEPTION WHEN OTHERS THEN NULL; END;")

    def _generate_column_def(self, col, opt: GenerateOptions) -> str:
        """DM column def: uses IDENTITY for auto-increment (not Oracle's AS IDENTITY)."""
        sb = StringIO()
        sb.write(f"  {self.quote_identifier(col.name)} {self.map_type(col.type)}")

        if col.default_value is not None:
            sb.write(f" DEFAULT {self.generate_expr(col.default_value, opt)}")

        if col.constraints:
            for c in col.constraints:
                match c:
                    case ColNotNull():
                        sb.write(" NOT NULL")
                    case ColPrimaryKey(auto_increment=ai):
                        sb.write(" PRIMARY KEY")
                        if ai:
                            sb.write(" IDENTITY")  # DM uses IDENTITY keyword
                    case ColUnique():
                        sb.write(" UNIQUE")
                    case ColCheck(condition=cond):
                        sb.write(f" CHECK ({self.generate_expr(cond, opt)})")
                    case ColReferences():
                        sb.write(f" REFERENCES {self.quote_identifier(c.target_table)}"
                                 f"({self.quote_identifier(c.target_column)})")
                    case ColGenerated():
                        sb.write(" GENERATED ALWAYS AS")
                        if c.expression:
                            sb.write(f" ({self.generate_expr(c.expression, opt)})")
                        if not c.virtual:
                            sb.write(" STORED")
        return sb.getvalue()

    # ======================================================================
    #  CREATE INDEX -- DM IF NOT EXISTS uses broader exception swallowing
    # ======================================================================

    def _generate_create_index(self, idx, opt: GenerateOptions) -> str:
        result = super(OracleBackend, self)._generate_create_index(idx, opt)

        if not idx.if_not_exists:
            return result

        ddl = result.replace("'", "''")
        return (f"BEGIN EXECUTE IMMEDIATE '{ddl}'; "
                f"EXCEPTION WHEN OTHERS THEN NULL; END;")

    # ======================================================================
    #  DROP TABLE -- DM CASCADE (without CONSTRAINTS suffix), broader IF EXISTS
    # ======================================================================

    def _generate_drop_table(self, dt, opt: GenerateOptions) -> str:
        cascade_suffix = " CASCADE" if dt.cascade else ""
        if not dt.if_exists:
            return f"DROP TABLE {self.quote_identifier(dt.name)}{cascade_suffix}"

        quoted = self.quote_identifier(dt.name).replace("'", "''")
        return (f"BEGIN EXECUTE IMMEDIATE 'DROP TABLE {quoted}{cascade_suffix}'; "
                f"EXCEPTION WHEN OTHERS THEN NULL; END;")

    # ======================================================================
    #  ALTER COLUMN TYPE -- DM uses MODIFY (same as Oracle)
    # ======================================================================

    # Inherited from OracleBackend

    # ======================================================================
    #  TCL -- DM: similar to Oracle, no standalone BEGIN
    # ======================================================================

    def _generate_tcl(self, tcl: IRTCL, opt: GenerateOptions) -> str:
        match tcl.type:
            case TclType.BEGIN:
                # DM: implicit transaction start, no BEGIN needed
                return "SELECT 1 FROM DUAL"
            case TclType.COMMIT:
                return "COMMIT"
            case TclType.ROLLBACK:
                return "ROLLBACK"
            case TclType.SAVEPOINT:
                return f"SAVEPOINT {tcl.savepoint_name}"
            case TclType.RELEASE_SAVEPOINT:
                return f"RELEASE SAVEPOINT {tcl.savepoint_name}"
            case TclType.SET_TRANSACTION:
                return "SET TRANSACTION ISOLATION LEVEL READ COMMITTED"
