package com.usql.parser;

import com.usql.ast.USqlAst;
import com.usql.ast.USqlAst.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Converts a USql parse tree (antlr4 CST) into the clean USqlAst.
 *
 * Requires antlr4-generated classes from USql.g4 (com.usql.parser package).
 * Once antlr4:generate runs, extend USqlBaseVisitor<T> here.
 *
 * For now, the main walking logic is inline; after antlr4 runs,
 * swap to extending USqlBaseVisitor<Object> and override visit* methods.
 */
public class AstBuilder {

    // ══════════════════════════════════════════════════
    //  Public entry — to be called after parse
    // ══════════════════════════════════════════════════

    /**
     * Build the AST from a parsed program context.
     * Call this after: parser.program() → ProgramContext
     */
    public static List<Statement> build(Object programCtx) {
        // After antlr4 generation:
        // ProgramContext ctx = (ProgramContext) programCtx;
        // return ctx.statement().stream()
        //     .map(AstBuilder::visitStatement)
        //     .collect(Collectors.toList());
        throw new UnsupportedOperationException(
            "AstBuilder requires antlr4-generated classes. Run 'mvn generate-sources' first.");
    }

    // ══════════════════════════════════════════════════
    //  Statement visitors
    // ══════════════════════════════════════════════════

    // After antlr4 generation, uncomment and implement:
    //
    // @Override
    // public Statement visitSelectStatement(USqlParser.SelectStatementContext ctx) { ... }
    // @Override
    // public Statement visitInsertStatement(USqlParser.InsertStatementContext ctx) { ... }
    // etc.

    // ══════════════════════════════════════════════════
    //  Manual IR construction helpers (for MVP without antlr4)
    // ══════════════════════════════════════════════════

    /**
     * Build a simple SELECT statement manually (used in tests until parser is connected).
     */
    public static SelectStmt select(
        List<SelectItem> projections,
        List<TableRef> from,
        Expression where,
        List<OrderByItem> orderBy,
        FetchClause fetch
    ) {
        return new SelectStmt(
            null, false, projections, from, where,
            null, null, orderBy, fetch, null, null
        );
    }

    // ── Convenience factory methods ──

    public static ExprItem col(String name) {
        return new ExprItem(new ColumnRef(List.of(), name), null);
    }

    public static ExprItem col(String qualifier, String name) {
        return new ExprItem(new ColumnRef(List.of(qualifier), name), null);
    }

    public static ExprItem col(String qualifier, String name, String alias) {
        return new ExprItem(new ColumnRef(List.of(qualifier), name), alias);
    }

    public static ExprItem func(String name, List<Expression> args, String alias) {
        return new ExprItem(new FunctionCall(name, args, false), alias);
    }

    public static ExprItem star() {
        return new StarItem(null);
    }

    public static SimpleTable table(String name) {
        return new SimpleTable(name, null);
    }

    public static SimpleTable table(String name, String alias) {
        return new SimpleTable(name, alias);
    }

    public static JoinTable join(TableRef left, JoinType type, TableRef right, Expression on) {
        return new JoinTable(left, type, right, on);
    }

    public static BinaryOp eq(Expression left, Expression right) {
        return new BinaryOp(left, BinOp.EQ, right);
    }

    public static BinaryOp gt(Expression left, Expression right) {
        return new BinaryOp(left, BinOp.GT, right);
    }

    public static BinaryOp and(Expression left, Expression right) {
        return new BinaryOp(left, BinOp.AND, right);
    }

    public static OrderByItem asc(Expression expr) {
        return new OrderByItem(expr, false, false);
    }

    public static OrderByItem desc(Expression expr) {
        return new OrderByItem(expr, true, false);
    }

    public static IntLiteral num(long v) { return new IntLiteral(v); }
    public static StringLiteral str(String v) { return new StringLiteral(v); }
    public static BoolLiteral bool(boolean v) { return new BoolLiteral(v); }
    public static NullLiteral nil() { return new NullLiteral(); }

    // ══════════════════════════════════════════════════
    //  Debug: pretty-print AST
    // ══════════════════════════════════════════════════

    public static String prettyPrint(Statement stmt) {
        var sb = new StringBuilder();
        prettyPrint(stmt, sb, 0);
        return sb.toString();
    }

    private static void prettyPrint(Statement stmt, StringBuilder sb, int indent) {
        String pad = "  ".repeat(indent);
        switch (stmt) {
            case SelectStmt s -> {
                sb.append(pad).append("SELECT\n");
                for (var p : s.projections()) {
                    sb.append(pad).append("  ");
                    prettyPrintSelectItem(p, sb);
                    sb.append('\n');
                }
                sb.append(pad).append("FROM\n");
                for (var f : s.from()) {
                    prettyPrintTableRef(f, sb, indent + 1);
                }
                if (s.where() != null) {
                    sb.append(pad).append("WHERE ");
                    prettyPrintExpr(s.where(), sb);
                    sb.append('\n');
                }
                if (s.fetch() != null) {
                    sb.append(pad).append("LIMIT ").append(s.fetch().limit())
                      .append(" OFFSET ").append(s.fetch().offset()).append('\n');
                }
            }
            default -> sb.append(pad).append(stmt.getClass().getSimpleName()).append('\n');
        }
    }

    private static void prettyPrintSelectItem(SelectItem item, StringBuilder sb) {
        switch (item) {
            case ExprItem e -> {
                prettyPrintExpr(e.expr(), sb);
                if (e.alias() != null) sb.append(" AS ").append(e.alias());
            }
            case StarItem s -> sb.append('*');
        }
    }

    private static void prettyPrintTableRef(TableRef ref, StringBuilder sb, int indent) {
        String pad = "  ".repeat(indent);
        switch (ref) {
            case SimpleTable t -> sb.append(pad).append(t.name()).append('\n');
            case JoinTable j -> {
                prettyPrintTableRef(j.left(), sb, indent);
                sb.append(pad).append(j.type()).append(" JOIN\n");
                prettyPrintTableRef(j.right(), sb, indent);
                if (j.condition() != null) {
                    sb.append(pad).append("ON ");
                    prettyPrintExpr(j.condition(), sb);
                    sb.append('\n');
                }
            }
            default -> sb.append(pad).append("?\n");
        }
    }

    private static void prettyPrintExpr(Expression expr, StringBuilder sb) {
        switch (expr) {
            case ColumnRef c -> {
                if (c.qualifierStr() != null) sb.append(c.qualifierStr()).append('.');
                sb.append(c.name());
            }
            case IntLiteral i -> sb.append(i.value());
            case StringLiteral s -> sb.append('\'').append(s.value()).append('\'');
            case BoolLiteral b -> sb.append(b.value());
            case NullLiteral n -> sb.append("NULL");
            case BinaryOp b -> {
                sb.append('(');
                prettyPrintExpr(b.left(), sb);
                sb.append(' ').append(b.op()).append(' ');
                prettyPrintExpr(b.right(), sb);
                sb.append(')');
            }
            case FunctionCall f -> sb.append(f.name()).append("(...)");
            default -> sb.append(expr.getClass().getSimpleName());
        }
    }
}
