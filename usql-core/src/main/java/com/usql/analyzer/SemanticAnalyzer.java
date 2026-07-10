package com.usql.analyzer;

import com.usql.CompilationResult;
import com.usql.SchemaProvider;
import com.usql.ast.USqlAst.*;
import com.usql.catalog.FunctionCatalog;
import com.usql.catalog.TypeCatalog;
import com.usql.ir.*;
import com.usql.ir.IRExpr.*;
import com.usql.ir.IRStatement.*;
import com.usql.ir.IRStatement;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Semantic analysis: USqlAst → SemanticIR.
 *
 * Responsibilities:
 *   1. Symbol resolution — resolve column refs against schema / subquery aliases
 *   2. Type derivation — every expression node gets its DataType
 *   3. Capability marking — which features does this statement need?
 *   4. Scope checking — are all references valid?
 */
public class SemanticAnalyzer {

    private final SchemaProvider schema;
    private final FunctionCatalog functions;
    private final TypeCatalog types;
    private final List<CompilationResult.Error> errors = new ArrayList<>();
    private final List<CompilationResult.Warning> warnings = new ArrayList<>();

    /** Stack of visible table aliases → their column definitions */
    private final Deque<Map<String, ScopeEntry>> scopes = new ArrayDeque<>();

    public SemanticAnalyzer(SchemaProvider schema, FunctionCatalog functions, TypeCatalog types) {
        this.schema = schema;
        this.functions = functions;
        this.types = types;
    }

    // ══════════════════════════════════════════════════
    //  Public
    // ══════════════════════════════════════════════════

    public record AnalysisResult(SemanticIR ir, List<CompilationResult.Error> errors,
                                  List<CompilationResult.Warning> warnings) {}

    public AnalysisResult analyze(Statement stmt) {
        errors.clear();
        warnings.clear();
        scopes.clear();
        pushScope(); // ensure scope is always available for table ref resolution

        IRStatement ir = analyzeStatement(stmt);
        popScope();

        return new AnalysisResult(
            new SemanticIR(ir),
            List.copyOf(errors),
            List.copyOf(warnings)
        );
    }

    // ══════════════════════════════════════════════════
    //  Statement dispatch
    // ══════════════════════════════════════════════════

    private IRStatement analyzeStatement(Statement stmt) {
        return switch (stmt) {
            case SelectStmt s      -> analyzeSelect(s);
            case InsertStmt s      -> analyzeInsert(s);
            case UpdateStmt s      -> analyzeUpdate(s);
            case DeleteStmt s      -> analyzeDelete(s);
            case CreateTableStmt s -> analyzeCreateTable(s);
            case CreateIndexStmt s -> analyzeCreateIndex(s);
            case MergeStmt s             -> analyzeMerge(s);
            case CreateProcedureStmt s   -> analyzeCreateProcedure(s);
            case CreateFunctionStmt s    -> analyzeCreateFunction(s);
            case CallStmt s              -> analyzeCall(s);
            default -> throw new UnsupportedOperationException(
                "Analysis not implemented for: " + stmt.getClass().getSimpleName());
        };
    }

    // ══════════════════════════════════════════════════
    //  SELECT
    // ══════════════════════════════════════════════════

    private IRSelect analyzeSelect(SelectStmt s) {
        pushScope();

        // Analyze FROM → populate scope
        List<IRTableRef> from = s.from() != null
            ? s.from().stream().map(this::analyzeTableRef).collect(Collectors.toList())
            : List.of();

        // Analyze projections
        List<IRSelectItem> projections = new ArrayList<>();
        Set<Capability> caps = new LinkedHashSet<>();

        for (var item : s.projections()) {
            projections.add(analyzeSelectItem(item));
        }

        // WHERE
        IRExpr where = s.where() != null ? analyzeExpr(s.where()) : null;

        // GROUP BY
        List<IRGroupBy> groupBy = null;
        if (s.groupBy() != null && !s.groupBy().isEmpty()) {
            caps.add(Capability.AGGREGATE);
            groupBy = s.groupBy().stream()
                .map(g -> {
                    if (g.kind() != com.usql.ast.USqlAst.GroupByKind.PLAIN) caps.add(Capability.GROUPING_SETS);
                    return new IRGroupBy(analyzeExpr(g.expr()),
                        switch (g.kind()) {
                            case PLAIN -> IRStatement.GroupByKind.PLAIN;
                            case ROLLUP -> IRStatement.GroupByKind.ROLLUP;
                            case CUBE -> IRStatement.GroupByKind.CUBE;
                            case GROUPING_SETS -> IRStatement.GroupByKind.GROUPING_SETS;
                        });
                })
                .collect(Collectors.toList());
        }

        // HAVING
        IRExpr having = s.having() != null ? analyzeExpr(s.having()) : null;
        if (having != null) caps.add(Capability.HAVING);

        // ORDER BY
        List<OrderBy> orderBy = null;
        if (s.orderBy() != null && !s.orderBy().isEmpty()) {
            orderBy = s.orderBy().stream()
                .map(o -> new OrderBy(analyzeExpr(o.expr()),
                    o.desc() ? OrderDir.DESC : OrderDir.ASC,
                    o.nullsFirst() ? NullsOrder.FIRST : NullsOrder.LAST))
                .collect(Collectors.toList());
        }

        // FETCH
        IRStatement.FetchClause fetch = null;
        if (s.fetch() != null) {
            caps.add(Capability.LIMIT_OFFSET);
            IRExpr limit = s.fetch().limit() != null ? analyzeExpr(s.fetch().limit()) : null;
            IRExpr offset = s.fetch().offset() != null ? analyzeExpr(s.fetch().offset()) : null;
            fetch = new IRStatement.FetchClause(limit, offset);
        }

        if (s.distinct()) caps.add(Capability.DISTINCT);

        popScope();

        List<IRStatement.IRCommonTable> withClause = null;
        if (s.withClause() != null && !s.withClause().isEmpty()) {
            withClause = s.withClause().stream()
                .map(cte -> new IRStatement.IRCommonTable(cte.name(), cte.columns(),
                    analyzeSelect(cte.query()), cte.recursive()))
                .collect(Collectors.toList());
        }

        SelectCore core = new SelectCore(
            projections, from, where, groupBy, having,
            withClause,
            s.setOp() != null ? mapSetOp(s.setOp()) : null,
            s.setOperand() != null ? analyzeSelect(s.setOperand()) : null,
            s.distinct()
        );

        return new IRSelect(core, orderBy, fetch, caps);
    }

    private IRSelectItem analyzeSelectItem(SelectItem item) {
        return switch (item) {
            case ExprItem e -> new IRExprSelect(analyzeExpr(e.expr()), e.alias());
            case StarItem s  -> new IRWildcardSelect(
                new IRWildcard(s.qualifier())
            );
        };
    }

    // ══════════════════════════════════════════════════
    //  Table references → scope population
    // ══════════════════════════════════════════════════

    private IRTableRef analyzeTableRef(TableRef ref) {
        return switch (ref) {
            case SimpleTable t -> {
                // Register the table alias in scope always — even without schema info
                String alias = t.alias() != null ? t.alias() : t.name();
                var tableDef = schema.getTable(t.name());
                if (tableDef.isPresent()) {
                    Map<String, DataType> columns = new LinkedHashMap<>();
                    for (var col : tableDef.get().columns()) {
                        columns.put(col.name(), col.type());
                    }
                    scopes.peek().put(alias, new ScopeEntry(alias, columns, false));
                } else {
                    // Table not in schema — warn but still register with open scope
                    // so that qualifier references (e.g., d.name) resolve correctly
                    warnings.add(CompilationResult.Warning.of(0, 0,
                        "Table '" + t.name() + "' not found in schema"));
                    scopes.peek().put(alias, ScopeEntry.open(alias));
                }
                yield new IRTableName(t.name(), t.alias(), null);
            }
            case SubqueryTable sq -> {
                pushScope();
                IRSelect subQuery = analyzeSelect(sq.query());
                popScope();
                // Register subquery columns in outer scope
                Map<String, DataType> columns = extractSubqueryColumns(sq.query());
                scopes.peek().put(sq.alias(), new ScopeEntry(sq.alias(), columns, true));
                yield new IRSubqueryTable(subQuery, sq.alias());
            }
            case JoinTable jt -> {
                IRTableRef left = analyzeTableRef(jt.left());
                IRTableRef right = analyzeTableRef(jt.right());
                IRExpr condition = jt.condition() != null ? analyzeExpr(jt.condition()) : null;
                IRStatement.JoinType jtype = switch (jt.type()) {
                    case INNER -> IRStatement.JoinType.INNER;
                    case LEFT  -> IRStatement.JoinType.LEFT;
                    case RIGHT -> IRStatement.JoinType.RIGHT;
                    case CROSS -> IRStatement.JoinType.CROSS;
                    case FULL  -> IRStatement.JoinType.FULL;
                };
                yield new IRJoin(left, jtype, right, condition);
            }
            case FunctionTable ft ->
                new IRFunctionTable(ft.funcName(),
                    ft.args().stream().map(this::analyzeExpr).collect(Collectors.toList()),
                    ft.alias(), ft.lateral());
        };
    }

    private Map<String, DataType> extractSubqueryColumns(SelectStmt subquery) {
        Map<String, DataType> cols = new LinkedHashMap<>();
        for (var item : subquery.projections()) {
            if (item instanceof ExprItem e) {
                String name = e.alias() != null ? e.alias() : extractColumnName(e.expr());
                cols.put(name, TypeInferrer.inferExpressionType(e.expr()));
            }
        }
        return cols;
    }

    private String extractColumnName(Expression expr) {
        if (expr instanceof ColumnRef c) return c.name();
        if (expr instanceof FunctionCall f) return f.name();
        return "?column?";
    }

    // ══════════════════════════════════════════════════
    //  Expression analysis → typed IRExpr
    // ══════════════════════════════════════════════════

    private IRExpr analyzeExpr(Expression expr) {
        return switch (expr) {
            case IntLiteral i      -> new IRLiteral(i.value(), DataType.IntType.INT);
            case FloatLiteral f    -> new IRLiteral(f.value(), DataType.FloatType.DOUBLE);
            case StringLiteral s   -> new IRLiteral(s.value(), new DataType.VarcharType(s.value().length()));
            case BoolLiteral b     -> new IRLiteral(b.value(), new DataType.BooleanType());
            case NullLiteral n     -> new IRLiteral(null, new DataType.NullType());
            case DateLiteral d     -> new IRLiteral(d.value(), new DataType.DateType());
            case TimestampLiteral t -> new IRLiteral(t.value(), new DataType.DatetimeType(3));
            case IntervalLiteral i -> new IRLiteral(i.value(), new DataType.IntervalDaySecond(0));

            case ColumnRef c -> resolveColumnRef(c);
            case StarExpr s  -> new IRWildcard(s.qualifier());
            case ParamRef p  -> new IRParameter(p.name(), null);

            case BinaryOp bo  -> analyzeBinaryOp(bo);
            case UnaryOp uo   -> analyzeUnaryOp(uo);
            case FunctionCall fc -> analyzeFunctionCall(fc);
            case CaseExpr cs  -> analyzeCase(cs);
            case CastExpr ct  -> analyzeCast(ct);
            case BetweenExpr bw -> analyzeBetween(bw);
            case InListExpr in  -> analyzeInList(in);
            case IsNullExpr isn -> new IRIsNull(analyzeExpr(isn.expr()), isn.not(), new DataType.BooleanType());
            case SubqueryExpr sq -> new IRSubquery(analyzeSelect(sq.query()), null);

            default -> {
                errors.add(CompilationResult.Error.of(0, 0,
                    "Unknown expression: " + expr.getClass().getSimpleName(),
                    "U-SQL does not support this expression type. "
                        + "Check the syntax reference for supported expressions."));
                yield new IRLiteral(null, new DataType.NullType());
            }
        };
    }

    private IRColumnRef resolveColumnRef(ColumnRef c) {
        String qual = c.qualifierStr();
        String name = c.name();

        if (qual != null) {
            // Qualified: d.name → look up 'd' in scope, then 'name' in its columns
            for (var scope : scopes) {
                var entry = scope.get(qual);
                if (entry != null) {
                    if (entry.open()) {
                        // Open scope (table not in schema): accept any column
                        return new IRColumnRef(name, qual, new DataType.NullType());
                    }
                    DataType type = entry.columns().getOrDefault(name, new DataType.NullType());
                    if (type instanceof DataType.NullType && !entry.columns().containsKey(name)) {
                        warnings.add(CompilationResult.Warning.of(0, 0,
                            "Column '" + name + "' not found in table '" + qual + "'"));
                    }
                    return new IRColumnRef(name, qual, type);
                }
            }
            errors.add(CompilationResult.Error.of(0, 0,
                "Unknown qualifier '" + qual + "' — not a table alias in scope",
                "Available table aliases in scope: " + scopes.stream()
                    .flatMap(s -> s.keySet().stream())
                    .collect(java.util.stream.Collectors.joining(", "))));
            return new IRColumnRef(name, qual, new DataType.NullType());
        } else {
            // Unqualified: search all scopes
            for (var scope : scopes) {
                for (var entry : scope.values()) {
                    if (entry.columns().containsKey(name)) {
                        return new IRColumnRef(name, entry.alias(), entry.columns().get(name));
                    }
                }
            }
            // Column not found in scope — might be a standalone expression
            warnings.add(CompilationResult.Warning.of(0, 0,
                "Column '" + name + "' not found in any table — treating as untyped"));
            return new IRColumnRef(name, null, new DataType.NullType());
        }
    }

    private IRExpr analyzeBinaryOp(BinaryOp bo) {
        IRExpr left = analyzeExpr(bo.left());
        IRExpr right = analyzeExpr(bo.right());

        DataType resultType = TypeInferrer.inferBinaryResultType(left.getType(), right.getType(), bo.op());

        IRBinaryOp.BinaryOp irOp = switch (bo.op()) {
            case ADD  -> IRBinaryOp.BinaryOp.ADD;
            case SUB  -> IRBinaryOp.BinaryOp.SUB;
            case MUL  -> IRBinaryOp.BinaryOp.MUL;
            case DIV  -> IRBinaryOp.BinaryOp.DIV;
            case MOD  -> IRBinaryOp.BinaryOp.MOD;
            case EQ   -> IRBinaryOp.BinaryOp.EQ;
            case NEQ  -> IRBinaryOp.BinaryOp.NEQ;
            case LT   -> IRBinaryOp.BinaryOp.LT;
            case GT   -> IRBinaryOp.BinaryOp.GT;
            case LTE  -> IRBinaryOp.BinaryOp.LTE;
            case GTE  -> IRBinaryOp.BinaryOp.GTE;
            case AND  -> IRBinaryOp.BinaryOp.AND;
            case OR   -> IRBinaryOp.BinaryOp.OR;
            case CONCAT -> IRBinaryOp.BinaryOp.CONCAT;
            case LIKE     -> IRBinaryOp.BinaryOp.LIKE;
            case NOT_LIKE -> IRBinaryOp.BinaryOp.NOT_LIKE;
        };

        return new IRBinaryOp(left, irOp, right, resultType);
    }

    private IRExpr analyzeUnaryOp(UnaryOp uo) {
        IRExpr operand = analyzeExpr(uo.operand());
        IRUnaryOp.UnaryOp irOp = switch (uo.op()) {
            case NEG   -> IRUnaryOp.UnaryOp.NEG;
            case NOT   -> IRUnaryOp.UnaryOp.NOT;
            case IS_NULL     -> IRUnaryOp.UnaryOp.IS_NULL;
            case IS_NOT_NULL -> IRUnaryOp.UnaryOp.IS_NOT_NULL;
            case IS_TRUE     -> IRUnaryOp.UnaryOp.IS_TRUE;
            case IS_NOT_TRUE -> IRUnaryOp.UnaryOp.IS_NOT_TRUE;
            case IS_FALSE    -> IRUnaryOp.UnaryOp.IS_FALSE;
            case IS_NOT_FALSE -> IRUnaryOp.UnaryOp.IS_NOT_FALSE;
            case EXISTS -> IRUnaryOp.UnaryOp.EXISTS;
        };
        return new IRUnaryOp(irOp, operand, new DataType.BooleanType());
    }

    private IRExpr analyzeFunctionCall(FunctionCall fc) {
        List<IRExpr> args = fc.args() != null
            ? fc.args().stream().map(this::analyzeExpr).collect(Collectors.toList())
            : List.of();

        if (fc.star()) {
            args = List.of(new IRWildcard(null));
        }

        final List<IRExpr> finalArgs = args;
        DataType returnType = functions.get(fc.name())
            .map(fd -> fd.returnType)
            .orElseGet(() -> TypeInferrer.inferFunctionReturnType(fc.name(), finalArgs));

        // KEEP clause
        KeepSpec keep = null;
        if (fc.keep() != null) {
            var orderBy = fc.keep().orderBy().stream()
                .map(o -> new IRStatement.OrderBy(
                    analyzeExpr(o.expr()),
                    o.desc() ? IRStatement.OrderDir.DESC : IRStatement.OrderDir.ASC,
                    o.nullsFirst() ? IRStatement.NullsOrder.FIRST : IRStatement.NullsOrder.LAST))
                .collect(Collectors.toList());
            keep = fc.keep().last()
                ? new KeepSpec.Last(orderBy)
                : new KeepSpec.First(orderBy);
        }

        IRWindowOver over = null;
        if (fc.over() != null) {
            List<IRExpr> partitionBy = fc.over().partitionBy() != null
                ? fc.over().partitionBy().stream().map(this::analyzeExpr).collect(Collectors.toList())
                : null;
            List<IRStatement.OrderBy> orderBy = fc.over().orderBy() != null
                ? fc.over().orderBy().stream().map(o -> new IRStatement.OrderBy(
                    analyzeExpr(o.expr()), o.desc() ? IRStatement.OrderDir.DESC : IRStatement.OrderDir.ASC,
                    o.nullsFirst() ? IRStatement.NullsOrder.FIRST : IRStatement.NullsOrder.LAST))
                    .collect(Collectors.toList())
                : null;
            over = new IRWindowOver(partitionBy, orderBy, fc.over().frame());
        }

        return new IRFunctionCall(fc.name(), args, returnType, over, keep);
    }

    private IRExpr analyzeCase(CaseExpr cs) {
        List<IRCase.WhenClause> whens = cs.whens().stream()
            .map(w -> new IRCase.WhenClause(analyzeExpr(w.condition()), analyzeExpr(w.result())))
            .collect(Collectors.toList());
        IRExpr elseExpr = cs.elseExpr() != null ? analyzeExpr(cs.elseExpr()) : null;

        // Result type = first non-null branch (ELSE → THENs)
        DataType resultType = new DataType.NullType();
        if (elseExpr != null && !(elseExpr.getType() instanceof DataType.NullType)) {
            resultType = elseExpr.getType();
        } else {
            for (var w : whens) {
                if (!(w.result().getType() instanceof DataType.NullType)) {
                    resultType = w.result().getType();
                    break;
                }
            }
        }

        return new IRCase(whens, elseExpr, resultType);
    }

    private IRExpr analyzeCast(CastExpr ct) {
        DataType targetType = TypeInferrer.parseTypeName(ct.typeName(), ct.precision(), ct.scale());
        return new IRCast(analyzeExpr(ct.expr()), targetType);
    }

    private IRExpr analyzeBetween(BetweenExpr bw) {
        return new IRBetween(analyzeExpr(bw.expr()), analyzeExpr(bw.low()),
            analyzeExpr(bw.high()), bw.not(), new DataType.BooleanType());
    }

    private IRExpr analyzeInList(InListExpr in) {
        List<IRExpr> values = in.values() != null
            ? in.values().stream().map(this::analyzeExpr).collect(Collectors.toList())
            : List.of();
        IRSelect subquery = in.subquery() != null ? analyzeSelect(in.subquery()) : null;
        return new IRInList(analyzeExpr(in.expr()), values, subquery, in.not(), new DataType.BooleanType());
    }

    // ══════════════════════════════════════════════════
    //  INSERT / UPDATE / DELETE
    // ══════════════════════════════════════════════════

    private IRInsert analyzeInsert(InsertStmt ins) {
        IRTableRef table = analyzeTableRef(ins.table());
        List<List<IRExpr>> valueRows = null;
        if (ins.values() != null) {
            valueRows = ins.values().stream()
                .map(row -> row.stream().map(this::analyzeExpr).collect(Collectors.toList()))
                .collect(Collectors.toList());
        }
        IRSelect selectSource = ins.selectSource() != null ? analyzeSelect(ins.selectSource()) : null;
        Set<Capability> caps = new LinkedHashSet<>();
        if (ins.ignore()) caps.add(Capability.MERGE_INTO); // INSERT IGNORE → ON CONFLICT behavior

        return new IRInsert(table, ins.columns(), valueRows, selectSource, ins.ignore(), caps);
    }

    private IRUpdate analyzeUpdate(UpdateStmt upd) {
        IRTableRef table = analyzeTableRef(upd.table());
        List<IRStatement.SetClause> sets = upd.sets().stream()
            .map(s -> new IRStatement.SetClause(s.column(), analyzeExpr(s.value())))
            .collect(Collectors.toList());
        IRExpr where = upd.where() != null ? analyzeExpr(upd.where()) : null;
        return new IRUpdate(table, sets, where, new LinkedHashSet<>());
    }

    private IRDelete analyzeDelete(DeleteStmt del) {
        IRTableRef table = analyzeTableRef(del.table());
        IRExpr where = del.where() != null ? analyzeExpr(del.where()) : null;
        return new IRDelete(table, where, new LinkedHashSet<>());
    }

    private IRMerge analyzeMerge(MergeStmt merge) {
        IRTableRef target = analyzeTableRef(merge.target());
        IRTableRef source = analyzeTableRef(merge.source());
        IRExpr onCondition = analyzeExpr(merge.onCondition());
        List<IRMerge.IRMergeAction> actions = new ArrayList<>();
        if (merge.actions() != null) {
            for (var action : merge.actions()) {
                if (action instanceof com.usql.ast.USqlAst.MergeInsert ins) {
                    List<IRExpr> values = ins.values().stream().map(this::analyzeExpr).collect(Collectors.toList());
                    actions.add(new IRMerge.MergeInsert(ins.columns(), values));
                } else if (action instanceof com.usql.ast.USqlAst.MergeUpdate upd) {
                    List<IRStatement.SetClause> sets = upd.sets().stream()
                        .map(s -> new IRStatement.SetClause(s.column(), analyzeExpr(s.value())))
                        .collect(Collectors.toList());
                    actions.add(new IRMerge.MergeUpdate(sets));
                } else if (action instanceof com.usql.ast.USqlAst.MergeDelete) {
                    actions.add(new IRMerge.MergeDelete());
                }
            }
        }
        Set<Capability> caps = new LinkedHashSet<>();
        caps.add(Capability.MERGE_INTO);
        return new IRMerge(target, source, onCondition, actions, caps);
    }

    // ══════════════════════════════════════════════════
    //  DDL
    // ══════════════════════════════════════════════════

    private IRStatement analyzeCreateTable(CreateTableStmt ct) {
        Set<Capability> caps = new LinkedHashSet<>();
        List<IRColumnDef> columns = ct.columns().stream()
            .map(c -> analyzeColumnDef(c, caps))
            .collect(Collectors.toList());

        List<IRTableConstraint> constraints = ct.constraints() != null
            ? ct.constraints().stream().map(this::analyzeTableConstraint).collect(Collectors.toList())
            : List.of();

        IRTableName name = new IRTableName(ct.tableName(), null, null);

        IRStatement.TableOptions opts = null;
        if (ct.options() != null) {
            opts = new IRStatement.TableOptions(ct.options().engine(), ct.options().tablespace(),
                ct.options().characterSet(), ct.options().collation(), ct.options().comment());
        }

        return new IRCreateTable(name, ct.ifNotExists(), columns, constraints, opts, caps);
    }

    private IRColumnDef analyzeColumnDef(ColumnDef c, Set<Capability> caps) {
        DataType type;
        if ("ENUM".equalsIgnoreCase(c.typeName()) && c.enumValues() != null && !c.enumValues().isEmpty()) {
            type = new DataType.EnumType(c.enumValues());
        } else {
            type = TypeInferrer.parseTypeName(c.typeName(), c.typePrecision(), c.typeScale());
        }
        List<IRColumnConstraint> constraints = new ArrayList<>();
        if (c.constraints() != null) {
            for (var con : c.constraints()) {
                if (con instanceof NotNullConstraint) constraints.add(new ColNotNull());
                else if (con instanceof PrimaryKeyConstraint pk) {
                    if (pk.autoIncrement()) caps.add(Capability.AUTO_INCREMENT);
                    constraints.add(new ColPrimaryKey(pk.autoIncrement()));
                }
                else if (con instanceof UniqueConstraint) constraints.add(new ColUnique(false));
                else if (con instanceof CheckConstraint chk)
                    constraints.add(new ColCheck(analyzeExpr(chk.condition())));
                else if (con instanceof ReferencesConstraint ref)
                    constraints.add(new ColReferences(ref.targetTable(), ref.targetColumn(),
                        ForeignKeyAction.RESTRICT, ForeignKeyAction.RESTRICT));
                else if (con instanceof GeneratedConstraint gen) {
                    caps.add(Capability.GENERATED_COLUMN);
                    constraints.add(new ColGenerated(
                        gen.always() ? GeneratedStrategy.ALWAYS : GeneratedStrategy.BY_DEFAULT,
                        gen.virtual(), analyzeExpr(gen.expression())));
                }
            }
        }
        IRExpr defaultVal = c.defaultValue() != null ? analyzeExpr(c.defaultValue()) : null;
        return new IRColumnDef(c.name(), type, constraints, defaultVal);
    }

    private IRTableConstraint analyzeTableConstraint(TableConstraint tc) {
        return switch (tc) {
            case TbPrimaryKey pk -> new TBPrimaryKey(pk.columns(), pk.name());
            case TbUnique uq     -> new TBUnique(uq.columns(), uq.name());
            case TbForeignKey fk -> new TBForeignKey(fk.columns(), fk.targetTable(), fk.targetColumns(),
                fk.name(), ForeignKeyAction.RESTRICT, ForeignKeyAction.RESTRICT, false);
            case TbCheck chk     -> new TBCheck(analyzeExpr(chk.condition()), chk.name());
        };
    }

    private IRStatement analyzeCreateIndex(CreateIndexStmt ci) {
        Set<Capability> caps = new LinkedHashSet<>();
        IRExpr where = ci.where() != null ? analyzeExpr(ci.where()) : null;
        if (where != null) caps.add(Capability.PARTIAL_INDEX);

        var cols = ci.columns().stream()
            .<IRStatement.IndexColumn>map(c -> new IRStatement.IndexColumn(c.name(),
                c.desc() ? IRStatement.OrderDir.DESC : IRStatement.OrderDir.ASC,
                c.nullsFirst() ? IRStatement.NullsOrder.FIRST : IRStatement.NullsOrder.LAST))
            .collect(Collectors.toList());

        return new IRCreateIndex(ci.name(), new IRTableName(ci.tableName(), null, null),
            cols, ci.unique(), ci.ifNotExists(), IRStatement.IndexType.BTREE, where, caps);
    }

    // ══════════════════════════════════════════════════
    //  Stored Procedures
    // ══════════════════════════════════════════════════

    private IRCreateProcedure analyzeCreateProcedure(CreateProcedureStmt sp) {
        List<ProcedureParam> params = sp.params() != null
            ? sp.params().stream().map(this::analyzeParam).toList()
            : List.of();
        return new IRCreateProcedure(sp.name(), params, sp.body(), sp.orReplace(), Set.of());
    }

    private IRCreateFunction analyzeCreateFunction(CreateFunctionStmt sf) {
        List<ProcedureParam> params = sf.params() != null
            ? sf.params().stream().map(this::analyzeParam).toList()
            : List.of();
        DataType returnType = TypeInferrer.parseTypeName(
            sf.returnType().name(), sf.returnType().precision(), sf.returnType().scale());
        return new IRCreateFunction(sf.name(), params, returnType, sf.body(), sf.orReplace(), Set.of());
    }

    private IRCall analyzeCall(CallStmt call) {
        List<IRExpr> args = call.args() != null
            ? call.args().stream().map(this::analyzeExpr).toList()
            : List.of();
        return new IRCall(call.name(), args, Set.of());
    }

    private ProcedureParam analyzeParam(ParamDef p) {
        DataType type = TypeInferrer.parseTypeName(p.type().name(), p.type().precision(), p.type().scale());
        ParamMode mode = switch (p.direction()) {
            case IN -> ParamMode.IN;
            case OUT -> ParamMode.OUT;
            case INOUT -> ParamMode.INOUT;
        };
        return new ProcedureParam(p.name(), type, mode);
    }

    // ══════════════════════════════════════════════════
    //  Type inference
    // ══════════════════════════════════════════════════

    private DataType parseTypeName(String name, int precision, int scale) {
        return switch (name.toUpperCase()) {
            case "TINYINT"   -> DataType.IntType.TINYINT;
            case "SMALLINT"  -> DataType.IntType.SMALLINT;
            case "INT", "INTEGER" -> DataType.IntType.INT;
            case "BIGINT"    -> DataType.IntType.BIGINT;
            case "DECIMAL", "NUMERIC" -> new DataType.DecimalType(precision > 0 ? precision : 10, Math.max(scale, 0));
            case "FLOAT"     -> DataType.FloatType.FLOAT;
            case "REAL"      -> DataType.FloatType.FLOAT;
            case "DOUBLE"    -> DataType.FloatType.DOUBLE;
            case "CHAR"      -> new DataType.CharType(precision > 0 ? precision : 1);
            case "VARCHAR"   -> new DataType.VarcharType(precision > 0 ? precision : 255);
            case "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT", "CLOB" -> new DataType.TextType();
            case "BOOLEAN"   -> new DataType.BooleanType();
            case "DATE"      -> new DataType.DateType();
            case "TIME"      -> new DataType.TimeType(Math.max(scale, 0));
            case "DATETIME"  -> new DataType.DatetimeType(Math.max(scale, 0));
            case "TIMESTAMP" -> new DataType.TimestampType(Math.max(scale, 0));
            case "JSON"      -> new DataType.JsonType();
            case "UUID"      -> new DataType.UuidType();
            case "BINARY"    -> new DataType.BinaryType(precision > 0 ? precision : 1);
            case "VARBINARY" -> new DataType.VarbinaryType(precision > 0 ? precision : 255);
            case "BLOB"      -> new DataType.BlobType();
            case "BIT"       -> new DataType.BooleanType();
            default -> new DataType.VarcharType(255); // fallback
        };
    }

    // ══════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════

    private void pushScope() {
        scopes.push(new LinkedHashMap<>());
    }

    private void popScope() {
        if (!scopes.isEmpty()) scopes.pop();
    }

    private IRStatement.SetOp mapSetOp(com.usql.ast.USqlAst.SetOp op) {
        return switch (op) {
            case UNION -> IRStatement.SetOp.UNION;
            case UNION_ALL -> IRStatement.SetOp.UNION_ALL;
            case INTERSECT -> IRStatement.SetOp.INTERSECT;
            case EXCEPT -> IRStatement.SetOp.EXCEPT;
        };
    }

    // ══════════════════════════════════════════════════
    //  Scope entry
    // ══════════════════════════════════════════════════

    record ScopeEntry(String alias, Map<String, DataType> columns, boolean isSubquery, boolean open) {
        /** Create an open scope entry — accepts any column (table not in schema) */
        static ScopeEntry open(String alias) {
            return new ScopeEntry(alias, Map.of(), false, true);
        }
        /** Normal scope entry with known columns */
        ScopeEntry(String alias, Map<String, DataType> columns, boolean isSubquery) {
            this(alias, columns, isSubquery, false);
        }
        /** Check if a column exists in this scope (open scopes accept everything) */
        boolean hasColumn(String name) {
            return open || columns.containsKey(name);
        }
    }
}
