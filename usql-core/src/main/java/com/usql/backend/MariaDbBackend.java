package com.usql.backend;

import com.usql.dialect.Dialect;
import com.usql.ir.*;
import com.usql.ir.IRStatement.*;

import java.util.stream.Collectors;

/**
 * MariaDB backend — extends MySQL with native IF NOT EXISTS support
 * for ALTER TABLE ADD COLUMN (MariaDB 10.0+) and CREATE INDEX (MariaDB 10.5+),
 * avoiding the MySQL stored-procedure polyfill.
 */
public class MariaDbBackend extends MySqlBackend {

    @Override
    public Dialect targetDialect() { return Dialect.MARIADB; }

    @Override
    protected String generateAlterTableAddColumn(IRAlterTableAddColumn aa, GenerateOptions opt) {
        var col = aa.column();
        var sb = new StringBuilder("ALTER TABLE ").append(quoteIdentifier(aa.tableName()))
            .append(" ADD ");
        if (aa.ifNotExists()) sb.append("IF NOT EXISTS ");
        sb.append(quoteIdentifier(col.name())).append(" ").append(mapType(col.type()));
        if (col.constraints() != null) {
            for (var c : col.constraints()) {
                if (c instanceof ColNotNull) sb.append(" NOT NULL");
                else if (c instanceof ColPrimaryKey) sb.append(" PRIMARY KEY");
                else if (c instanceof ColUnique) sb.append(" UNIQUE");
            }
        }
        return sb.toString();
    }

    /** MariaDB 10.5+ supports CREATE INDEX IF NOT EXISTS natively — no stored-procedure
     *  polyfill needed (and no allowMultiQueries requirement). */
    @Override
    protected String generateCreateIndex(IRCreateIndex idx, GenerateOptions opt) {
        var sb = new StringBuilder("CREATE ");
        if (idx.unique()) sb.append("UNIQUE ");
        sb.append("INDEX ");
        if (idx.ifNotExists()) sb.append("IF NOT EXISTS ");
        sb.append(quoteIdentifier(idx.name()));
        sb.append(" ON ").append(quoteIdentifier(idx.table().name()));
        sb.append(" (");
        sb.append(idx.columns().stream()
            .map(c -> quoteIdentifier(c.name()) + (c.dir() == OrderDir.DESC ? " DESC" : ""))
            .collect(Collectors.joining(", ")));
        sb.append(")");
        if (idx.type() != null && idx.type() != IndexType.BTREE) {
            sb.append(" USING ").append(idx.type().name());
        }
        return sb.toString();
    }
}

