package com.usql.verify;

import com.usql.ir.DataType;
import com.usql.ir.IRStatement.*;
import com.usql.ir.IRStatement;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Auto-generates test data INSERT statements from table schema definitions.
 *
 * Analyses column types and constraints to produce sensible default values.
 * Handles foreign key relationships by generating compatible values.
 */
public class TestDataGenerator {

    private final int rowCount;
    private final Random rng;

    public TestDataGenerator(int rowCount) {
        this.rowCount = rowCount;
        this.rng = new Random(42); // deterministic seed
    }

    public TestDataGenerator() {
        this(5);
    }

    // ══════════════════════════════════════════════════
    //  Public API
    // ══════════════════════════════════════════════════

    /**
     * Generate INSERT statements for the given tables.
     * Tables are processed in order so foreign keys can reference earlier tables' PKs.
     */
    public List<String> generate(List<IRCreateTable> tables) {
        List<String> statements = new ArrayList<>();
        Map<String, List<Integer>> pkValues = new LinkedHashMap<>(); // table → PK values

        for (IRCreateTable table : tables) {
            String tableName = table.name().name();
            List<String> insertStatements = generateForTable(table, pkValues);
            statements.addAll(insertStatements);

            // Record PK values for foreign key references
            for (var col : table.columns()) {
                for (var c : col.constraints()) {
                    if (c instanceof ColPrimaryKey) {
                        List<Integer> pks = new ArrayList<>();
                        for (int i = 1; i <= rowCount; i++) pks.add(i);
                        pkValues.put(tableName, pks);
                    }
                }
            }
        }
        return statements;
    }

    /**
     * Generate INSERTs for a single table, returned as separate SQL strings.
     */
    public List<String> generateForTable(IRCreateTable table, Map<String, List<Integer>> pkValues) {
        String tableName = table.name().name();
        List<String> colNames = table.columns().stream()
            .map(IRColumnDef::name).toList();
        String colList = colNames.stream()
            .map(n -> "\"" + n + "\"")
            .collect(Collectors.joining(", "));

        List<String> inserts = new ArrayList<>();
        for (int row = 1; row <= rowCount; row++) {
            List<String> values = new ArrayList<>();
            for (var col : table.columns()) {
                values.add(generateValue(col, row, pkValues));
            }
            String valList = String.join(", ", values);
            inserts.add("INSERT INTO \"" + tableName + "\" (" + colList + ") VALUES (" + valList + ")");
        }
        return inserts;
    }

    // ══════════════════════════════════════════════════
    //  Value generation per column type
    // ══════════════════════════════════════════════════

    private String generateValue(IRColumnDef col, int row, Map<String, List<Integer>> pkValues) {
        // Check for foreign key — use referenced PK values
        for (var c : col.constraints()) {
            if (c instanceof ColReferences ref) {
                List<Integer> refPks = pkValues.get(ref.targetTable());
                if (refPks != null && !refPks.isEmpty()) {
                    int idx = (row - 1) % refPks.size();
                    return String.valueOf(refPks.get(idx));
                }
            }
        }

        // Handle NULL for nullable columns (last row gets NULL)
        boolean hasNotNull = col.constraints() != null && col.constraints().stream()
            .anyMatch(c -> c instanceof ColNotNull);
        if (!hasNotNull && row == rowCount) return "NULL";

        // Check for auto-increment primary key
        boolean isAutoInc = col.constraints() != null && col.constraints().stream()
            .anyMatch(c -> c instanceof ColPrimaryKey pk && pk.autoIncrement());
        if (isAutoInc) return String.valueOf(row);

        // Plain primary key — use row number
        boolean isPK = col.constraints() != null && col.constraints().stream()
            .anyMatch(c -> c instanceof ColPrimaryKey);
        if (isPK) return String.valueOf(row);

        // Default value present
        if (col.defaultValue() != null) return "DEFAULT";

        return generateTypedValue(col.type(), col.name(), row);
    }

    private String generateTypedValue(DataType type, String colName, int row) {
        if (type instanceof DataType.IntType it) {
            long max = it.bits() <= 8 ? 100 : it.bits() <= 16 ? 30000 : 1000000;
            return String.valueOf(row * 10 + rng.nextInt(10));
        }
        if (type instanceof DataType.FloatType || type instanceof DataType.DecimalType) {
            return String.format(java.util.Locale.US, "%.2f", row * 1000.0 + rng.nextDouble() * 999);
        }
        if (type instanceof DataType.VarcharType || type instanceof DataType.CharType
            || type instanceof DataType.TextType) {
            String[] words = {"Alpha", "Bravo", "Charlie", "Delta", "Echo",
                              "Foxtrot", "Golf", "Hotel", "India", "Juliet"};
            return "'" + words[(row - 1) % words.length] + "'";
        }
        if (type instanceof DataType.BooleanType) {
            return row % 2 == 1 ? "TRUE" : "FALSE";
        }
        if (type instanceof DataType.DateType) {
            return "DATE '202" + ((row - 1) % 5) + "-0" + ((row % 9) + 1) + "-" +
                String.format("%02d", (row * 5) % 28 + 1) + "'";
        }
        if (type instanceof DataType.DatetimeType || type instanceof DataType.TimestampType) {
            return "TIMESTAMP '202" + ((row - 1) % 5) + "-0" + ((row % 9) + 1) + "-" +
                String.format("%02d", (row * 5) % 28 + 1) + " 10:00:00'";
        }
        if (type instanceof DataType.TimeType) {
            return "TIME '" + String.format("%02d", (row * 3) % 24) + ":00:00'";
        }
        if (type instanceof DataType.EnumType et) {
            String val = et.values().get((row - 1) % et.values().size());
            return "'" + val.replace("'", "''") + "'";
        }
        if (type instanceof DataType.JsonType) {
            return "'{\"id\":" + row + ",\"key\":\"value_" + row + "\"}'";
        }
        if (type instanceof DataType.UuidType) {
            return "'00000000-0000-0000-0000-00000000000" + String.format("%x", row) + "'";
        }
        if (type instanceof DataType.BinaryType || type instanceof DataType.VarbinaryType
            || type instanceof DataType.BlobType) {
            return "X'00" + String.format("%02X", row) + "'";
        }
        // Fallback: string
        return "'val_" + row + "'";
    }
}
