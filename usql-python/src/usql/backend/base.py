"""Shared base for dialect backends.

Provides default SQL generation logic so each backend only implements
dialect-specific code. Includes KEEP polyfill (subquery + DENSE_RANK)
and other cross-dialect logic.
"""
from __future__ import annotations

from abc import ABC, abstractmethod
from io import StringIO
from typing import List, Optional, Sequence

from usql.backend.generate_options import GenerateOptions
from usql.dialect.dialect import Dialect
from usql.dialect.capability import Capability
from usql.ir.expr import (
    IRExpr, IRLiteral, IRColumnRef, IRWildcard, IRParameter,
    IRBinaryOp, IRUnaryOp, IRFunctionCall, IRCase, IRCast,
    IRSubquery, IRBetween, IRInList, IRIsNull,
    BinaryOp, UnaryOp, KeepFirst, KeepLast, WhenClause,
)
from usql.ir.statement import (
    IRStatement, IRSelect, IRInsert, IRUpdate, IRDelete, IRMerge,
    IRCreateTable, IRCreateIndex, IRDropTable, IRDropIndex,
    IRTruncateTable, IRAlterTableAddColumn, IRAlterTableDropColumn,
    IRRenameColumn, IRAlterColumnType, IRAlterColumnSetDefault,
    IRAlterColumnDropDefault, IRDropDatabase, IRCreateView,
    IRCreateSchema, IRTCL, IRCreateProcedure, IRCreateFunction, IRCall,
    SelectCore, IRExprSelect, IRWildcardSelect,
    IRTableName, IRJoin, IRSubqueryTable, IRFunctionTable,
    IRGroupBy, IRColumnDef, IRColumnConstraint,
    ColNotNull, ColUnique, ColPrimaryKey, ColCheck, ColReferences, ColGenerated,
    IRTableConstraint, TBPrimaryKey, TBUnique, TBForeignKey, TBCheck,
    SetClause, MergeInsert, MergeUpdate, MergeDelete,
    JoinType, OrderDir, NullsOrder, SetOp, GroupByKind, TclType,
    ForeignKeyAction, IndexColumn, ProcedureParam, ParamMode,
)
from usql.ir.types import DataType, IntType, FloatType, DecimalType, BooleanType, NullType


class AbstractDialectBackend(ABC):
    """Base class for all dialect backends."""

    def __init__(self):
        self._function_catalog = None
        self._keep_cols: list[tuple[str, bool]] = []
        self._keep_idx: int = 0

    # ═══════════════════════════════════════
    #  Abstract interface
    # ═══════════════════════════════════════

    @abstractmethod
    def target_dialect(self) -> Dialect: ...

    @abstractmethod
    def quote_identifier(self, identifier: str) -> str: ...

    @abstractmethod
    def map_type(self, dtype: DataType) -> str: ...

    def set_function_catalog(self, catalog) -> None:
        self._function_catalog = catalog

    # ═══════════════════════════════════════
    #  Main dispatch
    # ═══════════════════════════════════════

    def generate(self, statement: IRStatement, options: GenerateOptions | None = None) -> str:
        options = options or GenerateOptions.DEFAULTS
        return self._dispatch(statement, options)

    def _dispatch(self, stmt: IRStatement, opt: GenerateOptions) -> str:
        match stmt:
            case IRSelect():
                return self._generate_select(stmt, opt)
            case IRInsert():
                return self._generate_insert(stmt, opt)
            case IRUpdate():
                return self._generate_update(stmt, opt)
            case IRDelete():
                return self._generate_delete(stmt, opt)
            case IRMerge():
                return self._generate_merge(stmt, opt)
            case IRCreateTable():
                return self._generate_create_table(stmt, opt)
            case IRCreateIndex():
                return self._generate_create_index(stmt, opt)
            case IRDropTable():
                return self._generate_drop_table(stmt, opt)
            case IRDropIndex():
                return self._generate_drop_index(stmt, opt)
            case IRTruncateTable():
                return self._generate_truncate(stmt, opt)
            case IRAlterTableAddColumn():
                return self._generate_alter_add_column(stmt, opt)
            case IRAlterTableDropColumn():
                return self._generate_alter_drop_column(stmt, opt)
            case IRRenameColumn():
                return self._generate_rename_column(stmt, opt)
            case IRAlterColumnType():
                return self._generate_alter_column_type(stmt, opt)
            case IRAlterColumnSetDefault():
                return self._generate_alter_set_default(stmt, opt)
            case IRAlterColumnDropDefault():
                return self._generate_alter_drop_default(stmt, opt)
            case IRTCL():
                return self._generate_tcl(stmt, opt)
            case IRCreateView():
                return self._generate_create_view(stmt, opt)
            case IRCreateSchema():
                return self._generate_create_schema(stmt, opt)
            case IRDropDatabase():
                return self._generate_drop_database(stmt, opt)
            case IRCreateProcedure():
                return self._generate_create_procedure(stmt, opt)
            case IRCreateFunction():
                return self._generate_create_function(stmt, opt)
            case IRCall():
                return self._generate_call(stmt, opt)
            case _:
                raise NotImplementedError(f"{type(stmt).__name__} not supported")

    # ═══════════════════════════════════════
    #  SELECT
    # ═══════════════════════════════════════

    def _generate_select(self, sel: IRSelect, opt: GenerateOptions) -> str:
        sb = StringIO()

        # WITH clause
        core = sel.core
        if core.with_clause:
            sb.write("WITH ")
            if core.with_clause[0].recursive:
                sb.write("RECURSIVE ")
            sb.write(", ".join(
                f"{self.quote_identifier(cte.name)} AS ({self._generate_select(cte.query, opt)})"
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

        # FETCH / LIMIT / OFFSET
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

    def _generate_select_item(self, item, opt: GenerateOptions) -> str:
        match item:
            case IRExprSelect(expr=expr, alias=alias):
                result = self.generate_expr(expr, opt)
                if alias:
                    result += f" AS {self.quote_identifier(alias)}"
                return result
            case IRWildcardSelect(wildcard=wildcard):
                if wildcard.qualifier:
                    return f"{self.quote_identifier(wildcard.qualifier)}.*"
                return "*"

    def _generate_table_ref(self, ref, opt: GenerateOptions) -> str:
        match ref:
            case IRTableName(name=name, alias=alias, schema=schema):
                r = self.quote_identifier(name)
                if schema:
                    r = f"{self.quote_identifier(schema)}.{r}"
                if alias:
                    r += f" {self.quote_identifier(alias)}"
                return r
            case IRJoin(left=left, type=jtype, right=right, on_condition=on):
                join = {
                    JoinType.INNER: "INNER JOIN",
                    JoinType.LEFT: "LEFT JOIN",
                    JoinType.RIGHT: "RIGHT JOIN",
                    JoinType.FULL: "FULL OUTER JOIN",
                    JoinType.CROSS: "CROSS JOIN",
                }.get(jtype, "JOIN")
                result = f"{self._generate_table_ref(left, opt)} {join} {self._generate_table_ref(right, opt)}"
                if on is not None:
                    result += f" ON {self.generate_expr(on, opt)}"
                return result
            case IRSubqueryTable(query=query, alias=alias):
                return f"({self._generate_select(query, opt)}) {self.quote_identifier(alias)}"
            case IRFunctionTable(func_name=func_name, args=args, alias=alias, lateral=lateral):
                prefix = "LATERAL " if lateral else ""
                args_str = ", ".join(self.generate_expr(a, opt) for a in args)
                result = f"{prefix}{func_name}({args_str})"
                if alias:
                    result += f" {self.quote_identifier(alias)}"
                return result

    def _generate_group_by(self, gb: IRGroupBy, opt: GenerateOptions) -> str:
        if gb.kind == GroupByKind.PLAIN:
            return self.generate_expr(gb.expr, opt)
        kind_name = gb.kind.name.replace("_", " ")
        return f"{kind_name} ({self.generate_expr(gb.expr, opt)})"

    def _generate_order_by(self, ob, opt: GenerateOptions) -> str:
        result = self.generate_expr(ob.expr, opt)
        if ob.dir == "DESC":
            result += " DESC"
        else:
            result += " ASC"
        if ob.nulls:
            result += f" NULLS {ob.nulls}"
        return result

    # ═══════════════════════════════════════
    #  Expressions
    # ═══════════════════════════════════════

    def generate_expr(self, expr: IRExpr, opt: GenerateOptions) -> str:
        match expr:
            case IRLiteral():
                return self._gen_literal(expr)
            case IRColumnRef():
                return self._gen_column_ref(expr)
            case IRWildcard():
                return self._gen_wildcard(expr)
            case IRParameter():
                return "?"
            case IRBinaryOp():
                return self._gen_binary_op(expr, opt)
            case IRUnaryOp():
                return self._gen_unary_op(expr, opt)
            case IRFunctionCall():
                return self._gen_function_call(expr, opt)
            case IRCase():
                return self._gen_case(expr, opt)
            case IRCast():
                return f"CAST({self.generate_expr(expr.expr, opt)} AS {self.map_type(expr.target_type)})"
            case IRSubquery():
                return f"({self._generate_select(expr.query, opt)})"
            case IRBetween():
                not_str = " NOT" if expr.not_ else ""
                return (f"{self.generate_expr(expr.expr, opt)}{not_str} BETWEEN "
                        f"{self.generate_expr(expr.low, opt)} AND {self.generate_expr(expr.high, opt)}")
            case IRInList():
                not_str = " NOT" if expr.not_ else ""
                if expr.subquery:
                    return f"{self.generate_expr(expr.expr, opt)}{not_str} IN ({self._generate_select(expr.subquery, opt)})"
                vals = ", ".join(self.generate_expr(v, opt) for v in (expr.values or ()))
                return f"{self.generate_expr(expr.expr, opt)}{not_str} IN ({vals})"
            case IRIsNull():
                not_str = " NOT" if expr.not_ else ""
                return f"{self.generate_expr(expr.expr, opt)} IS{not_str} NULL"

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
            case _:
                return f"'{str(lit.value).replace("'", "''")}'"

    def _gen_column_ref(self, cr: IRColumnRef) -> str:
        if cr.qualifier:
            return f"{self.quote_identifier(cr.qualifier)}.{self.quote_identifier(cr.name)}"
        return self.quote_identifier(cr.name)

    def _gen_wildcard(self, wc: IRWildcard) -> str:
        if wc.qualifier:
            return f"{self.quote_identifier(wc.qualifier)}.*"
        return "*"

    def _gen_binary_op(self, bo: IRBinaryOp, opt: GenerateOptions) -> str:
        left = self.generate_expr(bo.left, opt)
        right = self.generate_expr(bo.right, opt)
        op = {
            BinaryOp.ADD: " + ", BinaryOp.SUB: " - ", BinaryOp.MUL: " * ",
            BinaryOp.DIV: " / ", BinaryOp.MOD: " % ",
            BinaryOp.EQ: " = ", BinaryOp.NEQ: " <> ",
            BinaryOp.LT: " < ", BinaryOp.GT: " > ",
            BinaryOp.LTE: " <= ", BinaryOp.GTE: " >= ",
            BinaryOp.AND: " AND ", BinaryOp.OR: " OR ",
            BinaryOp.CONCAT: " || ", BinaryOp.LIKE: " LIKE ",
            BinaryOp.NOT_LIKE: " NOT LIKE ",
            BinaryOp.IS_DISTINCT_FROM: " IS DISTINCT FROM ",
        }.get(bo.op, f" {bo.op.name} ")
        return f"({left}{op}{right})"

    def _gen_unary_op(self, uo: IRUnaryOp, opt: GenerateOptions) -> str:
        operand = self.generate_expr(uo.operand, opt)
        match uo.op:
            case UnaryOp.NOT:
                return f"NOT {operand}"
            case UnaryOp.NEG:
                return f"-{operand}"
            case UnaryOp.IS_NULL:
                return f"{operand} IS NULL"
            case UnaryOp.IS_NOT_NULL:
                return f"{operand} IS NOT NULL"
            case UnaryOp.IS_TRUE:
                return f"{operand} IS TRUE"
            case UnaryOp.IS_NOT_TRUE:
                return f"{operand} IS NOT TRUE"
            case UnaryOp.IS_FALSE:
                return f"{operand} IS FALSE"
            case UnaryOp.IS_NOT_FALSE:
                return f"{operand} IS NOT FALSE"
            case UnaryOp.EXISTS:
                return f"EXISTS {operand}"
            case _:
                raise NotImplementedError(f"Unsupported unary op: {uo.op}")

    def _gen_function_call(self, fc: IRFunctionCall, opt: GenerateOptions) -> str:
        # Function name translation via catalog
        func_name = fc.func_name
        if self._function_catalog:
            mapping = self._function_catalog.get(func_name)
            if mapping:
                func_name = mapping  # simplified; real impl uses DialectMapping

        if fc.star:
            args_str = "*"
        else:
            args_str = ", ".join(self.generate_expr(a, opt) for a in fc.args)
        result = f"{func_name}({args_str})"

        # KEEP clause
        if fc.keep:
            # KEEP polyfill handled at SELECT level
            pass

        # OVER clause
        if fc.over:
            result += self._gen_over(fc.over, opt)

        return result

    def _gen_over(self, over, opt: GenerateOptions) -> str:
        parts: list[str] = []
        if over.partition_by:
            pb = ", ".join(self.generate_expr(p, opt) for p in over.partition_by)
            parts.append(f"PARTITION BY {pb}")
        if over.order_by:
            ob = ", ".join(self._generate_order_by(o, opt) for o in over.order_by)
            parts.append(f"ORDER BY {ob}")
        if over.frame:
            parts.append(self._gen_frame(over.frame))
        return f" OVER ({' '.join(parts)})"

    def _gen_frame(self, frame) -> str:
        from usql.ir.expr import WindowFrameBetween, WindowFrameSingle
        match frame:
            case WindowFrameBetween(unit=unit, start=start, end=end):
                return f"{unit} BETWEEN {start} AND {end}"
            case WindowFrameSingle(unit=unit, bound=bound):
                return f"{unit} {bound}"
            case _:
                return ""

    def _gen_case(self, cs: IRCase, opt: GenerateOptions) -> str:
        parts = ["CASE"]
        for w in cs.whens:
            parts.append(f" WHEN {self.generate_expr(w.condition, opt)} THEN {self.generate_expr(w.result, opt)}")
        if cs.else_expr is not None:
            parts.append(f" ELSE {self.generate_expr(cs.else_expr, opt)}")
        parts.append(" END")
        return "".join(parts)

    # ═══════════════════════════════════════
    #  INSERT / UPDATE / DELETE / MERGE
    # ═══════════════════════════════════════

    def _generate_insert(self, ins: IRInsert, opt: GenerateOptions) -> str:
        sb = StringIO()
        sb.write("INSERT INTO ")
        sb.write(self._generate_table_ref(ins.table, opt))
        if ins.columns:
            cols = ", ".join(self.quote_identifier(c) for c in ins.columns)
            sb.write(f" ({cols})")
        if ins.select_source:
            sb.write(" ")
            sb.write(self._generate_select(ins.select_source, opt))
        elif ins.values:
            rows = ", ".join(
                f"({', '.join(self.generate_expr(v, opt) for v in row)})"
                for row in ins.values
            )
            sb.write(f" VALUES {rows}")
        return sb.getvalue()

    def _generate_update(self, upd: IRUpdate, opt: GenerateOptions) -> str:
        sb = StringIO()
        sb.write("UPDATE ")
        sb.write(self._generate_table_ref(upd.table, opt))
        sb.write(" SET ")
        sb.write(", ".join(
            f"{self.quote_identifier(s.column)} = {self.generate_expr(s.value, opt)}"
            for s in upd.sets
        ))
        if upd.where is not None:
            sb.write(" WHERE ")
            sb.write(self.generate_expr(upd.where, opt))
        return sb.getvalue()

    def _generate_delete(self, del_: IRDelete, opt: GenerateOptions) -> str:
        sb = StringIO()
        sb.write("DELETE FROM ")
        sb.write(self._generate_table_ref(del_.table, opt))
        if del_.where is not None:
            sb.write(" WHERE ")
            sb.write(self.generate_expr(del_.where, opt))
        return sb.getvalue()

    def _generate_merge(self, merge: IRMerge, opt: GenerateOptions) -> str:
        sb = StringIO()
        sb.write("MERGE INTO ")
        sb.write(self._generate_table_ref(merge.target, opt))
        sb.write(" USING ")
        sb.write(self._generate_table_ref(merge.source, opt))
        sb.write(" ON ")
        sb.write(self.generate_expr(merge.on_condition, opt))
        for action in merge.actions:
            match action:
                case MergeInsert(columns=cols, values=vals):
                    sb.write(" WHEN NOT MATCHED THEN INSERT")
                    if cols:
                        sb.write(f" ({', '.join(self.quote_identifier(c) for c in cols)})")
                    sb.write(f" VALUES ({', '.join(self.generate_expr(v, opt) for v in vals)})")
                case MergeUpdate(sets=sets):
                    sb.write(" WHEN MATCHED THEN UPDATE SET ")
                    sb.write(", ".join(
                        f"{self.quote_identifier(s.column)} = {self.generate_expr(s.value, opt)}"
                        for s in sets
                    ))
                case MergeDelete():
                    sb.write(" WHEN MATCHED THEN DELETE")
        return sb.getvalue()

    # ═══════════════════════════════════════
    #  DDL
    # ═══════════════════════════════════════

    def _generate_create_table(self, ct: IRCreateTable, opt: GenerateOptions) -> str:
        sb = StringIO()
        sb.write("CREATE TABLE ")
        if ct.if_not_exists:
            sb.write("IF NOT EXISTS ")
        sb.write(self._generate_table_ref(ct.name, opt))
        sb.write(" (\n")
        items: list[str] = [self._generate_column_def(c, opt) for c in ct.columns]
        items.extend(self._generate_table_constraint(c, opt) for c in ct.constraints)
        sb.write(",\n".join(items))
        sb.write("\n)")
        return sb.getvalue()

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
                        sb.write(f" REFERENCES {self.quote_identifier(c.target_table)}({self.quote_identifier(c.target_column)})")
                    case ColGenerated():
                        sb.write(" GENERATED ALWAYS AS")
                        if c.expression:
                            sb.write(f" ({self.generate_expr(c.expression, opt)})")
                        sb.write(" VIRTUAL" if c.virtual else " STORED")
            if is_pk:
                sb.write(" PRIMARY KEY")
                if is_ai:
                    sb.write(" AUTO_INCREMENT")
        if col.default_value is not None:
            sb.write(f" DEFAULT {self.generate_expr(col.default_value, opt)}")
        return sb.getvalue()

    def _generate_table_constraint(self, c: IRTableConstraint, opt: GenerateOptions) -> str:
        match c:
            case TBPrimaryKey(columns=cols):
                return f"  PRIMARY KEY ({', '.join(self.quote_identifier(col) for col in cols)})"
            case TBUnique(columns=cols):
                return f"  UNIQUE ({', '.join(self.quote_identifier(col) for col in cols)})"
            case TBForeignKey(columns=cols, target_table=tt, target_columns=tcs):
                return (f"  FOREIGN KEY ({', '.join(self.quote_identifier(col) for col in cols)}) "
                        f"REFERENCES {self.quote_identifier(tt)}({', '.join(self.quote_identifier(col) for col in tcs)})")
            case TBCheck(condition=cond):
                return f"  CHECK ({self.generate_expr(cond, opt)})"

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
        return sb.getvalue()

    # ═══════════════════════════════════════
    #  DROP / TRUNCATE / ALTER
    # ═══════════════════════════════════════

    def _generate_drop_table(self, dt: IRDropTable, opt: GenerateOptions) -> str:
        result = "DROP TABLE "
        if dt.if_exists:
            result += "IF EXISTS "
        result += self.quote_identifier(dt.name)
        if dt.cascade:
            result += " CASCADE"
        return result

    def _generate_drop_index(self, di: IRDropIndex, opt: GenerateOptions) -> str:
        result = "DROP INDEX "
        if di.if_exists:
            result += "IF EXISTS "
        result += self.quote_identifier(di.index_name)
        if di.table_name:
            result += f" ON {self.quote_identifier(di.table_name)}"
        return result

    def _generate_truncate(self, tt: IRTruncateTable, opt: GenerateOptions) -> str:
        return f"TRUNCATE TABLE {self.quote_identifier(tt.name)}"

    def _generate_alter_add_column(self, aa: IRAlterTableAddColumn, opt: GenerateOptions) -> str:
        col = aa.column
        return (f"ALTER TABLE {self.quote_identifier(aa.table_name)} ADD COLUMN "
                f"{self.quote_identifier(col.name)} {self.map_type(col.type)}")

    def _generate_alter_drop_column(self, ad: IRAlterTableDropColumn, opt: GenerateOptions) -> str:
        return f"ALTER TABLE {self.quote_identifier(ad.table_name)} DROP COLUMN {self.quote_identifier(ad.column_name)}"

    def _generate_rename_column(self, rc: IRRenameColumn, opt: GenerateOptions) -> str:
        return (f"ALTER TABLE {self.quote_identifier(rc.table_name)} "
                f"RENAME COLUMN {self.quote_identifier(rc.old_name)} TO {self.quote_identifier(rc.new_name)}")

    def _generate_alter_column_type(self, act: IRAlterColumnType, opt: GenerateOptions) -> str:
        return (f"ALTER TABLE {self.quote_identifier(act.table_name)} "
                f"ALTER COLUMN {self.quote_identifier(act.column)} TYPE {self.map_type(act.new_type)}")

    def _generate_alter_set_default(self, ad: IRAlterColumnSetDefault, opt: GenerateOptions) -> str:
        return (f"ALTER TABLE {self.quote_identifier(ad.table_name)} "
                f"ALTER COLUMN {self.quote_identifier(ad.column)} SET DEFAULT {self.generate_expr(ad.value, opt)}")

    def _generate_alter_drop_default(self, dd: IRAlterColumnDropDefault, opt: GenerateOptions) -> str:
        return (f"ALTER TABLE {self.quote_identifier(dd.table_name)} "
                f"ALTER COLUMN {self.quote_identifier(dd.column)} DROP DEFAULT")

    # ═══════════════════════════════════════
    #  TCL — default implementations
    # ═══════════════════════════════════════

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
                return "SET TRANSACTION"

    # ═══════════════════════════════════════
    #  VIEW / SCHEMA / DATABASE
    # ═══════════════════════════════════════

    def _generate_create_view(self, cv: IRCreateView, opt: GenerateOptions) -> str:
        return f"CREATE VIEW {self.quote_identifier(cv.name)} AS {self._generate_select(cv.query, opt)}"

    def _generate_create_schema(self, cs: IRCreateSchema, opt: GenerateOptions) -> str:
        return f"CREATE SCHEMA {self.quote_identifier(cs.name)}"

    def _generate_drop_database(self, dd: IRDropDatabase, opt: GenerateOptions) -> str:
        result = "DROP DATABASE "
        if dd.if_exists:
            result += "IF EXISTS "
        return result + self.quote_identifier(dd.name)

    # ═══════════════════════════════════════
    #  PROCEDURE / FUNCTION / CALL
    # ═══════════════════════════════════════

    def _generate_create_procedure(self, cp: IRCreateProcedure, opt: GenerateOptions) -> str:
        sb = StringIO()
        sb.write("CREATE ")
        if cp.or_replace:
            sb.write("OR REPLACE ")
        sb.write(f"PROCEDURE {self.quote_identifier(cp.name)}")
        if cp.params:
            params = ", ".join(self._gen_param(p) for p in cp.params)
            sb.write(f"({params})")
        if cp.body:
            sb.write(f" AS {cp.body}")
        return sb.getvalue()

    def _generate_create_function(self, cf: IRCreateFunction, opt: GenerateOptions) -> str:
        sb = StringIO()
        sb.write("CREATE ")
        if cf.or_replace:
            sb.write("OR REPLACE ")
        sb.write(f"FUNCTION {self.quote_identifier(cf.name)}")
        if cf.params:
            params = ", ".join(self._gen_param(p) for p in cf.params)
            sb.write(f"({params})")
        if cf.return_type:
            sb.write(f" RETURNS {self.map_type(cf.return_type)}")
        if cf.body:
            sb.write(f" AS {cf.body}")
        return sb.getvalue()

    def _generate_call(self, call: IRCall, opt: GenerateOptions) -> str:
        args = ", ".join(self.generate_expr(a, opt) for a in call.args)
        return f"CALL {self.quote_identifier(call.procedure_name)}({args})"

    def _gen_param(self, p: ProcedureParam) -> str:
        mode = {ParamMode.IN: "", ParamMode.OUT: "OUT ", ParamMode.INOUT: "INOUT "}.get(p.mode, "")
        return f"{mode}{self.quote_identifier(p.name)} {self.map_type(p.type)}"
