package com.usql.backend;

import com.usql.dialect.Dialect;
import com.usql.ir.*;
import com.usql.ir.IRStatement.*;
import java.util.stream.Collectors;

/**
 * DuckDB backend — extends PgBackend, overrides only DDL differences.
 * DuckDB is PostgreSQL-compatible except:
 *   - No GENERATED AS IDENTITY (use plain PRIMARY KEY for auto-increment)
 *   - No stored procedures
 */
public class DuckDbBackend extends PgBackend {

    @Override
    public Dialect targetDialect() { return Dialect.DUCKDB; }

    @Override
    public String generate(IRStatement stmt, GenerateOptions opt) {
        return switch (stmt) {
            case IRCreateTable ct          -> duckCreateTable(ct, opt);
            case IRCreateProcedure cp      -> "-- DuckDB: stored procedures not supported";
            case IRCreateFunction cf       -> "-- DuckDB: stored functions not supported";
            case IRCall call               -> "-- DuckDB: CALL not supported";
            default -> super.generate(stmt, opt);
        };
    }

    private String duckCreateTable(IRCreateTable ct, GenerateOptions opt) {
        // Generate sequences for auto-increment columns
        var preSeq = new StringBuilder();
        for (var col : ct.columns()) {
            if (col.constraints() != null) {
                for (var c : col.constraints()) {
                    if (c instanceof ColPrimaryKey pk && pk.autoIncrement()) {
                        preSeq.append("CREATE SEQUENCE IF NOT EXISTS ")
                            .append(quoteIdentifier(col.name() + "_seq")).append(";\n");
                    }
                }
            }
        }
        var sb = new StringBuilder(preSeq.toString());
        sb.append("CREATE TABLE ");
        if (ct.ifNotExists()) sb.append("IF NOT EXISTS ");
        sb.append(quoteIdentifier(ct.name().name())).append(" (\n");
        sb.append(ct.columns().stream()
            .map(c -> duckColumnDef(c, opt))
            .collect(Collectors.joining(",\n")));
        if (ct.constraints() != null && !ct.constraints().isEmpty()) {
            sb.append(",\n");
            sb.append(ct.constraints().stream()
                .map(c -> duckTableConstraint(c, opt))
                .collect(Collectors.joining(",\n")));
        }
        sb.append("\n)");
        return sb.toString();
    }

    private String duckColumnDef(IRColumnDef col, GenerateOptions opt) {
        var sb = new StringBuilder("  ").append(quoteIdentifier(col.name())).append(" ").append(mapType(col.type()));
        if (col.defaultValue() != null)
            sb.append(" DEFAULT ").append(superGenerateExpr(col.defaultValue(), opt));
        if (col.constraints() != null) {
            boolean isPK = false;
            for (var c : col.constraints()) {
                if (c instanceof ColPrimaryKey pk) {
                isPK = true;
                if (pk.autoIncrement())
                    sb.append(" DEFAULT nextval('" + quoteIdentifier(col.name() + "_seq") + "')");
            } else if (c instanceof ColNotNull && !isPK) sb.append(" NOT NULL");
                else if (c instanceof ColUnique) sb.append(" UNIQUE");
                else if (c instanceof ColCheck chk)
                    sb.append(" CHECK (").append(superGenerateExpr(chk.condition(), opt)).append(")");
                else if (c instanceof ColReferences ref)
                    sb.append(" REFERENCES ").append(quoteIdentifier(ref.targetTable()))
                      .append("(").append(quoteIdentifier(ref.targetColumn())).append(")");
            }
            if (isPK) sb.append(" PRIMARY KEY");
        }
        return sb.toString();
    }

    private String duckTableConstraint(IRTableConstraint c, GenerateOptions opt) {
        return switch (c) {
            case TBPrimaryKey pk -> "  PRIMARY KEY ("
                + pk.columns().stream().map(this::quoteIdentifier).collect(Collectors.joining(", ")) + ")";
            case TBUnique uq -> "  UNIQUE ("
                + uq.columns().stream().map(this::quoteIdentifier).collect(Collectors.joining(", ")) + ")";
            case TBForeignKey fk -> "  FOREIGN KEY ("
                + fk.columns().stream().map(this::quoteIdentifier).collect(Collectors.joining(", "))
                + ") REFERENCES " + quoteIdentifier(fk.targetTable()) + "("
                + fk.targetColumns().stream().map(this::quoteIdentifier).collect(Collectors.joining(", ")) + ")";
            case TBCheck chk -> "  CHECK (" + superGenerateExpr(chk.condition(), opt) + ")";
        };
    }

    // PgBackend.generateExpr is private — need to expose it via super call hack
    // DuckDB uses same expression generation as PG
    private String superGenerateExpr(IRExpr expr, GenerateOptions opt) {
        // Go through generate() with a dummy SELECT to get expr rendering
        var dummy = new IRSelect(new SelectCore(
            java.util.List.of(new IRExprSelect(expr, null)),
            null, null, null, null, null, null, null, false),
            null, null, java.util.Set.of());
        String sql = super.generate(dummy, GenerateOptions.MINIMAL);
        // "SELECT expr" → extract just "expr" (robust: find first space after SELECT)
        int idx = sql.indexOf(' ');
        return idx >= 0 ? sql.substring(idx + 1).trim() : sql.trim();
    }
}
