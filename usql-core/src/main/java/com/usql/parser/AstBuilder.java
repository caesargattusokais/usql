package com.usql.parser;

import com.usql.ast.USqlAst;
import com.usql.ast.USqlAst.*;
import com.usql.ir.IRExpr.WindowFrame;
import com.usql.ir.IRExpr.WindowFrame.Bound;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.antlr.v4.runtime.tree.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Converts a USql parse tree (antlr4 CST) into the clean USqlAst.
 * Extends the generated USqlBaseVisitor.
 */
public class AstBuilder extends USqlBaseVisitor<Object> {

    // ══════════════════════════════════════════════════
    //  Entry points
    // ══════════════════════════════════════════════════

    /**
     * Parse U-SQL text and return a list of AST statements.
     */
    @SuppressWarnings("unchecked")
    public static List<Statement> build(String usql) {
        USqlLexer lexer = new USqlLexer(CharStreams.fromString(usql));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        USqlParser parser = new USqlParser(tokens);

        List<String> parseErrors = new ArrayList<>();
        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                    int line, int charPositionInLine, String msg, RecognitionException e) {
                parseErrors.add("line " + line + ":" + charPositionInLine + " — " + msg);
            }
        });

        USqlParser.ProgramContext ctx = parser.program();

        if (!parseErrors.isEmpty()) {
            throw new ParseException(String.join("\n", parseErrors));
        }

        AstBuilder builder = new AstBuilder();
        return (List<Statement>) builder.visitProgram(ctx);
    }

    /** Parse and return a single statement */
    public static Statement buildSingle(String usql) {
        List<Statement> stmts = build(usql);
        if (stmts.isEmpty()) throw new ParseException("Empty input");
        return stmts.get(0);
    }

    public static class ParseException extends RuntimeException {
        public ParseException(String msg) { super(msg); }
    }

    // ══════════════════════════════════════════════════
    //  Top-level
    // ══════════════════════════════════════════════════

    @Override
    @SuppressWarnings("unchecked")
    public List<Statement> visitProgram(USqlParser.ProgramContext ctx) {
        return ctx.statement().stream()
            .map(s -> (Statement) visit(s))
            .collect(Collectors.toList());
    }

    @Override
    public Statement visitStatement(USqlParser.StatementContext ctx) {
        if (ctx.selectStatement() != null) return visitSelectStatement(ctx.selectStatement());
        if (ctx.insertStatement() != null) return visitInsertStatement(ctx.insertStatement());
        if (ctx.updateStatement() != null) return visitUpdateStatement(ctx.updateStatement());
        if (ctx.deleteStatement() != null) return visitDeleteStatement(ctx.deleteStatement());
        if (ctx.mergeStatement() != null) return visitMergeStatement(ctx.mergeStatement());
        if (ctx.createTableStatement() != null) return visitCreateTableStatement(ctx.createTableStatement());
        if (ctx.createIndexStatement() != null) return visitCreateIndexStatement(ctx.createIndexStatement());
        if (ctx.createProcedureStatement() != null) return visitCreateProcedureStatement(ctx.createProcedureStatement());
        if (ctx.createFunctionStatement() != null) return visitCreateFunctionStatement(ctx.createFunctionStatement());
        if (ctx.callStatement() != null) return visitCallStatement(ctx.callStatement());
        if (ctx.dropTableStatement() != null) return visitDropTableStatement(ctx.dropTableStatement());
        if (ctx.dropIndexStatement() != null) return visitDropIndexStatement(ctx.dropIndexStatement());
        if (ctx.dropDatabaseStatement() != null) return visitDropDatabaseStatement(ctx.dropDatabaseStatement());
        if (ctx.createViewStatement() != null) return visitCreateViewStatement(ctx.createViewStatement());
        if (ctx.createSchemaStatement() != null) return visitCreateSchemaStatement(ctx.createSchemaStatement());
        if (ctx.beginTransactionStatement() != null) return new TCLStmt(ctx.beginTransactionStatement().getText());
        if (ctx.commitStatement() != null) return new TCLStmt(ctx.commitStatement().getText());
        if (ctx.rollbackStatement() != null) return new TCLStmt(ctx.rollbackStatement().getText());
        if (ctx.savepointStatement() != null) return new TCLStmt(ctx.savepointStatement().getText());
        if (ctx.truncateStatement() != null) return visitTruncateStatement(ctx.truncateStatement());
        if (ctx.alterTableStatement() != null) return visitAlterTableStatement(ctx.alterTableStatement());
        throw new IllegalStateException("Unknown statement type");
    }

    // ══════════════════════════════════════════════════
    //  SELECT
    // ══════════════════════════════════════════════════

    @Override
    @SuppressWarnings("unchecked")
    public SelectStmt visitSelectStatement(USqlParser.SelectStatementContext ctx) {
        List<CommonTable> withClause = null;
        if (ctx.withClause() != null) {
            withClause = ctx.withClause().cteDefinition().stream()
                .map(c -> (CommonTable) visit(c))
                .collect(Collectors.toList());
        }

        boolean distinct = ctx.DISTINCT() != null;

        List<SelectItem> projections = ctx.selectItem().stream()
            .map(s -> (SelectItem) visit(s))
            .collect(Collectors.toList());

        List<TableRef> from = null;
        if (ctx.tableRef() != null && !ctx.tableRef().isEmpty()) {
            from = ctx.tableRef().stream()
                .map(t -> (TableRef) visit(t))
                .collect(Collectors.toList());
        }

        Expression where = ctx.whereClause() != null ? visitWhereClause(ctx.whereClause()) : null;

        List<GroupByItem> groupBy = null;
        if (ctx.groupByClause() != null) groupBy = visitGroupByClause(ctx.groupByClause());

        Expression having = ctx.havingClause() != null ? visitHavingClause(ctx.havingClause()) : null;

        List<OrderByItem> orderBy = null;
        if (ctx.orderByClause() != null) orderBy = visitOrderByClause(ctx.orderByClause());

        FetchClause fetch = ctx.fetchClause() != null ? visitFetchClause(ctx.fetchClause()) : null;

        SetOp setOp = null;
        SelectStmt setOperand = null;
        if (ctx.setOpClause() != null) {
            USqlParser.SetOpClauseContext setCtx = ctx.setOpClause();
            if (setCtx.UNION() != null) setOp = setCtx.ALL() != null ? SetOp.UNION_ALL : SetOp.UNION;
            else if (setCtx.INTERSECT() != null) setOp = SetOp.INTERSECT;
            else setOp = SetOp.EXCEPT;
            setOperand = visitSelectStatement(setCtx.selectStatement());
        }

        return new SelectStmt(withClause, distinct, projections, from, where,
            groupBy, having, orderBy, fetch, setOp, setOperand);
    }

    @Override
    public CommonTable visitCteDefinition(USqlParser.CteDefinitionContext ctx) {
        String name = getIdentifier(ctx.identifier());
        List<String> columns = ctx.columnList() != null ? getColumnList(ctx.columnList()) : null;
        SelectStmt query = visitSelectStatement(ctx.selectStatement());
        // Recursive flag is on the parent withClause rule, not here
        boolean recursive = false;
        if (ctx.getParent() instanceof USqlParser.WithClauseContext parent) {
            recursive = parent.RECURSIVE() != null;
        }
        return new CommonTable(name, columns, query, recursive);
    }

    @Override
    public SelectItem visitExprSelectItem(USqlParser.ExprSelectItemContext ctx) {
        Expression expr = (Expression) visit(ctx.expr());
        String alias = ctx.alias != null ? getIdentifier(ctx.alias) : null;
        return new ExprItem(expr, alias);
    }

    @Override
    public SelectItem visitStarSelectItem(USqlParser.StarSelectItemContext ctx) {
        return new StarItem(null);
    }

    @Override
    public SelectItem visitQualifiedStarSelectItem(USqlParser.QualifiedStarSelectItemContext ctx) {
        return new StarItem(getIdentifier(ctx.identifier()));
    }

    // ══════════════════════════════════════════════════
    //  Table references
    // ══════════════════════════════════════════════════

    @Override
    public TableRef visitSimpleTableRef(USqlParser.SimpleTableRefContext ctx) {
        String name = getIdentifier(ctx.tableName);
        String alias = ctx.alias != null ? getIdentifier(ctx.alias) : null;
        return new SimpleTable(name, alias);
    }

    @Override
    public TableRef visitSubqueryTableRef(USqlParser.SubqueryTableRefContext ctx) {
        SelectStmt query = visitSelectStatement(ctx.selectStatement());
        String alias = ctx.alias != null ? getIdentifier(ctx.alias) : "sub";
        return new SubqueryTable(query, alias);
    }

    @Override
    public TableRef visitJoinTableRef(USqlParser.JoinTableRefContext ctx) {
        TableRef left = (TableRef) visit(ctx.tableRef(0));
        TableRef right = (TableRef) visit(ctx.tableRef(1));

        JoinType type;
        if (ctx.INNER() != null) type = JoinType.INNER;
        else if (ctx.LEFT() != null) type = JoinType.LEFT;
        else if (ctx.RIGHT() != null) type = JoinType.RIGHT;
        else if (ctx.FULL() != null) type = JoinType.FULL;
        else if (ctx.CROSS() != null) type = JoinType.CROSS;
        else type = JoinType.INNER; // bare JOIN defaults to INNER

        Expression condition = ctx.joinCondition != null ? (Expression) visit(ctx.joinCondition) : null;

        return new JoinTable(left, type, right, condition);
    }

    @Override
    public TableRef visitFunctionTableRef(USqlParser.FunctionTableRefContext ctx) {
        boolean lateral = ctx.LATERAL() != null;
        String funcName = getIdentifier(ctx.functionCall().funcName);
        List<Expression> args = getExprList(ctx.functionCall().exprList());
        String alias = ctx.alias != null ? getIdentifier(ctx.alias) : funcName;
        return new FunctionTable(lateral, funcName, args, alias);
    }

    // ══════════════════════════════════════════════════
    //  WHERE / GROUP BY / HAVING / ORDER BY / FETCH
    // ══════════════════════════════════════════════════

    @Override
    public Expression visitWhereClause(USqlParser.WhereClauseContext ctx) {
        return (Expression) visit(ctx.expr());
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<GroupByItem> visitGroupByClause(USqlParser.GroupByClauseContext ctx) {
        return ctx.groupByItem().stream()
            .map(g -> (GroupByItem) visit(g))
            .collect(Collectors.toList());
    }

    @Override
    public GroupByItem visitPlainGroupBy(USqlParser.PlainGroupByContext ctx) {
        return new GroupByItem((Expression) visit(ctx.expr()), GroupByKind.PLAIN);
    }

    @Override
    public GroupByItem visitRollupGroupBy(USqlParser.RollupGroupByContext ctx) {
        var args = ctx.expr().stream().map(e -> (Expression) visit(e)).collect(Collectors.toList());
        return new GroupByItem(new FunctionCall("ROLLUP", args, false, null, null), GroupByKind.ROLLUP);
    }

    @Override
    public GroupByItem visitCubeGroupBy(USqlParser.CubeGroupByContext ctx) {
        var args = ctx.expr().stream().map(e -> (Expression) visit(e)).collect(Collectors.toList());
        return new GroupByItem(new FunctionCall("CUBE", args, false, null, null), GroupByKind.CUBE);
    }

    @Override
    public GroupByItem visitGroupingSetsGroupBy(USqlParser.GroupingSetsGroupByContext ctx) {
        var args = ctx.expr().stream().map(e -> (Expression) visit(e)).collect(Collectors.toList());
        return new GroupByItem(new FunctionCall("GROUPING SETS", args, false, null, null), GroupByKind.GROUPING_SETS);
    }

    @Override
    public Expression visitHavingClause(USqlParser.HavingClauseContext ctx) {
        return (Expression) visit(ctx.expr());
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<OrderByItem> visitOrderByClause(USqlParser.OrderByClauseContext ctx) {
        return ctx.orderByItem().stream()
            .map(o -> (OrderByItem) visit(o))
            .collect(Collectors.toList());
    }

    @Override
    public OrderByItem visitOrderByItem(USqlParser.OrderByItemContext ctx) {
        Expression expr = (Expression) visit(ctx.expr());
        boolean desc = ctx.DESC() != null;
        boolean nullsFirst = ctx.NULLS() != null && ctx.FIRST() != null;
        return new OrderByItem(expr, desc, nullsFirst);
    }

    @Override
    public FetchClause visitFetchClause(USqlParser.FetchClauseContext ctx) {
        Expression limit = null;
        Expression offset = null;

        if (ctx.LIMIT() != null) {
            limit = (Expression) visit(ctx.expr(0));
            if (ctx.OFFSET() != null) offset = (Expression) visit(ctx.expr(1));
        } else if (ctx.OFFSET() != null && ctx.LIMIT() != null) {
            offset = (Expression) visit(ctx.expr(0));
            limit = (Expression) visit(ctx.expr(1));
        }

        return new FetchClause(limit, offset);
    }

    // ══════════════════════════════════════════════════
    //  Expressions
    // ══════════════════════════════════════════════════

    @Override
    public Expression visitLiteralExpr(USqlParser.LiteralExprContext ctx) {
        return (Expression) visit(ctx.literal());
    }

    @Override
    public Expression visitColumnRefExpr(USqlParser.ColumnRefExprContext ctx) {
        if (ctx.identifier().size() == 2) {
            String qual = getIdentifier(ctx.identifier(0));
            String name = getIdentifier(ctx.identifier(1));
            return new ColumnRef(List.of(qual), name);
        }
        String name = getIdentifier(ctx.identifier(0));
        return new ColumnRef(List.of(), name);
    }

    @Override
    public Expression visitStarExpr(USqlParser.StarExprContext ctx) {
        return new StarExpr(null);
    }

    @Override
    public Expression visitQualifiedStarExpr(USqlParser.QualifiedStarExprContext ctx) {
        return new StarExpr(getIdentifier(ctx.identifier()));
    }

    @Override
    public Expression visitParameterExpr(USqlParser.ParameterExprContext ctx) {
        return (Expression) visit(ctx.parameter());
    }

    @Override
    public Expression visitFunctionCallExpr(USqlParser.FunctionCallExprContext ctx) {
        return (Expression) visit(ctx.functionCall());
    }

    @Override
    public Expression visitParenExpr(USqlParser.ParenExprContext ctx) {
        return (Expression) visit(ctx.expr());
    }

    @Override
    public Expression visitSubqueryExpr(USqlParser.SubqueryExprContext ctx) {
        return new SubqueryExpr(visitSelectStatement(ctx.selectStatement()));
    }

    @Override
    public Expression visitUnaryOpExpr(USqlParser.UnaryOpExprContext ctx) {
        Expression operand = (Expression) visit(ctx.expr());
        if (ctx.op.getText().equals("-")) return new UnaryOp(UnOp.NEG, operand);
        return new UnaryOp(UnOp.NEG, operand); // + is no-op, treat as identity
    }

    @Override
    public Expression visitNotExpr(USqlParser.NotExprContext ctx) {
        return new UnaryOp(UnOp.NOT, (Expression) visit(ctx.expr()));
    }

    @Override
    public Expression visitExistsExpr(USqlParser.ExistsExprContext ctx) {
        return new UnaryOp(UnOp.EXISTS, new SubqueryExpr(visitSelectStatement(ctx.selectStatement())));
    }

    @Override
    public Expression visitMulDivExpr(USqlParser.MulDivExprContext ctx) {
        Expression left = (Expression) visit(ctx.expr(0));
        Expression right = (Expression) visit(ctx.expr(1));
        BinOp op = switch (ctx.op.getText()) {
            case "*" -> BinOp.MUL; case "/" -> BinOp.DIV; default -> BinOp.MOD;
        };
        return new BinaryOp(left, op, right);
    }

    @Override
    public Expression visitAddSubExpr(USqlParser.AddSubExprContext ctx) {
        Expression left = (Expression) visit(ctx.expr(0));
        Expression right = (Expression) visit(ctx.expr(1));
        BinOp op = ctx.op.getText().equals("+") ? BinOp.ADD : BinOp.SUB;
        return new BinaryOp(left, op, right);
    }

    @Override
    public Expression visitConcatExpr(USqlParser.ConcatExprContext ctx) {
        Expression left = (Expression) visit(ctx.expr(0));
        Expression right = (Expression) visit(ctx.expr(1));
        return new BinaryOp(left, BinOp.CONCAT, right);
    }

    @Override
    public Expression visitComparisonExpr(USqlParser.ComparisonExprContext ctx) {
        Expression left = (Expression) visit(ctx.expr(0));
        Expression right = (Expression) visit(ctx.expr(1));
        BinOp op = switch (ctx.op.getText()) {
            case "=" -> BinOp.EQ; case "!=", "<>" -> BinOp.NEQ;
            case "<" -> BinOp.LT; case ">" -> BinOp.GT;
            case "<=" -> BinOp.LTE; case ">=" -> BinOp.GTE;
            default -> BinOp.EQ;
        };
        return new BinaryOp(left, op, right);
    }

    @Override
    public Expression visitIsNullExpr(USqlParser.IsNullExprContext ctx) {
        return new IsNullExpr((Expression) visit(ctx.expr()), ctx.NOT() != null);
    }

    @Override
    public Expression visitIsBoolExpr(USqlParser.IsBoolExprContext ctx) {
        Expression expr = (Expression) visit(ctx.expr());
        boolean not = ctx.NOT() != null;
        if (ctx.TRUE() != null) return not ? new UnaryOp(UnOp.IS_NOT_TRUE, expr) : new UnaryOp(UnOp.IS_TRUE, expr);
        return not ? new UnaryOp(UnOp.IS_NOT_FALSE, expr) : new UnaryOp(UnOp.IS_FALSE, expr);
    }

    @Override
    public Expression visitBetweenExpr(USqlParser.BetweenExprContext ctx) {
        Expression expr = (Expression) visit(ctx.expr(0));
        Expression low = (Expression) visit(ctx.expr(1));
        Expression high = (Expression) visit(ctx.expr(2));
        return new BetweenExpr(expr, low, high, ctx.NOT() != null);
    }

    @Override
    public Expression visitInExpr(USqlParser.InExprContext ctx) {
        Expression expr = (Expression) visit(ctx.expr());
        boolean not = ctx.NOT() != null;
        if (ctx.exprList() != null) {
            List<Expression> values = getExprList(ctx.exprList());
            return new InListExpr(expr, values, null, not);
        }
        SelectStmt subquery = visitSelectStatement(ctx.selectStatement());
        return new InListExpr(expr, null, subquery, not);
    }

    @Override
    public Expression visitLikeExpr(USqlParser.LikeExprContext ctx) {
        Expression left = (Expression) visit(ctx.expr(0));
        Expression right = (Expression) visit(ctx.expr(1));
        BinOp op = ctx.NOT() != null ? BinOp.NOT_LIKE : BinOp.LIKE;
        return new BinaryOp(left, op, right);
    }

    @Override
    public Expression visitAndExpr(USqlParser.AndExprContext ctx) {
        return new BinaryOp((Expression) visit(ctx.expr(0)), BinOp.AND, (Expression) visit(ctx.expr(1)));
    }

    @Override
    public Expression visitOrExpr(USqlParser.OrExprContext ctx) {
        return new BinaryOp((Expression) visit(ctx.expr(0)), BinOp.OR, (Expression) visit(ctx.expr(1)));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Expression visitCaseExpr(USqlParser.CaseExprContext ctx) {
        List<WhenClause> whens = ctx.whenClause().stream()
            .map(w -> (WhenClause) visit(w))
            .collect(Collectors.toList());
        Expression elseExpr = ctx.expr() != null ? (Expression) visit(ctx.expr()) : null;
        return new CaseExpr(whens, elseExpr);
    }

    @Override
    public WhenClause visitWhenClause(USqlParser.WhenClauseContext ctx) {
        return new WhenClause((Expression) visit(ctx.expr(0)), (Expression) visit(ctx.expr(1)));
    }

    @Override
    public Expression visitCastExpr(USqlParser.CastExprContext ctx) {
        Expression expr = (Expression) visit(ctx.expr());
        int[] typeInfo = extractTypeInfo(ctx.dataType());
        return new CastExpr(expr, ctx.dataType().getText().split("\\(")[0], typeInfo[0], typeInfo[1]);
    }

    // ══════════════════════════════════════════════════
    //  Function calls
    // ══════════════════════════════════════════════════

    @Override
    public Expression visitFunctionCall(USqlParser.FunctionCallContext ctx) {
        String name = getIdentifier(ctx.funcName);
        boolean star = ctx.STAR() != null;
        List<Expression> args = ctx.exprList() != null ? getExprList(ctx.exprList()) : List.of();
        // KEEP clause
        KeepClause keep = null;
        if (ctx.keepClause() != null) {
            var kc = ctx.keepClause();
            boolean last = kc.LAST() != null;
            List<OrderByItem> orderBy = visitOrderByClause(kc.orderByClause());
            keep = new KeepClause(last, orderBy);
        }
        // OVER clause
        WindowOver over = null;
        if (ctx.overClause() != null) {
            var oc = ctx.overClause();
            List<Expression> partitionBy = null;
            if (oc.PARTITION() != null) {
                partitionBy = oc.expr().stream()
                    .map(e -> (Expression) visit(e))
                    .collect(Collectors.toList());
            }
            List<OrderByItem> orderBy = null;
            if (oc.orderByClause() != null) {
                orderBy = visitOrderByClause(oc.orderByClause());
            }
            WindowFrame frame = null;
            if (oc.frameClause() != null) {
                frame = parseFrameClause(oc.frameClause());
            }
            over = new WindowOver(partitionBy, orderBy, frame);
        }
        return new FunctionCall(name, args, star, keep, over);
    }

    private static WindowFrame parseFrameClause(USqlParser.FrameClauseContext ctx) {
        var unit = ctx.ROWS() != null ? WindowFrame.Unit.ROWS : WindowFrame.Unit.RANGE;
        var fb = ctx.frameBound();
        if (fb instanceof USqlParser.FrameBetweenUbAndCurrentContext)
            return new WindowFrame.Between(unit, Bound.UNBOUNDED_PRECEDING, Bound.CURRENT_ROW);
        if (fb instanceof USqlParser.FrameBetweenUbAndUbContext)
            return new WindowFrame.Between(unit, Bound.UNBOUNDED_PRECEDING, Bound.UNBOUNDED_FOLLOWING);
        if (fb instanceof USqlParser.FrameBetweenCurrentAndUbContext)
            return new WindowFrame.Between(unit, Bound.CURRENT_ROW, Bound.UNBOUNDED_FOLLOWING);
        if (fb instanceof USqlParser.FrameUnboundedPrecedingContext)
            return new WindowFrame.Single(unit, Bound.UNBOUNDED_PRECEDING);
        return new WindowFrame.Single(unit, Bound.CURRENT_ROW);
    }

    // ══════════════════════════════════════════════════
    //  Literals
    // ══════════════════════════════════════════════════

    @Override
    public Expression visitLiteral(USqlParser.LiteralContext ctx) {
        if (ctx.INT_LITERAL() != null) return new IntLiteral(Long.parseLong(ctx.INT_LITERAL().getText()));
        if (ctx.FLOAT_LITERAL() != null) return new FloatLiteral(Double.parseDouble(ctx.FLOAT_LITERAL().getText()));
        if (ctx.STRING_LITERAL() != null) {
            String s = ctx.STRING_LITERAL().getText();
            return new StringLiteral(s.substring(1, s.length() - 1).replace("''", "'"));
        }
        if (ctx.TRUE() != null) return new BoolLiteral(true);
        if (ctx.FALSE() != null) return new BoolLiteral(false);
        if (ctx.NULL() != null) return new NullLiteral();
        return new NullLiteral();
    }

    @Override
    public Expression visitParameter(USqlParser.ParameterContext ctx) {
        if (ctx.COLON() != null) return new ParamRef(getIdentifier(ctx.identifier()));
        return new ParamRef("?" + (ctx.QMARK() != null ? "?" : ""));
    }

    // ══════════════════════════════════════════════════
    //  DML statements
    // ══════════════════════════════════════════════════

    @Override
    @SuppressWarnings("unchecked")
    public InsertStmt visitInsertStatement(USqlParser.InsertStatementContext ctx) {
        boolean ignore = ctx.IGNORE() != null;
        TableRef table = (TableRef) visit(ctx.tableRef());
        List<String> columns = ctx.columnList() != null ? getColumnList(ctx.columnList()) : null;
        List<List<Expression>> values = null;
        SelectStmt selectSource = null;

        if (ctx.selectStatement() != null) {
            selectSource = visitSelectStatement(ctx.selectStatement());
        } else if (ctx.exprList() != null && !ctx.exprList().isEmpty()) {
            values = new ArrayList<>();
            for (var exprList : ctx.exprList()) {
                values.add(getExprList(exprList));
            }
        }

        return new InsertStmt(ignore, table, columns, values, selectSource);
    }

    @Override
    @SuppressWarnings("unchecked")
    public UpdateStmt visitUpdateStatement(USqlParser.UpdateStatementContext ctx) {
        TableRef table = (TableRef) visit(ctx.tableRef());
        List<SetClause> sets = ctx.setClause().stream()
            .map(s -> (SetClause) visit(s))
            .collect(Collectors.toList());
        Expression where = ctx.whereClause() != null ? visitWhereClause(ctx.whereClause()) : null;
        return new UpdateStmt(table, sets, where);
    }

    @Override
    public SetClause visitSetClause(USqlParser.SetClauseContext ctx) {
        return new SetClause(getIdentifier(ctx.identifier()), (Expression) visit(ctx.expr()));
    }

    @Override
    public DeleteStmt visitDeleteStatement(USqlParser.DeleteStatementContext ctx) {
        TableRef table = (TableRef) visit(ctx.tableRef());
        Expression where = ctx.whereClause() != null ? visitWhereClause(ctx.whereClause()) : null;
        return new DeleteStmt(table, where);
    }

    @Override
    public MergeStmt visitMergeStatement(USqlParser.MergeStatementContext ctx) {
        TableRef target = (TableRef) visit(ctx.tableRef(0));
        String targetAlias = ctx.alias != null ? getIdentifier(ctx.alias) : null;
        TableRef source = (TableRef) visit(ctx.tableRef(1));
        Expression onCondition = (Expression) visit(ctx.expr());

        List<MergeAction> actions = new ArrayList<>();
        if (ctx.mergeInsert() != null) {
            for (var insCtx : ctx.mergeInsert()) {
                actions.add((MergeAction) visit(insCtx));
            }
        }
        if (ctx.mergeUpdate() != null) {
            for (var updCtx : ctx.mergeUpdate()) {
                actions.add((MergeAction) visit(updCtx));
            }
        }
        if (ctx.mergeDelete() != null) {
            for (var delCtx : ctx.mergeDelete()) {
                actions.add((MergeAction) visit(delCtx));
            }
        }
        return new MergeStmt(target, targetAlias, source, onCondition, actions);
    }

    @Override
    public MergeInsert visitMergeInsert(USqlParser.MergeInsertContext ctx) {
        List<String> columns = ctx.columnList() != null ? getColumnList(ctx.columnList()) : List.of();
        List<Expression> values = getExprList(ctx.exprList());
        return new MergeInsert(columns, values);
    }

    @Override
    public MergeUpdate visitMergeUpdate(USqlParser.MergeUpdateContext ctx) {
        List<SetClause> sets = ctx.setClause().stream()
            .map(s -> (SetClause) visit(s))
            .collect(Collectors.toList());
        return new MergeUpdate(sets);
    }

    @Override
    public MergeDelete visitMergeDelete(USqlParser.MergeDeleteContext ctx) {
        return new MergeDelete();
    }

    // ══════════════════════════════════════════════════
    //  CREATE TABLE
    // ══════════════════════════════════════════════════

    @Override
    @SuppressWarnings("unchecked")
    public CreateTableStmt visitCreateTableStatement(USqlParser.CreateTableStatementContext ctx) {
        boolean ifNotExists = ctx.EXISTS() != null;
        String tableName = getIdentifier(ctx.tableName);

        List<ColumnDef> columns = ctx.columnDef().stream()
            .map(c -> (ColumnDef) visit(c))
            .collect(Collectors.toList());

        List<TableConstraint> constraints = ctx.tableConstraint().stream()
            .map(c -> (TableConstraint) visit(c))
            .collect(Collectors.toList());

        TableOptions options = ctx.tableOptions() != null ? visitTableOptions(ctx.tableOptions()) : null;

        return new CreateTableStmt(ifNotExists, tableName, columns, constraints, options);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ColumnDef visitColumnDef(USqlParser.ColumnDefContext ctx) {
        String name = getIdentifier(ctx.columnName);
        int[] typeInfo = extractTypeInfo(ctx.dataType());
        String typeName = ctx.dataType().getText().split("\\(")[0];

        // Extract ENUM values
        List<String> enumValues = List.of();
        if ("ENUM".equalsIgnoreCase(typeName)) {
            String text = ctx.dataType().getText();
            int lp = text.indexOf('(');
            int rp = text.lastIndexOf(')');
            if (lp >= 0 && rp > lp) {
                String valPart = text.substring(lp + 1, rp);
                enumValues = java.util.Arrays.stream(valPart.split(","))
                    .map(s -> s.trim().replaceAll("^'|'$", ""))
                    .collect(Collectors.toList());
            }
        }

        List<ColumnConstraint> constraints = ctx.columnConstraint().stream()
            .map(c -> (ColumnConstraint) visit(c))
            .collect(Collectors.toList());

        Expression defaultVal = ctx.defaultValue != null ? (Expression) visit(ctx.defaultValue) : null;

        return new ColumnDef(name, typeName, typeInfo[0], typeInfo[1], enumValues, constraints, defaultVal);
    }

    @Override
    public ColumnConstraint visitNotNullConstraint(USqlParser.NotNullConstraintContext ctx) {
        return new NotNullConstraint();
    }

    @Override
    public ColumnConstraint visitNullConstraint(USqlParser.NullConstraintContext ctx) {
        return new NullConstraint();
    }

    @Override
    public ColumnConstraint visitPrimaryKeyConstraint(USqlParser.PrimaryKeyConstraintContext ctx) {
        return new PrimaryKeyConstraint(ctx.AUTO_INCREMENT() != null || ctx.IDENTITY() != null);
    }

    @Override
    public ColumnConstraint visitUniqueConstraint(USqlParser.UniqueConstraintContext ctx) {
        return new UniqueConstraint();
    }

    @Override
    public ColumnConstraint visitCheckConstraint(USqlParser.CheckConstraintContext ctx) {
        return new CheckConstraint((Expression) visit(ctx.expr()));
    }

    @Override
    public ColumnConstraint visitReferencesConstraint(USqlParser.ReferencesConstraintContext ctx) {
        String targetTable = getIdentifier(ctx.identifier(0));
        String targetColumn = getIdentifier(ctx.identifier(1));
        String onUpdate = null, onDelete = null;
        // Parse fkAction if present
        return new ReferencesConstraint(targetTable, targetColumn, onUpdate, onDelete);
    }

    @Override
    public ColumnConstraint visitGeneratedConstraint(USqlParser.GeneratedConstraintContext ctx) {
        boolean virtual = ctx.STORED() == null; // default is VIRTUAL
        return new GeneratedConstraint(true, virtual, (Expression) visit(ctx.expr()));
    }

    @Override
    public TableConstraint visitTbPrimaryKey(USqlParser.TbPrimaryKeyContext ctx) {
        String name = getConstraintName(ctx);
        return new TbPrimaryKey(getColumnList(ctx.columnList()), name);
    }

    @Override
    public TableConstraint visitTbUnique(USqlParser.TbUniqueContext ctx) {
        String name = getConstraintName(ctx);
        return new TbUnique(getColumnList(ctx.columnList()), name);
    }

    @Override
    public TableConstraint visitTbForeignKey(USqlParser.TbForeignKeyContext ctx) {
        String name = getConstraintName(ctx);
        List<String> columns = getColumnList(ctx.columnList(0));
        String targetTable = getIdentifier(ctx.refTable);
        List<String> targetColumns = getColumnList(ctx.columnList(1));
        return new TbForeignKey(columns, targetTable, targetColumns, name, null, null);
    }

    @Override
    public TableConstraint visitTbCheck(USqlParser.TbCheckContext ctx) {
        String name = getConstraintName(ctx);
        return new TbCheck((Expression) visit(ctx.expr()), name);
    }

    @Override
    public TableOptions visitTableOptions(USqlParser.TableOptionsContext ctx) {
        String engine = null, tablespace = null, charset = null, collation = null, comment = null;
        for (var opt : ctx.tableOption()) {
            if (opt.ENGINE() != null) engine = getIdentifier(opt.identifier());
            else if (opt.TABLESPACE() != null) tablespace = getIdentifier(opt.identifier());
            else if (opt.CHARACTER() != null) charset = getIdentifier(opt.identifier());
            else if (opt.COLLATE() != null) collation = getIdentifier(opt.identifier());
            else if (opt.COMMENT() != null) {
                String s = opt.STRING_LITERAL().getText();
                comment = s.substring(1, s.length() - 1);
            }
        }
        return new TableOptions(engine, tablespace, charset, collation, comment);
    }

    // ══════════════════════════════════════════════════
    //  CREATE INDEX
    // ══════════════════════════════════════════════════

    @Override
    @SuppressWarnings("unchecked")
    public CreateIndexStmt visitCreateIndexStatement(USqlParser.CreateIndexStatementContext ctx) {
        boolean unique = ctx.UNIQUE() != null;
        boolean ifNotExists = ctx.EXISTS() != null;
        String name = getIdentifier(ctx.name);
        String tableName = getIdentifier(ctx.tableName);
        List<IndexColumn> columns = ctx.indexColumn().stream()
            .map(c -> (IndexColumn) visit(c))
            .collect(Collectors.toList());
        Expression where = ctx.whereClause() != null ? visitWhereClause(ctx.whereClause()) : null;
        return new CreateIndexStmt(unique, ifNotExists, name, tableName, columns, where);
    }

    @Override
    public IndexColumn visitIndexColumn(USqlParser.IndexColumnContext ctx) {
        String name = getIdentifier(ctx.identifier());
        boolean desc = ctx.DESC() != null;
        boolean nullsFirst = ctx.NULLS() != null && ctx.FIRST() != null;
        return new IndexColumn(name, desc, nullsFirst);
    }

    // ══════════════════════════════════════════════════
    //  Stored Procedures
    // ══════════════════════════════════════════════════

    public CreateProcedureStmt visitCreateProcedureStatement(USqlParser.CreateProcedureStatementContext ctx) {
        String name = getIdentifier(ctx.identifier());
        boolean orReplace = ctx.REPLACE() != null;
        List<ParamDef> params = new ArrayList<>();
        if (ctx.procedureParam() != null) {
            for (var p : ctx.procedureParam()) {
                params.add(visitProcedureParam(p));
            }
        }
        String body = ctx.body != null ? stripQuotes(ctx.body.getText()) : null;
        return new CreateProcedureStmt(name, params, orReplace, body);
    }

    public CreateFunctionStmt visitCreateFunctionStatement(USqlParser.CreateFunctionStatementContext ctx) {
        String name = getIdentifier(ctx.identifier());
        boolean orReplace = ctx.REPLACE() != null;
        List<ParamDef> params = new ArrayList<>();
        if (ctx.procedureParam() != null) {
            for (var p : ctx.procedureParam()) {
                params.add(visitProcedureParam(p));
            }
        }
        DataTypeDecl returnType = parseDataType(ctx.dataType());
        String body = ctx.body != null ? stripQuotes(ctx.body.getText()) : null;
        return new CreateFunctionStmt(name, params, returnType, orReplace, body);
    }

    public CallStmt visitCallStatement(USqlParser.CallStatementContext ctx) {
        String name = getIdentifier(ctx.identifier());
        List<Expression> args = new ArrayList<>();
        if (ctx.expr() != null) {
            for (var e : ctx.expr()) {
                args.add((Expression) visit(e));
            }
        }
        return new CallStmt(name, args);
    }

    public ParamDef visitProcedureParam(USqlParser.ProcedureParamContext ctx) {
        String name = getIdentifier(ctx.paramName);
        DataTypeDecl type = parseDataType(ctx.dataType());
        ParamDir dir = ParamDir.IN;
        if (ctx.INOUT() != null) dir = ParamDir.INOUT;
        else if (ctx.OUT() != null) dir = ParamDir.OUT;
        else if (ctx.IN() != null) dir = ParamDir.IN;
        return new ParamDef(name, type, dir);
    }

    private DataTypeDecl parseDataType(USqlParser.DataTypeContext ctx) {
        String name = ctx.getChild(0).getText();
        int precision = ctx.precision != null
            ? Integer.parseInt(ctx.precision.getText()) : 0;
        int scale = ctx.scale != null
            ? Integer.parseInt(ctx.scale.getText()) : 0;
        return new DataTypeDecl(name, precision, scale);
    }

    private String stripQuotes(String s) {
        if (s == null) return null;
        if (s.startsWith("'") && s.endsWith("'")) return s.substring(1, s.length() - 1);
        return s;
    }

    // ══════════════════════════════════════════════════
    //  DROP / TRUNCATE / ALTER TABLE
    // ══════════════════════════════════════════════════

    public DropTableStmt visitDropTableStatement(USqlParser.DropTableStatementContext ctx) {
        String name = getIdentifier(ctx.tableName);
        boolean ifExists = ctx.EXISTS() != null;
        boolean cascade = ctx.CASCADE() != null;
        return new DropTableStmt(name, ifExists, cascade);
    }

    public DropIndexStmt visitDropIndexStatement(USqlParser.DropIndexStatementContext ctx) {
        String indexName = getIdentifier(ctx.indexName);
        String tableName = ctx.tableName != null ? getIdentifier(ctx.tableName) : null;
        boolean ifExists = ctx.EXISTS() != null;
        return new DropIndexStmt(indexName, tableName, ifExists);
    }

    public DropDatabaseStmt visitDropDatabaseStatement(USqlParser.DropDatabaseStatementContext ctx) {
        return new DropDatabaseStmt(getIdentifier(ctx.dbName), ctx.EXISTS() != null);
    }

    public CreateViewStmt visitCreateViewStatement(USqlParser.CreateViewStatementContext ctx) {
        return new CreateViewStmt(getIdentifier(ctx.viewName), visitSelectStatement(ctx.selectStatement()));
    }

    public CreateSchemaStmt visitCreateSchemaStatement(USqlParser.CreateSchemaStatementContext ctx) {
        return new CreateSchemaStmt(getIdentifier(ctx.schemaName));
    }

    public TruncateStmt visitTruncateStatement(USqlParser.TruncateStatementContext ctx) {
        return new TruncateStmt(getIdentifier(ctx.tableName));
    }

    public AlterTableStmt visitAlterTableStatement(USqlParser.AlterTableStatementContext ctx) {
        String name = getIdentifier(ctx.tableName);
        AlterAction action = (AlterAction) visit(ctx.alterAction());
        return new AlterTableStmt(name, action);
    }

    public AlterAction visitAddColumn(USqlParser.AddColumnContext ctx) {
        USqlParser.ColumnDefContext cd = ctx.columnDef();
        String name = getIdentifier(cd.columnName);
        DataTypeDecl type = parseDataType(cd.dataType());
        List<ColumnConstraint> constraints = new ArrayList<>();
        for (var cc : cd.columnConstraint()) {
            constraints.add((ColumnConstraint) visit(cc));
        }
        Expression defaultVal = cd.defaultValue != null ? (Expression) visit(cd.defaultValue) : null;
        return new AddColumn(name, type, constraints, defaultVal);
    }

    public AlterAction visitDropColumn(USqlParser.DropColumnContext ctx) {
        return new DropColumn(getIdentifier(ctx.identifier()));
    }

    public AlterAction visitAlterColumnType(USqlParser.AlterColumnTypeContext ctx) {
        return new AlterColumnType(getIdentifier(ctx.col), parseDataType(ctx.dataType()));
    }

    public AlterAction visitAlterColumnSetDefault(USqlParser.AlterColumnSetDefaultContext ctx) {
        return new AlterColumnSetDefault(getIdentifier(ctx.col), (Expression) visit(ctx.expr()));
    }

    public AlterAction visitAlterColumnDropDefault(USqlParser.AlterColumnDropDefaultContext ctx) {
        return new AlterColumnDropDefault(getIdentifier(ctx.col));
    }

    public AlterAction visitRenameColumn(USqlParser.RenameColumnContext ctx) {
        return new RenameColumn(getIdentifier(ctx.old), getIdentifier(ctx.newName));
    }

    // ══════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private List<Expression> getExprList(USqlParser.ExprListContext ctx) {
        if (ctx == null) return List.of();
        return ctx.expr().stream()
            .map(e -> (Expression) visit(e))
            .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private List<String> getColumnList(USqlParser.ColumnListContext ctx) {
        return ctx.identifier().stream()
            .map(this::getIdentifier)
            .collect(Collectors.toList());
    }

    private String getIdentifier(USqlParser.IdentifierContext ctx) {
        if (ctx.IDENTIFIER() != null) return ctx.IDENTIFIER().getText();
        if (ctx.STRING_LITERAL() != null) {
            String s = ctx.STRING_LITERAL().getText();
            return s.substring(1, s.length() - 1);
        }
        if (ctx.BACKTICK_ID() != null) {
            String s = ctx.BACKTICK_ID().getText();
            return s.substring(1, s.length() - 1);
        }
        return ctx.getText().toUpperCase();
    }

    /** Extract precision and scale from a dataType context */
    private int[] extractTypeInfo(USqlParser.DataTypeContext ctx) {
        int precision = 0, scale = 0;
        if (ctx.precision != null) precision = Integer.parseInt(ctx.precision.getText());
        if (ctx.scale != null) scale = Integer.parseInt(ctx.scale.getText());
        if (ctx.length != null) precision = Integer.parseInt(ctx.length.getText());
        if (ctx.bits != null) precision = Integer.parseInt(ctx.bits.getText());
        if (ctx.frac != null) scale = Integer.parseInt(ctx.frac.getText());
        return new int[]{precision, scale};
    }

    /** Walk up to the parent tableConstraint to get the optional CONSTRAINT name */
    private String getConstraintName(ParserRuleContext ctx) {
        if (ctx.getParent() instanceof USqlParser.TableConstraintContext parent) {
            if (parent.name != null) return getIdentifier(parent.name);
        }
        return null;
    }

    // ══════════════════════════════════════════════════
    //  Convenience factory methods (for tests)
    // ══════════════════════════════════════════════════

    public static ExprItem col(String name) { return new ExprItem(new ColumnRef(List.of(), name), null); }
    public static ExprItem col(String qual, String name) { return new ExprItem(new ColumnRef(List.of(qual), name), null); }
    public static ExprItem col(String qual, String name, String alias) { return new ExprItem(new ColumnRef(List.of(qual), name), alias); }
    public static ExprItem func(String name, List<Expression> args, String alias) { return new ExprItem(new FunctionCall(name, args, false, null, null), alias); }
    public static StarItem star() { return new StarItem(null); }
    public static SimpleTable table(String name) { return new SimpleTable(name, null); }
    public static SimpleTable table(String name, String alias) { return new SimpleTable(name, alias); }
    public static JoinTable join(TableRef left, JoinType type, TableRef right, Expression on) { return new JoinTable(left, type, right, on); }
    public static BinaryOp eq(Expression left, Expression right) { return new BinaryOp(left, BinOp.EQ, right); }
    public static BinaryOp gt(Expression left, Expression right) { return new BinaryOp(left, BinOp.GT, right); }
    public static IntLiteral num(long v) { return new IntLiteral(v); }
    public static StringLiteral str(String v) { return new StringLiteral(v); }
}
