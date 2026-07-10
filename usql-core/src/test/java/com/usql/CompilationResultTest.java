package com.usql;

import com.usql.CompilationResult.Error;
import com.usql.CompilationResult.Warning;

import java.util.List;

/**
 * Tests for CompilationResult error/warning reporting.
 */
public class CompilationResultTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        System.out.println("=== CompilationResult Test ===\n");

        testError();
        testWarning();
        testSuccess();
        testReport();

        System.out.println("\n=== Result: " + pass + "/" + (pass + fail) + " passed ===");
        if (fail > 0) System.exit(1);
    }

    static void testError() {
        // Error with hint
        Error e = Error.of(1, 5, "Unexpected token", "Try using a valid keyword");
        check(e.line() == 1, "Error line = 1");
        check(e.col() == 5, "Error col = 5");
        check(e.message().equals("Unexpected token"), "Error message preserved");
        check(e.hint().equals("Try using a valid keyword"), "Error hint preserved");

        // Error without hint
        Error e2 = Error.of(0, 0, "Syntax error");
        check(e2.hint() == null, "Error without hint has null hint");

        // Failed compilation
        CompilationResult r = CompilationResult.failed(List.of(e, e2));
        check(!r.isSuccess(), "Failed result isSuccess = false");
        check(r.getSql() == null, "Failed result has null SQL");
        check(r.getErrors().size() == 2, "2 errors");
        check(r.getWarnings().isEmpty(), "No warnings in failed result");

        System.out.println("  ✅ Error: 8 checks");
    }

    static void testWarning() {
        // Warning without hint
        Warning w = Warning.of(0, 0, "Polyfill applied");
        check(w.message().equals("Polyfill applied"), "Warning message");
        check(w.hint() == null, "No hint by default");

        // Warning with hint
        Warning w2 = Warning.of(0, 0, "Type widened", "INT promoted to BIGINT");
        check(w2.hint() != null, "Warning with hint");
        check(w2.hint().equals("INT promoted to BIGINT"), "Hint content");

        System.out.println("  ✅ Warning: 4 checks");
    }

    static void testSuccess() {
        // Success with SQL
        CompilationResult r = CompilationResult.success("SELECT * FROM t");
        check(r.isSuccess(), "Success isSuccess = true");
        check(r.getSql().equals("SELECT * FROM t"), "SQL preserved");
        check(r.getErrors().isEmpty(), "No errors");
        check(r.getReferenceSql() == null, "No reference SQL by default");

        // Success with SQL + warnings + reference
        CompilationResult r2 = CompilationResult.success(
            "SELECT * FROM t",
            "SELECT * FROM \"t\"",
            List.of(Warning.of(0, 0, "Limit polyfill applied")));
        check(r2.isSuccess(), "Success with reference");
        check(r2.getReferenceSql().equals("SELECT * FROM \"t\""), "Reference SQL preserved");
        check(r2.getWarnings().size() == 1, "1 warning");

        System.out.println("  ✅ Success: 4 checks");
    }

    static void testReport() {
        Error e = Error.of(2, 3, "Unknown column 'foo'", "Did you mean 'bar'?");
        CompilationResult r = CompilationResult.failed(List.of(e));
        String report = r.report();
        check(report.contains("❌ FAILED"), "Report shows failure");
        check(report.contains("Error [2:3]"), "Report includes error location");
        check(report.contains("Unknown column 'foo'"), "Report includes error message");
        check(report.contains("Did you mean 'bar'?"), "Report includes hint");

        Warning w = Warning.of(1, 1, "Polyfill: LIMIT_OFFSET → ROWNUM", "Oracle polyfill");
        CompilationResult r2 = CompilationResult.success("SELECT * FROM t",
            "SELECT * FROM t",
            List.of(w));
        String report2 = r2.report();
        check(report2.contains("✅ SUCCESS"), "Report shows success");
        check(report2.contains("Polyfill: LIMIT_OFFSET"), "Report includes warning");
        check(report2.contains("Oracle polyfill"), "Report includes warning hint");
        check(report2.contains("SELECT * FROM t"), "Report includes generated SQL");
        check(report2.contains("H2 Reference"), "Report includes reference section");

        System.out.println("  ✅ Report: 9 checks");
    }

    static void check(boolean condition, String msg) {
        if (condition) { pass++; }
        else { fail++; System.err.println("  ❌ FAIL: " + msg); }
    }
}
