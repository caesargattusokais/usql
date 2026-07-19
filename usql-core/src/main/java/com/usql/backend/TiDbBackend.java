package com.usql.backend;

import com.usql.dialect.Dialect;
import com.usql.ir.*;
import com.usql.ir.IRStatement.*;

import java.util.stream.Collectors;

/**
 * TiDB backend — MySQL-protocol but with native CREATE INDEX IF NOT EXISTS
 * (TiDB supports it, unlike MySQL 8.0). Also note TiDB does NOT support dynamic
 * PREPARE inside stored procedures, so the MySQL stored-procedure polyfill would
 * fail — use the native IF NOT EXISTS clause instead.
 */
public class TiDbBackend extends MySqlBackend {

    @Override
    public Dialect targetDialect() { return Dialect.TIDB; }

    /** TiDB supports CREATE INDEX IF NOT EXISTS natively — no polyfill needed. */
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
