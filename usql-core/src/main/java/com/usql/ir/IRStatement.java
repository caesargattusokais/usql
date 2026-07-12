package com.usql.ir;

import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;

/**
 * Top-level statement nodes in the Semantic IR.
 * This is the "logical plan" layer — it describes intent, not syntax.
 */
public sealed interface IRStatement {

    /** Set of capabilities this statement requires from the target database */
    Set<Capability> capabilities();

    // ══════════════════════════════════════════════════
    //  DML Statements
    // ══════════════════════════════════════════════════

    /** SELECT statement */
    record IRSelect(
        SelectCore core,
        List<OrderBy> orderBy,
        FetchClause fetch,
        Set<Capability> capabilities
    ) implements IRStatement {

        public IRSelect {
            if (capabilities == null) capabilities = collectCapabilities(core, orderBy, fetch);
        }

        private static Set<Capability> collectCapabilities(SelectCore core,
                                                            List<OrderBy> orderBy,
                                                            FetchClause fetch) {
            Set<Capability> caps = new LinkedHashSet<>();
            if (fetch != null) caps.add(Capability.LIMIT_OFFSET);
            if (core != null && core.groupBy() != null && !core.groupBy().isEmpty()) {
                caps.add(Capability.AGGREGATE);
            }
            if (core != null && core.having() != null) caps.add(Capability.HAVING);
            if (core != null && core.distinct()) caps.add(Capability.DISTINCT);
            // JOIN types and other caps are discovered during semantic analysis
            return caps;
        }
    }

    /** SELECT core — the body without ORDER BY / LIMIT */
    record SelectCore(
        List<IRSelectItem> projections,     // SELECT list
        List<IRTableRef> from,              // FROM clause
        IRExpr where,                        // WHERE
        List<IRGroupBy> groupBy,             // GROUP BY
        IRExpr having,                       // HAVING
        List<IRCommonTable> withClause,     // WITH (CTE)
        SetOp setOp,                        // UNION / INTERSECT / EXCEPT
        IRSelect setOperand,                 // right operand for set ops
        boolean distinct                     // SELECT DISTINCT
    ) {}

    /** SELECT projection item */
    sealed interface IRSelectItem {}
    record IRExprSelect(IRExpr expr, String alias) implements IRSelectItem {}
    record IRWildcardSelect(IRExpr.IRWildcard wildcard) implements IRSelectItem {}

    // ── Table references ──

    sealed interface IRTableRef {}

    /** Simple table: my_table AS alias */
    record IRTableName(String name, String alias, String schema) implements IRTableRef {}

    /** JOIN */
    record IRJoin(IRTableRef left, JoinType type, IRTableRef right, IRExpr onCondition) implements IRTableRef {}

    /** Subquery as table: (SELECT ...) AS alias */
    record IRSubqueryTable(IRSelect query, String alias) implements IRTableRef {}

    /** Table function / LATERAL: func(args) AS alias */
    record IRFunctionTable(String funcName, List<IRExpr> args, String alias, boolean lateral) implements IRTableRef {}

    enum JoinType { INNER, LEFT, RIGHT, CROSS, FULL }

    // ── GROUP BY ──

    record IRGroupBy(IRExpr expr, GroupByKind kind) {}

    enum GroupByKind { PLAIN, ROLLUP, CUBE, GROUPING_SETS }

    // ── ORDER BY ──

    record OrderBy(IRExpr expr, OrderDir dir, NullsOrder nulls) {}

    enum OrderDir { ASC, DESC }
    enum NullsOrder { FIRST, LAST }

    // ── Fetch (pagination) ──

    /** Semantic pagination — stored as intent, not syntax */
    record FetchClause(IRExpr limit, IRExpr offset) {}

    // ── Set operations ──

    enum SetOp { UNION, UNION_ALL, INTERSECT, EXCEPT }

    // ── CTE ──

    record IRCommonTable(String name, List<String> columns, IRSelect query, boolean recursive) {}

    // ══════════════════════════════════════════════════
    //  INSERT
    // ══════════════════════════════════════════════════

    record IRInsert(
        IRTableRef table,
        List<String> columns,
        List<List<IRExpr>> values,       // multiple value rows
        IRSelect selectSource,           // INSERT ... SELECT
        boolean ignoreErrors,            // INSERT IGNORE / ON CONFLICT DO NOTHING
        Set<Capability> capabilities
    ) implements IRStatement {}

    // ══════════════════════════════════════════════════
    //  UPDATE
    // ══════════════════════════════════════════════════

    record IRUpdate(
        IRTableRef table,
        List<SetClause> sets,
        IRExpr where,
        Set<Capability> capabilities
    ) implements IRStatement {}

    record SetClause(String column, IRExpr value) {}

    // ══════════════════════════════════════════════════
    //  DELETE
    // ══════════════════════════════════════════════════

    record IRDelete(
        IRTableRef table,
        IRExpr where,
        Set<Capability> capabilities
    ) implements IRStatement {}

    // ══════════════════════════════════════════════════
    //  MERGE (UPSERT)
    // ══════════════════════════════════════════════════

    record IRMerge(
        IRTableRef target,
        IRTableRef source,
        IRExpr onCondition,
        List<IRMergeAction> actions,
        Set<Capability> capabilities
    ) implements IRStatement {
        public IRMerge {
            capabilities = new java.util.LinkedHashSet<>(capabilities);
            capabilities.add(Capability.MERGE_INTO);
        }
    }

    sealed interface IRMergeAction {}
    record MergeInsert(List<String> columns, List<IRExpr> values) implements IRMergeAction {}
    record MergeUpdate(List<SetClause> sets) implements IRMergeAction {}
    record MergeDelete() implements IRMergeAction {}

    // ══════════════════════════════════════════════════
    //  DDL Statements
    // ══════════════════════════════════════════════════

    record IRCreateTable(
        IRTableName name,
        boolean ifNotExists,
        List<IRColumnDef> columns,
        List<IRTableConstraint> constraints,
        TableOptions options,
        Set<Capability> capabilities
    ) implements IRStatement {}

    record IRColumnDef(
        String name,
        DataType type,
        List<IRColumnConstraint> constraints,
        IRExpr defaultValue
    ) {}

    sealed interface IRColumnConstraint {}
    record ColNotNull() implements IRColumnConstraint {}
    record ColUnique(boolean clustered) implements IRColumnConstraint {}
    record ColPrimaryKey(boolean autoIncrement) implements IRColumnConstraint {}
    record ColCheck(IRExpr condition) implements IRColumnConstraint {}
    record ColReferences(
        String targetTable, String targetColumn,
        ForeignKeyAction onUpdate, ForeignKeyAction onDelete
    ) implements IRColumnConstraint {}
    record ColGenerated(GeneratedStrategy strategy, boolean virtual, IRExpr expression) implements IRColumnConstraint {}

    enum GeneratedStrategy { ALWAYS, BY_DEFAULT }
    enum ForeignKeyAction { CASCADE, SET_NULL, RESTRICT, NO_ACTION }

    record TableOptions(
        String engine,        // InnoDB / MyISAM
        String tablespace,
        String characterSet,
        String collation,
        String comment
    ) {}

    // Table-level constraints
    sealed interface IRTableConstraint {}
    record TBUnique(List<String> columns, String constraintName) implements IRTableConstraint {}
    record TBPrimaryKey(List<String> columns, String constraintName) implements IRTableConstraint {}
    record TBForeignKey(List<String> columns, String targetTable, List<String> targetColumns,
                        String constraintName, ForeignKeyAction onUpdate, ForeignKeyAction onDelete,
                        boolean deferrable) implements IRTableConstraint {}
    record TBCheck(IRExpr condition, String constraintName) implements IRTableConstraint {}

    // ══════════════════════════════════════════════════
    //  CREATE INDEX
    // ══════════════════════════════════════════════════

    record IRCreateIndex(
        String name,
        IRTableName table,
        List<IndexColumn> columns,
        boolean unique,
        boolean ifNotExists,
        IndexType type,
        IRExpr whereClause,
        Set<Capability> capabilities
    ) implements IRStatement {
        public IRCreateIndex {
            capabilities = new java.util.LinkedHashSet<>(capabilities);
            if (whereClause != null) capabilities.add(Capability.PARTIAL_INDEX);
        }
    }

    record IndexColumn(String name, IRStatement.OrderDir dir, IRStatement.NullsOrder nulls) {}

    enum IndexType { BTREE, HASH, GIST, GIN, BRIN }

    // ══════════════════════════════════════════════════
    //  Stored Procedures
    // ══════════════════════════════════════════════════

    /** CREATE PROCEDURE — body is raw dialect-specific SQL */
    record IRCreateProcedure(
        String name,
        List<ProcedureParam> params,
        String body,                         // raw SQL body (dialect-specific)
        boolean orReplace,                   // CREATE OR REPLACE
        Set<Capability> capabilities
    ) implements IRStatement {}

    /** CREATE FUNCTION — returns a scalar value */
    record IRCreateFunction(
        String name,
        List<ProcedureParam> params,
        DataType returnType,
        String body,                         // raw SQL body
        boolean orReplace,
        Set<Capability> capabilities
    ) implements IRStatement {}

    /** CALL procedure(args) */
    record IRCall(
        String procedureName,
        List<IRExpr> args,
        Set<Capability> capabilities
    ) implements IRStatement {}

    record ProcedureParam(
        String name,
        DataType type,
        ParamMode mode                       // IN / OUT / INOUT
    ) {}

    enum ParamMode { IN, OUT, INOUT }

    // ══════════════════════════════════════════════════
    //  DROP / TRUNCATE / ALTER TABLE
    // ══════════════════════════════════════════════════

    record IRDropTable(String name, boolean ifExists, boolean cascade, Set<Capability> capabilities) implements IRStatement {}
    record IRDropIndex(String indexName, String tableName, boolean ifExists, Set<Capability> capabilities) implements IRStatement {}
    record IRDropDatabase(String name, boolean ifExists, Set<Capability> capabilities) implements IRStatement {}
    record IRCreateView(String name, IRSelect query, Set<Capability> capabilities) implements IRStatement {}
    record IRCreateSchema(String name, Set<Capability> capabilities) implements IRStatement {}
    record IRTCL(String sql, Set<Capability> capabilities) implements IRStatement {}  // pass-through TCL
    record IRTruncateTable(String name, Set<Capability> capabilities) implements IRStatement {}
    record IRAlterTableAddColumn(String tableName, IRColumnDef column, Set<Capability> capabilities) implements IRStatement {}
    record IRAlterTableDropColumn(String tableName, String columnName, Set<Capability> capabilities) implements IRStatement {}
    record IRAlterColumnType(String tableName, String column, DataType newType, Set<Capability> capabilities) implements IRStatement {}
    record IRAlterColumnSetDefault(String tableName, String column, IRExpr value, Set<Capability> capabilities) implements IRStatement {}
    record IRAlterColumnDropDefault(String tableName, String column, Set<Capability> capabilities) implements IRStatement {}
    record IRRenameColumn(String tableName, String oldName, String newName, Set<Capability> capabilities) implements IRStatement {}
}
