package com.usql.backend;

import com.usql.ir.IRStatement;
import com.usql.dialect.Dialect;

/**
 * Generates target-dialect SQL from the Semantic IR.
 * One implementation per database.
 */
public interface DialectBackend {

    /** Which dialect does this backend target? */
    Dialect targetDialect();

    /**
     * Generate SQL text from the IR statement.
     *
     * @param statement  the semantically analyzed IR
     * @param options    generation options (quoting style, indentation, etc.)
     * @return generated SQL string
     */
    String generate(IRStatement statement, GenerateOptions options);

    /**
     * Generate SQL text with default options.
     */
    default String generate(IRStatement statement) {
        return generate(statement, GenerateOptions.DEFAULTS);
    }

    /**
     * Quote an identifier (table name, column name) according to dialect conventions.
     */
    String quoteIdentifier(String identifier);

    /**
     * Map a U-SQL canonical type to this dialect's native type name.
     */
    String mapType(com.usql.ir.DataType type);
}
