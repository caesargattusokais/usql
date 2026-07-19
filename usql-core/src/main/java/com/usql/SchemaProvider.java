package com.usql;

import com.usql.ir.DataType;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Provides schema information to the compiler.
 * The compiler uses this for name resolution and type derivation.
 */
public interface SchemaProvider {

    /** Empty schema — for simple queries that don't reference tables */
    SchemaProvider EMPTY = new SchemaProvider() {};

    /**
     * Look up a table by name.
     * @return the table definition, or empty if not found
     */
    default Optional<TableDef> getTable(String name) {
        return Optional.empty();
    }

    /**
     * Look up a table by schema-qualified name.
     * Default implementation ignores the schema qualifier — override this
     * in schema-aware implementations to support multi-schema lookups.
     */
    default Optional<TableDef> getTable(String schema, String name) {
        // Try schema-qualified lookup first, then fall back to unqualified
        // Default: no schema support — just use the name
        return getTable(name);
    }

    /**
     * List all known table names (for error reporting).
     */
    default List<String> tableNames() {
        return List.of();
    }

    /**
     * Does a table exist?
     */
    default boolean tableExists(String name) {
        return getTable(name).isPresent();
    }

    // ── Supporting types ──

    /** Table definition */
    record TableDef(
        String name,
        String schema,
        List<ColumnDef> columns,
        List<IndexDef> indexes,
        List<ForeignKeyDef> foreignKeys
    ) {}

    /** Column definition */
    record ColumnDef(
        String name,
        DataType type,
        boolean nullable,
        boolean primaryKey,
        Object defaultValue
    ) {}

    /** Index definition */
    record IndexDef(
        String name,
        List<String> columns,
        boolean unique,
        boolean clustered
    ) {}

    /** Foreign key definition */
    record ForeignKeyDef(
        String name,
        List<String> columns,
        String targetTable,
        List<String> targetColumns,
        String onUpdate,
        String onDelete
    ) {}
}
