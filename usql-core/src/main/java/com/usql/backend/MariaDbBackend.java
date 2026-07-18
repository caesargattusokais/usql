package com.usql.backend;

import com.usql.dialect.Dialect;
import com.usql.ir.*;
import com.usql.ir.IRStatement.*;

/**
 * MariaDB backend — extends MySQL with IF NOT EXISTS support
 * for ALTER TABLE ADD COLUMN (MariaDB 10.0+).
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
}
