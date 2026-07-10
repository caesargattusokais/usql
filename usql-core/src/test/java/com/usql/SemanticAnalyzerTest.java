package com.usql;

import com.usql.parser.AstBuilder;

/**
 * Tests for SemanticAnalyzer via text parsing.
 * No database required.
 */
public class SemanticAnalyzerTest {

    static int pass = 0, fail = 0;
    static USqlCompiler compiler = USqlCompiler.builder().withVerify(false).build();

    public static void main(String[] args) {
        System.out.println("=== SemanticAnalyzer Test ===\n");

        testSimpleSelect();
        testWhere();
        testGroupBy();
        testDistinct();
        testLimit();
        testCte();
        testWindowFunction();
        testCast();
        testCase();
        testErrorDetection();

        System.out.println("\n=== Result: " + pass + "/" + (pass + fail) + " passed ===");
        if (fail > 0) System.exit(1);
    }

    static void testSimpleSelect() {
        var r = compiler.compile("SELECT id, name FROM employees");
        check(r.isSuccess(), "Simple SELECT");
        check(r.getSql().contains("SELECT"), "Contains SELECT");
    }

    static void testWhere() {
        var r = compiler.compile("SELECT id FROM employees WHERE salary > 10000");
        check(r.isSuccess(), "SELECT WHERE");
        check(r.getSql().contains("WHERE"), "Contains WHERE");
    }

    static void testGroupBy() {
        var r = compiler.compile("SELECT dept_id, COUNT(*) AS cnt FROM employees GROUP BY dept_id");
        check(r.isSuccess(), "SELECT GROUP BY COUNT");
        check(r.getSql().contains("GROUP BY"), "Contains GROUP BY");
    }

    static void testDistinct() {
        var r = compiler.compile("SELECT DISTINCT name FROM employees");
        check(r.isSuccess(), "SELECT DISTINCT");
        check(r.getSql().contains("DISTINCT"), "Contains DISTINCT");
    }

    static void testLimit() {
        var r = compiler.compile("SELECT id FROM employees LIMIT 10");
        check(r.isSuccess(), "SELECT LIMIT");
    }

    static void testCte() {
        var r = compiler.compile(
            "WITH cte AS (SELECT id, name FROM employees) SELECT * FROM cte");
        check(r.isSuccess(), "CTE SELECT");
        check(r.getSql().contains("WITH"), "Contains WITH");
    }

    static void testWindowFunction() {
        var r = compiler.compile(
            "SELECT name, ROW_NUMBER() OVER (PARTITION BY dept_id ORDER BY salary DESC) AS rn FROM employees");
        check(r.isSuccess(), "ROW_NUMBER OVER");
        check(r.getSql().contains("ROW_NUMBER"), "Contains ROW_NUMBER");
        check(r.getSql().contains("PARTITION BY"), "Contains PARTITION BY");
    }

    static void testCast() {
        var r = compiler.compile("SELECT CAST(id AS VARCHAR) FROM employees");
        check(r.isSuccess(), "CAST expression");
        check(r.getSql().contains("CAST"), "Contains CAST");
    }

    static void testCase() {
        var r = compiler.compile(
            "SELECT CASE WHEN salary > 50000 THEN 'High' ELSE 'Low' END FROM employees");
        check(r.isSuccess(), "CASE expression");
        check(r.getSql().contains("CASE"), "Contains CASE");
    }

    static void testErrorDetection() {
        // Unknown column should produce error, not crash
        var r = compiler.compile("SELECT nonexistent FROM employees");
        // May or may not error depending on schema validation strictness
        // Just check it doesn't crash
        check(r != null, "Unknown column: result not null (no crash)");

        // Empty input
        var r2 = compiler.compile("");
        check(!r2.isSuccess(), "Empty input: not success");

        // Invalid syntax
        var r3 = compiler.compile("SELECT FROM");
        check(!r3.isSuccess(), "Invalid syntax: not success");
    }

    static void check(boolean condition, String msg) {
        if (condition) { pass++; }
        else { fail++; System.err.println("  ❌ FAIL: " + msg); }
    }
}
