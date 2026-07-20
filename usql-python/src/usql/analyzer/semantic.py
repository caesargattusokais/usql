"""Semantic analyzer — transforms AST nodes into Semantic IR.

This is the bridge between the parser's AST and the backend's IR.
Performs type inference, name resolution, and capability tracking.
"""
from __future__ import annotations

from usql.ast.nodes import (
    Statement, SelectStmt, InsertStmt, UpdateStmt, DeleteStmt,
    CreateTableStmt, CreateIndexStmt, DropTableStmt, DropIndexStmt,
    TruncateStmt, AlterTableStmt, TCLStmt, CallStmt,
    CreateViewStmt, CreateSchemaStmt, DropDatabaseStmt,
    MergeStmt, CreateProcedureStmt, CreateFunctionStmt,
    # Alter actions
    AddColumn, DropColumn, AlterColumnType, AlterColumnSetDefault,
    AlterColumnDropDefault, RenameColumn,
    # Constraints
    PrimaryKeyConstraint, NotNullConstraint, NullConstraint,
    UniqueConstraint, CheckConstraint, ReferencesConstraint,
    GeneratedConstraint,
    # Table constraints
    TbPrimaryKey, TbUnique, TbForeignKey, TbCheck,
    # Helpers
    ColumnDef, DataTypeDecl, IndexColumn, ParamDef,
)
from usql.catalog.function import FunctionCatalog
from usql.dialect.capability import Capability
from usql.ir.expr import (
    IRExpr, IRLiteral, IRColumnRef, IRWildcard, IRParameter,
    IRBinaryOp, IRUnaryOp, IRFunctionCall, IRCase, IRCast,
    IRSubquery, IRBetween, IRInList, IRIsNull,
    BinaryOp, UnaryOp,
)
from usql.ir.statement import (
    IRStatement, IRSelect, IRInsert, IRUpdate, IRDelete,
    SelectCore, IRExprSelect, IRWildcardSelect,
    IRTableName, IRJoin, IRSubqueryTable, IRFunctionTable,
    IRGroupBy, FetchClause, SetClause,
    JoinType, GroupByKind, SetOp,
    TclType, IRTCL,
    IRCreateTable, IRCreateIndex, IRDropTable, IRDropIndex,
    IRTruncateTable, IRAlterTableAddColumn, IRAlterTableDropColumn,
    IRRenameColumn, IRAlterColumnType, IRAlterColumnSetDefault,
    IRAlterColumnDropDefault, IRCreateView, IRCreateSchema,
    IRDropDatabase, IRCall, IRCreateProcedure, IRCreateFunction,
    IRColumnDef, IRColumnConstraint, ColNotNull, ColPrimaryKey,
    ColUnique, ColCheck, ColReferences, ColGenerated,
    IRTableConstraint, TBPrimaryKey, TBUnique, TBForeignKey, TBCheck,
    IndexColumn, TableOptions, ProcedureParam,
)
from usql.ir.types import (
    DataType, IntType, DecimalType, VarcharType, NullType, BooleanType,
)
from usql.schema import SchemaProvider, EmptySchemaProvider


class SemanticAnalyzer:
    """Transforms AST nodes into Semantic IR."""

    def __init__(self, schema: SchemaProvider | None = None,
                 function_catalog: FunctionCatalog | None = None):
        self._schema = schema or EmptySchemaProvider()
        self._function_catalog = function_catalog

    def analyze(self, ast_node: Statement) -> IRStatement:
        """Analyze an AST node and produce an IR statement."""
        match ast_node:
            case SelectStmt():
                return self._analyze_select(ast_node)
            case InsertStmt():
                return self._analyze_insert(ast_node)
            case UpdateStmt():
                return self._analyze_update(ast_node)
            case DeleteStmt():
                return self._analyze_delete(ast_node)
            case CreateTableStmt():
                return self._analyze_create_table(ast_node)
            case CreateIndexStmt():
                return self._analyze_create_index(ast_node)
            case DropTableStmt():
                return self._analyze_drop_table(ast_node)
            case DropIndexStmt():
                return self._analyze_drop_index(ast_node)
            case TruncateStmt():
                return self._analyze_truncate(ast_node)
            case AlterTableStmt():
                return self._analyze_alter_table(ast_node)
            case TCLStmt():
                return self._analyze_tcl(ast_node)
            case CreateViewStmt():
                return self._analyze_create_view(ast_node)
            case CreateSchemaStmt():
                return self._analyze_create_schema(ast_node)
            case DropDatabaseStmt():
                return self._analyze_drop_database(ast_node)
            case CallStmt():
                return self._analyze_call(ast_node)
            case MergeStmt():
                return self._analyze_merge(ast_node)
            case CreateProcedureStmt():
                return self._analyze_create_procedure(ast_node)
            case CreateFunctionStmt():
                return self._analyze_create_function(ast_node)
            case _:
                raise ValueError(f"Unsupported AST node type: {type(ast_node).__name__}")

    # ═══════════════════════════════════════
    #  SELECT
    # ═══════════════════════════════════════

    def _analyze_select(self, sel: SelectStmt) -> IRSelect:
        """Analyze a SELECT statement."""
        # Projections
        projections = []
        for item in (sel.projections or []):
            projections.append(self._analyze_select_item(item))

        # FROM
        from_clause = None
        if sel.from_:
            from_clause = tuple(self._analyze_table_ref(f) for f in sel.from_)

        # WHERE
        where = self._analyze_expr(sel.where) if sel.where else None

        # GROUP BY
        group_by = None
        if sel.group_by:
            group_by = tuple(self._analyze_group_by(g) for g in sel.group_by)

        # HAVING
        having = self._analyze_expr(sel.having) if sel.having else None

        # Collect capabilities
        caps = set()
        if sel.fetch:
            caps.add(Capability.LIMIT_OFFSET)
        if group_by:
            caps.add(Capability.AGGREGATE)
        if having:
            caps.add(Capability.HAVING)
        if sel.distinct:
            caps.add(Capability.DISTINCT)

        core = SelectCore(
            projections=tuple(projections),
            from_clause=from_clause,
            where=where,
            group_by=group_by,
            having=having,
            distinct=sel.distinct,
        )

        # ORDER BY
        order_by = None
        if sel.order_by:
            from usql.ir.expr import OrderBy
            order_by = tuple(
                OrderBy(expr=self._analyze_expr(o.expr), dir="DESC" if o.desc else "ASC")
                for o in sel.order_by
            )

        # FETCH
        fetch = None
        if sel.fetch:
            fetch = FetchClause(
                limit=self._analyze_expr(sel.fetch.limit) if sel.fetch.limit else None,
                offset=self._analyze_expr(sel.fetch.offset) if sel.fetch.offset else None,
            )

        return IRSelect(
            core=core,
            order_by=order_by,
            fetch=fetch,
            capabilities=frozenset(caps),
        )

    def _analyze_select_item(self, item) -> IRExprSelect | IRWildcardSelect:
        from usql.ast.nodes import ExprItem, StarItem
        match item:
            case ExprItem(expr=expr, alias=alias):
                return IRExprSelect(expr=self._analyze_expr(expr), alias=alias)
            case StarItem(qualifier=qualifier):
                return IRWildcardSelect(wildcard=IRWildcard(qualifier=qualifier))
            case _:
                return IRExprSelect(expr=IRLiteral(value="*", type=VarcharType(1)), alias=None)

    def _analyze_table_ref(self, ref):
        from usql.ast.nodes import SimpleTable, JoinTable, SubqueryTable, FunctionTable
        match ref:
            case SimpleTable(name=name, alias=alias):
                return IRTableName(name=name, alias=alias)
            case JoinTable(left=left, type=jtype, right=right, condition=on):
                join_type = {
                    "INNER": JoinType.INNER, "LEFT": JoinType.LEFT,
                    "RIGHT": JoinType.RIGHT, "FULL": JoinType.FULL,
                    "CROSS": JoinType.CROSS,
                }.get(jtype, JoinType.INNER)
                return IRJoin(
                    left=self._analyze_table_ref(left),
                    type=join_type,
                    right=self._analyze_table_ref(right),
                    on_condition=self._analyze_expr(on) if on else None,
                )
            case SubqueryTable(query=query, alias=alias):
                return IRSubqueryTable(query=self._analyze_select(query), alias=alias)
            case FunctionTable(lateral=lateral, name=name, args=args, alias=alias):
                return IRFunctionTable(
                    func_name=name,
                    args=tuple(self._analyze_expr(a) for a in (args or [])),
                    alias=alias,
                    lateral=lateral,
                )
            case _:
                return IRTableName(name=str(ref))

    def _analyze_group_by(self, item):
        from usql.ast.nodes import GroupByItem
        kind = {
            "ROLLUP": GroupByKind.ROLLUP,
            "CUBE": GroupByKind.CUBE,
            "GROUPING_SETS": GroupByKind.GROUPING_SETS,
        }.get(item.kind, GroupByKind.PLAIN)
        return IRGroupBy(expr=self._analyze_expr(item.expr), kind=kind)

    # ═══════════════════════════════════════
    #  DML: INSERT / UPDATE / DELETE
    # ═══════════════════════════════════════

    def _analyze_insert(self, ins: InsertStmt) -> IRInsert:
        return IRInsert(
            table=self._analyze_table_ref(ins.table),
            columns=tuple(ins.columns) if ins.columns else None,
            values=tuple(
                tuple(self._analyze_expr(v) for v in row)
                for row in (ins.values or [])
            ) or None,
            select_source=self._analyze_select(ins.select_source) if ins.select_source else None,
            ignore_errors=ins.ignore,
        )

    def _analyze_update(self, upd: UpdateStmt) -> IRUpdate:
        sets = tuple(
            SetClause(column=s.column, value=self._analyze_expr(s.value))
            for s in (upd.sets or [])
        )
        return IRUpdate(
            table=self._analyze_table_ref(upd.table),
            sets=sets,
            where=self._analyze_expr(upd.where) if upd.where else None,
        )

    def _analyze_delete(self, del_: DeleteStmt) -> IRDelete:
        return IRDelete(
            table=self._analyze_table_ref(del_.table),
            where=self._analyze_expr(del_.where) if del_.where else None,
        )

    # ═══════════════════════════════════════
    #  DDL: CREATE TABLE
    # ═══════════════════════════════════════

    def _analyze_create_table(self, ct: CreateTableStmt) -> IRCreateTable:
        columns = tuple(self._analyze_column_def(c) for c in (ct.columns or []))
        constraints = tuple(self._analyze_table_constraint(c) for c in (ct.constraints or []))
        options = self._analyze_table_options(ct.options) if ct.options else None
        return IRCreateTable(
            name=IRTableName(name=ct.table_name),
            if_not_exists=ct.if_not_exists,
            columns=columns,
            constraints=constraints,
            options=options,
        )

    def _analyze_column_def(self, col: ColumnDef) -> IRColumnDef:
        dtype = self._resolve_type(col.type_name, col.type_precision, col.type_scale)
        constraints = tuple(self._analyze_column_constraint(c) for c in (col.constraints or []))
        default = self._analyze_expr(col.default_value) if col.default_value else None
        return IRColumnDef(
            name=col.name,
            type=dtype,
            constraints=constraints,
            default_value=default,
        )

    def _analyze_column_constraint(self, c) -> IRColumnConstraint:
        match c:
            case PrimaryKeyConstraint(auto_increment=ai):
                return ColPrimaryKey(auto_increment=ai or False)
            case NotNullConstraint():
                return ColNotNull()
            case NullConstraint():
                return ColNotNull()  # NULL is the default, but we track it
            case UniqueConstraint():
                return ColUnique()
            case CheckConstraint(condition=cond):
                return ColCheck(condition=self._analyze_expr(cond))
            case ReferencesConstraint(target_table=tt, target_column=tc, on_update=ou, on_delete=od):
                return ColReferences(target_table=tt, target_column=tc, on_update=ou, on_delete=od)
            case GeneratedConstraint(strategy=strat, virtual=virt, expression=expr):
                return ColGenerated(strategy=strat or "ALWAYS", virtual=virt or False,
                                    expression=self._analyze_expr(expr) if expr else None)
            case _:
                return ColNotNull()  # fallback

    def _analyze_table_constraint(self, c) -> IRTableConstraint:
        match c:
            case TbPrimaryKey(columns=cols, constraint_name=name):
                return TBPrimaryKey(columns=tuple(cols), constraint_name=name)
            case TbUnique(columns=cols, constraint_name=name):
                return TBUnique(columns=tuple(cols), constraint_name=name)
            case TbForeignKey(columns=cols, target_table=tt, target_columns=tcs,
                              constraint_name=name, on_update=ou, on_delete=od, deferrable=d):
                return TBForeignKey(columns=tuple(cols), target_table=tt,
                                    target_columns=tuple(tcs) if tcs else None,
                                    constraint_name=name, on_update=ou, on_delete=od, deferrable=d)
            case TbCheck(condition=cond, constraint_name=name):
                return TBCheck(condition=self._analyze_expr(cond), constraint_name=name)
            case _:
                return TBPrimaryKey(columns=(), constraint_name=None)

    def _analyze_table_options(self, opts) -> TableOptions:
        return TableOptions(
            engine=opts.engine,
            tablespace=opts.tablespace,
            character_set=opts.character_set,
            collation=opts.collation,
            comment=opts.comment,
        )

    # ═══════════════════════════════════════
    #  DDL: CREATE INDEX
    # ═══════════════════════════════════════

    def _analyze_create_index(self, ci: CreateIndexStmt) -> IRCreateIndex:
        columns = tuple(
            IndexColumn(name=c.name, dir="DESC" if c.desc else "ASC",
                        nulls="FIRST" if c.nulls_first else "LAST")
            for c in (ci.columns or [])
        )
        where = self._analyze_expr(ci.where) if ci.where else None
        return IRCreateIndex(
            name=ci.name,
            table=IRTableName(name=ci.table_name),
            columns=columns,
            unique=ci.unique,
            if_not_exists=ci.if_not_exists,
            where_clause=where,
        )

    # ═══════════════════════════════════════
    #  DDL: DROP TABLE / DROP INDEX / DROP DATABASE
    # ═══════════════════════════════════════

    def _analyze_drop_table(self, dt: DropTableStmt) -> IRDropTable:
        return IRDropTable(
            name=dt.table_name,
            if_exists=dt.if_exists,
            cascade=dt.cascade,
        )

    def _analyze_drop_index(self, di: DropIndexStmt) -> IRDropIndex:
        return IRDropIndex(
            index_name=di.index_name,
            table_name=di.table_name,
            if_exists=di.if_exists,
        )

    def _analyze_drop_database(self, dd: DropDatabaseStmt) -> IRDropDatabase:
        return IRDropDatabase(
            name=dd.database_name,
            if_exists=dd.if_exists,
        )

    # ═══════════════════════════════════════
    #  DDL: TRUNCATE
    # ═══════════════════════════════════════

    def _analyze_truncate(self, tr: TruncateStmt) -> IRTruncateTable:
        return IRTruncateTable(name=tr.table_name)

    # ═══════════════════════════════════════
    #  DDL: ALTER TABLE
    # ═══════════════════════════════════════

    def _analyze_alter_table(self, at: AlterTableStmt) -> IRStatement:
        action = at.action
        match action:
            case AddColumn(name=col_name, type=type_decl, constraints=cons,
                           default_val=default, if_not_exists=ine):
                dtype = self._resolve_type(type_decl.name, type_decl.precision, type_decl.scale)
                ir_cons = tuple(self._analyze_column_constraint(c) for c in (cons or []))
                ir_default = self._analyze_expr(default) if default else None
                col_def = IRColumnDef(name=col_name, type=dtype, constraints=ir_cons,
                                      default_value=ir_default)
                return IRAlterTableAddColumn(
                    table_name=at.table_name,
                    column=col_def,
                    if_not_exists=ine or False,
                )
            case DropColumn(name=col_name):
                return IRAlterTableDropColumn(
                    table_name=at.table_name,
                    column_name=col_name,
                )
            case AlterColumnType(column=col, new_type=type_decl):
                dtype = self._resolve_type(type_decl.name, type_decl.precision, type_decl.scale)
                return IRAlterColumnType(
                    table_name=at.table_name,
                    column=col,
                    new_type=dtype,
                )
            case AlterColumnSetDefault(column=col, value=val):
                return IRAlterColumnSetDefault(
                    table_name=at.table_name,
                    column=col,
                    value=self._analyze_expr(val),
                )
            case AlterColumnDropDefault(column=col):
                return IRAlterColumnDropDefault(
                    table_name=at.table_name,
                    column=col,
                )
            case RenameColumn(old_name=old, new_name=new):
                return IRRenameColumn(
                    table_name=at.table_name,
                    old_name=old,
                    new_name=new,
                )
            case _:
                raise ValueError(f"Unsupported ALTER action: {type(action).__name__}")

    # ═══════════════════════════════════════
    #  DDL: CREATE VIEW / CREATE SCHEMA
    # ═══════════════════════════════════════

    def _analyze_create_view(self, cv: CreateViewStmt) -> IRCreateView:
        return IRCreateView(
            name=cv.view_name,
            query=self._analyze_select(cv.query) if cv.query else None,
        )

    def _analyze_create_schema(self, cs: CreateSchemaStmt) -> IRCreateSchema:
        return IRCreateSchema(name=cs.schema_name)

    # ═══════════════════════════════════════
    #  TCL
    # ═══════════════════════════════════════

    def _analyze_tcl(self, tcl: TCLStmt) -> IRTCL:
        sql = (tcl.sql or "").upper().strip()
        if sql.startswith("BEGIN") or sql.startswith("START"):
            tcl_type = TclType.BEGIN
            savepoint = None
        elif sql.startswith("COMMIT"):
            tcl_type = TclType.COMMIT
            savepoint = None
        elif sql.startswith("ROLLBACK"):
            tcl_type = TclType.ROLLBACK
            savepoint = None
        elif sql.startswith("SAVEPOINT"):
            tcl_type = TclType.SAVEPOINT
            parts = sql.split(None, 1)
            savepoint = parts[1].strip() if len(parts) > 1 else None
        elif sql.startswith("RELEASE"):
            tcl_type = TclType.RELEASE_SAVEPOINT
            parts = sql.split(None, 1)
            savepoint = parts[1].strip() if len(parts) > 1 else None
        else:
            tcl_type = TclType.BEGIN
            savepoint = None
        return IRTCL(type=tcl_type, savepoint_name=savepoint)

    # ═══════════════════════════════════════
    #  CALL / MERGE / PROCEDURE / FUNCTION
    # ═══════════════════════════════════════

    def _analyze_call(self, call: CallStmt) -> IRCall:
        return IRCall(
            procedure_name=call.procedure_name,
            args=tuple(self._analyze_expr(a) for a in (call.args or [])),
        )

    def _analyze_merge(self, merge: MergeStmt) -> IRStatement:
        from usql.ir.statement import IRMerge, MergeInsert, MergeUpdate, MergeDelete, IRMergeAction
        actions = []
        for a in (merge.actions or []):
            match a:
                case MergeInsert(columns=cols, values=vals):
                    actions.append(MergeInsert(
                        columns=tuple(cols) if cols else None,
                        values=tuple(tuple(self._analyze_expr(v) for v in row) for row in (vals or [])),
                    ))
                case MergeUpdate(sets=sets):
                    actions.append(MergeUpdate(
                        sets=tuple(SetClause(column=s.column, value=self._analyze_expr(s.value)) for s in (sets or [])),
                    ))
                case MergeDelete():
                    actions.append(MergeDelete())
                case _:
                    pass
        return IRMerge(
            target=IRTableName(name=merge.target, alias=merge.target_alias),
            source=self._analyze_table_ref(merge.source) if merge.source else None,
            on_condition=self._analyze_expr(merge.on_condition) if merge.on_condition else None,
            actions=tuple(actions),
        )

    def _analyze_create_procedure(self, cp: CreateProcedureStmt) -> IRCreateProcedure:
        params = tuple(
            ProcedureParam(name=p.name, type=self._resolve_type(p.type_name, 0, 0), mode=p.mode or "IN")
            for p in (cp.params or [])
        )
        return IRCreateProcedure(
            name=cp.procedure_name,
            params=params,
            body=cp.body or "",
        )

    def _analyze_create_function(self, cf: CreateFunctionStmt) -> IRCreateFunction:
        params = tuple(
            ProcedureParam(name=p.name, type=self._resolve_type(p.type_name, 0, 0), mode=p.mode or "IN")
            for p in (cf.params or [])
        )
        ret_type = self._resolve_type(cf.return_type, 0, 0) if cf.return_type else None
        return IRCreateFunction(
            name=cf.function_name,
            params=params,
            return_type=ret_type,
            body=cf.body or "",
        )

    # ═══════════════════════════════════════
    #  Expression analysis
    # ═══════════════════════════════════════

    def _analyze_expr(self, expr) -> IRExpr:
        """Recursively analyze an AST expression into an IRExpr."""
        if expr is None:
            return IRLiteral(value=None, type=NullType())

        from usql.ast.nodes import (
            ColumnRef, BinaryOp as AstBinOp, UnaryOp as AstUnOp,
            FunctionCall, IntLiteral, FloatLiteral, StringLiteral,
            BoolLiteral, NullLiteral, StarExpr, ParamRef,
            CaseExpr, CastExpr, SubqueryExpr,
            BetweenExpr, InListExpr, IsNullExpr,
            BinOp, UnOp,
        )

        match expr:
            case IntLiteral(value=v):
                return IRLiteral(value=v, type=IntType(64))
            case FloatLiteral(value=v):
                from usql.ir.types import FloatType
                return IRLiteral(value=v, type=FloatType(64))
            case StringLiteral(value=v):
                return IRLiteral(value=v, type=VarcharType(len(v) if v else 1))
            case BoolLiteral(value=v):
                return IRLiteral(value=v, type=BooleanType())
            case NullLiteral():
                return IRLiteral(value=None, type=NullType())
            case StarExpr(qualifier=q):
                return IRWildcard(qualifier=q)
            case ColumnRef(qualifier=qs, name=n):
                q = qs[0] if qs else None
                return IRColumnRef(name=n, qualifier=q)
            case ParamRef(name=n):
                return IRParameter(name=n)
            case AstBinOp(left=l, op=op, right=r):
                bin_op = {
                    BinOp.ADD: BinaryOp.ADD, BinOp.SUB: BinaryOp.SUB,
                    BinOp.MUL: BinaryOp.MUL, BinOp.DIV: BinaryOp.DIV,
                    BinOp.MOD: BinaryOp.MOD,
                    BinOp.EQ: BinaryOp.EQ, BinOp.NEQ: BinaryOp.NEQ,
                    BinOp.LT: BinaryOp.LT, BinOp.GT: BinaryOp.GT,
                    BinOp.LTE: BinaryOp.LTE, BinOp.GTE: BinaryOp.GTE,
                    BinOp.AND: BinaryOp.AND, BinOp.OR: BinaryOp.OR,
                    BinOp.CONCAT: BinaryOp.CONCAT,
                    BinOp.LIKE: BinaryOp.LIKE, BinOp.NOT_LIKE: BinaryOp.NOT_LIKE,
                }.get(op, BinaryOp.EQ)
                return IRBinaryOp(
                    left=self._analyze_expr(l),
                    op=bin_op,
                    right=self._analyze_expr(r),
                )
            case AstUnOp(op=op, operand=operand):
                un_op = {
                    UnOp.NEG: UnaryOp.NEG, UnOp.NOT: UnaryOp.NOT,
                    UnOp.IS_NULL: UnaryOp.IS_NULL, UnOp.IS_NOT_NULL: UnaryOp.IS_NOT_NULL,
                    UnOp.IS_TRUE: UnaryOp.IS_TRUE, UnOp.IS_NOT_TRUE: UnaryOp.IS_NOT_TRUE,
                    UnOp.IS_FALSE: UnaryOp.IS_FALSE, UnOp.IS_NOT_FALSE: UnaryOp.IS_NOT_FALSE,
                    UnOp.EXISTS: UnaryOp.EXISTS,
                }.get(op, UnaryOp.NOT)
                return IRUnaryOp(op=un_op, operand=self._analyze_expr(operand))
            case FunctionCall(name=n, args=args, star=star, keep=keep, over=over):
                analyzed_args = tuple(self._analyze_expr(a) for a in (args or []))
                ir_over = None
                if over:
                    from usql.ir.expr import IRWindowOver, OrderBy
                    pb = tuple(self._analyze_expr(p) for p in (over.partition_by or []))
                    ob = tuple(
                        OrderBy(expr=self._analyze_expr(o.expr), dir="DESC" if o.desc else "ASC")
                        for o in (over.order_by or [])
                    )
                    ir_over = IRWindowOver(partition_by=pb, order_by=ob)
                return IRFunctionCall(func_name=n, args=analyzed_args, over=ir_over, star=star or False)
            case CaseExpr(operand=_, whens=whens, else_expr=else_expr):
                from usql.ir.expr import WhenClause
                analyzed_whens = tuple(
                    WhenClause(condition=self._analyze_expr(w.condition), result=self._analyze_expr(w.result))
                    for w in (whens or [])
                )
                return IRCase(whens=analyzed_whens, else_expr=self._analyze_expr(else_expr) if else_expr else None)
            case CastExpr(expr=e, type_name=tn, precision=p, scale=s):
                target_type = self._resolve_type(tn, p, s)
                return IRCast(expr=self._analyze_expr(e), target_type=target_type)
            case SubqueryExpr(query=q):
                return IRSubquery(query=self._analyze_select(q))
            case BetweenExpr(expr=e, low=lo, high=hi, not_=not_):
                return IRBetween(
                    expr=self._analyze_expr(e),
                    low=self._analyze_expr(lo),
                    high=self._analyze_expr(hi),
                    not_=not_,
                )
            case InListExpr(expr=e, values=vs, subquery=sq, not_=not_):
                analyzed_values = tuple(self._analyze_expr(v) for v in (vs or []))
                analyzed_sq = self._analyze_select(sq) if sq else None
                return IRInList(expr=self._analyze_expr(e), values=analyzed_values, subquery=analyzed_sq, not_=not_)
            case IsNullExpr(expr=e, not_=not_):
                return IRIsNull(expr=self._analyze_expr(e), not_=not_)
            case _:
                # Fallback: treat as literal
                return IRLiteral(value=str(expr), type=VarcharType(255))

    def _resolve_type(self, type_name: str, precision: int = 0, scale: int = 0) -> DataType:
        """Resolve a type name string to a DataType."""
        from usql.ir.types import (
            CharType, VarbinaryType, BinaryType as BinType,
            TimestampType, TimeType, DatetimeType, DateType,
            BlobType, JsonType, UuidType, EnumType,
        )
        match type_name.upper():
            case "TINYINT":
                return IntType(8)
            case "SMALLINT":
                return IntType(16)
            case "INT" | "INTEGER":
                return IntType(32)
            case "BIGINT":
                return IntType(64)
            case "DECIMAL" | "NUMERIC":
                return DecimalType(precision or 10, scale)
            case "FLOAT" | "REAL":
                from usql.ir.types import FloatType
                return FloatType(32)
            case "DOUBLE" | "DOUBLE PRECISION":
                from usql.ir.types import FloatType
                return FloatType(64)
            case "CHAR":
                return CharType(precision or 1)
            case "VARCHAR":
                return VarcharType(precision or 255)
            case "TEXT" | "TINYTEXT" | "MEDIUMTEXT" | "LONGTEXT" | "CLOB":
                return VarcharType(255)  # simplified
            case "BOOLEAN":
                return BooleanType()
            case "DATE":
                return DateType()
            case "TIME":
                return TimeType(precision)
            case "DATETIME":
                return DatetimeType(precision)
            case "TIMESTAMP":
                return TimestampType(precision)
            case "JSON":
                return JsonType()
            case "UUID":
                return UuidType()
            case "BINARY":
                return BinType(precision or 1)
            case "VARBINARY":
                return VarbinaryType(precision or 255)
            case "BLOB":
                return BlobType()
            case "ENUM":
                return EnumType(values=())
            case _:
                return VarcharType(255)
