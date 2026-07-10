package com.usql;

import com.usql.dialect.Dialect;
import com.usql.ir.Capability;
import java.util.Set;

public class DialectTest {
    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        System.out.println("=== Dialect Test ===\n");
        testAllDialectsExist();
        testCapabilitySets();
        testDisplayNames();
        testMissingCapabilities();
        System.out.println("\n=== " + pass + "/" + (pass+fail) + " passed ===");
        if (fail > 0) System.exit(1);
    }

    static void testAllDialectsExist() {
        Dialect[] all = {Dialect.MYSQL, Dialect.POSTGRESQL, Dialect.ORACLE,
                         Dialect.DM, Dialect.SQLSERVER, Dialect.H2};
        check(all.length == 6, "6 dialects defined");
    }

    static void testCapabilitySets() {
        // MySQL
        check(Dialect.MYSQL.supports(Capability.LIMIT_OFFSET), "MySQL LIMIT_OFFSET ✓");
        check(Dialect.MYSQL.supports(Capability.WINDOW_FUNCTION), "MySQL WINDOW_FUNCTION ✓");
        check(!Dialect.MYSQL.supports(Capability.FULL_OUTER_JOIN), "MySQL FULL_OUTER_JOIN ✗");
        check(!Dialect.MYSQL.supports(Capability.LATERAL_JOIN), "MySQL LATERAL_JOIN ✗");
        check(!Dialect.MYSQL.supports(Capability.RETURNING_CLAUSE), "MySQL RETURNING_CLAUSE ✗");

        // PostgreSQL — most complete
        check(Dialect.POSTGRESQL.supports(Capability.LIMIT_OFFSET), "PG LIMIT_OFFSET ✓");
        check(Dialect.POSTGRESQL.supports(Capability.WINDOW_FUNCTION), "PG WINDOW_FUNCTION ✓");
        check(Dialect.POSTGRESQL.supports(Capability.FULL_OUTER_JOIN), "PG FULL_OUTER_JOIN ✓");
        check(Dialect.POSTGRESQL.supports(Capability.LATERAL_JOIN), "PG LATERAL_JOIN ✓");
        check(Dialect.POSTGRESQL.supports(Capability.ARRAY_TYPE), "PG ARRAY_TYPE ✓");
        check(Dialect.POSTGRESQL.supports(Capability.BOOLEAN_TYPE), "PG BOOLEAN_TYPE ✓");
        check(!Dialect.POSTGRESQL.supports(Capability.AUTO_INCREMENT), "PG AUTO_INCREMENT ✗");

        // Oracle
        check(Dialect.ORACLE.supports(Capability.WINDOW_FUNCTION), "Oracle WINDOW_FUNCTION ✓");
        check(Dialect.ORACLE.supports(Capability.MERGE_INTO), "Oracle MERGE_INTO ✓");
        check(!Dialect.ORACLE.supports(Capability.LIMIT_OFFSET), "Oracle LIMIT_OFFSET ✗");
        check(!Dialect.ORACLE.supports(Capability.BOOLEAN_TYPE), "Oracle BOOLEAN_TYPE ✗");

        // SQL Server
        check(Dialect.SQLSERVER.supports(Capability.LIMIT_OFFSET), "SQL Server LIMIT_OFFSET ✓");
        check(Dialect.SQLSERVER.supports(Capability.WINDOW_FUNCTION), "SQL Server WINDOW_FUNCTION ✓");
        check(!Dialect.SQLSERVER.supports(Capability.BOOLEAN_TYPE), "SQL Server BOOLEAN_TYPE ✗");

        // DM
        check(Dialect.DM.supports(Capability.WINDOW_FUNCTION), "DM WINDOW_FUNCTION ✓");
        check(Dialect.DM.supports(Capability.AUTO_INCREMENT), "DM AUTO_INCREMENT ✓");
    }

    static void testDisplayNames() {
        check(Dialect.MYSQL.displayName().equals("MySQL"), "MySQL displayName");
        check(Dialect.POSTGRESQL.displayName().equals("PostgreSQL"), "PG displayName");
        check(Dialect.ORACLE.displayName().equals("Oracle"), "Oracle displayName");
        check(Dialect.DM.displayName().equals("达梦DM"), "DM displayName");
        check(Dialect.SQLSERVER.displayName().equals("SQL Server"), "SQL Server displayName");
    }

    static void testMissingCapabilities() {
        Set<Capability> required = Set.of(Capability.LIMIT_OFFSET, Capability.WINDOW_FUNCTION);
        Set<Capability> missing = Dialect.ORACLE.missingCapabilities(required);
        check(missing.contains(Capability.LIMIT_OFFSET), "Oracle missing LIMIT_OFFSET from {LIMIT_OFFSET, WINDOW}");
        check(!missing.contains(Capability.WINDOW_FUNCTION), "Oracle NOT missing WINDOW_FUNCTION");

        Set<Capability> missing2 = Dialect.POSTGRESQL.missingCapabilities(required);
        check(missing2.isEmpty(), "PG missing nothing from common caps");
    }

    static void check(boolean c, String m) { if(c) pass++; else { fail++; System.err.println("  ❌ "+m); } }
}
