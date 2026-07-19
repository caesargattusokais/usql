package com.usql.parser;

import com.usql.ast.USqlAst;
import com.usql.ast.USqlAst.*;
import com.usql.parser.HandLexer.*;
import java.util.*;

/**
 * Hand-written recursive descent + Pratt expression parser.
 * Matches USqlAst record types exactly.
 */
public class HandParser {

    private final List<Token> tokens;
    private int pos;

    public HandParser(List<Token> tokens) { this.tokens = tokens; this.pos = 0; }

    public List<Statement> parseProgram() {
        List<Statement> stmts = new ArrayList<>();
        while (!is(TokenType.EOF)) {
            stmts.add(parseStatement());
            if (is(TokenType.SEMI)) advance();
        }
        return stmts;
    }

    Statement parseStatement() {
        Token t = peek();
        if (t.is(TokenType.SELECT) || t.is(TokenType.WITH)) return parseSelect();
        if (t.is(TokenType.INSERT)) return parseInsert();
        if (t.is(TokenType.UPDATE)) return parseUpdate();
        if (t.is(TokenType.DELETE)) return parseDelete();
        if (t.is(TokenType.MERGE)) return parseMerge();
        if (t.is(TokenType.CREATE)) return parseCreate();
        if (t.is(TokenType.DROP)) return parseDrop();
        if (t.is(TokenType.TRUNCATE)) return parseTruncate();
        if (t.is(TokenType.ALTER)) return parseAlter();
        if (t.is(TokenType.BEGIN) || t.is(TokenType.START)) return parseTcl();
        if (t.is(TokenType.COMMIT) || t.is(TokenType.ROLLBACK) || t.is(TokenType.SAVEPOINT) || t.is(TokenType.RELEASE)) return parseTcl();
        if (t.is(TokenType.CALL)) { parseCall(); return new TCLStmt(""); }
        throw error("Unexpected: " + t.type);
    }

    // ═══════════════ SELECT ═══════════════

    SelectStmt parseSelect() {
        List<CommonTable> withClause = null;
        if (is(TokenType.WITH)) { advance(); boolean rec = is(TokenType.RECURSIVE); if (rec) advance(); withClause = new ArrayList<>(); do { String n = expectIdentifier(); List<String> cols = null; if (is(TokenType.LPAREN)) { advance(); cols = idList(); expect(TokenType.RPAREN); } expect(TokenType.AS); expect(TokenType.LPAREN); withClause.add(new CommonTable(n, cols, parseSelect(), rec)); expect(TokenType.RPAREN); } while (is(TokenType.COMMA) && advance() != null); }
        expect(TokenType.SELECT);
        boolean distinct = false; if (is(TokenType.DISTINCT)) { advance(); distinct = true; } else if (is(TokenType.ALL)) advance();
        List<SelectItem> proj = new ArrayList<>();
        do {
            if (is(TokenType.STAR)) { advance(); proj.add(new StarItem(null)); }
            else if (is(TokenType.IDENTIFIER) && peek2() != null && peek2().is(TokenType.DOT) && peek3() != null && peek3().is(TokenType.STAR)) { String q = advance().text; advance(); advance(); proj.add(new StarItem(q)); }
            else { Expression e = parseExpr(); String alias = null; if (!is(TokenType.COMMA) && !is(TokenType.FROM) && !is(TokenType.WHERE) && !is(TokenType.GROUP) && !is(TokenType.HAVING) && !is(TokenType.ORDER) && !is(TokenType.LIMIT) && !is(TokenType.OFFSET) && !is(TokenType.FETCH) && !is(TokenType.UNION) && !is(TokenType.INTERSECT) && !is(TokenType.EXCEPT) && !is(TokenType.RPAREN) && !is(TokenType.EOF)) { if (is(TokenType.AS)) advance(); if (is(TokenType.IDENTIFIER) || is(TokenType.STRING_LITERAL)) alias = advance().text; } proj.add(new ExprItem(e, alias)); }
        } while (is(TokenType.COMMA) && advance() != null);

        List<TableRef> from = null;
        if (is(TokenType.FROM)) { advance(); from = new ArrayList<>(); do { from.add(parseTableRef()); } while (is(TokenType.COMMA) && advance() != null); }
        Expression where = null; if (is(TokenType.WHERE)) { advance(); where = parseExpr(); }
        List<GroupByItem> groupBy = null; if (is(TokenType.GROUP)) { advance(); expect(TokenType.BY); groupBy = new ArrayList<>(); do { groupBy.add(parseGroupByItem()); } while (is(TokenType.COMMA) && advance() != null); }
        Expression having = null; if (is(TokenType.HAVING)) { advance(); having = parseExpr(); }
        List<OrderByItem> orderBy = null; if (is(TokenType.ORDER)) { advance(); expect(TokenType.BY); orderBy = new ArrayList<>(); do { Expression e = parseExpr(); boolean desc = false; if (is(TokenType.DESC)) { advance(); desc = true; } else if (is(TokenType.ASC)) advance(); boolean nf = false; if (is(TokenType.NULLS)) { advance(); nf = is(TokenType.FIRST); advance(); } orderBy.add(new OrderByItem(e, desc, nf)); } while (is(TokenType.COMMA) && advance() != null); }
        FetchClause fetch = null;
        if (is(TokenType.LIMIT)) { advance(); Expression limit = parseExpr(); Expression offset = null; if (is(TokenType.OFFSET)) { advance(); offset = parseExpr(); } fetch = new FetchClause(limit, offset); }
        else if (is(TokenType.OFFSET) || is(TokenType.FETCH)) { Expression offset; if (is(TokenType.OFFSET)) { advance(); offset = parseExpr(); } else offset = new IntLiteral(0L); if (is(TokenType.ROWS) || is(TokenType.ROW)) advance(); if (is(TokenType.FETCH)) advance(); if (is(TokenType.NEXT)) advance(); Expression limit = parseExpr(); if (is(TokenType.ROWS) || is(TokenType.ROW)) advance(); if (is(TokenType.ONLY)) advance(); fetch = new FetchClause(limit, offset); }
        SetOp setOp = null; SelectStmt setOperand = null;
        if (is(TokenType.UNION) || is(TokenType.INTERSECT) || is(TokenType.EXCEPT)) { Token op = advance(); boolean all = is(TokenType.ALL); if (all) advance(); setOp = op.is(TokenType.UNION) ? (all ? SetOp.UNION_ALL : SetOp.UNION) : op.is(TokenType.INTERSECT) ? SetOp.INTERSECT : SetOp.EXCEPT; setOperand = parseSelect(); }
        return new SelectStmt(withClause, distinct, proj, from, where, groupBy, having, orderBy, fetch, setOp, setOperand);
    }

    // ═══════════════ FROM ═══════════════

    TableRef parseTableRef() {
        TableRef left = parseTablePrimary();
        while (true) {
            JoinType jt = null;
            if (is(TokenType.INNER)) { advance(); if (is(TokenType.JOIN)) advance(); jt = JoinType.INNER; }
            else if (is(TokenType.LEFT)) { advance(); if (is(TokenType.OUTER)) advance(); if (is(TokenType.JOIN)) advance(); jt = JoinType.LEFT; }
            else if (is(TokenType.RIGHT)) { advance(); if (is(TokenType.OUTER)) advance(); if (is(TokenType.JOIN)) advance(); jt = JoinType.RIGHT; }
            else if (is(TokenType.FULL)) { advance(); if (is(TokenType.OUTER)) advance(); if (is(TokenType.JOIN)) advance(); jt = JoinType.FULL; }
            else if (is(TokenType.CROSS)) { advance(); if (is(TokenType.JOIN)) advance(); jt = JoinType.CROSS; }
            else if (is(TokenType.JOIN)) { advance(); jt = JoinType.INNER; }
            // NOTE: top-level comma (FROM a, b) is handled by parseSelect's FROM loop,
            // producing a multi-table list (implicit cross product). Do NOT consume it
            // here as a join — that would collapse "a, b" into a single JoinTable and
            // emit "a INNER JOIN b" with no ON clause (a syntax error in most dialects).
            else break;
            TableRef right = parseTablePrimary();
            Expression on = null; if (is(TokenType.ON)) { advance(); on = parseExpr(); }
            left = new JoinTable(left, jt, right, on);
        }
        return left;
    }

    TableRef parseTablePrimary() {
        boolean lateral = is(TokenType.LATERAL); if (lateral) advance();
        if (is(TokenType.LPAREN)) {
            advance(); SelectStmt sq = parseSelect(); expect(TokenType.RPAREN);
            String alias = null; if (is(TokenType.AS)) advance(); if (is(TokenType.IDENTIFIER)) alias = advance().text;
            return new SubqueryTable(sq, alias);
        }
        String name = expectIdentifier();
        if (is(TokenType.LPAREN)) {
            advance(); List<Expression> args = new ArrayList<>(); if (!is(TokenType.RPAREN)) { do { args.add(parseExpr()); } while (is(TokenType.COMMA) && advance() != null); } expect(TokenType.RPAREN);
            String alias = null; if (is(TokenType.AS)) advance(); if (is(TokenType.IDENTIFIER)) alias = advance().text;
            return new FunctionTable(lateral, name, args, alias);
        }
        String alias = null; if (is(TokenType.AS)) advance(); if (is(TokenType.IDENTIFIER)) alias = advance().text;
        return new SimpleTable(name, alias);
    }

    // ═══════════════ GROUP BY ═══════════════

    GroupByItem parseGroupByItem() {
        TokenType t = peek().type;
        if (t == TokenType.ROLLUP || t == TokenType.CUBE || t == TokenType.GROUPING) {
            advance(); if (t == TokenType.GROUPING) { advance(); t = TokenType.GROUPING; }
            expect(TokenType.LPAREN); List<Expression> args = new ArrayList<>(); do { args.add(parseExpr()); } while (is(TokenType.COMMA) && advance() != null); expect(TokenType.RPAREN);
            GroupByKind k = t == TokenType.ROLLUP ? GroupByKind.ROLLUP : t == TokenType.CUBE ? GroupByKind.CUBE : GroupByKind.GROUPING_SETS;
            return new GroupByItem(new FunctionCall(k.name(), args, false, null, null), k);
        }
        return new GroupByItem(parseExpr(), GroupByKind.PLAIN);
    }

    // ═══════════════ DML ═══════════════

    InsertStmt parseInsert() { advance(); boolean ig = is(TokenType.IGNORE); if (ig) advance(); expect(TokenType.INTO); String tn = expectIdentifier(); String ta = null; if (is(TokenType.AS)) advance(); if (is(TokenType.IDENTIFIER)) ta = advance().text; TableRef tab = new SimpleTable(tn, ta); List<String> cols = null; if (is(TokenType.LPAREN)) { advance(); cols = idList(); expect(TokenType.RPAREN); } if (is(TokenType.VALUES)) { advance(); List<List<Expression>> vals = new ArrayList<>(); do { expect(TokenType.LPAREN); List<Expression> row = new ArrayList<>(); do { row.add(parseExpr()); } while (is(TokenType.COMMA) && advance() != null); expect(TokenType.RPAREN); vals.add(row); } while (is(TokenType.COMMA) && advance() != null); return new InsertStmt(ig, tab, cols, vals, null); } else { return new InsertStmt(ig, tab, cols, null, parseSelect()); } }
    UpdateStmt parseUpdate() { advance(); String tn = expectIdentifier(); String ta = null; if (is(TokenType.AS)) advance(); if (is(TokenType.IDENTIFIER)) ta = advance().text; TableRef tab = new SimpleTable(tn, ta); expect(TokenType.SET); List<SetClause> sets = new ArrayList<>(); do { String c = parseSetColumn(); expect(TokenType.EQ); sets.add(new SetClause(c, parseExpr())); } while (is(TokenType.COMMA) && advance() != null); Expression w = null; if (is(TokenType.WHERE)) { advance(); w = parseExpr(); } return new UpdateStmt(tab, sets, w); }
    DeleteStmt parseDelete() { advance(); expect(TokenType.FROM); String tn = expectIdentifier(); String ta = null; if (is(TokenType.AS)) advance(); if (is(TokenType.IDENTIFIER)) ta = advance().text; TableRef tab = new SimpleTable(tn, ta); Expression w = null; if (is(TokenType.WHERE)) { advance(); w = parseExpr(); } return new DeleteStmt(tab, w); }
    MergeStmt parseMerge() { advance(); expect(TokenType.INTO); TableRef tgt = parseTablePrimary(); String tAlias = null; if (is(TokenType.AS)) advance(); if (is(TokenType.IDENTIFIER)) tAlias = advance().text; expect(TokenType.USING); TableRef src = parseTablePrimary(); expect(TokenType.ON); Expression on = parseExpr(); List<MergeAction> acts = new ArrayList<>(); while (is(TokenType.WHEN)) { advance(); boolean m = is(TokenType.MATCHED); advance(); if (!m) { advance(); } expect(TokenType.THEN); if (is(TokenType.UPDATE)) { advance(); expect(TokenType.SET); List<SetClause> s = new ArrayList<>(); do { String c = parseSetColumn(); expect(TokenType.EQ); s.add(new SetClause(c, parseExpr())); } while (is(TokenType.COMMA) && advance() != null); acts.add(new MergeUpdate(s)); } else if (is(TokenType.INSERT)) { advance(); List<String> cs = null; if (is(TokenType.LPAREN)) { advance(); cs = idList(); expect(TokenType.RPAREN); } expect(TokenType.VALUES); expect(TokenType.LPAREN); List<Expression> vs = new ArrayList<>(); do { vs.add(parseExpr()); } while (is(TokenType.COMMA) && advance() != null); expect(TokenType.RPAREN); acts.add(new MergeInsert(cs, vs)); } else { advance(); acts.add(new MergeDelete()); } } return new MergeStmt(tgt, tAlias, src, on, acts); }

    // ═══════════════ CREATE ═══════════════

    Statement parseCreate() { advance(); boolean orReplace = false; if (is(TokenType.OR)) { advance(); advance(); orReplace = true; } if (is(TokenType.TABLE)) return parseCreateTable(); if (is(TokenType.INDEX) || is(TokenType.UNIQUE)) return parseCreateIndex(); if (is(TokenType.VIEW)) { advance(); return new CreateViewStmt(expectIdentifier(), parseSelectAfterAs()); } if (is(TokenType.SCHEMA)) { advance(); return new CreateSchemaStmt(expectIdentifier()); } if (is(TokenType.PROCEDURE)) { advance(); String n = expectIdentifier(); List<ParamDef> p = parseParams(); String b = null; if (is(TokenType.AS)) { advance(); if (is(TokenType.STRING_LITERAL)) b = advance().text; else skipToSemi(); } return new CreateProcedureStmt(n, p, orReplace, b); } if (is(TokenType.FUNCTION)) { advance(); String n = expectIdentifier(); List<ParamDef> p = parseParams(); expect(TokenType.RETURNS); DataTypeDecl rt = parseDataTypeDecl(); String b = null; if (is(TokenType.AS)) { advance(); if (is(TokenType.STRING_LITERAL)) b = advance().text; else skipToSemi(); } return new CreateFunctionStmt(n, p, rt, orReplace, b); } throw error("Unknown CREATE"); }

    SelectStmt parseSelectAfterAs() { expect(TokenType.AS); return parseSelect(); }

    CreateTableStmt parseCreateTable() { advance(); boolean ifn = false; if (is(TokenType.IF)) { advance(); advance(); ifn = true; } String tn = expectIdentifier(); expect(TokenType.LPAREN); List<ColumnDef> cols = new ArrayList<>(); List<TableConstraint> cons = new ArrayList<>(); if (!is(TokenType.RPAREN)) { do { if (is(TokenType.PRIMARY) || is(TokenType.FOREIGN) || is(TokenType.UNIQUE) || is(TokenType.CHECK) || is(TokenType.CONSTRAINT)) cons.add(parseTableConstraint()); else cols.add(parseColumnDef()); } while (is(TokenType.COMMA) && advance() != null); } expect(TokenType.RPAREN); return new CreateTableStmt(ifn, tn, cols, cons, parseTableOptions()); }

    ColumnDef parseColumnDef() { String n = expectIdentifier(); Token dt = advance(); String tn = dt.text; int prec = 0, scale = 0; List<String> ev = null; if (is(TokenType.LPAREN)) { advance(); if (tn.equalsIgnoreCase("ENUM")) { ev = new ArrayList<>(); do { ev.add(expect(TokenType.STRING_LITERAL).text); } while (is(TokenType.COMMA) && advance() != null); } else { if (is(TokenType.INT_LITERAL)) prec = Integer.parseInt(advance().text); if (is(TokenType.COMMA)) { advance(); scale = Integer.parseInt(expect(TokenType.INT_LITERAL).text); } } expect(TokenType.RPAREN); } List<ColumnConstraint> cc = new ArrayList<>(); while (isConstraint()) cc.add(parseColumnConstraint()); Expression def = null; if (is(TokenType.DEFAULT)) { advance(); def = parseExpr(); } return new ColumnDef(n, tn, prec, scale, ev, cc, def); }

    boolean isConstraint() { TokenType t = peek().type; return t == TokenType.NOT || t == TokenType.NULL || t == TokenType.PRIMARY || t == TokenType.UNIQUE || t == TokenType.CHECK || t == TokenType.REFERENCES || t == TokenType.AUTO_INCREMENT || t == TokenType.IDENTITY || t == TokenType.GENERATED; }

    ColumnConstraint parseColumnConstraint() { if (is(TokenType.NOT)) { advance(); advance(); return new NotNullConstraint(); } if (is(TokenType.NULL)) { advance(); return new NullConstraint(); } if (is(TokenType.PRIMARY)) { advance(); expect(TokenType.KEY); boolean ai = false; if (is(TokenType.AUTO_INCREMENT) || is(TokenType.IDENTITY)) { advance(); ai = true; } return new PrimaryKeyConstraint(ai); } if (is(TokenType.UNIQUE)) { advance(); return new UniqueConstraint(); } if (is(TokenType.CHECK)) { advance(); expect(TokenType.LPAREN); Expression e = parseExpr(); expect(TokenType.RPAREN); return new CheckConstraint(e); } if (is(TokenType.REFERENCES)) { advance(); String rt = expectIdentifier(); expect(TokenType.LPAREN); String rc = expectIdentifier(); expect(TokenType.RPAREN); return new ReferencesConstraint(rt, rc, null, null); } if (is(TokenType.AUTO_INCREMENT)) { advance(); return new PrimaryKeyConstraint(true); } if (is(TokenType.IDENTITY)) { advance(); if (is(TokenType.LPAREN)) { advance(); advance(); advance(); expect(TokenType.RPAREN); } return new PrimaryKeyConstraint(true); } if (is(TokenType.GENERATED)) { advance(); advance(); expect(TokenType.AS); expect(TokenType.LPAREN); parseExpr(); expect(TokenType.RPAREN); advance(); return new GeneratedConstraint(true, false, null); } throw error("Unknown constraint"); }

    TableConstraint parseTableConstraint() { if (is(TokenType.CONSTRAINT)) { advance(); advance(); } if (is(TokenType.PRIMARY)) { advance(); expect(TokenType.KEY); expect(TokenType.LPAREN); List<String> cs = idList(); expect(TokenType.RPAREN); return new TbPrimaryKey(cs, null); } if (is(TokenType.FOREIGN)) { advance(); expect(TokenType.KEY); expect(TokenType.LPAREN); List<String> cs = idList(); expect(TokenType.RPAREN); expect(TokenType.REFERENCES); String rt = expectIdentifier(); expect(TokenType.LPAREN); List<String> rcs = idList(); expect(TokenType.RPAREN); return new TbForeignKey(cs, rt, rcs, null, null, null); } if (is(TokenType.UNIQUE)) { advance(); expect(TokenType.LPAREN); List<String> cs = idList(); expect(TokenType.RPAREN); return new TbUnique(cs, null); } if (is(TokenType.CHECK)) { advance(); expect(TokenType.LPAREN); Expression e = parseExpr(); expect(TokenType.RPAREN); return new TbCheck(e, null); } throw error("Unknown table constraint"); }

    List<String> idList() { List<String> l = new ArrayList<>(); do { l.add(expectIdentifier()); } while (is(TokenType.COMMA) && advance() != null); return l; }

    TableOptions parseTableOptions() { String e = null, ts = null, cs = null, col = null, cm = null; while (is(TokenType.ENGINE) || is(TokenType.TABLESPACE) || is(TokenType.CHARACTER) || is(TokenType.COLLATE) || is(TokenType.COMMENT)) { if (is(TokenType.ENGINE)) { advance(); if (is(TokenType.EQ)) advance(); e = advance().text; } else if (is(TokenType.TABLESPACE)) { advance(); if (is(TokenType.EQ)) advance(); ts = advance().text; } else if (is(TokenType.CHARACTER)) { advance(); if (is(TokenType.SET)) advance(); if (is(TokenType.EQ)) advance(); cs = advance().text; } else if (is(TokenType.COLLATE)) { advance(); if (is(TokenType.EQ)) advance(); col = advance().text; } else { advance(); if (is(TokenType.EQ)) advance(); cm = expect(TokenType.STRING_LITERAL).text; } } return new TableOptions(e, ts, cs, col, cm); }

    CreateIndexStmt parseCreateIndex() { boolean uq = is(TokenType.UNIQUE); if (uq) advance(); expect(TokenType.INDEX); boolean ifn = false; if (is(TokenType.IF)) { advance(); advance(); ifn = true; } String nm = expectIdentifier(); expect(TokenType.ON); String tn = expectIdentifier(); expect(TokenType.LPAREN); List<IndexColumn> cs = new ArrayList<>(); do { String c = expectIdentifier(); boolean d = false; if (is(TokenType.DESC)) { advance(); d = true; } else if (is(TokenType.ASC)) advance(); cs.add(new IndexColumn(c, d, false)); } while (is(TokenType.COMMA) && advance() != null); expect(TokenType.RPAREN); Expression w = null; if (is(TokenType.WHERE)) { advance(); w = parseExpr(); } return new CreateIndexStmt(uq, ifn, nm, tn, cs, w); }

    List<ParamDef> parseParams() { expect(TokenType.LPAREN); List<ParamDef> p = new ArrayList<>(); if (!is(TokenType.RPAREN)) { do { ParamDir d = ParamDir.IN; if (is(TokenType.IN)) { advance(); } else if (is(TokenType.OUT)) { advance(); d = ParamDir.OUT; } else if (is(TokenType.INOUT)) { advance(); d = ParamDir.INOUT; } String n = expectIdentifier(); DataTypeDecl dt = parseDataTypeDecl(); p.add(new ParamDef(n, dt, d)); } while (is(TokenType.COMMA) && advance() != null); } expect(TokenType.RPAREN); return p; }

    DataTypeDecl parseDataTypeDecl() { Token t = advance(); int prec = 0, scale = 0; if (is(TokenType.LPAREN)) { advance(); if (is(TokenType.INT_LITERAL)) prec = Integer.parseInt(advance().text); if (is(TokenType.COMMA)) { advance(); scale = Integer.parseInt(expect(TokenType.INT_LITERAL).text); } expect(TokenType.RPAREN); } return new DataTypeDecl(t.text, prec, scale); }

    // ═══════════════ DROP / TRUNCATE / ALTER ═══════════════

    Statement parseDrop() { advance(); if (is(TokenType.TABLE)) { advance(); boolean ifEx = false; if (is(TokenType.IF)) { advance(); advance(); ifEx = true; } String n = expectIdentifier(); boolean csc = false; if (is(TokenType.CASCADE) || is(TokenType.RESTRICT)) { advance(); csc = true; } return new DropTableStmt(n, ifEx, csc); } if (is(TokenType.INDEX)) { advance(); boolean ifEx = false; if (is(TokenType.IF)) { advance(); advance(); ifEx = true; } String n = expectIdentifier(); String t = null; if (is(TokenType.ON)) { advance(); t = expectIdentifier(); } return new DropIndexStmt(n, t, ifEx); } if (is(TokenType.DATABASE)) { advance(); boolean ifEx = false; if (is(TokenType.IF)) { advance(); advance(); ifEx = true; } return new DropDatabaseStmt(expectIdentifier(), ifEx); } throw error("Unknown DROP"); }

    Statement parseTruncate() { advance(); if (is(TokenType.TABLE)) advance(); return new TruncateStmt(expectIdentifier()); }

    Statement parseAlter() { advance(); expect(TokenType.TABLE); String tbl = expectIdentifier(); if (is(TokenType.ADD)) { advance(); if (is(TokenType.COLUMN)) advance(); boolean ifn = false; if (is(TokenType.IF)) { advance(); advance(); advance(); ifn = true; } ColumnDef cd = parseColumnDef(); return new AlterTableStmt(tbl, new AddColumn(cd.name(), new DataTypeDecl(cd.typeName(), cd.typePrecision(), cd.typeScale()), cd.constraints(), cd.defaultValue(), ifn)); } if (is(TokenType.DROP)) { advance(); if (is(TokenType.COLUMN)) advance(); return new AlterTableStmt(tbl, new DropColumn(expectIdentifier())); } if (is(TokenType.ALTER)) { advance(); if (is(TokenType.COLUMN)) advance(); String col = expectIdentifier(); if (is(TokenType.TYPE)) { advance(); return new AlterTableStmt(tbl, new AlterColumnType(col, parseDataTypeDecl())); } if (is(TokenType.SET)) { advance(); advance(); return new AlterTableStmt(tbl, new AlterColumnSetDefault(col, parseExpr())); } if (is(TokenType.DROP)) { advance(); return new AlterTableStmt(tbl, new AlterColumnDropDefault(col)); } } if (is(TokenType.RENAME)) { advance(); if (is(TokenType.COLUMN)) advance(); String old = expectIdentifier(); expect(TokenType.TO); return new AlterTableStmt(tbl, new RenameColumn(old, expectIdentifier())); } throw error("Unknown ALTER"); }

    // ═══════════════ TCL ═══════════════

    Statement parseTcl() { StringBuilder sb = new StringBuilder(); while (!is(TokenType.SEMI) && !is(TokenType.EOF)) { if (sb.length() > 0) sb.append(' '); sb.append(advance().text); } return new TCLStmt(sb.toString()); }

    void parseCall() { while (!is(TokenType.SEMI) && !is(TokenType.EOF)) advance(); }

    // ═══════════════ EXPRESSIONS (Pratt) ═══════════════

    Expression parseExpr() { return parseExpr(0); }

    Expression parseExpr(int minPrec) {
        Expression left = parsePrefix();
        while (true) {
            int prec = infixPrec(peek().type);
            if (prec <= minPrec) break;
            TokenType t = peek().type;
            if (t == TokenType.IS) { advance(); boolean not = is(TokenType.NOT); if (not) advance(); if (is(TokenType.NULL)) { advance(); left = new IsNullExpr(left, not); } else { advance(); } continue; }
            if (t == TokenType.NOT) { if (peek2() != null && (peek2().is(TokenType.IN) || peek2().is(TokenType.LIKE) || peek2().is(TokenType.BETWEEN))) { advance(); if (is(TokenType.IN)) { advance(); expect(TokenType.LPAREN); if (is(TokenType.SELECT)) { SelectStmt sq = parseSelect(); expect(TokenType.RPAREN); left = new InListExpr(left, null, sq, true); } else { List<Expression> vs = new ArrayList<>(); do { vs.add(parseExpr()); } while (is(TokenType.COMMA) && advance() != null); expect(TokenType.RPAREN); left = new InListExpr(left, vs, null, true); } } else if (is(TokenType.LIKE)) { advance(); left = new BinaryOp(left, BinOp.NOT_LIKE, parseExpr(prec)); } else { advance(); Expression lo = parseExpr(30); expect(TokenType.AND); left = new BetweenExpr(left, lo, parseExpr(30), true); } continue; } }
            if (t == TokenType.BETWEEN) { advance(); Expression lo = parseExpr(30); expect(TokenType.AND); left = new BetweenExpr(left, lo, parseExpr(30), false); continue; }
            if (t == TokenType.IN) { advance(); expect(TokenType.LPAREN); if (is(TokenType.SELECT)) { SelectStmt sq = parseSelect(); expect(TokenType.RPAREN); left = new InListExpr(left, null, sq, false); } else { List<Expression> vs = new ArrayList<>(); do { vs.add(parseExpr()); } while (is(TokenType.COMMA) && advance() != null); expect(TokenType.RPAREN); left = new InListExpr(left, vs, null, false); } continue; }
            if (t == TokenType.LIKE) { advance(); left = new BinaryOp(left, BinOp.LIKE, parseExpr(prec)); continue; }
            Token op = advance(); BinOp bop = mapBinOp(op.type); Expression right = parseExpr(prec); left = new BinaryOp(left, bop, right);
        }
        return left;
    }

    int infixPrec(TokenType t) { return switch (t) { case OR -> 10; case AND -> 20; case EQ, NEQ, LT, GT, LTE, GTE, IS, LIKE, IN -> 30; case BETWEEN -> 35; case PLUS, MINUS, CONCAT -> 40; case STAR, DIV, MOD -> 50; default -> 0; }; }

    BinOp mapBinOp(TokenType t) { return switch (t) { case PLUS -> BinOp.ADD; case MINUS -> BinOp.SUB; case STAR -> BinOp.MUL; case DIV -> BinOp.DIV; case MOD -> BinOp.MOD; case EQ -> BinOp.EQ; case NEQ -> BinOp.NEQ; case LT -> BinOp.LT; case GT -> BinOp.GT; case LTE -> BinOp.LTE; case GTE -> BinOp.GTE; case AND -> BinOp.AND; case OR -> BinOp.OR; case CONCAT -> BinOp.CONCAT; default -> throw error("Unknown binop: " + t); }; }

    Expression parsePrefix() {
        Token t = peek();
        return switch (t.type) {
            case INT_LITERAL -> { advance(); yield new IntLiteral(Long.parseLong(t.text)); }
            case FLOAT_LITERAL -> { advance(); yield new FloatLiteral(Double.parseDouble(t.text)); }
            case STRING_LITERAL -> { advance(); yield new StringLiteral(t.text); }
            case TRUE -> { advance(); yield new BoolLiteral(true); }
            case FALSE -> { advance(); yield new BoolLiteral(false); }
            case NULL -> { advance(); yield new NullLiteral(); }
            case STAR -> { advance(); yield new StarExpr(null); }
            case LPAREN -> { advance(); if (is(TokenType.SELECT)) { SelectStmt sq = parseSelect(); expect(TokenType.RPAREN); yield new SubqueryExpr(sq); } Expression e = parseExpr(); expect(TokenType.RPAREN); yield e; }
            case MINUS -> { advance(); yield new UnaryOp(UnOp.NEG, parseExpr()); }
            case PLUS -> { advance(); yield parseExpr(); }
            case NOT -> { advance(); yield new UnaryOp(UnOp.NOT, parseExpr()); }
            case EXISTS -> { advance(); expect(TokenType.LPAREN); SelectStmt sq = parseSelect(); expect(TokenType.RPAREN); yield new UnaryOp(UnOp.EXISTS, new SubqueryExpr(sq)); }
            case CASE -> { yield parseCase(); }
            case CAST -> { yield parseCast(); }
            case IDENTIFIER -> { yield parseIdentOrFunc(); }
            case QMARK, COLON -> { advance(); yield new ParamRef("?"); }
            default -> {
                if (t.type.ordinal() < TokenType.LPAREN.ordinal()) yield parseIdentOrFunc();
                throw error("Unexpected: " + t.type);
            }
        };
    }

    Expression parseIdentOrFunc() {
        Token t = peek();
        // Accept both IDENTIFIER and keywords as function/column names
        if (!t.is(TokenType.IDENTIFIER) && t.type.ordinal() >= TokenType.LPAREN.ordinal())
            throw error("Expected identifier, got " + t.type);
        String name = advance().text;
        if (is(TokenType.LPAREN)) {
            advance(); List<Expression> args = new ArrayList<>(); boolean star = false;
            if (is(TokenType.STAR)) { advance(); star = true; } else if (!is(TokenType.RPAREN)) { do { args.add(parseExpr()); } while (is(TokenType.COMMA) && advance() != null); }
            expect(TokenType.RPAREN);
            KeepClause keep = null; WindowOver over = null;
            if (is(TokenType.KEEP)) { advance(); expect(TokenType.LPAREN); expect(TokenType.DENSE_RANK); boolean last = false; Token x = advance(); if (x.text.equalsIgnoreCase("LAST")) last = true; expect(TokenType.ORDER); expect(TokenType.BY); List<OrderByItem> ob = new ArrayList<>(); do { Expression e = parseExpr(); boolean d = false; if (is(TokenType.DESC)) { advance(); d = true; } ob.add(new OrderByItem(e, d, false)); } while (is(TokenType.COMMA) && advance() != null); expect(TokenType.RPAREN); keep = new KeepClause(last, ob); }
            if (is(TokenType.OVER)) { advance(); expect(TokenType.LPAREN); List<Expression> pb = null; if (is(TokenType.PARTITION)) { advance(); expect(TokenType.BY); pb = new ArrayList<>(); do { pb.add(parseExpr()); } while (is(TokenType.COMMA) && advance() != null); } List<OrderByItem> ob = null; if (is(TokenType.ORDER)) { advance(); expect(TokenType.BY); ob = new ArrayList<>(); do { Expression e = parseExpr(); boolean d = false; if (is(TokenType.DESC)) { advance(); d = true; } ob.add(new OrderByItem(e, d, false)); } while (is(TokenType.COMMA) && advance() != null); } expect(TokenType.RPAREN); over = new WindowOver(pb, ob, null); }
            return new FunctionCall(name, args, star, keep, over);
        }
        if (is(TokenType.DOT)) { advance(); if (is(TokenType.STAR)) { advance(); return new StarExpr(name); } String col = expectIdentifier(); return new ColumnRef(List.of(name), col); }
        return new ColumnRef(List.of(), name);
    }

    Expression parseCase() { advance(); List<WhenClause> ws = new ArrayList<>(); while (is(TokenType.WHEN)) { advance(); Expression c = parseExpr(); expect(TokenType.THEN); ws.add(new WhenClause(c, parseExpr())); } Expression el = null; if (is(TokenType.ELSE)) { advance(); el = parseExpr(); } expect(TokenType.END); return new CaseExpr(ws, el); }

    Expression parseCast() { advance(); expect(TokenType.LPAREN); Expression e = parseExpr(); expect(TokenType.AS); DataTypeDecl dt = parseDataTypeDecl(); expect(TokenType.RPAREN); return new CastExpr(e, dt.name(), dt.precision(), dt.scale()); }

    // ═══════════════ HELPERS ═══════════════

    Token peek() { return pos < tokens.size() ? tokens.get(pos) : new Token(TokenType.EOF, "", 0, 0); }
    Token peek2() { return pos + 1 < tokens.size() ? tokens.get(pos + 1) : null; }
    Token peek3() { return pos + 2 < tokens.size() ? tokens.get(pos + 2) : null; }
    boolean is(TokenType t) { return peek().type == t; }
    Token advance() { return tokens.get(pos++); }
    Token expect(TokenType t) { Token tok = advance(); if (tok.type != t) throw error("Expected " + t + " got " + tok.type + " '" + tok.text + "'"); return tok; }
    /** Accept IDENTIFIER or any keyword token as an identifier name. Returns the original text preserving case. */
    String expectIdentifier() {
        Token tok = advance();
        if (tok.type != TokenType.IDENTIFIER && tok.type.ordinal() >= TokenType.LPAREN.ordinal())
            throw error("Expected identifier, got " + tok.type);
        return tok.text;
    }
    /** Parse the left-hand side of a SET assignment: accepts `col` or `qualifier.col`,
     *  returning just the column name (the qualifier is dropped — the target table is
     *  already fixed by the enclosing UPDATE/MERGE, so SetClause only stores a name). */
    String parseSetColumn() {
        String first = expectIdentifier();
        if (is(TokenType.DOT)) { advance(); return expectIdentifier(); }
        return first;
    }
    void skipToSemi() { while (pos < tokens.size() && !is(TokenType.SEMI) && !is(TokenType.EOF)) advance(); }
    RuntimeException error(String msg) { Token t = peek(); return new RuntimeException("Parse error line " + t.line + ":" + t.col + " — " + msg); }
}
