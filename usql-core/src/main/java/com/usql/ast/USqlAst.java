package com.usql.ast;

import java.util.List;
import java.util.Optional;

/**
 * AST (Abstract Syntax Tree) — intermediate between CST (parse tree) and SemanticIR.
 *
 * Strips syntax noise (commas, semicolons, keyword variants) but preserves
 * the structural intent close to what the user wrote. No type information yet.
 */
public final class USqlAst {

    private USqlAst() {} // namespace

    // ══════════════════════════════════════════════════
    //  Top-level
    // ══════════════════════════════════════════════════

    public sealed interface Statement {}

    public record SelectStmt(
        List<CommonTable> withClause,
        boolean distinct,
        List<SelectItem> projections,
        List<TableRef> from,
        Expression where,
        List<GroupByItem> groupBy,
        Expression having,
        List<OrderByItem> orderBy,
        FetchClause fetch,
        SetOp setOp,
        SelectStmt setOperand
    ) implements Statement {}

    public record InsertStmt(
        boolean ignore,
        TableRef table,
        List<String> columns,
        List<List<Expression>> values,
        SelectStmt selectSource
    ) implements Statement {}

    public record UpdateStmt(
        TableRef table,
        List<SetClause> sets,
        Expression where
    ) implements Statement {}

    public record DeleteStmt(
        TableRef table,
        Expression where
    ) implements Statement {}

    public record MergeStmt(
        TableRef target,
        String targetAlias,
        TableRef source,
        Expression onCondition,
        List<MergeAction> actions
    ) implements Statement {}

    public record CreateTableStmt(
        boolean ifNotExists,
        String tableName,
        List<ColumnDef> columns,
        List<TableConstraint> constraints,
        TableOptions options
    ) implements Statement {}

    public record CreateIndexStmt(
        boolean unique,
        boolean ifNotExists,
        String name,
        String tableName,
        List<IndexColumn> columns,
        Expression where
    ) implements Statement {}

    // ══════════════════════════════════════════════════
    //  SELECT components
    // ══════════════════════════════════════════════════

    public sealed interface SelectItem {}
    public record ExprItem(Expression expr, String alias) implements SelectItem {}
    public record StarItem(String qualifier) implements SelectItem {}

    public sealed interface TableRef {}
    public record SimpleTable(String name, String alias) implements TableRef {}
    public record SubqueryTable(SelectStmt query, String alias) implements TableRef {}
    public record JoinTable(TableRef left, JoinType type, TableRef right, Expression condition) implements TableRef {}
    public record FunctionTable(boolean lateral, String funcName, List<Expression> args, String alias) implements TableRef {}

    public enum JoinType { INNER, LEFT, RIGHT, CROSS, FULL }

    public record GroupByItem(Expression expr, GroupByKind kind) {}
    public enum GroupByKind { PLAIN, ROLLUP, CUBE, GROUPING_SETS }

    public record OrderByItem(Expression expr, boolean desc, boolean nullsFirst) {}

    public record FetchClause(Expression limit, Expression offset) {}

    public enum SetOp { UNION, UNION_ALL, INTERSECT, EXCEPT }

    public record CommonTable(String name, List<String> columns, SelectStmt query, boolean recursive) {}

    // ══════════════════════════════════════════════════
    //  DML helpers
    // ══════════════════════════════════════════════════

    public record SetClause(String column, Expression value) {}

    public sealed interface MergeAction {}
    public record MergeInsert(List<String> columns, List<Expression> values) implements MergeAction {}
    public record MergeUpdate(List<SetClause> sets) implements MergeAction {}
    public record MergeDelete() implements MergeAction {}

    // ══════════════════════════════════════════════════
    //  DDL helpers
    // ══════════════════════════════════════════════════

    public record ColumnDef(
        String name,
        String typeName,          // raw type text from parser, e.g. "VARCHAR(100)"
        int typePrecision,
        int typeScale,
        List<String> enumValues,  // for ENUM('v1','v2',...)
        List<ColumnConstraint> constraints,
        Expression defaultValue
    ) {}

    public sealed interface ColumnConstraint {}
    public record NotNullConstraint() implements ColumnConstraint {}
    public record NullConstraint() implements ColumnConstraint {}
    public record PrimaryKeyConstraint(boolean autoIncrement) implements ColumnConstraint {}
    public record UniqueConstraint() implements ColumnConstraint {}
    public record CheckConstraint(Expression condition) implements ColumnConstraint {}
    public record ReferencesConstraint(String targetTable, String targetColumn,
                                        String onUpdate, String onDelete) implements ColumnConstraint {}
    public record GeneratedConstraint(boolean always, boolean virtual, Expression expression) implements ColumnConstraint {}

    public sealed interface TableConstraint {}
    public record TbPrimaryKey(List<String> columns, String name) implements TableConstraint {}
    public record TbUnique(List<String> columns, String name) implements TableConstraint {}
    public record TbForeignKey(List<String> columns, String targetTable, List<String> targetColumns,
                                String name, String onUpdate, String onDelete) implements TableConstraint {}
    public record TbCheck(Expression condition, String name) implements TableConstraint {}

    public record TableOptions(String engine, String tablespace, String characterSet,
                                String collation, String comment) {}

    public record IndexColumn(String name, boolean desc, boolean nullsFirst) {}

    // ══════════════════════════════════════════════════
    //  Expressions
    // ══════════════════════════════════════════════════

    public sealed interface Expression {}

    // Literals
    public record IntLiteral(long value) implements Expression {}
    public record FloatLiteral(double value) implements Expression {}
    public record StringLiteral(String value) implements Expression {}
    public record BoolLiteral(boolean value) implements Expression {}
    public record NullLiteral() implements Expression {}
    public record DateLiteral(String value) implements Expression {}
    public record TimestampLiteral(String value) implements Expression {}
    public record IntervalLiteral(String value, String unit) implements Expression {}

    // References
    public record ColumnRef(List<String> qualifier, String name) implements Expression {
        public String qualifierStr() {
            return qualifier.isEmpty() ? null : String.join(".", qualifier);
        }
    }
    public record StarExpr(String qualifier) implements Expression {}

    // Parameter
    public record ParamRef(String name) implements Expression {}

    // Operators
    public record BinaryOp(Expression left, BinOp op, Expression right) implements Expression {}
    public record UnaryOp(UnOp op, Expression operand) implements Expression {}

    public enum BinOp {
        ADD, SUB, MUL, DIV, MOD,
        EQ, NEQ, LT, GT, LTE, GTE,
        AND, OR, CONCAT,
        LIKE, NOT_LIKE
    }

    public enum UnOp {
        NEG, NOT, IS_NULL, IS_NOT_NULL,
        IS_TRUE, IS_NOT_TRUE, IS_FALSE, IS_NOT_FALSE,
        EXISTS
    }

    // Function call
    public record FunctionCall(String name, List<Expression> args, boolean star,
                                KeepClause keep, WindowOver over) implements Expression {}

    public record KeepClause(boolean last, List<OrderByItem> orderBy) {}

    public record WindowOver(List<Expression> partitionBy,
                              List<OrderByItem> orderBy) {}

    // CASE
    public record CaseExpr(List<WhenClause> whens, Expression elseExpr) implements Expression {}
    public record WhenClause(Expression condition, Expression result) {}

    // Other
    public record BetweenExpr(Expression expr, Expression low, Expression high, boolean not) implements Expression {}
    public record InListExpr(Expression expr, List<Expression> values, SelectStmt subquery, boolean not) implements Expression {}
    public record CastExpr(Expression expr, String typeName, int precision, int scale) implements Expression {}
    public record SubqueryExpr(SelectStmt query) implements Expression {}
    public record IsNullExpr(Expression expr, boolean not) implements Expression {}
}
