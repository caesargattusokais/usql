package com.usql.ir;

import com.usql.ir.IRStatement.IRSelect;

import java.util.List;

/**
 * Typed expression nodes in the Semantic IR.
 * Every expression node carries its resolved DataType.
 */
public sealed interface IRExpr {

    /** Resolved type of this expression — always present after semantic analysis */
    DataType getType();

    // ── Leaf nodes ──

    /** A literal value: 42, 'hello', TRUE, NULL */
    record IRLiteral(Object value, DataType type) implements IRExpr {
        public DataType getType() { return type; }
    }

    /** A column reference: name, d.name, d."name" */
    record IRColumnRef(String name, String qualifier, DataType type) implements IRExpr {
        public DataType getType() { return type; }
        public String fullName() {
            return qualifier != null ? qualifier + "." + name : name;
        }
    }

    /** Wildcard: *  or  d.* */
    record IRWildcard(String qualifier) implements IRExpr {
        public DataType getType() { return new DataType.NullType(); } // wildcard has no single type
    }

    /** Named parameter placeholder: :param or ? */
    record IRParameter(String name, DataType type) implements IRExpr {
        public DataType getType() { return type; }
    }

    // ── Operators ──

    /** Binary operation: a + b, a = b, a AND b */
    record IRBinaryOp(IRExpr left, BinaryOp op, IRExpr right, DataType type) implements IRExpr {
        public DataType getType() { return type; }

        public enum BinaryOp {
            // Arithmetic
            ADD, SUB, MUL, DIV, MOD,
            // Comparison
            EQ, NEQ, LT, GT, LTE, GTE,
            // Logical
            AND, OR,
            // String
            CONCAT,
            // SQL-specific
            LIKE, NOT_LIKE, IN, NOT_IN, BETWEEN, IS_DISTINCT_FROM
        }
    }

    /** Unary operation: -x, NOT x, EXISTS */
    record IRUnaryOp(UnaryOp op, IRExpr operand, DataType type) implements IRExpr {
        public DataType getType() { return type; }

        public enum UnaryOp {
            NEG, NOT, IS_NULL, IS_NOT_NULL,
            IS_TRUE, IS_NOT_TRUE, IS_FALSE, IS_NOT_FALSE,
            EXISTS, UNIQUE
        }
    }

    // ── Function ──

    /** Function call: COUNT(*), UPPER(name), COALESCE(a, b), MAX(x) KEEP (DENSE_RANK LAST ...) */
    record IRFunctionCall(String funcName, List<IRExpr> args, DataType type,
                          IRWindowOver over, KeepSpec keep) implements IRExpr {
        public DataType getType() { return type; }
    }

    /** KEEP (DENSE_RANK FIRST|LAST ORDER BY ...) — Oracle aggregate extension */
    public sealed interface KeepSpec {
        record First(List<IRStatement.OrderBy> orderBy) implements KeepSpec {}
        record Last(List<IRStatement.OrderBy> orderBy) implements KeepSpec {}
    }

    /** OVER clause for window functions */
    record IRWindowOver(List<IRExpr> partitionBy,
                        List<IRStatement.OrderBy> orderBy) {}

    // ── CASE ──

    /** CASE WHEN cond THEN val ... ELSE default END */
    record IRCase(List<WhenClause> whens, IRExpr elseExpr, DataType type) implements IRExpr {
        public DataType getType() { return type; }

        public record WhenClause(IRExpr condition, IRExpr result) {}
    }

    // ── Type conversion ──

    /** CAST(expr AS targetType) */
    record IRCast(IRExpr expr, DataType targetType) implements IRExpr {
        public DataType getType() { return targetType; }
    }

    // ── Subquery ──

    /** Subquery expression: (SELECT ...) */
    record IRSubquery(IRSelect query, DataType type) implements IRExpr {
        public DataType getType() { return type; }
    }

    // ── BETWEEN ──

    /** BETWEEN low AND high */
    record IRBetween(IRExpr expr, IRExpr low, IRExpr high, boolean not, DataType type) implements IRExpr {
        public DataType getType() { return type; }
    }

    // ── IN list ──

    /** expr IN (val1, val2, ...)  or  expr IN (subquery) */
    record IRInList(IRExpr expr, List<IRExpr> values, IRSelect subquery, boolean not, DataType type) implements IRExpr {
        public DataType getType() { return type; }
    }

    // ── IS [NOT] NULL ──

    /** x IS NULL  or  x IS NOT NULL */
    record IRIsNull(IRExpr expr, boolean not, DataType type) implements IRExpr {
        public DataType getType() { return type; }
    }
}
