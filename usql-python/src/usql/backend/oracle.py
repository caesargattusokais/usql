"""Oracle backend -- generates Oracle-compatible SQL from the Semantic IR.

Key adaptations:
  - LIMIT/OFFSET -> ROWNUM wrapping
  - SELECT without FROM -> FROM DUAL
  - BOOLEAN -> NUMBER(1) comparison
  - VARCHAR -> VARCHAR2
  - MOD as function call (not infix operator)
  - Oracle-native MERGE syntax
  - PL/SQL wrapper for IF NOT EXISTS
"""
from __future__ import annotations

from io import StringIO

from usql.backend.base import AbstractDialectBackend
from usql.backend.generate_options import GenerateOptions
from usql.dialect.dialect import Dialect
from usql.ir.expr import (
    IRExpr, IRLiteral, IRBinaryOp, IRUnaryOp, IRFunctionCall,
    BinaryOp, UnaryOp,
)
from usql.ir.statement import (
    IRSelect, IRInsert, IRUpdate, IRDelete, IRMerge,
    IRCreateTable, IRCreateIndex, IRDropTable, IRDropIndex,
    IRAlterTableAddColumn, IRAlterColumnType,
    IRAlterColumnSetDefault, IRAlterColumnDropDefault,
    IRTCL, IRCall,
    SelectCore, IRColumnDef,
    ColNotNull, ColPrimaryKey, ColUnique, ColCheck, ColReferences, ColGenerated,
    MergeInsert, MergeUpdate, MergeDelete,
    OrderDir, SetOp, TclType, ParamMode,
)
from usql.ir.types import (
    DataType, IntType, DecimalType, FloatType, CharType, VarcharType,
    TextType, BooleanType, DateType, TimeType, DatetimeType, TimestampType,
    IntervalYearMonth, IntervalDaySecond, JsonType, UuidType,
    BinaryType, VarbinaryType, BlobType, EnumType, NullType,
)


class OracleBackend(AbstractDialectBackend):
    """Oracle dialect backend."""

    def target_dialect(self) -> Dialect:
        return Dialect.ORACLE

    def quote_identifier(self, identifier: str) -> str:
        # Oracle auto-folds unquoted identifiers to UPPERCASE.
        # Double-quoting preserves case.
        return f'"{identifier.replace(chr(34), chr(34) + chr(34))}"'

    def map_type(self, dtype: DataType) -> str:
        match dtype:
            case IntType(bits=8):
                return "NUMBER(3)"
            case IntType(bits=16):
                return "NUMBER(5)"
            case IntType(bits=32):
                return "NUMBER(10)"
            case IntType(bits=64):
                return "NUMBER(19)"
            case IntType():
                return "NUMBER(10)"
            case DecimalType(precision=p, scale=s):
                return f"NUMBER({p},{s})"
            case FloatType(bits=b):
                return "BINARY_FLOAT" if b <= 32 else "BINARY_DOUBLE"
            case CharType(length=l):
                return f"CHAR({l})"
            case VarcharType(length=l):
                return f"VARCHAR2({l} CHAR)"
            case TextType():
                return "CLOB"
            case BooleanType():
                return "NUMBER(1)"
            case DateType():
                return "DATE"
            case TimeType(fractional_seconds=fs):
                return f"INTERVAL DAY(0) TO SECOND({fs})"
            case DatetimeType(fractional_seconds=fs):
                return f"TIMESTAMP({fs})"
            case TimestampType(fractional_seconds=fs):
                return f"TIMESTAMP({fs}) WITH TIME ZONE"
            case IntervalYearMonth():
                return "INTERVAL YEAR TO MONTH"
            case IntervalDaySecond(fractional_seconds=fs):
                return f"INTERVAL DAY(2) TO SECOND({fs})"
            case JsonType():
                return "CLOB"
            case UuidType():
                return "RAW(16)"
            case BinaryType(length=l):
                return f"RAW({l})"
            case VarbinaryType(length=l):
                return f"RAW({l})"
            case BlobType():
                return "BLOB"
            case EnumType():
                return "VARCHAR2(255)"
            case _:
                return getattr(dtype, "type_name", type(dtype).__name__)

    # ======================================================================
    #  SELECT -- ROWNUM wrapping for LIMIT/OFFSET, FROM DUAL
    # ======================================================================

    def _generate_select(self, sel: IRSelect, opt: GenerateOptions) -> str:
        inner_sql = self._generate_select_core(sel, opt)

        # ROWNUM wrapping for LIMIT/OFFSET polyfill
        if sel.fetch and sel.fetch.limit is not None:
            has_offset = sel.fetch.offset is not None

            if has_offset:
                # ROWNUM 3-layer wrap: Oracle classic pattern
                limit_expr = self.generate_expr(sel.fetch.limit, opt)
                offset_expr = self.generate_expr(sel.fetch.offset, opt)

                return (f'SELECT {self.quote_identifier("inner__")}.* FROM (\n'
                        f'  SELECT {self.quote_identifier("core__")}.*, ROWNUM AS {self.quote_identifier("rn__")} FROM (\n'
                        f'    {inner_sql}\n'
                        f'  ) {self.quote_identifier("core__")}\n'
                        f'  WHERE ROWNUM <= {limit_expr} + {offset_expr}\n'
                        f') {self.quote_identifier("inner__")}\n'
                        f'WHERE {self.quote_identifier("rn__")} > {offset_expr}')
            else:
                # Simple ROWNUM: no offset
                return (f'SELECT * FROM (\n  {inner_sql}\n) WHERE ROWNUM <= '
                        f'{self.generate_expr(sel.fetch.limit, opt)}')

        return inner_sql

    def _generate_select_core(self, sel: IRSelect, opt: GenerateOptions) -> str:
        """Generate the core SELECT without ROWNUM wrapping."""
        sb = StringIO()
        core = sel.core

        # WITH clause
        if core.with_clause:
            sb.write("WITH ")
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

        # FROM -- Oracle requires FROM DUAL when no table is referenced
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

        # Set operations -- Oracle uses MINUS instead of EXCEPT
        if core.set_op and core.set_operand:
            op_name = core.set_op.name.replace("_", " ")
            if core.set_op == SetOp.EXCEPT:
                op_name = "MINUS"
            sb.write(f" {op_name} ")
            sb.write(self._generate_select_core(core.set_operand, opt))

        return sb.getvalue()

    # ======================================================================
    #  Expression overrides
    # ======================================================================

    def _gen_literal(self, lit: IRLiteral) -> str:
        if lit.value is None:
            return "NULL"
        if lit.type is None:
            return str(lit.value)
        match lit.type:
            case BooleanType():
                return "1" if lit.value else "0"
            case NullType():
                return "NULL"
            case IntType() | FloatType() | DecimalType():
                return str(lit.value)
            case DateType():
                return f"DATE '{lit.value}'"
            case DatetimeType():
                return f"TIMESTAMP '{lit.value}'"
            case _:
                escaped = str(lit.value).replace("'", "''")
                return f"'{escaped}'"

    def _gen_binary_op(self, bo: IRBinaryOp, opt: GenerateOptions) -> str:
        left = self.generate_expr(bo.left, opt)
        right = self.generate_expr(bo.right, opt)

        # Oracle does not support MOD as infix operator -- use MOD(a, b) function
        if bo.op == BinaryOp.MOD:
            return f"MOD({left}, {right})"

        op = {
            BinaryOp.ADD: " + ", BinaryOp.SUB: " - ", BinaryOp.MUL: " * ",
            BinaryOp.DIV: " / ",
            BinaryOp.EQ: " = ", BinaryOp.NEQ: " != ",
            BinaryOp.LT: " < ", BinaryOp.GT: " > ",
            BinaryOp.LTE: " <= ", BinaryOp.GTE: " >= ",
            BinaryOp.AND: " AND ", BinaryOp.OR: " OR ",
            BinaryOp.CONCAT: " || ",
            BinaryOp.LIKE: " LIKE ", BinaryOp.NOT_LIKE: " NOT LIKE ",
            BinaryOp.IS_DISTINCT_FROM: " IS DISTINCT FROM ",
        }.get(bo.op, f" {bo.op.name} ")
        return f"({left}{op}{right})"

    def _gen_unary_op(self, uo: IRUnaryOp, opt: GenerateOptions) -> str:
        operand = self.generate_expr(uo.operand, opt)
        match uo.op:
            case UnaryOp.NOT:
                return f"NOT ({operand})"
            case UnaryOp.NEG:
                return f"-({operand})"
            case UnaryOp.IS_NULL:
                return f"{operand} IS NULL"
            case UnaryOp.IS_NOT_NULL:
                return f"{operand} IS NOT NULL"
            # Oracle: no boolean type -> compare with 1/0
            case UnaryOp.IS_TRUE:
                return f"{operand} = 1"
            case UnaryOp.IS_NOT_TRUE:
                return f"({operand} IS NULL OR {operand} != 1)"
            case UnaryOp.IS_FALSE:
                return f"{operand} = 0"
            case UnaryOp.IS_NOT_FALSE:
                return f"({operand} IS NULL OR {operand} != 0)"
            case UnaryOp.EXISTS:
                return f"EXISTS {operand}"
            case _:
                raise NotImplementedError(f"Unsupported unary op: {uo.op}")

    # ======================================================================
    #  Function call -- Oracle native KEEP clause
    # ======================================================================

    def _gen_function_call(self, fc: IRFunctionCall, opt: GenerateOptions) -> str:
        # Function name translation via catalog
        func_name = fc.func_name
        if self._function_catalog:
            mapping = self._function_catalog.get(func_name)
            if mapping:
                func_name = mapping

        if fc.star:
            args_str = "*"
        else:
            args_str = ", ".join(self.generate_expr(a, opt) for a in fc.args)
        result = f"{func_name}({args_str})"

        # Oracle native KEEP clause (before OVER)
        if fc.keep:
            result += " KEEP (DENSE_RANK "
            result += "LAST" if fc.keep.is_last else "FIRST"
            if fc.keep.order_by:
                ob = ", ".join(
                    f"{self.generate_expr(o.expr, opt)}"
                    f"{' DESC' if o.dir == OrderDir.DESC else ''}"
                    for o in fc.keep.order_by
                )
                result += f" ORDER BY {ob}"
            result += ")"

        # OVER clause (after KEEP)
        if fc.over:
            result += self._gen_over(fc.over, opt)

        return result

    # ======================================================================
    #  INSERT -- multi-row via SELECT UNION ALL with FROM DUAL
    # ======================================================================

    def _generate_insert(self, ins: IRInsert, opt: GenerateOptions) -> str:
        multi_row = ins.values is not None and len(ins.values) > 1

        if multi_row:
            sb = StringIO()
            sb.write("INSERT INTO ")
            sb.write(self._generate_table_ref(ins.table, opt))
            if ins.columns:
                cols = ", ".join(self.quote_identifier(c) for c in ins.columns)
                sb.write(f" ({cols})")
            for i, row in enumerate(ins.values):
                if i > 0:
                    sb.write(" UNION ALL")
                sb.write("\n  SELECT ")
                exprs = [self.generate_expr(v, opt) for v in row]
                # Deduplicate identical expressions to avoid ORA-00918
                seen: set[str] = set()
                for j, e in enumerate(exprs):
                    if j > 0:
                        sb.write(", ")
                    if e in seen:
                        e = f"CAST({e} AS NUMBER)"
                    seen.add(e)
                    sb.write(e)
                sb.write(" FROM DUAL")
            return sb.getvalue()

        return super()._generate_insert(ins, opt)

    # ======================================================================
    #  MERGE -- Oracle native syntax
    # ======================================================================

    def _generate_merge(self, merge: IRMerge, opt: GenerateOptions) -> str:
        sb = StringIO()
        sb.write("MERGE INTO ")
        sb.write(self._generate_table_ref(merge.target, opt))
        sb.write(" USING ")
        sb.write(self._generate_table_ref(merge.source, opt))
        sb.write(" ON (")
        sb.write(self.generate_expr(merge.on_condition, opt))
        sb.write(")")
        for action in merge.actions:
            match action:
                case MergeUpdate(sets=sets):
                    sb.write(" WHEN MATCHED THEN UPDATE SET ")
                    sb.write(", ".join(
                        f"{self.quote_identifier(s.column)} = {self.generate_expr(s.value, opt)}"
                        for s in sets
                    ))
                case MergeInsert(columns=cols, values=vals):
                    sb.write(" WHEN NOT MATCHED THEN INSERT (")
                    sb.write(", ".join(self.quote_identifier(c) for c in (cols or ())))
                    sb.write(") VALUES (")
                    sb.write(", ".join(self.generate_expr(v, opt) for v in vals))
                    sb.write(")")
                case MergeDelete():
                    sb.write(" WHEN MATCHED THEN DELETE")
        return sb.getvalue()

    # ======================================================================
    #  CREATE TABLE -- Oracle-specific DDL
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

        # Oracle doesn't support IF NOT EXISTS -- PL/SQL wrapper
        # Only swallow ORA-00955 (name already used), re-raise everything else
        ddl = sb.getvalue().replace("'", "''")
        return (f"BEGIN EXECUTE IMMEDIATE '{ddl}'; "
                f"EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;")

    def _generate_column_def(self, col: IRColumnDef, opt: GenerateOptions) -> str:
        sb = StringIO()
        sb.write(f"  {self.quote_identifier(col.name)} {self.map_type(col.type)}")

        # Oracle: DEFAULT before NOT NULL
        if col.default_value is not None:
            sb.write(f" DEFAULT {self.generate_expr(col.default_value, opt)}")

        if col.constraints:
            for c in col.constraints:
                match c:
                    case ColNotNull():
                        sb.write(" NOT NULL")
                    case ColPrimaryKey(auto_increment=ai):
                        if ai:
                            sb.write(" GENERATED BY DEFAULT ON NULL AS IDENTITY PRIMARY KEY")
                        else:
                            sb.write(" PRIMARY KEY")
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
    #  CREATE INDEX -- PL/SQL wrapper for IF NOT EXISTS
    # ======================================================================

    def _generate_create_index(self, idx, opt: GenerateOptions) -> str:
        result = super()._generate_create_index(idx, opt)

        if not idx.if_not_exists:
            return result

        ddl = result.replace("'", "''")
        return (f"BEGIN EXECUTE IMMEDIATE '{ddl}'; "
                f"EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;")

    # ======================================================================
    #  DROP TABLE -- CASCADE CONSTRAINTS, PL/SQL for IF EXISTS
    # ======================================================================

    def _generate_drop_table(self, dt, opt: GenerateOptions) -> str:
        cascade_suffix = " CASCADE CONSTRAINTS" if dt.cascade else ""
        if not dt.if_exists:
            return f"DROP TABLE {self.quote_identifier(dt.name)}{cascade_suffix}"

        # PL/SQL wrapper to swallow ORA-00942 (table does not exist)
        quoted = self.quote_identifier(dt.name).replace("'", "''")
        return (f"BEGIN EXECUTE IMMEDIATE 'DROP TABLE {quoted}{cascade_suffix}'; "
                f"EXCEPTION WHEN OTHERS THEN IF SQLCODE != -942 THEN RAISE; END IF; END;")

    # ======================================================================
    #  ALTER TABLE -- Oracle uses MODIFY instead of ALTER COLUMN
    # ======================================================================

    def _generate_alter_column_type(self, act, opt: GenerateOptions) -> str:
        return (f"ALTER TABLE {self.quote_identifier(act.table_name)} "
                f"MODIFY {self.quote_identifier(act.column)} {self.map_type(act.new_type)}")

    def _generate_alter_set_default(self, acs, opt: GenerateOptions) -> str:
        return (f"ALTER TABLE {self.quote_identifier(acs.table_name)} "
                f"MODIFY {self.quote_identifier(acs.column)} DEFAULT {self.generate_expr(acs.value, opt)}")

    def _generate_alter_drop_default(self, acd, opt: GenerateOptions) -> str:
        return (f"ALTER TABLE {self.quote_identifier(acd.table_name)} "
                f"MODIFY {self.quote_identifier(acd.column)} DEFAULT NULL")

    # ======================================================================
    #  TCL -- Oracle: implicit transaction start, no BEGIN needed
    # ======================================================================

    def _generate_tcl(self, tcl: IRTCL, opt: GenerateOptions) -> str:
        match tcl.type:
            case TclType.BEGIN:
                # Oracle: implicit transaction start, no BEGIN needed.
                # Use a no-op SELECT to start a session without error.
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

    # ======================================================================
    #  Procedure / Function / Call -- Oracle PL/SQL syntax
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
        sb.write(f" AS\n{cp.body};")
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
        if cf.return_type:
            sb.write(f" RETURN {self.map_type(cf.return_type)}")
        sb.write(f" AS\n{cf.body};")
        return sb.getvalue()

    def _generate_call(self, call, opt: GenerateOptions) -> str:
        args = ", ".join(self.generate_expr(a, opt) for a in call.args)
        if args:
            return f"BEGIN {self.quote_identifier(call.procedure_name)}({args}); END;"
        return f"BEGIN {self.quote_identifier(call.procedure_name)}; END;"

    def _gen_param(self, p) -> str:
        mode = {ParamMode.IN: "", ParamMode.OUT: "OUT ", ParamMode.INOUT: "IN OUT "}.get(p.mode, "")
        return f"{self.quote_identifier(p.name)} {mode}{self.map_type(p.type)}"
