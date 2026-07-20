"""PostgreSQL backend -- generates PostgreSQL-compatible SQL from the Semantic IR."""
from __future__ import annotations

from io import StringIO

from usql.backend.base import AbstractDialectBackend
from usql.backend.generate_options import GenerateOptions
from usql.dialect.dialect import Dialect
from usql.ir.expr import IRLiteral, IRBinaryOp, BinaryOp
from usql.ir.statement import (
    IRSelect, IRInsert, IRMerge, IRCreateTable, IRCreateIndex,
    IRAlterTableAddColumn, IRAlterColumnType,
    IRTCL, TclType, IRColumnDef,
    ColNotNull, ColPrimaryKey, ColUnique, ColCheck, ColReferences, ColGenerated,
    MergeInsert, MergeUpdate,
)
from usql.ir.types import (
    DataType, IntType, DecimalType, FloatType, CharType, VarcharType,
    TextType, BooleanType, DateType, TimeType, DatetimeType, TimestampType,
    IntervalYearMonth, IntervalDaySecond, JsonType, UuidType,
    BinaryType, VarbinaryType, BlobType, ArrayType, EnumType, NullType,
)


class PgBackend(AbstractDialectBackend):
    """PostgreSQL dialect backend.

    Key adaptations:
      - Double-quote identifier quoting
      - PG-specific type mapping (SERIAL, BIGSERIAL, VARCHAR, TEXT, BOOLEAN,
        TIMESTAMP, BYTEA, JSONB, UUID)
      - FETCH FIRST n ROWS ONLY pagination style
      - MERGE -> INSERT ON CONFLICT
      - Boolean literals TRUE/FALSE
    """

    def target_dialect(self) -> Dialect:
        return Dialect.POSTGRESQL

    def quote_identifier(self, identifier: str) -> str:
        return f'"{identifier.replace(chr(34), chr(34) + chr(34))}"'

    def map_type(self, dtype: DataType) -> str:
        match dtype:
            case IntType(bits=8):
                return "SMALLINT"  # PG has no TINYINT
            case IntType(bits=16):
                return "SMALLINT"
            case IntType(bits=32):
                return "INTEGER"
            case IntType(bits=64):
                return "BIGINT"
            case IntType():
                return "INTEGER"
            case DecimalType(precision=p, scale=s):
                return f"NUMERIC({p},{s})"
            case FloatType(bits=b):
                return "REAL" if b <= 32 else "DOUBLE PRECISION"
            case CharType(length=l):
                return f"CHAR({l})"
            case VarcharType(length=l):
                return f"VARCHAR({l})"
            case TextType():
                return "TEXT"
            case BooleanType():
                return "BOOLEAN"
            case DateType():
                return "DATE"
            case TimeType(fractional_seconds=fs):
                return f"TIME({fs})"
            case DatetimeType(fractional_seconds=fs):
                return f"TIMESTAMP({fs})"
            case TimestampType(fractional_seconds=fs):
                return f"TIMESTAMPTZ({fs})"
            case IntervalYearMonth():
                return "INTERVAL YEAR TO MONTH"
            case IntervalDaySecond(fractional_seconds=fs):
                return f"INTERVAL DAY TO SECOND({fs})"
            case JsonType():
                return "JSONB"
            case UuidType():
                return "UUID"
            case BinaryType(length=l):
                return "BYTEA"
            case VarbinaryType():
                return "BYTEA"
            case BlobType():
                return "BYTEA"
            case ArrayType(element_type=et):
                return f"{self.map_type(et)}[]"
            case EnumType():
                return "VARCHAR(255)"
            case _:
                # Fallback: use type_name property
                return getattr(dtype, "type_name", type(dtype).__name__)

    # ======================================================================
    #  SELECT -- FETCH FIRST n ROWS ONLY pagination
    # ======================================================================

    def _generate_select(self, sel: IRSelect, opt: GenerateOptions) -> str:
        sb = StringIO()

        # WITH clause
        core = sel.core
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

        # FROM
        if core.from_clause:
            sb.write(" FROM ")
            sb.write(", ".join(self._generate_table_ref(f, opt) for f in core.from_clause))

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

        # FETCH FIRST n ROWS ONLY pagination (PG standard SQL style)
        if sel.fetch:
            if sel.fetch.limit is not None and sel.fetch.offset is not None:
                sb.write(" OFFSET ")
                sb.write(self.generate_expr(sel.fetch.offset, opt))
                sb.write(" ROWS")
                sb.write(" FETCH FIRST ")
                sb.write(self.generate_expr(sel.fetch.limit, opt))
                sb.write(" ROWS ONLY")
            elif sel.fetch.limit is not None:
                sb.write(" FETCH FIRST ")
                sb.write(self.generate_expr(sel.fetch.limit, opt))
                sb.write(" ROWS ONLY")
            elif sel.fetch.offset is not None:
                sb.write(" OFFSET ")
                sb.write(self.generate_expr(sel.fetch.offset, opt))
                sb.write(" ROWS")

        # Set operations
        if core.set_op and core.set_operand:
            op_name = core.set_op.name.replace("_", " ")
            sb.write(f" {op_name} ")
            sb.write(self._generate_select(core.set_operand, opt))

        return sb.getvalue()

    # ======================================================================
    #  INSERT -- ON CONFLICT DO NOTHING for ignore_errors
    # ======================================================================

    def _generate_insert(self, ins: IRInsert, opt: GenerateOptions) -> str:
        result = super()._generate_insert(ins, opt)
        # PG: INSERT ... ON CONFLICT DO NOTHING
        if ins.ignore_errors and "ON CONFLICT" not in result:
            result += " ON CONFLICT DO NOTHING"
        return result

    # ======================================================================
    #  MERGE -> INSERT ON CONFLICT (PG upsert)
    # ======================================================================

    def _generate_merge(self, merge: IRMerge, opt: GenerateOptions) -> str:
        ins_action: MergeInsert | None = None
        upd_action: MergeUpdate | None = None
        for action in merge.actions:
            if isinstance(action, MergeInsert):
                ins_action = action
            elif isinstance(action, MergeUpdate):
                upd_action = action

        sb = StringIO()
        sb.write("INSERT INTO ")
        sb.write(self._generate_table_ref(merge.target, opt))

        if ins_action and ins_action.columns:
            cols = ", ".join(self.quote_identifier(c) for c in ins_action.columns)
            sb.write(f" ({cols})")
            sb.write(" SELECT ")
            sb.write(", ".join(self.generate_expr(v, opt) for v in ins_action.values))
            sb.write(" FROM ")
            sb.write(self._generate_table_ref(merge.source, opt))

        if upd_action:
            # Extract conflict columns from MERGE ON condition
            conflict_cols = self._extract_conflict_columns(merge)
            sb.write(" ON CONFLICT (")
            sb.write(", ".join(self.quote_identifier(c) for c in conflict_cols))
            sb.write(") DO UPDATE SET ")
            sb.write(", ".join(
                f"{self.quote_identifier(s.column)} = EXCLUDED.{self.quote_identifier(s.column)}"
                for s in upd_action.sets
            ))

        return sb.getvalue()

    def _extract_conflict_columns(self, merge: IRMerge) -> list[str]:
        """Extract target-table column names from the MERGE ON condition."""
        from usql.ir.statement import IRTableName

        cols: list[str] = []
        target_ref = merge.target
        target_alias = target_ref.alias if isinstance(target_ref, IRTableName) else None
        target_name = target_ref.name if isinstance(target_ref, IRTableName) else None

        self._collect_conflict_columns(
            merge.on_condition, target_alias, target_name, cols
        )

        # Fallback: use INSERT columns if extraction failed
        if not cols:
            for action in merge.actions:
                if isinstance(action, MergeInsert) and action.columns:
                    cols.extend(action.columns)
                    break

        return cols

    def _collect_conflict_columns(
        self, expr, target_alias: str | None, target_name: str | None, out: list[str]
    ) -> None:
        from usql.ir.expr import IRColumnRef

        if isinstance(expr, IRBinaryOp) and expr.op == BinaryOp.EQ:
            col = self._match_target_column(expr.left, target_alias, target_name)
            if col is None:
                col = self._match_target_column(expr.right, target_alias, target_name)
            if col is not None:
                out.append(col)
        elif isinstance(expr, IRBinaryOp) and expr.op == BinaryOp.AND:
            self._collect_conflict_columns(expr.left, target_alias, target_name, out)
            self._collect_conflict_columns(expr.right, target_alias, target_name, out)

    def _match_target_column(
        self, expr, target_alias: str | None, target_name: str | None
    ) -> str | None:
        from usql.ir.expr import IRColumnRef

        if isinstance(expr, IRColumnRef):
            if expr.qualifier:
                if expr.qualifier == target_alias or expr.qualifier == target_name:
                    return expr.name
            else:
                return expr.name
        return None

    # ======================================================================
    #  CREATE TABLE -- GENERATED ALWAYS AS IDENTITY for auto-increment
    # ======================================================================

    def _generate_create_table(self, ct: IRCreateTable, opt: GenerateOptions) -> str:
        result = super()._generate_create_table(ct, opt)

        # Append ENUM CHECK constraints
        enum_checks: list[str] = []
        for col in ct.columns:
            if isinstance(col.type, EnumType) and col.type.values:
                values = ", ".join(
                    f"'{v.replace(chr(39), chr(39) + chr(39))}'" for v in col.type.values
                )
                enum_checks.append(
                    f"  CHECK ({self.quote_identifier(col.name)} IN ({values}))"
                )

        if enum_checks:
            # Insert before the final closing paren
            result = result.rstrip(")") + ",\n" + ",\n".join(enum_checks) + "\n)"

        return result

    def _generate_column_def(self, col: IRColumnDef, opt: GenerateOptions) -> str:
        sb = StringIO()
        sb.write(f"  {self.quote_identifier(col.name)} {self.map_type(col.type)}")
        if col.constraints:
            is_pk = False
            is_ai = False
            for c in col.constraints:
                match c:
                    case ColPrimaryKey(auto_increment=ai):
                        is_pk = True
                        is_ai = ai
                    case ColNotNull():
                        sb.write(" NOT NULL")
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
                        sb.write(" STORED")
            if is_pk:
                sb.write(" PRIMARY KEY")
                if is_ai:
                    # PG uses GENERATED ALWAYS AS IDENTITY for auto-increment
                    sb.write(" GENERATED ALWAYS AS IDENTITY")
        if col.default_value is not None:
            sb.write(f" DEFAULT {self.generate_expr(col.default_value, opt)}")
        return sb.getvalue()

    # ======================================================================
    #  CREATE INDEX -- partial index (WHERE clause)
    # ======================================================================

    def _generate_create_index(self, idx, opt: GenerateOptions) -> str:
        result = super()._generate_create_index(idx, opt)
        # PG supports partial indexes with WHERE
        if idx.where_clause is not None:
            result += f" WHERE {self.generate_expr(idx.where_clause, opt)}"
        return result

    # ======================================================================
    #  ALTER TABLE ADD COLUMN -- IF NOT EXISTS support
    # ======================================================================

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

    # ======================================================================
    #  ALTER COLUMN TYPE
    # ======================================================================

    def _generate_alter_column_type(self, act: IRAlterColumnType, opt: GenerateOptions) -> str:
        return (f"ALTER TABLE {self.quote_identifier(act.table_name)} "
                f"ALTER COLUMN {self.quote_identifier(act.column)} TYPE {self.map_type(act.new_type)}")

    # ======================================================================
    #  TCL -- PG-specific
    # ======================================================================

    def _generate_tcl(self, tcl: IRTCL, opt: GenerateOptions) -> str:
        match tcl.type:
            case TclType.BEGIN:
                return "BEGIN"
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

    # ======================================================================
    #  Literal generation -- PG boolean literals
    # ======================================================================

    def _gen_literal(self, lit: IRLiteral) -> str:
        if lit.value is None:
            return "NULL"
        if lit.type is None:
            return str(lit.value)
        match lit.type:
            case BooleanType():
                return "TRUE" if lit.value else "FALSE"
            case NullType():
                return "NULL"
            case IntType() | FloatType() | DecimalType():
                return str(lit.value)
            case _:
                escaped = str(lit.value).replace("\\", "\\\\").replace("'", "''")
                return f"'{escaped}'"

    # ======================================================================
    #  Procedure / Function -- PG plpgsql syntax
    # ======================================================================

    def _generate_create_procedure(self, cp, opt: GenerateOptions) -> str:
        sb = StringIO()
        sb.write("CREATE ")
        if cp.or_replace:
            sb.write("OR REPLACE ")
        sb.write(f"PROCEDURE {self.quote_identifier(cp.name)}")
        if cp.params:
            params = ", ".join(self._gen_param(p) for p in cp.params)
            sb.write(f"({params})")
        else:
            sb.write("()")
        sb.write(f" LANGUAGE plpgsql AS $$\n{cp.body}\n$$;")
        return sb.getvalue()

    def _generate_create_function(self, cf, opt: GenerateOptions) -> str:
        sb = StringIO()
        sb.write("CREATE ")
        if cf.or_replace:
            sb.write("OR REPLACE ")
        sb.write(f"FUNCTION {self.quote_identifier(cf.name)}")
        if cf.params:
            params = ", ".join(self._gen_param(p) for p in cf.params)
            sb.write(f"({params})")
        else:
            sb.write("()")
        if cf.return_type:
            sb.write(f" RETURNS {self.map_type(cf.return_type)}")
        sb.write(f" LANGUAGE plpgsql AS $$\n{cf.body}\n$$;")
        return sb.getvalue()
