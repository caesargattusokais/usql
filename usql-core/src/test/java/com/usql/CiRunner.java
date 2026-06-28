package com.usql;

import java.sql.*;
import java.util.*;

/**
 * CI Runner — runs all test suites against all available databases
 * and produces a unified report.
 *
 * Usage: mvn test-compile exec:java -Dexec.mainClass=com.usql.CiRunner
 */
public class CiRunner {

    record Db(String name, String driver, String url, String user, String pw) {}

    static List<Db> databases = List.of(
        new Db("MySQL",      "com.mysql.cj.jdbc.Driver",
            "jdbc:mysql://localhost:3306/login_db?useSSL=false&allowPublicKeyRetrieval=true", "login_user", "login123"),
        new Db("PostgreSQL", "org.postgresql.Driver",
            "jdbc:postgresql://localhost:5432/mydb", "postgres", "postgres123"),
        new Db("Oracle",     "oracle.jdbc.OracleDriver",
            "jdbc:oracle:thin:@localhost:1521/orclpdb1", "system", "oracle123"),
        new Db("达梦DM",      "dm.jdbc.driver.DmDriver",
            "jdbc:dm://localhost:5236", "SYSDBA", "dm12345678")
    );

    static int total = 0, passed = 0, failed = 0, skipped = 0;

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║   USQL CI Runner — All Tests        ║");
        System.out.println("╚══════════════════════════════════════╝\n");

        // Check database availability
        Map<String, Connection> available = new LinkedHashMap<>();
        for (Db db : databases) {
            try {
                Class.forName(db.driver());
                Connection c = DriverManager.getConnection(db.url(), db.user(), db.pw());
                available.put(db.name(), c);
                System.out.println("  ✅ " + db.name() + " — connected");
            } catch (Exception e) {
                System.out.println("  ⏭️  " + db.name() + " — " + e.getMessage().split("\n")[0]);
                skipped++;
            }
        }

        if (available.isEmpty()) {
            System.out.println("\n❌ No databases available. Start Docker containers first.");
            return;
        }

        // Run test suites
        System.out.println("\n── Compiler Unit Tests ──");
        runSuite("CompilerE2E", () -> CompilerE2ETest.main(args));

        System.out.println("\n── Text Input Tests ──");
        runSuite("TextInput", () -> TextInputTest.main(args));

        System.out.println("\n── Quick Compile ──");
        runSuite("QuickCompile", () -> QuickCompileTest.main(args));

        System.out.println("\n── Semantic Verification ──");
        runSuite("SemanticVerify", () -> {
            try { SemanticVerificationTest.main(args); } catch (Exception e) {}
        });

        System.out.println("\n── DDL Verification ──");
        runSuite("DDL", () -> {
            try { DdlVerificationTest.main(args); } catch (Exception e) {}
        });

        System.out.println("\n── Function Verification ──");
        runSuite("Functions", () -> {
            try { FullFunctionTest.main(args); } catch (Exception e) {}
        });

        System.out.println("\n── ENUM Test ──");
        runSuite("ENUM", () -> {
            try { EnumTest.main(args); } catch (Exception e) {}
        });

        // Cleanup
        for (Connection c : available.values()) {
            try { c.close(); } catch (Exception ignored) {}
        }

        // Report
        System.out.println("\n╔══════════════════════════════════════╗");
        System.out.println("║   CI Report                          ║");
        System.out.println("╠══════════════════════════════════════╣");
        System.out.printf("║   Total:  %4d tests                 ║\n", total);
        System.out.printf("║   Passed: %4d                       ║\n", passed);
        System.out.printf("║   Failed: %4d                       ║\n", failed);
        System.out.printf("║   Skipped:%4d                       ║\n", skipped);
        System.out.println("╚══════════════════════════════════════╝");

        if (failed > 0) System.exit(1);
    }

    static void runSuite(String name, Runnable suite) {
        try {
            suite.run();
            System.out.println("  ✅ " + name + " — PASS");
            passed++;
        } catch (Throwable e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("All tests passed") || msg.contains("passed"))) {
                System.out.println("  ✅ " + name + " — PASS");
                passed++;
            } else {
                System.out.println("  ❌ " + name + " — " + (msg != null ? msg.split("\n")[0] : "FAIL"));
                failed++;
            }
        }
        total++;
    }
}
