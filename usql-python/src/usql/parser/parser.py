"""Hand-written recursive descent + Pratt expression parser — Python port of Java HandParser."""

from __future__ import annotations

from usql.parser.lexer import Lexer, Token, TokenType
from usql.ast.nodes import *


class ParseError(RuntimeError):
    """Raised on syntax errors during parsing."""


class Parser:
    """Recursive descent + Pratt expression parser for U-SQL.

    Usage::

        tokens = Lexer(sql).tokenize()
        stmts = Parser(tokens).parse_program()
    """

    def __init__(self, tokens: list[Token]) -> None:
        self._tokens = tokens
        self._pos = 0

    # ══════════════════════════════════════════════════
    #  Program
    # ══════════════════════════════════════════════════

    def parse_program(self) -> list[Statement]:
        stmts: list[Statement] = []
        while not self._is(TokenType.EOF):
            stmts.append(self._parse_statement())
            if self._is(TokenType.SEMI):
                self._advance()
        return stmts

    def _parse_statement(self) -> Statement:
        t = self._peek()
        match t.type:
            case TokenType.SELECT | TokenType.WITH:
                return self._parse_select()
            case TokenType.INSERT:
                return self._parse_insert()
            case TokenType.UPDATE:
                return self._parse_update()
            case TokenType.DELETE:
                return self._parse_delete()
            case TokenType.MERGE:
                return self._parse_merge()
            case TokenType.CREATE:
                return self._parse_create()
            case TokenType.DROP:
                return self._parse_drop()
            case TokenType.TRUNCATE:
                return self._parse_truncate()
            case TokenType.ALTER:
                return self._parse_alter()
            case TokenType.BEGIN | TokenType.START:
                return self._parse_tcl()
            case TokenType.COMMIT | TokenType.ROLLBACK | TokenType.SAVEPOINT | TokenType.RELEASE:
                return self._parse_tcl()
            case TokenType.CALL:
                return self._parse_call_stmt()
            case _:
                raise ParseError(self._error(f"Unexpected: {t.type.name}"))

    # ══════════════════════════════════════════════════
    #  SELECT
    # ══════════════════════════════════════════════════

    def _parse_select(self) -> SelectStmt:
        # WITH clause
        with_clause: list[CommonTable] | None = None
        if self._is(TokenType.WITH):
            self._advance()
            recursive = self._is(TokenType.RECURSIVE)
            if recursive:
                self._advance()
            with_clause = []
            while True:
                name = self._expect_identifier()
                cols: list[str] | None = None
                if self._is(TokenType.LPAREN):
                    self._advance()
                    cols = self._id_list()
                    self._expect(TokenType.RPAREN)
                self._expect(TokenType.AS)
                self._expect(TokenType.LPAREN)
                with_clause.append(CommonTable(name, cols, self._parse_select(), recursive))
                self._expect(TokenType.RPAREN)
                if not self._is(TokenType.COMMA):
                    break
                self._advance()

        # SELECT
        self._expect(TokenType.SELECT)

        # DISTINCT / ALL
        distinct = False
        if self._is(TokenType.DISTINCT):
            self._advance()
            distinct = True
        elif self._is(TokenType.ALL):
            self._advance()

        # Projections
        proj: list[SelectItem] = []
        while True:
            if self._is(TokenType.STAR):
                self._advance()
                proj.append(StarItem(None))
            elif (
                self._is(TokenType.IDENTIFIER)
                and self._peek2() is not None
                and self._peek2().type is TokenType.DOT
                and self._peek3() is not None
                and self._peek3().type is TokenType.STAR
            ):
                q = self._advance().text
                self._advance()  # DOT
                self._advance()  # STAR
                proj.append(StarItem(q))
            else:
                e = self._parse_expr()
                alias: str | None = None
                if not self._is(
                    TokenType.COMMA, TokenType.FROM, TokenType.WHERE,
                    TokenType.GROUP, TokenType.HAVING, TokenType.ORDER,
                    TokenType.LIMIT, TokenType.OFFSET, TokenType.FETCH,
                    TokenType.UNION, TokenType.INTERSECT, TokenType.EXCEPT,
                    TokenType.RPAREN, TokenType.EOF,
                ):
                    if self._is(TokenType.AS):
                        self._advance()
                    if self._is(TokenType.IDENTIFIER, TokenType.STRING_LITERAL):
                        alias = self._advance().text
                proj.append(ExprItem(e, alias))
            if not self._is(TokenType.COMMA):
                break
            self._advance()

        # FROM
        from_: list[TableRef] | None = None
        if self._is(TokenType.FROM):
            self._advance()
            from_ = []
            while True:
                from_.append(self._parse_table_ref())
                if not self._is(TokenType.COMMA):
                    break
                self._advance()

        # WHERE
        where: Expression | None = None
        if self._is(TokenType.WHERE):
            self._advance()
            where = self._parse_expr()

        # GROUP BY
        group_by: list[GroupByItem] | None = None
        if self._is(TokenType.GROUP):
            self._advance()
            self._expect(TokenType.BY)
            group_by = []
            while True:
                group_by.append(self._parse_group_by_item())
                if not self._is(TokenType.COMMA):
                    break
                self._advance()

        # HAVING
        having: Expression | None = None
        if self._is(TokenType.HAVING):
            self._advance()
            having = self._parse_expr()

        # ORDER BY
        order_by: list[OrderByItem] | None = None
        if self._is(TokenType.ORDER):
            self._advance()
            self._expect(TokenType.BY)
            order_by = []
            while True:
                e = self._parse_expr()
                desc = False
                if self._is(TokenType.DESC):
                    self._advance()
                    desc = True
                elif self._is(TokenType.ASC):
                    self._advance()
                nulls_first = False
                if self._is(TokenType.NULLS):
                    self._advance()
                    nulls_first = self._is(TokenType.FIRST)
                    self._advance()
                order_by.append(OrderByItem(e, desc, nulls_first))
                if not self._is(TokenType.COMMA):
                    break
                self._advance()

        # FETCH / LIMIT / OFFSET
        fetch: FetchClause | None = None
        if self._is(TokenType.LIMIT):
            self._advance()
            limit = self._parse_expr()
            offset: Expression | None = None
            if self._is(TokenType.OFFSET):
                self._advance()
                offset = self._parse_expr()
            fetch = FetchClause(limit, offset)
        elif self._is(TokenType.OFFSET) or self._is(TokenType.FETCH):
            if self._is(TokenType.OFFSET):
                self._advance()
                offset = self._parse_expr()
            else:
                offset = IntLiteral(0)
            if self._is(TokenType.ROWS, TokenType.ROW):
                self._advance()
            if self._is(TokenType.FETCH):
                self._advance()
            if self._is(TokenType.NEXT):
                self._advance()
            limit = self._parse_expr()
            if self._is(TokenType.ROWS, TokenType.ROW):
                self._advance()
            if self._is(TokenType.ONLY):
                self._advance()
            fetch = FetchClause(limit, offset)

        # Set operations (UNION / INTERSECT / EXCEPT)
        set_op: SetOp | None = None
        set_operand: SelectStmt | None = None
        if self._is(TokenType.UNION, TokenType.INTERSECT, TokenType.EXCEPT):
            op = self._advance()
            all_ = self._is(TokenType.ALL)
            if all_:
                self._advance()
            match op.type:
                case TokenType.UNION:
                    set_op = SetOp.UNION_ALL if all_ else SetOp.UNION
                case TokenType.INTERSECT:
                    set_op = SetOp.INTERSECT
                case TokenType.EXCEPT:
                    set_op = SetOp.EXCEPT
                case _:
                    pass
            set_operand = self._parse_select()

        return SelectStmt(
            with_clause, distinct, proj, from_, where,
            group_by, having, order_by, fetch, set_op, set_operand,
        )

    # ══════════════════════════════════════════════════
    #  FROM / table references
    # ══════════════════════════════════════════════════

    def _parse_table_ref(self) -> TableRef:
        left = self._parse_table_primary()
        while True:
            jt: JoinType | None = None
            if self._is(TokenType.INNER):
                self._advance()
                if self._is(TokenType.JOIN):
                    self._advance()
                jt = JoinType.INNER
            elif self._is(TokenType.LEFT):
                self._advance()
                if self._is(TokenType.OUTER):
                    self._advance()
                if self._is(TokenType.JOIN):
                    self._advance()
                jt = JoinType.LEFT
            elif self._is(TokenType.RIGHT):
                self._advance()
                if self._is(TokenType.OUTER):
                    self._advance()
                if self._is(TokenType.JOIN):
                    self._advance()
                jt = JoinType.RIGHT
            elif self._is(TokenType.FULL):
                self._advance()
                if self._is(TokenType.OUTER):
                    self._advance()
                if self._is(TokenType.JOIN):
                    self._advance()
                jt = JoinType.FULL
            elif self._is(TokenType.CROSS):
                self._advance()
                if self._is(TokenType.JOIN):
                    self._advance()
                jt = JoinType.CROSS
            elif self._is(TokenType.JOIN):
                self._advance()
                jt = JoinType.INNER
            else:
                break
            right = self._parse_table_primary()
            on: Expression | None = None
            if self._is(TokenType.ON):
                self._advance()
                on = self._parse_expr()
            left = JoinTable(left, jt, right, on)
        return left

    def _parse_table_primary(self) -> TableRef:
        lateral = self._is(TokenType.LATERAL)
        if lateral:
            self._advance()
        if self._is(TokenType.LPAREN):
            self._advance()
            sq = self._parse_select()
            self._expect(TokenType.RPAREN)
            alias: str | None = None
            if self._is(TokenType.AS):
                self._advance()
            if self._is(TokenType.IDENTIFIER):
                alias = self._advance().text
            return SubqueryTable(sq, alias)
        name = self._expect_identifier()
        if self._is(TokenType.LPAREN):
            self._advance()
            args: list[Expression] = []
            if not self._is(TokenType.RPAREN):
                while True:
                    args.append(self._parse_expr())
                    if not self._is(TokenType.COMMA):
                        break
                    self._advance()
            self._expect(TokenType.RPAREN)
            alias = None
            if self._is(TokenType.AS):
                self._advance()
            if self._is(TokenType.IDENTIFIER):
                alias = self._advance().text
            return FunctionTable(lateral, name, args, alias)
        alias = None
        if self._is(TokenType.AS):
            self._advance()
        if self._is(TokenType.IDENTIFIER):
            alias = self._advance().text
        return SimpleTable(name, alias)

    # ══════════════════════════════════════════════════
    #  GROUP BY
    # ══════════════════════════════════════════════════

    def _parse_group_by_item(self) -> GroupByItem:
        t = self._peek().type
        if t in (TokenType.ROLLUP, TokenType.CUBE, TokenType.GROUPING):
            self._advance()
            if t == TokenType.GROUPING:
                self._advance()  # consume SETS
            self._expect(TokenType.LPAREN)
            args: list[Expression] = []
            while True:
                args.append(self._parse_expr())
                if not self._is(TokenType.COMMA):
                    break
                self._advance()
            self._expect(TokenType.RPAREN)
            kind = (
                GroupByKind.ROLLUP if t == TokenType.ROLLUP
                else GroupByKind.CUBE if t == TokenType.CUBE
                else GroupByKind.GROUPING_SETS
            )
            return GroupByItem(FunctionCall(kind.name, args, False, None, None), kind)
        return GroupByItem(self._parse_expr(), GroupByKind.PLAIN)

    # ══════════════════════════════════════════════════
    #  DML
    # ══════════════════════════════════════════════════

    def _parse_insert(self) -> InsertStmt:
        self._advance()  # INSERT
        ignore = self._is(TokenType.IGNORE)
        if ignore:
            self._advance()
        self._expect(TokenType.INTO)
        tn = self._expect_identifier()
        ta: str | None = None
        if self._is(TokenType.AS):
            self._advance()
        if self._is(TokenType.IDENTIFIER):
            ta = self._advance().text
        tab = SimpleTable(tn, ta)
        cols: list[str] | None = None
        if self._is(TokenType.LPAREN):
            self._advance()
            cols = self._id_list()
            self._expect(TokenType.RPAREN)
        if self._is(TokenType.VALUES):
            self._advance()
            vals: list[list[Expression]] = []
            while True:
                self._expect(TokenType.LPAREN)
                row: list[Expression] = []
                while True:
                    row.append(self._parse_expr())
                    if not self._is(TokenType.COMMA):
                        break
                    self._advance()
                self._expect(TokenType.RPAREN)
                vals.append(row)
                if not self._is(TokenType.COMMA):
                    break
                self._advance()
            return InsertStmt(ignore, tab, cols, vals, None)
        else:
            return InsertStmt(ignore, tab, cols, None, self._parse_select())

    def _parse_update(self) -> UpdateStmt:
        self._advance()  # UPDATE
        tn = self._expect_identifier()
        ta: str | None = None
        if self._is(TokenType.AS):
            self._advance()
        if self._is(TokenType.IDENTIFIER):
            ta = self._advance().text
        tab = SimpleTable(tn, ta)
        self._expect(TokenType.SET)
        sets: list[SetClause] = []
        while True:
            c = self._parse_set_column()
            self._expect(TokenType.EQ)
            sets.append(SetClause(c, self._parse_expr()))
            if not self._is(TokenType.COMMA):
                break
            self._advance()
        w: Expression | None = None
        if self._is(TokenType.WHERE):
            self._advance()
            w = self._parse_expr()
        return UpdateStmt(tab, sets, w)

    def _parse_delete(self) -> DeleteStmt:
        self._advance()  # DELETE
        self._expect(TokenType.FROM)
        tn = self._expect_identifier()
        ta: str | None = None
        if self._is(TokenType.AS):
            self._advance()
        if self._is(TokenType.IDENTIFIER):
            ta = self._advance().text
        tab = SimpleTable(tn, ta)
        w: Expression | None = None
        if self._is(TokenType.WHERE):
            self._advance()
            w = self._parse_expr()
        return DeleteStmt(tab, w)

    def _parse_merge(self) -> MergeStmt:
        self._advance()  # MERGE
        self._expect(TokenType.INTO)
        tgt = self._parse_table_primary()
        t_alias: str | None = None
        if self._is(TokenType.AS):
            self._advance()
        if self._is(TokenType.IDENTIFIER):
            t_alias = self._advance().text
        self._expect(TokenType.USING)
        src = self._parse_table_primary()
        self._expect(TokenType.ON)
        on = self._parse_expr()
        acts: list[MergeAction] = []
        while self._is(TokenType.WHEN):
            self._advance()
            matched = self._is(TokenType.MATCHED)
            self._advance()
            if not matched:
                self._advance()  # NOT
            self._expect(TokenType.THEN)
            if self._is(TokenType.UPDATE):
                self._advance()
                self._expect(TokenType.SET)
                s: list[SetClause] = []
                while True:
                    c = self._parse_set_column()
                    self._expect(TokenType.EQ)
                    s.append(SetClause(c, self._parse_expr()))
                    if not self._is(TokenType.COMMA):
                        break
                    self._advance()
                acts.append(MergeUpdate(s))
            elif self._is(TokenType.INSERT):
                self._advance()
                cs: list[str] | None = None
                if self._is(TokenType.LPAREN):
                    self._advance()
                    cs = self._id_list()
                    self._expect(TokenType.RPAREN)
                self._expect(TokenType.VALUES)
                self._expect(TokenType.LPAREN)
                vs: list[Expression] = []
                while True:
                    vs.append(self._parse_expr())
                    if not self._is(TokenType.COMMA):
                        break
                    self._advance()
                self._expect(TokenType.RPAREN)
                acts.append(MergeInsert(cs, vs))
            else:
                self._advance()  # DELETE keyword
                acts.append(MergeDelete())
        return MergeStmt(tgt, t_alias, src, on, acts)

    # ══════════════════════════════════════════════════
    #  CREATE
    # ══════════════════════════════════════════════════

    def _parse_create(self) -> Statement:
        self._advance()  # CREATE
        or_replace = False
        if self._is(TokenType.OR):
            self._advance()
            self._advance()  # REPLACE
            or_replace = True
        if self._is(TokenType.TABLE):
            return self._parse_create_table()
        if self._is(TokenType.INDEX, TokenType.UNIQUE):
            return self._parse_create_index()
        if self._is(TokenType.VIEW):
            self._advance()
            return CreateViewStmt(self._expect_identifier(), self._parse_select_after_as())
        if self._is(TokenType.SCHEMA):
            self._advance()
            return CreateSchemaStmt(self._expect_identifier())
        if self._is(TokenType.PROCEDURE):
            self._advance()
            n = self._expect_identifier()
            p = self._parse_params()
            b: str | None = None
            if self._is(TokenType.AS):
                self._advance()
                if self._is(TokenType.STRING_LITERAL):
                    b = self._advance().text
                else:
                    self._skip_to_semi()
            return CreateProcedureStmt(n, p, or_replace, b)
        if self._is(TokenType.FUNCTION):
            self._advance()
            n = self._expect_identifier()
            p = self._parse_params()
            self._expect(TokenType.RETURNS)
            rt = self._parse_data_type_decl()
            b = None
            if self._is(TokenType.AS):
                self._advance()
                if self._is(TokenType.STRING_LITERAL):
                    b = self._advance().text
                else:
                    self._skip_to_semi()
            return CreateFunctionStmt(n, p, rt, or_replace, b)
        raise ParseError(self._error("Unknown CREATE"))

    def _parse_select_after_as(self) -> SelectStmt:
        self._expect(TokenType.AS)
        return self._parse_select()

    def _parse_create_table(self) -> CreateTableStmt:
        self._advance()  # TABLE
        ifn = False
        if self._is(TokenType.IF):
            self._advance()
            self._advance()  # NOT
            self._advance()  # EXISTS
            ifn = True
        tn = self._expect_identifier()
        self._expect(TokenType.LPAREN)
        cols: list[ColumnDef] = []
        cons: list[TableConstraint] = []
        if not self._is(TokenType.RPAREN):
            while True:
                if self._is(
                    TokenType.PRIMARY, TokenType.FOREIGN,
                    TokenType.UNIQUE, TokenType.CHECK, TokenType.CONSTRAINT,
                ):
                    cons.append(self._parse_table_constraint())
                else:
                    cols.append(self._parse_column_def())
                if not self._is(TokenType.COMMA):
                    break
                self._advance()
        self._expect(TokenType.RPAREN)
        return CreateTableStmt(ifn, tn, cols, cons, self._parse_table_options())

    def _parse_column_def(self) -> ColumnDef:
        n = self._expect_identifier()
        dt = self._advance()
        tn = dt.text
        prec = 0
        scale = 0
        ev: list[str] | None = None
        if self._is(TokenType.LPAREN):
            self._advance()
            if tn.upper() == "ENUM":
                ev = []
                while True:
                    ev.append(self._expect(TokenType.STRING_LITERAL).text)
                    if not self._is(TokenType.COMMA):
                        break
                    self._advance()
            else:
                if self._is(TokenType.INT_LITERAL):
                    prec = int(self._advance().text)
                if self._is(TokenType.COMMA):
                    self._advance()
                    scale = int(self._expect(TokenType.INT_LITERAL).text)
            self._expect(TokenType.RPAREN)
        cc: list[ColumnConstraint] = []
        while self._is_constraint():
            cc.append(self._parse_column_constraint())
        default: Expression | None = None
        if self._is(TokenType.DEFAULT):
            self._advance()
            default = self._parse_expr()
            while self._is_constraint():
                cc.append(self._parse_column_constraint())
        return ColumnDef(n, tn, prec, scale, ev, cc, default)

    def _is_constraint(self) -> bool:
        t = self._peek().type
        return t in (
            TokenType.NOT, TokenType.NULL, TokenType.PRIMARY,
            TokenType.UNIQUE, TokenType.CHECK, TokenType.REFERENCES,
            TokenType.AUTO_INCREMENT, TokenType.IDENTITY, TokenType.GENERATED,
        )

    def _parse_column_constraint(self) -> ColumnConstraint:
        if self._is(TokenType.NOT):
            self._advance()
            self._advance()  # NULL
            return NotNullConstraint()
        if self._is(TokenType.NULL):
            self._advance()
            return NullConstraint()
        if self._is(TokenType.PRIMARY):
            self._advance()
            self._expect(TokenType.KEY)
            ai = False
            if self._is(TokenType.AUTO_INCREMENT, TokenType.IDENTITY):
                self._advance()
                ai = True
            return PrimaryKeyConstraint(ai)
        if self._is(TokenType.UNIQUE):
            self._advance()
            return UniqueConstraint()
        if self._is(TokenType.CHECK):
            self._advance()
            self._expect(TokenType.LPAREN)
            e = self._parse_expr()
            self._expect(TokenType.RPAREN)
            return CheckConstraint(e)
        if self._is(TokenType.REFERENCES):
            self._advance()
            rt = self._expect_identifier()
            self._expect(TokenType.LPAREN)
            rc = self._expect_identifier()
            self._expect(TokenType.RPAREN)
            return ReferencesConstraint(rt, rc, None, None)
        if self._is(TokenType.AUTO_INCREMENT):
            self._advance()
            return PrimaryKeyConstraint(True)
        if self._is(TokenType.IDENTITY):
            self._advance()
            if self._is(TokenType.LPAREN):
                self._advance()
                self._advance()  # seed
                self._advance()  # increment
                self._expect(TokenType.RPAREN)
            return PrimaryKeyConstraint(True)
        if self._is(TokenType.GENERATED):
            self._advance()
            self._advance()  # ALWAYS / BY DEFAULT
            self._expect(TokenType.AS)
            self._expect(TokenType.LPAREN)
            self._parse_expr()
            self._expect(TokenType.RPAREN)
            self._advance()  # VIRTUAL / STORED
            return GeneratedConstraint(True, False, None)
        raise ParseError(self._error("Unknown constraint"))

    def _parse_table_constraint(self) -> TableConstraint:
        if self._is(TokenType.CONSTRAINT):
            self._advance()
            self._advance()  # constraint name
        if self._is(TokenType.PRIMARY):
            self._advance()
            self._expect(TokenType.KEY)
            self._expect(TokenType.LPAREN)
            cs = self._id_list()
            self._expect(TokenType.RPAREN)
            return TbPrimaryKey(cs, None)
        if self._is(TokenType.FOREIGN):
            self._advance()
            self._expect(TokenType.KEY)
            self._expect(TokenType.LPAREN)
            cs = self._id_list()
            self._expect(TokenType.RPAREN)
            self._expect(TokenType.REFERENCES)
            rt = self._expect_identifier()
            self._expect(TokenType.LPAREN)
            rcs = self._id_list()
            self._expect(TokenType.RPAREN)
            return TbForeignKey(cs, rt, rcs, None, None, None)
        if self._is(TokenType.UNIQUE):
            self._advance()
            self._expect(TokenType.LPAREN)
            cs = self._id_list()
            self._expect(TokenType.RPAREN)
            return TbUnique(cs, None)
        if self._is(TokenType.CHECK):
            self._advance()
            self._expect(TokenType.LPAREN)
            e = self._parse_expr()
            self._expect(TokenType.RPAREN)
            return TbCheck(e, None)
        raise ParseError(self._error("Unknown table constraint"))

    def _id_list(self) -> list[str]:
        result: list[str] = []
        while True:
            result.append(self._expect_identifier())
            if not self._is(TokenType.COMMA):
                break
            self._advance()
        return result

    def _parse_table_options(self) -> TableOptions:
        e: str | None = None
        ts: str | None = None
        cs: str | None = None
        col: str | None = None
        cm: str | None = None
        while self._is(TokenType.ENGINE, TokenType.TABLESPACE, TokenType.CHARACTER, TokenType.COLLATE, TokenType.COMMENT):
            if self._is(TokenType.ENGINE):
                self._advance()
                if self._is(TokenType.EQ):
                    self._advance()
                e = self._advance().text
            elif self._is(TokenType.TABLESPACE):
                self._advance()
                if self._is(TokenType.EQ):
                    self._advance()
                ts = self._advance().text
            elif self._is(TokenType.CHARACTER):
                self._advance()
                if self._is(TokenType.SET):
                    self._advance()
                if self._is(TokenType.EQ):
                    self._advance()
                cs = self._advance().text
            elif self._is(TokenType.COLLATE):
                self._advance()
                if self._is(TokenType.EQ):
                    self._advance()
                col = self._advance().text
            else:  # COMMENT
                self._advance()
                if self._is(TokenType.EQ):
                    self._advance()
                cm = self._expect(TokenType.STRING_LITERAL).text
        return TableOptions(e, ts, cs, col, cm)

    def _parse_create_index(self) -> CreateIndexStmt:
        uq = self._is(TokenType.UNIQUE)
        if uq:
            self._advance()
        self._expect(TokenType.INDEX)
        ifn = False
        if self._is(TokenType.IF):
            self._advance()
            self._advance()  # NOT
            self._advance()  # EXISTS
            ifn = True
        nm = self._expect_identifier()
        self._expect(TokenType.ON)
        tn = self._expect_identifier()
        self._expect(TokenType.LPAREN)
        ics: list[IndexColumn] = []
        while True:
            c = self._expect_identifier()
            d = False
            if self._is(TokenType.DESC):
                self._advance()
                d = True
            elif self._is(TokenType.ASC):
                self._advance()
            ics.append(IndexColumn(c, d, False))
            if not self._is(TokenType.COMMA):
                break
            self._advance()
        self._expect(TokenType.RPAREN)
        w: Expression | None = None
        if self._is(TokenType.WHERE):
            self._advance()
            w = self._parse_expr()
        return CreateIndexStmt(uq, ifn, nm, tn, ics, w)

    def _parse_params(self) -> list[ParamDef]:
        self._expect(TokenType.LPAREN)
        p: list[ParamDef] = []
        if not self._is(TokenType.RPAREN):
            while True:
                d = ParamDir.IN
                if self._is(TokenType.IN):
                    self._advance()
                elif self._is(TokenType.OUT):
                    self._advance()
                    d = ParamDir.OUT
                elif self._is(TokenType.INOUT):
                    self._advance()
                    d = ParamDir.INOUT
                n = self._expect_identifier()
                dt = self._parse_data_type_decl()
                p.append(ParamDef(n, dt, d))
                if not self._is(TokenType.COMMA):
                    break
                self._advance()
        self._expect(TokenType.RPAREN)
        return p

    def _parse_data_type_decl(self) -> DataTypeDecl:
        t = self._advance()
        prec = 0
        scale = 0
        if self._is(TokenType.LPAREN):
            self._advance()
            if self._is(TokenType.INT_LITERAL):
                prec = int(self._advance().text)
            if self._is(TokenType.COMMA):
                self._advance()
                scale = int(self._expect(TokenType.INT_LITERAL).text)
            self._expect(TokenType.RPAREN)
        return DataTypeDecl(t.text, prec, scale)

    # ══════════════════════════════════════════════════
    #  DROP / TRUNCATE / ALTER
    # ══════════════════════════════════════════════════

    def _parse_drop(self) -> Statement:
        self._advance()  # DROP
        if self._is(TokenType.TABLE):
            self._advance()
            if_exists = False
            if self._is(TokenType.IF):
                self._advance()
                self._advance()  # EXISTS
                if_exists = True
            n = self._expect_identifier()
            cascade = False
            if self._is(TokenType.CASCADE):
                self._advance()
                cascade = True
            elif self._is(TokenType.RESTRICT):
                self._advance()
            return DropTableStmt(n, if_exists, cascade)
        if self._is(TokenType.INDEX):
            self._advance()
            if_exists = False
            if self._is(TokenType.IF):
                self._advance()
                self._advance()  # EXISTS
                if_exists = True
            n = self._expect_identifier()
            t: str | None = None
            if self._is(TokenType.ON):
                self._advance()
                t = self._expect_identifier()
            return DropIndexStmt(n, t, if_exists)
        if self._is(TokenType.DATABASE):
            self._advance()
            if_exists = False
            if self._is(TokenType.IF):
                self._advance()
                self._advance()  # EXISTS
                if_exists = True
            return DropDatabaseStmt(self._expect_identifier(), if_exists)
        raise ParseError(self._error("Unknown DROP"))

    def _parse_truncate(self) -> TruncateStmt:
        self._advance()  # TRUNCATE
        if self._is(TokenType.TABLE):
            self._advance()
        return TruncateStmt(self._expect_identifier())

    def _parse_alter(self) -> AlterTableStmt:
        self._advance()  # ALTER
        self._expect(TokenType.TABLE)
        tbl = self._expect_identifier()
        if self._is(TokenType.ADD):
            self._advance()
            if self._is(TokenType.COLUMN):
                self._advance()
            ifn = False
            if self._is(TokenType.IF):
                self._advance()
                self._advance()  # NOT
                self._advance()  # EXISTS
                ifn = True
            cd = self._parse_column_def()
            return AlterTableStmt(
                tbl,
                AddColumn(cd.name, DataTypeDecl(cd.type_name, cd.type_precision, cd.type_scale),
                          cd.constraints, cd.default_value, ifn),
            )
        if self._is(TokenType.DROP):
            self._advance()
            if self._is(TokenType.COLUMN):
                self._advance()
            return AlterTableStmt(tbl, DropColumn(self._expect_identifier()))
        if self._is(TokenType.ALTER):
            self._advance()
            if self._is(TokenType.COLUMN):
                self._advance()
            col = self._expect_identifier()
            if self._is(TokenType.TYPE):
                self._advance()
                return AlterTableStmt(tbl, AlterColumnType(col, self._parse_data_type_decl()))
            if self._is(TokenType.SET):
                self._advance()
                self._advance()  # DEFAULT
                return AlterTableStmt(tbl, AlterColumnSetDefault(col, self._parse_expr()))
            if self._is(TokenType.DROP):
                self._advance()
                return AlterTableStmt(tbl, AlterColumnDropDefault(col))
        if self._is(TokenType.RENAME):
            self._advance()
            if self._is(TokenType.COLUMN):
                self._advance()
            old = self._expect_identifier()
            self._expect(TokenType.TO)
            return AlterTableStmt(tbl, RenameColumn(old, self._expect_identifier()))
        raise ParseError(self._error("Unknown ALTER"))

    # ══════════════════════════════════════════════════
    #  TCL
    # ══════════════════════════════════════════════════

    def _parse_tcl(self) -> TCLStmt:
        parts: list[str] = []
        while not self._is(TokenType.SEMI, TokenType.EOF):
            if parts:
                parts.append(" ")
            parts.append(self._advance().text)
        return TCLStmt("".join(parts))

    # ══════════════════════════════════════════════════
    #  CALL
    # ══════════════════════════════════════════════════

    def _parse_call_stmt(self) -> CallStmt:
        self._advance()  # CALL
        name = self._expect_identifier()
        args: list[Expression] = []
        if self._is(TokenType.LPAREN):
            self._advance()
            if not self._is(TokenType.RPAREN):
                while True:
                    args.append(self._parse_expr())
                    if not self._is(TokenType.COMMA):
                        break
                    self._advance()
            self._expect(TokenType.RPAREN)
        return CallStmt(name, args)

    # ══════════════════════════════════════════════════
    #  EXPRESSIONS (Pratt)
    # ══════════════════════════════════════════════════

    def _parse_expr(self, min_prec: int = 0) -> Expression:
        left = self._parse_prefix()
        while True:
            prec = self._infix_prec(self._peek().type)
            if prec <= min_prec:
                break
            t = self._peek().type

            # IS [NOT] NULL / TRUE / FALSE
            if t is TokenType.IS:
                self._advance()
                not_ = self._is(TokenType.NOT)
                if not_:
                    self._advance()
                if self._is(TokenType.NULL):
                    self._advance()
                    left = IsNullExpr(left, not_)
                elif self._is(TokenType.TRUE):
                    self._advance()
                    left = UnaryOp(UnOp.IS_NOT_TRUE if not_ else UnOp.IS_TRUE, left)
                elif self._is(TokenType.FALSE):
                    self._advance()
                    left = UnaryOp(UnOp.IS_NOT_FALSE if not_ else UnOp.IS_FALSE, left)
                else:
                    self._advance()  # unknown IS ... skip
                continue

            # NOT IN / NOT LIKE / NOT BETWEEN
            if t is TokenType.NOT:
                p2 = self._peek2()
                if p2 is not None and p2.type in (TokenType.IN, TokenType.LIKE, TokenType.BETWEEN):
                    self._advance()
                    if self._is(TokenType.IN):
                        self._advance()
                        self._expect(TokenType.LPAREN)
                        if self._is(TokenType.SELECT):
                            sq = self._parse_select()
                            self._expect(TokenType.RPAREN)
                            left = InListExpr(left, None, sq, True)
                        else:
                            vs: list[Expression] = []
                            while True:
                                vs.append(self._parse_expr())
                                if not self._is(TokenType.COMMA):
                                    break
                                self._advance()
                            self._expect(TokenType.RPAREN)
                            left = InListExpr(left, vs, None, True)
                    elif self._is(TokenType.LIKE):
                        self._advance()
                        left = BinaryOp(left, BinOp.NOT_LIKE, self._parse_expr(prec))
                    else:  # BETWEEN
                        self._advance()
                        lo = self._parse_expr(30)
                        self._expect(TokenType.AND)
                        left = BetweenExpr(left, lo, self._parse_expr(30), True)
                    continue

            # BETWEEN
            if t is TokenType.BETWEEN:
                self._advance()
                lo = self._parse_expr(30)
                self._expect(TokenType.AND)
                left = BetweenExpr(left, lo, self._parse_expr(30), False)
                continue

            # IN
            if t is TokenType.IN:
                self._advance()
                self._expect(TokenType.LPAREN)
                if self._is(TokenType.SELECT):
                    sq = self._parse_select()
                    self._expect(TokenType.RPAREN)
                    left = InListExpr(left, None, sq, False)
                else:
                    vs = []
                    while True:
                        vs.append(self._parse_expr())
                        if not self._is(TokenType.COMMA):
                            break
                        self._advance()
                    self._expect(TokenType.RPAREN)
                    left = InListExpr(left, vs, None, False)
                continue

            # LIKE
            if t is TokenType.LIKE:
                self._advance()
                left = BinaryOp(left, BinOp.LIKE, self._parse_expr(prec))
                continue

            # Standard binary operators
            op = self._advance()
            bop = self._map_bin_op(op.type)
            right = self._parse_expr(prec)
            left = BinaryOp(left, bop, right)

        return left

    @staticmethod
    def _infix_prec(t: TokenType) -> int:
        match t:
            case TokenType.OR:
                return 10
            case TokenType.AND:
                return 20
            case TokenType.NOT:
                return 25  # NOT IN / NOT LIKE / NOT BETWEEN — must be > AND (20) and < comparison (30)
            case TokenType.EQ | TokenType.NEQ | TokenType.LT | TokenType.GT | TokenType.LTE | TokenType.GTE | TokenType.IS | TokenType.LIKE | TokenType.IN:
                return 30
            case TokenType.BETWEEN:
                return 35
            case TokenType.PLUS | TokenType.MINUS | TokenType.CONCAT:
                return 40
            case TokenType.STAR | TokenType.DIV | TokenType.MOD:
                return 50
            case _:
                return 0

    @staticmethod
    def _map_bin_op(t: TokenType) -> BinOp:
        match t:
            case TokenType.PLUS:
                return BinOp.ADD
            case TokenType.MINUS:
                return BinOp.SUB
            case TokenType.STAR:
                return BinOp.MUL
            case TokenType.DIV:
                return BinOp.DIV
            case TokenType.MOD:
                return BinOp.MOD
            case TokenType.EQ:
                return BinOp.EQ
            case TokenType.NEQ:
                return BinOp.NEQ
            case TokenType.LT:
                return BinOp.LT
            case TokenType.GT:
                return BinOp.GT
            case TokenType.LTE:
                return BinOp.LTE
            case TokenType.GTE:
                return BinOp.GTE
            case TokenType.AND:
                return BinOp.AND
            case TokenType.OR:
                return BinOp.OR
            case TokenType.CONCAT:
                return BinOp.CONCAT
            case _:
                raise ParseError(f"Unknown binop: {t}")

    # ══════════════════════════════════════════════════
    #  Prefix expressions
    # ══════════════════════════════════════════════════

    def _parse_prefix(self) -> Expression:
        t = self._peek()
        match t.type:
            case TokenType.INT_LITERAL:
                self._advance()
                return IntLiteral(int(t.text))
            case TokenType.FLOAT_LITERAL:
                self._advance()
                return FloatLiteral(float(t.text))
            case TokenType.STRING_LITERAL:
                self._advance()
                return StringLiteral(t.text)
            case TokenType.TRUE:
                self._advance()
                return BoolLiteral(True)
            case TokenType.FALSE:
                self._advance()
                return BoolLiteral(False)
            case TokenType.NULL:
                self._advance()
                return NullLiteral()
            case TokenType.STAR:
                self._advance()
                return StarExpr(None)
            case TokenType.LPAREN:
                self._advance()
                if self._is(TokenType.SELECT):
                    sq = self._parse_select()
                    self._expect(TokenType.RPAREN)
                    return SubqueryExpr(sq)
                e = self._parse_expr()
                self._expect(TokenType.RPAREN)
                return e
            case TokenType.MINUS:
                self._advance()
                return UnaryOp(UnOp.NEG, self._parse_expr(60))
            case TokenType.PLUS:
                self._advance()
                return self._parse_expr(60)
            case TokenType.NOT:
                self._advance()
                return UnaryOp(UnOp.NOT, self._parse_expr(35))
            case TokenType.EXISTS:
                self._advance()
                self._expect(TokenType.LPAREN)
                sq = self._parse_select()
                self._expect(TokenType.RPAREN)
                return UnaryOp(UnOp.EXISTS, SubqueryExpr(sq))
            case TokenType.CASE:
                return self._parse_case()
            case TokenType.CAST:
                return self._parse_cast()
            case TokenType.IDENTIFIER:
                return self._parse_ident_or_func()
            case TokenType.QMARK | TokenType.COLON:
                self._advance()
                return ParamRef("?")
            case _:
                # Keywords used as identifiers (function names, column names)
                if t.type.value < TokenType.LPAREN.value:
                    return self._parse_ident_or_func()
                raise ParseError(self._error(f"Unexpected: {t.type.name}"))

    def _parse_ident_or_func(self) -> Expression:
        t = self._peek()
        # Accept both IDENTIFIER and keywords as function/column names
        if t.type is not TokenType.IDENTIFIER and t.type.value >= TokenType.LPAREN.value:
            raise ParseError(self._error(f"Expected identifier, got {t.type.name}"))
        name = self._advance().text

        if self._is(TokenType.LPAREN):
            self._advance()
            args: list[Expression] = []
            star = False
            if self._is(TokenType.STAR):
                self._advance()
                star = True
            elif not self._is(TokenType.RPAREN):
                while True:
                    args.append(self._parse_expr())
                    if not self._is(TokenType.COMMA):
                        break
                    self._advance()
            self._expect(TokenType.RPAREN)

            # KEEP clause
            keep: KeepClause | None = None
            if self._is(TokenType.KEEP):
                self._advance()
                self._expect(TokenType.LPAREN)
                self._expect(TokenType.DENSE_RANK)
                last = False
                x = self._advance()
                if x.text.upper() == "LAST":
                    last = True
                self._expect(TokenType.ORDER)
                self._expect(TokenType.BY)
                ob: list[OrderByItem] = []
                while True:
                    e = self._parse_expr()
                    d = False
                    if self._is(TokenType.DESC):
                        self._advance()
                        d = True
                    ob.append(OrderByItem(e, d, False))
                    if not self._is(TokenType.COMMA):
                        break
                    self._advance()
                self._expect(TokenType.RPAREN)
                keep = KeepClause(last, ob)

            # OVER clause
            over: WindowOver | None = None
            if self._is(TokenType.OVER):
                self._advance()
                self._expect(TokenType.LPAREN)
                pb: list[Expression] | None = None
                if self._is(TokenType.PARTITION):
                    self._advance()
                    self._expect(TokenType.BY)
                    pb = []
                    while True:
                        pb.append(self._parse_expr())
                        if not self._is(TokenType.COMMA):
                            break
                        self._advance()
                ow_ob: list[OrderByItem] | None = None
                if self._is(TokenType.ORDER):
                    self._advance()
                    self._expect(TokenType.BY)
                    ow_ob = []
                    while True:
                        e = self._parse_expr()
                        d = False
                        if self._is(TokenType.DESC):
                            self._advance()
                            d = True
                        ow_ob.append(OrderByItem(e, d, False))
                        if not self._is(TokenType.COMMA):
                            break
                        self._advance()
                self._expect(TokenType.RPAREN)
                over = WindowOver(pb, ow_ob, None)

            return FunctionCall(name, args, star, keep, over)

        # Qualified name: table.column or table.*
        if self._is(TokenType.DOT):
            self._advance()
            if self._is(TokenType.STAR):
                self._advance()
                return StarExpr(name)
            col = self._expect_identifier()
            return ColumnRef([name], col)

        return ColumnRef([], name)

    def _parse_case(self) -> CaseExpr:
        self._advance()  # CASE
        operand: Expression | None = None
        if not self._is(TokenType.WHEN):
            operand = self._parse_expr()
        ws: list[WhenClause] = []
        while self._is(TokenType.WHEN):
            self._advance()
            c = self._parse_expr()
            self._expect(TokenType.THEN)
            ws.append(WhenClause(c, self._parse_expr()))
        el: Expression | None = None
        if self._is(TokenType.ELSE):
            self._advance()
            el = self._parse_expr()
        self._expect(TokenType.END)
        return CaseExpr(operand, ws, el)

    def _parse_cast(self) -> CastExpr:
        self._advance()  # CAST
        self._expect(TokenType.LPAREN)
        e = self._parse_expr()
        self._expect(TokenType.AS)
        dt = self._parse_data_type_decl()
        self._expect(TokenType.RPAREN)
        return CastExpr(e, dt.name, dt.precision, dt.scale)

    # ══════════════════════════════════════════════════
    #  Helpers
    # ══════════════════════════════════════════════════

    def _peek(self) -> Token:
        if self._pos < len(self._tokens):
            return self._tokens[self._pos]
        return Token(TokenType.EOF, "", 0, 0)

    def _peek2(self) -> Token | None:
        if self._pos + 1 < len(self._tokens):
            return self._tokens[self._pos + 1]
        return None

    def _peek3(self) -> Token | None:
        if self._pos + 2 < len(self._tokens):
            return self._tokens[self._pos + 2]
        return None

    def _is(self, *types: TokenType) -> bool:
        return self._peek().type in types

    def _advance(self) -> Token:
        tok = self._tokens[self._pos]
        self._pos += 1
        return tok

    def _expect(self, t: TokenType) -> Token:
        tok = self._advance()
        if tok.type is not t:
            raise ParseError(
                self._error(f"Expected {t.name} got {tok.type.name} '{tok.text}'")
            )
        return tok

    def _expect_identifier(self) -> str:
        tok = self._advance()
        if tok.type is not TokenType.IDENTIFIER and tok.type.value >= TokenType.LPAREN.value:
            raise ParseError(self._error(f"Expected identifier, got {tok.type.name}"))
        return tok.text

    def _parse_set_column(self) -> str:
        first = self._expect_identifier()
        if self._is(TokenType.DOT):
            self._advance()
            return self._expect_identifier()
        return first

    def _skip_to_semi(self) -> None:
        while self._pos < len(self._tokens) and not self._is(TokenType.SEMI, TokenType.EOF):
            self._advance()

    def _error(self, msg: str) -> str:
        t = self._peek()
        return f"Parse error line {t.line}:{t.col} — {msg}"
