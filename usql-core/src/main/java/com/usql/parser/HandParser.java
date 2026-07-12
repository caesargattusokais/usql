package com.usql.parser;

import com.usql.ast.USqlAst;
import com.usql.ast.USqlAst.*;
import com.usql.parser.HandLexer.TokenType;
import com.usql.parser.HandLexer.Token;

import java.util.*;

/**
 * Hand-written recursive descent parser for U-SQL.
 * Produces the same AST as the ANTLR-generated parser.
 */
public class HandParser {

    private final List<Token> tokens;
    private int pos;

    public HandParser(List<Token> tokens) {
        this.tokens = tokens;
        this.pos = 0;
    }

    public List<Statement> parseProgram() {
        List<Statement> stmts = new ArrayList<>();
        while (!is(TokenType.EOF)) {
            stmts.add(parseStatement());
            if (is(TokenType.SEMI)) advance();
        }
        return stmts;
    }

    // ═══════════════════════════
    //  Statement dispatch
    // ═══════════════════════════

    Statement parseStatement() {
        return switch (peek().type) {
            case SELECT, WITH -> parseSelect();
            case INSERT -> parseInsert();
            case UPDATE -> parseUpdate();
            case DELETE -> parseDelete();
            case MERGE -> parseMerge();
            case CREATE -> parseCreate();
            case DROP -> parseDrop();
            case TRUNCATE -> parseTruncate();
            case ALTER -> parseAlter();
            case BEGIN, START -> parseBegin();
            case COMMIT -> parseTcl("COMMIT");
            case ROLLBACK -> parseRollback();
            case SAVEPOINT -> parseTcl("SAVEPOINT");
            case RELEASE -> parseTcl("RELEASE");
            case CALL -> parseCall();
            default -> throw error("Unexpected statement: " + peek().type);
        };
    }

    // ═══════════════════════════
    //  SELECT
    // ═══════════════════════════

    SelectStmt parseSelect() {
        // WITH clause
        List<CommonTable> withClause = null;
        if (is(TokenType.WITH)) {
            advance();
            boolean recursive = is(TokenType.RECURSIVE);
            if (recursive) advance();
            withClause = new ArrayList<>();
            do {
                String cteName = expect(TokenType.IDENTIFIER).text;
                List<String> cteCols = null;
                if (is(TokenType.LPAREN)) {
                    advance();
                    cteCols = new ArrayList<>();
                    do {
                        cteCols.add(expect(TokenType.IDENTIFIER).text);
                    } while (is(TokenType.COMMA) && advance() != null);
                    expect(TokenType.RPAREN);
                }
                expect(TokenType.AS);
                expect(TokenType.LPAREN);
                SelectStmt cteQuery = parseSelect();
                expect(TokenType.RPAREN);
                withClause.add(new CommonTable(cteName, cteCols, cteQuery, false, recursive));
            } while (is(TokenType.COMMA) && advance() != null);
        }

        expect(TokenType.SELECT);
        boolean distinct = is(TokenType.DISTINCT);
        if (distinct) advance();
        if (is(TokenType.ALL)) advance();

        // Projections
        List<SelectItem> projections = new ArrayList<>();
        do {
            if (is(TokenType.STAR)) {
                advance(); projections.add(new StarItem(null));
            } else if (is(TokenType.IDENTIFIER) && peek2() != null && peek2().is(TokenType.DOT) && peek3() != null && peek3().is(TokenType.STAR)) {
                String qual = advance().text; advance(); advance();
                projections.add(new StarItem(qual));
            } else {
                Expression expr = parseExpr();
                String alias = null;
                if (is(TokenType.AS) || is(TokenType.IDENTIFIER)) {
                    if (is(TokenType.AS)) advance();
                    if (is(TokenType.IDENTIFIER)) alias = advance().text;
                }
                projections.add(new ExprItem(expr, alias));
            }
        } while (is(TokenType.COMMA) && advance() != null);

        // FROM
        List<TableRef> from = null;
        if (is(TokenType.FROM)) {
            advance();
            from = new ArrayList<>();
            do {
                from.add(parseTableRef());
            } while (is(TokenType.COMMA) && advance() != null);
        }

        // WHERE
        Expression where = null;
        if (is(TokenType.WHERE)) { advance(); where = parseExpr(); }

        // GROUP BY
        List<GroupByItem> groupBy = null;
        if (is(TokenType.GROUP)) {
            advance(); expect(TokenType.BY);
            groupBy = new ArrayList<>();
            do {
                groupBy.add(parseGroupByItem());
            } while (is(TokenType.COMMA) && advance() != null);
        }

        // HAVING
        Expression having = null;
        if (is(TokenType.HAVING)) { advance(); having = parseExpr(); }

        // ORDER BY
        List<OrderByItem> orderBy = null;
        if (is(TokenType.ORDER)) {
            advance(); expect(TokenType.BY);
            orderBy = new ArrayList<>();
            do {
                Expression e = parseExpr();
                OrderDir dir = OrderDir.ASC;
                if (is(TokenType.ASC)) advance();
                else if (is(TokenType.DESC)) { advance(); dir = OrderDir.DESC; }
                boolean nullsFirst = false;
                if (is(TokenType.NULLS)) { advance(); nullsFirst = is(TokenType.FIRST); advance(); }
                orderBy.add(new OrderByItem(e, dir, nullsFirst));
            } while (is(TokenType.COMMA) && advance() != null);
        }

        // LIMIT / FETCH
        FetchClause fetch = null;
        if (is(TokenType.LIMIT)) {
            advance();
            Expression limit = parseExpr();
            Expression offset = null;
            if (is(TokenType.OFFSET)) { advance(); offset = parseExpr(); }
            fetch = new FetchClause(limit, offset);
        } else if (is(TokenType.OFFSET) || is(TokenType.FETCH)) {
            Expression offset;
            if (is(TokenType.OFFSET)) { advance(); offset = parseExpr(); }
            else offset = new IntLiteral(0);
            if (is(TokenType.ROWS) || is(TokenType.ROW)) advance();
            if (is(TokenType.FETCH)) { advance(); } // skip FETCH
            if (is(TokenType.NEXT)) advance();
            Expression limit = parseExpr();
            if (is(TokenType.ROWS) || is(TokenType.ROW)) advance();
            if (is(TokenType.ONLY)) advance();
            fetch = new FetchClause(limit, offset);
        }

        // Set operation
        SetOp setOp = null;
        SelectStmt setOperand = null;
        if (is(TokenType.UNION) || is(TokenType.INTERSECT) || is(TokenType.EXCEPT)) {
            Token op = advance();
            boolean all = is(TokenType.ALL);
            if (all) advance();
            setOp = switch (op.type) {
                case UNION -> all ? SetOp.UNION_ALL : SetOp.UNION;
                case INTERSECT -> SetOp.INTERSECT;
                case EXCEPT -> SetOp.EXCEPT;
                default -> SetOp.UNION;
            };
            setOperand = parseSelect();
        }

        return new SelectStmt(withClause, distinct, projections, from, where, groupBy, having, orderBy, fetch, setOp, setOperand);
    }

    // ═══════════════════════════
    //  FROM / Table references
    // ═══════════════════════════

    TableRef parseTableRef() {
        TableRef left = parseTablePrimary();
        while (true) {
            JoinType jt = null;
            if (is(TokenType.JOIN)) jt = JoinType.INNER;
            else if (is(TokenType.INNER)) { advance(); if (is(TokenType.JOIN)) advance(); jt = JoinType.INNER; }
            else if (is(TokenType.LEFT)) { advance(); if (is(TokenType.OUTER)) advance(); if (is(TokenType.JOIN)) advance(); jt = JoinType.LEFT; }
            else if (is(TokenType.RIGHT)) { advance(); if (is(TokenType.OUTER)) advance(); if (is(TokenType.JOIN)) advance(); jt = JoinType.RIGHT; }
            else if (is(TokenType.FULL)) { advance(); if (is(TokenType.OUTER)) advance(); if (is(TokenType.JOIN)) advance(); jt = JoinType.FULL; }
            else if (is(TokenType.CROSS)) { advance(); if (is(TokenType.JOIN)) advance(); jt = JoinType.CROSS; }
            else if (is(TokenType.COMMA)) { advance(); jt = JoinType.INNER; }
            else break;
            TableRef right = parseTablePrimary();
            Expression on = null;
            if (is(TokenType.ON)) { advance(); on = parseExpr(); }
            left = new JoinTable(left, jt, right, on);
        }
        return left;
    }

    TableRef parseTablePrimary() {
        boolean lateral = is(TokenType.LATERAL);
        if (lateral) advance();

        if (is(TokenType.LPAREN)) {
            advance();
            SelectStmt query = parseSelect();
            expect(TokenType.RPAREN);
            String alias = null;
            if (is(TokenType.AS)) advance();
            if (is(TokenType.IDENTIFIER)) alias = advance().text;
            if (lateral) return new FunctionTable(true, alias != null ? alias : "lateral", List.of(), alias);
            return new SubqueryTable(query, alias);
        }

        // Function call as table
        if (is(TokenType.IDENTIFIER) && peek2() != null && peek2().is(TokenType.LPAREN)) {
            String funcName = advance().text;
            expect(TokenType.LPAREN);
            List<Expression> args = new ArrayList<>();
            if (!is(TokenType.RPAREN)) {
                do { args.add(parseExpr()); } while (is(TokenType.COMMA) && advance() != null);
            }
            expect(TokenType.RPAREN);
            String alias = null;
            if (is(TokenType.AS)) advance();
            if (is(TokenType.IDENTIFIER)) alias = advance().text;
            return new FunctionTable(lateral, funcName, args, alias);
        }

        String tableName = expect(TokenType.IDENTIFIER).text;
        String tableAlias = null;
        if (is(TokenType.AS)) advance();
        if (is(TokenType.IDENTIFIER)) tableAlias = advance().text;
        return new SimpleTable(tableName, tableAlias);
    }

    // ═══════════════════════════
    //  GROUP BY items
    // ═══════════════════════════

    GroupByItem parseGroupByItem() {
        if (is(TokenType.ROLLUP) || is(TokenType.CUBE) || is(TokenType.GROUPING)) {
            TokenType kind = advance().type;
            if (kind == TokenType.GROUPING) { expect(TokenType.SETS); kind = TokenType.GROUPING; }
            expect(TokenType.LPAREN);
            List<Expression> args = new ArrayList<>();
            do { args.add(parseExpr()); } while (is(TokenType.COMMA) && advance() != null);
            expect(TokenType.RPAREN);
            GroupByKind gbk = kind == TokenType.ROLLUP ? GroupByKind.ROLLUP
                : kind == TokenType.CUBE ? GroupByKind.CUBE : GroupByKind.GROUPING_SETS;
            return new GroupByItem(new FunctionCall(gbk.name(), args, false, null, null), gbk);
        }
        return new GroupByItem(parseExpr(), GroupByKind.PLAIN);
    }

    // ═══════════════════════════
    //  INSERT / UPDATE / DELETE
    // ═══════════════════════════

    InsertStmt parseInsert() {
        advance(); // INSERT
        boolean ignore = is(TokenType.IGNORE);
        if (ignore) advance();
        expect(TokenType.INTO);
        TableRef table = parseTablePrimary();
        List<String> columns = null;
        if (is(TokenType.LPAREN)) {
            advance(); columns = new ArrayList<>();
            do { columns.add(expect(TokenType.IDENTIFIER).text); } while (is(TokenType.COMMA) && advance() != null);
            expect(TokenType.RPAREN);
        }
        if (is(TokenType.WITH)) {
            // INSERT ... WITH is handled as a SELECT source
        }
        if (is(TokenType.VALUES)) {
            advance();
            List<List<Expression>> values = new ArrayList<>();
            do {
                expect(TokenType.LPAREN);
                List<Expression> row = new ArrayList<>();
                do { row.add(parseExpr()); } while (is(TokenType.COMMA) && advance() != null);
                expect(TokenType.RPAREN);
                values.add(row);
            } while (is(TokenType.COMMA) && advance() != null);
            return new InsertStmt(ignore, table, columns, values, null);
        } else {
            SelectStmt src = parseSelect();
            return new InsertStmt(ignore, table, columns, null, src);
        }
    }

    UpdateStmt parseUpdate() {
        advance(); // UPDATE
        TableRef table = parseTablePrimary();
        expect(TokenType.SET);
        List<SetClauseItem> sets = new ArrayList<>();
        do {
            String col = expect(TokenType.IDENTIFIER).text;
            expect(TokenType.EQ);
            Expression val = parseExpr();
            sets.add(new SetClauseItem(col, val));
        } while (is(TokenType.COMMA) && advance() != null);
        Expression where = null;
        if (is(TokenType.WHERE)) { advance(); where = parseExpr(); }
        return new UpdateStmt(table, sets, where);
    }

    DeleteStmt parseDelete() {
        advance(); // DELETE
        expect(TokenType.FROM);
        TableRef table = parseTablePrimary();
        Expression where = null;
        if (is(TokenType.WHERE)) { advance(); where = parseExpr(); }
        return new DeleteStmt(table, where);
    }

    // ═══════════════════════════
    //  MERGE
    // ═══════════════════════════

    MergeStmt parseMerge() {
        advance(); // MERGE
        expect(TokenType.INTO);
        TableRef target = parseTablePrimary();
        expect(TokenType.USING);
        TableRef source = parseTablePrimary();
        expect(TokenType.ON);
        Expression on = parseExpr();
        List<MergeAction> actions = new ArrayList<>();
        while (is(TokenType.WHEN)) {
            advance();
            boolean matched = is(TokenType.MATCHED);
            advance(); // MATCHED or NOT
            if (is(TokenType.NOT)) matched = false; // WHEN NOT MATCHED
            if (!matched) { advance(); advance(); } // skip NOT MATCHED
            expect(TokenType.THEN);
            if (is(TokenType.UPDATE)) {
                advance(); expect(TokenType.SET);
                List<SetClauseItem> sets = new ArrayList<>();
                do {
                    String col = expect(TokenType.IDENTIFIER).text;
                    expect(TokenType.EQ);
                    sets.add(new SetClauseItem(col, parseExpr()));
                } while (is(TokenType.COMMA) && advance() != null);
                actions.add(new MergeUpdateAction(matched, sets));
            } else {
                advance(); // INSERT
                // simplified
            }
        }
        return new MergeStmt(target, source, on, actions);
    }

    // ═══════════════════════════
    //  CREATE
    // ═══════════════════════════

    Statement parseCreate() {
        advance(); // CREATE
        if (is(TokenType.OR)) { advance(); advance(); } // OR REPLACE
        if (is(TokenType.TABLE)) return parseCreateTable();
        if (is(TokenType.INDEX) || is(TokenType.UNIQUE)) return parseCreateIndex();
        if (is(TokenType.VIEW)) return parseCreateView();
        if (is(TokenType.SCHEMA)) return parseCreateSchema();
        if (is(TokenType.PROCEDURE)) return parseCreateProcedure();
        if (is(TokenType.FUNCTION)) return parseCreateFunction();
        throw error("Unknown CREATE: " + peek().type);
    }

    CreateTableStmt parseCreateTable() {
        advance(); // TABLE
        boolean ifNotExists = false;
        if (is(TokenType.IF)) { advance(); advance(); ifNotExists = true; } // IF NOT EXISTS
        String tableName = expect(TokenType.IDENTIFIER).text;
        expect(TokenType.LPAREN);
        List<ColumnDef> columns = new ArrayList<>();
        List<TableConstraint> constraints = new ArrayList<>();
        if (!is(TokenType.RPAREN)) {
            do {
                if (is(TokenType.PRIMARY) || is(TokenType.FOREIGN) || is(TokenType.UNIQUE) || is(TokenType.CHECK) || is(TokenType.CONSTRAINT)) {
                    constraints.add(parseTableConstraint());
                } else {
                    columns.add(parseColumnDef());
                }
            } while (is(TokenType.COMMA) && advance() != null);
        }
        expect(TokenType.RPAREN);
        // Table options
        TableOptions options = parseTableOptions();
        return new CreateTableStmt(ifNotExists, tableName, columns, constraints, options);
    }

    ColumnDef parseColumnDef() {
        String name = expect(TokenType.IDENTIFIER).text;
        DataTypeDecl type = parseDataType();
        List<ColumnConstraint> constraints = new ArrayList<>();
        while (isConstraint()) {
            constraints.add(parseColumnConstraint());
        }
        Expression defaultVal = null;
        if (is(TokenType.DEFAULT)) { advance(); defaultVal = parseExpr(); }
        return new ColumnDef(name, type, constraints, defaultVal);
    }

    boolean isConstraint() {
        return is(TokenType.NOT) || is(TokenType.NULL) || is(TokenType.PRIMARY)
            || is(TokenType.UNIQUE) || is(TokenType.CHECK) || is(TokenType.REFERENCES)
            || is(TokenType.AUTO_INCREMENT) || is(TokenType.IDENTITY) || is(TokenType.GENERATED);
    }

    ColumnConstraint parseColumnConstraint() {
        if (is(TokenType.NOT)) { advance(); advance(); return new NotNullConstraint(); } // NOT NULL
        if (is(TokenType.NULL)) { advance(); return new NullConstraint(); }
        if (is(TokenType.PRIMARY)) { advance(); expect(TokenType.KEY); boolean ai = false; if (is(TokenType.AUTO_INCREMENT) || is(TokenType.IDENTITY)) { advance(); ai = true; } return new PrimaryKeyConstraint(ai); }
        if (is(TokenType.UNIQUE)) { advance(); return new UniqueConstraint(); }
        if (is(TokenType.CHECK)) { advance(); expect(TokenType.LPAREN); Expression e = parseExpr(); expect(TokenType.RPAREN); return new CheckConstraint(e); }
        if (is(TokenType.REFERENCES)) { advance(); String refT = expect(TokenType.IDENTIFIER).text; expect(TokenType.LPAREN); String refC = expect(TokenType.IDENTIFIER).text; expect(TokenType.RPAREN); return new ReferencesConstraint(refT, refC, null, null); }
        if (is(TokenType.AUTO_INCREMENT)) { advance(); return new PrimaryKeyConstraint(true); }
        if (is(TokenType.IDENTITY)) { advance(); if (is(TokenType.LPAREN)) { advance(); advance(); advance(); expect(TokenType.RPAREN); } return new PrimaryKeyConstraint(true); }
        if (is(TokenType.GENERATED)) { advance(); advance(); expect(TokenType.AS); expect(TokenType.LPAREN); Expression e = parseExpr(); expect(TokenType.RPAREN); advance(); return new GeneratedConstraint(true, false, e); }
        throw error("Unknown constraint");
    }

    TableConstraint parseTableConstraint() {
        if (is(TokenType.CONSTRAINT)) { advance(); advance(); } // skip CONSTRAINT name
        if (is(TokenType.PRIMARY)) { advance(); expect(TokenType.KEY); expect(TokenType.LPAREN); List<String> cols = parseColumnList(); expect(TokenType.RPAREN); return new TbPrimaryKey(cols, null); }
        if (is(TokenType.FOREIGN)) { advance(); expect(TokenType.KEY); expect(TokenType.LPAREN); List<String> cols = parseColumnList(); expect(TokenType.RPAREN); expect(TokenType.REFERENCES); String refT = expect(TokenType.IDENTIFIER).text; expect(TokenType.LPAREN); List<String> refCols = parseColumnList(); expect(TokenType.RPAREN); return new TbForeignKey(cols, refT, refCols, null, null, null, false); }
        if (is(TokenType.UNIQUE)) { advance(); expect(TokenType.LPAREN); List<String> cols = parseColumnList(); expect(TokenType.RPAREN); return new TbUnique(cols, null); }
        if (is(TokenType.CHECK)) { advance(); expect(TokenType.LPAREN); Expression e = parseExpr(); expect(TokenType.RPAREN); return new TbCheck(e, null); }
        throw error("Unknown table constraint");
    }

    List<String> parseColumnList() {
        List<String> cols = new ArrayList<>();
        do { cols.add(expect(TokenType.IDENTIFIER).text); } while (is(TokenType.COMMA) && advance() != null);
        return cols;
    }

    TableOptions parseTableOptions() {
        String engine = null, tablespace = null, charset = null, collation = null, comment = null;
        while (is(TokenType.ENGINE) || is(TokenType.TABLESPACE) || is(TokenType.CHARACTER) || is(TokenType.COLLATE) || is(TokenType.COMMENT)) {
            if (is(TokenType.ENGINE)) { advance(); expect(TokenType.EQ); engine = advance().text; }
            else if (is(TokenType.TABLESPACE)) { advance(); expect(TokenType.EQ); tablespace = advance().text; }
            else if (is(TokenType.CHARACTER)) { advance(); if (is(TokenType.SET)) advance(); expect(TokenType.EQ); charset = advance().text; }
            else if (is(TokenType.COLLATE)) { advance(); expect(TokenType.EQ); collation = advance().text; }
            else if (is(TokenType.COMMENT)) { advance(); expect(TokenType.EQ); comment = expect(TokenType.STRING_LITERAL).text; }
        }
        return new TableOptions(engine, tablespace, charset, collation, comment);
    }

    CreateIndexStmt parseCreateIndex() {
        boolean unique = is(TokenType.UNIQUE);
        if (unique) advance();
        expect(TokenType.INDEX);
        boolean ifNotExists = false;
        if (is(TokenType.IF)) { advance(); advance(); ifNotExists = true; }
        String name = expect(TokenType.IDENTIFIER).text;
        expect(TokenType.ON);
        String tableName = expect(TokenType.IDENTIFIER).text;
        expect(TokenType.LPAREN);
        List<IndexColumn> cols = new ArrayList<>();
        do {
            String col = expect(TokenType.IDENTIFIER).text;
            boolean desc = is(TokenType.DESC);
            if (desc) advance(); else if (is(TokenType.ASC)) advance();
            cols.add(new IndexColumn(col, desc, false));
        } while (is(TokenType.COMMA) && advance() != null);
        expect(TokenType.RPAREN);
        Expression where = null;
        if (is(TokenType.WHERE)) { advance(); where = parseExpr(); }
        return new CreateIndexStmt(unique, ifNotExists, name, tableName, cols, where);
    }

    CreateViewStmt parseCreateView() { advance(); String name = expect(TokenType.IDENTIFIER).text; expect(TokenType.AS); return new CreateViewStmt(name, parseSelect()); }
    CreateSchemaStmt parseCreateSchema() { advance(); return new CreateSchemaStmt(expect(TokenType.IDENTIFIER).text); }

    Statement parseCreateProcedure() { advance(); String name = expect(TokenType.IDENTIFIER).text; skipToSemi(); return new TCLStmt(""); }
    Statement parseCreateFunction() { advance(); String name = expect(TokenType.IDENTIFIER).text; skipToSemi(); return new TCLStmt(""); }

    // ═══════════════════════════
    //  DROP / TRUNCATE / ALTER / TCL
    // ═══════════════════════════

    DropTableStmt parseDrop() {
        advance(); // DROP
        if (is(TokenType.TABLE)) {
            advance();
            boolean ifExists = false;
            if (is(TokenType.IF)) { advance(); advance(); ifExists = true; }
            String name = expect(TokenType.IDENTIFIER).text;
            boolean cascade = is(TokenType.CASCADE) || is(TokenType.RESTRICT);
            if (cascade) advance();
            return new DropTableStmt(name, ifExists, cascade);
        }
        if (is(TokenType.INDEX)) {
            advance();
            boolean ifExists = false;
            if (is(TokenType.IF)) { advance(); advance(); ifExists = true; }
            String name = expect(TokenType.IDENTIFIER).text;
            String table = null;
            if (is(TokenType.ON)) { advance(); table = expect(TokenType.IDENTIFIER).text; }
            return new DropTableStmt(name, ifExists, false); // returning as DropTable for simplicity
        }
        if (is(TokenType.DATABASE)) {
            advance();
            boolean ifExists = false;
            if (is(TokenType.IF)) { advance(); advance(); ifExists = true; }
            return new DropTableStmt(expect(TokenType.IDENTIFIER).text, ifExists, false);
        }
        throw error("Unknown DROP target");
    }

    Statement parseTruncate() { advance(); if (is(TokenType.TABLE)) advance(); return new TruncateStmt(expect(TokenType.IDENTIFIER).text); }

    AlterTableStmt parseAlter() {
        advance(); // ALTER
        expect(TokenType.TABLE);
        String table = expect(TokenType.IDENTIFIER).text;
        if (is(TokenType.ADD)) return parseAlterAdd(table);
        if (is(TokenType.DROP)) return parseAlterDrop(table);
        if (is(TokenType.ALTER)) return parseAlterAlter(table);
        if (is(TokenType.RENAME)) return parseAlterRename(table);
        throw error("Unknown ALTER action");
    }

    AlterTableStmt parseAlterAdd(String table) { advance(); if (is(TokenType.COLUMN)) advance(); ColumnDef col = parseColumnDef(); return new AlterTableStmt(table, new AddColumn(col.name, col.type, col.constraints, col.defaultVal)); }
    AlterTableStmt parseAlterDrop(String table) { advance(); if (is(TokenType.COLUMN)) advance(); return new AlterTableStmt(table, new DropColumn(expect(TokenType.IDENTIFIER).text)); }

    AlterTableStmt parseAlterAlter(String table) {
        advance(); if (is(TokenType.COLUMN)) advance();
        String col = expect(TokenType.IDENTIFIER).text;
        if (is(TokenType.TYPE)) { advance(); return new AlterTableStmt(table, new AlterColumnType(col, parseDataType())); }
        if (is(TokenType.SET)) { advance(); advance(); return new AlterTableStmt(table, new AlterColumnSetDefault(col, parseExpr())); }
        if (is(TokenType.DROP)) { advance(); return new AlterTableStmt(table, new AlterColumnDropDefault(col)); }
        throw error("Unknown ALTER COLUMN action");
    }

    AlterTableStmt parseAlterRename(String table) {
        advance(); if (is(TokenType.COLUMN)) advance();
        String oldName = expect(TokenType.IDENTIFIER).text;
        expect(TokenType.TO);
        return new AlterTableStmt(table, new RenameColumn(oldName, expect(TokenType.IDENTIFIER).text));
    }

    // TCL
    TCLStmt parseBegin() { String t = advance().text; if (is(TokenType.TRANSACTION) || is(TokenType.WORK)) t += " " + advance().text; return new TCLStmt(t); }
    TCLStmt parseTcl(String kw) { advance(); return new TCLStmt(kw); }
    TCLStmt parseRollback() { advance(); if (is(TokenType.TO)) { advance(); if (is(TokenType.SAVEPOINT)) advance(); return new TCLStmt("ROLLBACK TO " + expect(TokenType.IDENTIFIER).text); } return new TCLStmt("ROLLBACK"); }
    TCLStmt parseCall() { advance(); skipToSemi(); return new TCLStmt("CALL"); }

    // ═══════════════════════════
    //  EXPRESSIONS — Pratt parser
    // ═══════════════════════════

    Expression parseExpr() { return parseExpr(0); }

    Expression parseExpr(int minPrec) {
        Expression left = parsePrefix();
        while (true) {
            int prec = infixPrec(peek().type);
            if (prec <= minPrec) break;

            if (is(TokenType.IS)) {
                advance(); boolean not = is(TokenType.NOT); if (not) advance();
                if (is(TokenType.NULL)) { advance(); left = new IsNullExpr(left, not); }
                else { advance(); left = new IsBoolExpr(left, not, advance().text.equals("TRUE")); } // TRUE/FALSE
                continue;
            }
            if (is(TokenType.NOT)) {
                if (peek2() != null && (peek2().is(TokenType.IN) || peek2().is(TokenType.LIKE) || peek2().is(TokenType.BETWEEN))) {
                    advance(); // NOT
                    if (is(TokenType.IN)) { left = parseInExpr(left, true); }
                    else if (is(TokenType.LIKE)) { advance(); left = new LikeExpr(left, parseExpr(), true); }
                    else if (is(TokenType.BETWEEN)) { advance(); Expression lo = parseExpr(); expect(TokenType.AND); left = new BetweenExpr(left, lo, parseExpr(), true); }
                    continue;
                }
            }
            if (is(TokenType.BETWEEN)) { advance(); Expression lo = parseExpr(); expect(TokenType.AND); left = new BetweenExpr(left, lo, parseExpr(), false); continue; }
            if (is(TokenType.IN)) { left = parseInExpr(left, false); continue; }
            if (is(TokenType.LIKE)) { advance(); left = new LikeExpr(left, parseExpr(), false); continue; }

            Token op = advance();
            Expression right = parseExpr(prec);
            left = new BinExpr(left, mapBinOp(op.type), right);
        }
        return left;
    }

    int infixPrec(TokenType t) {
        return switch (t) {
            case OR -> 10;
            case AND -> 20;
            case NOT -> 30;
            case EQ, NEQ, LT, GT, LTE, GTE, IS, LIKE, IN, BETWEEN -> 40;
            case PLUS, MINUS, CONCAT -> 50;
            case STAR, DIV, MOD -> 60;
            default -> 0;
        };
    }

    BinOp mapBinOp(TokenType t) {
        return switch (t) {
            case PLUS -> BinOp.ADD; case MINUS -> BinOp.SUB;
            case STAR -> BinOp.MUL; case DIV -> BinOp.DIV; case MOD -> BinOp.MOD;
            case EQ -> BinOp.EQ; case NEQ -> BinOp.NEQ;
            case LT -> BinOp.LT; case GT -> BinOp.GT;
            case LTE -> BinOp.LTE; case GTE -> BinOp.GTE;
            case AND -> BinOp.AND; case OR -> BinOp.OR;
            case CONCAT -> BinOp.CONCAT;
            default -> throw error("Unknown binop: " + t);
        };
    }

    Expression parsePrefix() {
        Token t = peek();
        return switch (t.type) {
            case INT_LITERAL -> { advance(); yield new IntLiteral(Integer.parseInt(t.text)); }
            case FLOAT_LITERAL -> { advance(); yield new FloatLiteral(Double.parseDouble(t.text)); }
            case STRING_LITERAL -> { advance(); yield new StringLiteral(t.text); }
            case TRUE -> { advance(); yield new BoolLiteral(true); }
            case FALSE -> { advance(); yield new BoolLiteral(false); }
            case NULL -> { advance(); yield new NullLiteral(); }
            case STAR -> { advance(); yield new StarExprItem(null); }
            case LPAREN -> { advance(); if (is(TokenType.SELECT)) { SelectStmt sq = parseSelect(); expect(TokenType.RPAREN); yield new SubqueryExpr(sq); } Expression e = parseExpr(); expect(TokenType.RPAREN); yield e; }
            case MINUS -> { advance(); yield new UnaryOpExpr(UnaryOp.NEG, parseExpr()); }
            case PLUS -> { advance(); yield parseExpr(); }
            case NOT -> { advance(); yield new UnaryOpExpr(UnaryOp.NOT, parseExpr()); }
            case EXISTS -> { advance(); expect(TokenType.LPAREN); SelectStmt sq = parseSelect(); expect(TokenType.RPAREN); yield new ExistsExpr(sq); }
            case CASE -> { yield parseCaseExpr(); }
            case CAST -> { yield parseCastExpr(); }
            case IDENTIFIER -> { yield parseIdentOrFunc(); }
            case QMARK, COLON -> { yield parseParameter(); }
            default -> throw error("Unexpected token: " + t.type);
        };
    }

    Expression parseIdentOrFunc() {
        String name = advance().text;
        if (is(TokenType.LPAREN)) {
            return parseFunctionCall(name);
        }
        if (is(TokenType.DOT)) {
            advance();
            if (is(TokenType.STAR)) { advance(); return new StarExprItem(name); }
            String col = expect(TokenType.IDENTIFIER).text;
            return new ColumnRef(col, name);
        }
        return new ColumnRef(name, null);
    }

    Expression parseFunctionCall(String name) {
        expect(TokenType.LPAREN);
        List<Expression> args = new ArrayList<>();
        boolean star = false;
        if (is(TokenType.STAR)) { advance(); star = true; }
        else if (!is(TokenType.RPAREN)) {
            do { args.add(parseExpr()); } while (is(TokenType.COMMA) && advance() != null);
        }
        expect(TokenType.RPAREN);

        KeepClause keep = null;
        if (is(TokenType.KEEP)) {
            advance(); expect(TokenType.LPAREN);
            boolean first = is(TokenType.DENSE_RANK); advance();
            expect(TokenType.FIRST); // or LAST — simplified
            if (is(TokenType.ORDER)) { advance(); expect(TokenType.BY); }
            List<OrderByItem> orderBy = new ArrayList<>();
            do {
                Expression e = parseExpr();
                OrderDir dir = OrderDir.ASC;
                if (is(TokenType.DESC)) { advance(); dir = OrderDir.DESC; }
                orderBy.add(new OrderByItem(e, dir, false));
            } while (is(TokenType.COMMA) && advance() != null);
            expect(TokenType.RPAREN);
            keep = new KeepClause(first, orderBy);
        }

        WindowOver over = null;
        if (is(TokenType.OVER)) {
            advance(); expect(TokenType.LPAREN);
            List<Expression> partitionBy = null;
            if (is(TokenType.PARTITION)) { advance(); expect(TokenType.BY); partitionBy = new ArrayList<>(); do { partitionBy.add(parseExpr()); } while (is(TokenType.COMMA) && advance() != null); }
            List<OrderByItem> orderBy = null;
            if (is(TokenType.ORDER)) { advance(); expect(TokenType.BY); orderBy = new ArrayList<>(); do { Expression e = parseExpr(); OrderDir dir = OrderDir.ASC; if (is(TokenType.DESC)) { advance(); dir = OrderDir.DESC; } orderBy.add(new OrderByItem(e, dir, false)); } while (is(TokenType.COMMA) && advance() != null); }
            String frame = null;
            if (is(TokenType.ROWS) || is(TokenType.RANGE)) { frame = advance().text;
                if (is(TokenType.BETWEEN)) { advance(); frame += " BETWEEN " + advance().text; if (is(TokenType.PRECEDING)) frame += " PRECEDING"; advance(); frame += " AND " + advance().text; if (is(TokenType.FOLLOWING)) frame += " FOLLOWING"; advance(); }
                else { frame += " " + advance().text; if (is(TokenType.PRECEDING)) frame += " PRECEDING"; advance(); }
            }
            expect(TokenType.RPAREN);
            over = new WindowOver(partitionBy, orderBy, frame);
        }

        return new FunctionCall(name, args, star, keep, over);
    }

    Expression parseCaseExpr() {
        advance(); // CASE
        List<WhenClause> whens = new ArrayList<>();
        while (is(TokenType.WHEN)) {
            advance(); Expression cond = parseExpr(); expect(TokenType.THEN); Expression result = parseExpr();
            whens.add(new WhenClause(cond, result));
        }
        Expression elseExpr = null;
        if (is(TokenType.ELSE)) { advance(); elseExpr = parseExpr(); }
        expect(TokenType.END);
        return new CaseExpr(whens, elseExpr);
    }

    Expression parseCastExpr() {
        advance(); expect(TokenType.LPAREN); Expression e = parseExpr(); expect(TokenType.AS);
        DataTypeDecl dt = parseDataType(); expect(TokenType.RPAREN);
        return new CastExpr(e, dt);
    }

    Expression parseInExpr(Expression left, boolean not) {
        advance(); // IN
        expect(TokenType.LPAREN);
        if (is(TokenType.SELECT)) { SelectStmt sq = parseSelect(); expect(TokenType.RPAREN); return new InExpr(left, null, sq, not); }
        List<Expression> vals = new ArrayList<>();
        do { vals.add(parseExpr()); } while (is(TokenType.COMMA) && advance() != null);
        expect(TokenType.RPAREN);
        return new InExpr(left, vals, null, not);
    }

    Expression parseParameter() { advance(); return new ParamRef("?"); }

    // ═══════════════════════════
    //  Data types
    // ═══════════════════════════

    DataTypeDecl parseDataType() {
        Token t = advance();
        String name = t.text;
        int precision = 0, scale = 0;
        if (is(TokenType.LPAREN)) {
            advance();
            if (is(TokenType.INT_LITERAL)) { precision = Integer.parseInt(advance().text); }
            if (is(TokenType.COMMA)) { advance(); scale = Integer.parseInt(expect(TokenType.INT_LITERAL).text); }
            expect(TokenType.RPAREN);
        }
        return new DataTypeDecl(name, precision, scale);
    }

    // ═══════════════════════════
    //  Helpers
    // ═══════════════════════════

    Token peek() { return pos < tokens.size() ? tokens.get(pos) : new Token(TokenType.EOF, "", 0, 0); }
    Token peek2() { return pos + 1 < tokens.size() ? tokens.get(pos + 1) : null; }
    Token peek3() { return pos + 2 < tokens.size() ? tokens.get(pos + 2) : null; }
    boolean is(TokenType t) { return peek().type == t; }
    Token advance() { return tokens.get(pos++); }
    Token expect(TokenType t) {
        Token tok = advance();
        if (tok.type != t) throw error("Expected " + t + " but got " + tok.type + " '" + tok.text + "'");
        return tok;
    }

    void skipToSemi() {
        while (pos < tokens.size() && !is(TokenType.SEMI) && !is(TokenType.EOF)) advance();
    }

    RuntimeException error(String msg) {
        Token t = peek();
        return new RuntimeException("Parse error at line " + t.line + ":" + t.col + " — " + msg);
    }
}
