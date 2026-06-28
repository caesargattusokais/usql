package com.usql.cli;

import com.usql.USqlCompiler;
import com.usql.dialect.Dialect;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * USQL Command Line Interface.
 *
 * Usage:
 *   java -jar usql-cli.jar translate --sql "SELECT ..." --to oracle
 *   java -jar usql-cli.jar migrate --from mysql --to postgres --input ./sql/ --output ./out/
 *   java -jar usql-cli.jar verify --sql "SELECT ..." --to oracle
 */
public class Main {

    private static final USqlCompiler compiler = USqlCompiler.builder().build();

    public static void main(String[] args) {
        if (args.length == 0) {
            printHelp();
            return;
        }

        try {
            switch (args[0]) {
                case "translate" -> cmdTranslate(args);
                case "migrate"   -> cmdMigrate(args);
                case "verify"    -> cmdVerify(args);
                case "dialects"  -> cmdDialects();
                default -> { System.out.println("Unknown command: " + args[0]); printHelp(); }
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    /** Translate a single SQL statement. */
    private static void cmdTranslate(String[] args) {
        String sql = null;
        Dialect to = null;

        for (int i = 1; i < args.length; i++) {
            if ("--sql".equals(args[i]) && i + 1 < args.length) sql = args[++i];
            else if ("--to".equals(args[i]) && i + 1 < args.length) to = Dialect.valueOf(args[++i].toUpperCase());
        }

        if (sql == null || to == null) {
            System.out.println("Usage: translate --sql \"SELECT ...\" --to <mysql|postgresql|oracle|dm>");
            return;
        }

        var result = compiler.compile(sql, to);
        if (result.isSuccess()) {
            System.out.println(result.getSql());
        } else {
            System.out.println(result.report());
            System.exit(1);
        }
    }

    /** Batch migrate SQL files from one dialect to another. */
    private static void cmdMigrate(String[] args) throws IOException {
        Dialect to = null;
        String input = null, output = null;

        for (int i = 1; i < args.length; i++) {
            if ("--from".equals(args[i]) && i + 1 < args.length) { i++; /* skip */ }
            else if ("--to".equals(args[i]) && i + 1 < args.length) to = Dialect.valueOf(args[++i].toUpperCase());
            else if ("--input".equals(args[i]) && i + 1 < args.length) input = args[++i];
            else if ("--output".equals(args[i]) && i + 1 < args.length) output = args[++i];
        }

        if (to == null || input == null || output == null) {
            System.out.println("Usage: migrate --to <dialect> --input <dir> --output <dir>");
            return;
        }

        Path inDir = Path.of(input);
        Path outDir = Path.of(output);
        Dialect finalTo = to;
        Files.createDirectories(outDir);

        try (var files = Files.list(inDir)) {
            files.filter(f -> f.toString().endsWith(".sql")).forEach(f -> {
                try {
                    String usql = Files.readString(f);
                    var result = compiler.compile(usql, finalTo);
                    Path outFile = outDir.resolve(f.getFileName());
                    if (result.isSuccess()) {
                        Files.writeString(outFile, result.getSql());
                        System.out.println("  OK: " + f.getFileName());
                    } else {
                        System.out.println("  FAIL: " + f.getFileName() + " — " + result.report());
                    }
                } catch (IOException e) {
                    System.out.println("  ERR: " + f.getFileName() + " — " + e.getMessage());
                }
            });
        }
    }

    /** Verify a SQL statement against a target database. */
    private static void cmdVerify(String[] args) {
        String sql = null;
        Dialect to = null;

        for (int i = 1; i < args.length; i++) {
            if ("--sql".equals(args[i]) && i + 1 < args.length) sql = args[++i];
            else if ("--to".equals(args[i]) && i + 1 < args.length) to = Dialect.valueOf(args[++i].toUpperCase());
        }

        if (sql == null || to == null) {
            System.out.println("Usage: verify --sql \"SELECT ...\" --to <mysql|postgresql|oracle|dm>");
            return;
        }

        var result = compiler.compile(sql, to);
        System.out.println(result.isSuccess() ? "✅ Valid" : "❌ " + result.report());
    }

    /** List supported dialects. */
    private static void cmdDialects() {
        System.out.println("Supported dialects:");
        for (Dialect d : Dialect.values()) {
            if (d == Dialect.H2) continue;
            System.out.printf("  %-12s — %s\n", d.name(), d.displayName());
        }
    }

    private static void printHelp() {
        System.out.println("USQL CLI — Universal SQL Compiler");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  translate  Translate a single SQL statement");
        System.out.println("  migrate    Batch migrate SQL files");
        System.out.println("  verify     Verify SQL against a target dialect");
        System.out.println("  dialects   List supported dialects");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  usql translate --sql \"SELECT ... LIMIT 10\" --to oracle");
        System.out.println("  usql migrate --from mysql --to postgres --input ./sql/ --output ./pg-sql/");
        System.out.println("  usql verify --sql \"SELECT ...\" --to oracle");
    }
}
