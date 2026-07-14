package com.usql;

import com.usql.parser.HandLexer;
import com.usql.parser.HandLexer.*;
import java.util.List;

public class HandLexerTest {
    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        System.out.println("=== HandLexer Test ===\n");
        testKeywords(); testIdentifiers(); testStrings(); testNumbers();
        testOperators(); testComments(); testComplexSQL();
        System.out.println("\n=== " + pass + "/" + (pass+fail) + " ===");
        if (fail > 0) System.exit(1);
    }

    static void testKeywords() {
        List<Token> tokens = tokenize("SELECT FROM WHERE AND OR NOT IN IS NULL TRUE FALSE");
        chk(tokens.size() >= 10, ">= 10 tokens (got " + tokens.size() + ") — includes EOF");
        chk(tokens.get(0).is(TokenType.SELECT), "SELECT");
        chk(tokens.get(1).is(TokenType.FROM), "FROM");
        chk(tokens.get(2).is(TokenType.WHERE), "WHERE");
        chk(tokens.get(4).is(TokenType.OR), "OR");
        chk(tokens.get(7).is(TokenType.IS), "IS");
        chk(tokens.get(8).is(TokenType.NULL), "NULL");
        chk(tokens.get(9).is(TokenType.TRUE), "TRUE");
        chk(tokens.get(10).is(TokenType.FALSE), "FALSE");
    }

    static void testIdentifiers() {
        List<Token> t = tokenize("my_table col1 _private");
        chk(t.get(0).is(TokenType.IDENTIFIER) && t.get(0).text.equals("my_table"), "identifier my_table");
        chk(t.get(1).is(TokenType.IDENTIFIER) && t.get(1).text.equals("col1"), "identifier col1");
        // Quoted
        t = tokenize("\"My Table\"");
        chk(t.get(0).text.equals("My Table"), "quoted identifier");
        t = tokenize("`backtick`");
        chk(t.get(0).text.equals("backtick"), "backtick identifier");
    }

    static void testStrings() {
        List<Token> t = tokenize("'hello world'");
        chk(t.get(0).is(TokenType.STRING_LITERAL) && t.get(0).text.equals("hello world"), "string literal");
        // SQL single quotes: 'it''s' → it's
        t = tokenize("'hello'");
        chk(t.get(0).text.equals("hello"), "string hello");
    }

    static void testNumbers() {
        List<Token> t = tokenize("42");
        chk(t.get(0).is(TokenType.INT_LITERAL) && t.get(0).text.equals("42"), "int 42");
        t = tokenize("3.14");
        chk(t.get(0).is(TokenType.FLOAT_LITERAL), "float 3.14");
        t = tokenize("0");
        chk(t.get(0).is(TokenType.INT_LITERAL), "int 0");
    }

    static void testOperators() {
        List<Token> t = tokenize("+ - * / % = != <> < > <= >= || . , ; ( ) : ?");
        chk(t.get(0).is(TokenType.PLUS), "+");
        chk(t.get(1).is(TokenType.MINUS), "-");
        chk(t.get(2).is(TokenType.STAR), "*");
        chk(t.get(3).is(TokenType.DIV), "/");
        chk(t.get(4).is(TokenType.MOD), "%");
        chk(t.get(5).is(TokenType.EQ), "=");
        chk(t.get(6).is(TokenType.NEQ) && t.get(6).text.equals("!="), "!=");
        chk(t.get(7).is(TokenType.NEQ) && t.get(7).text.equals("<>"), "<>");
        chk(t.get(8).is(TokenType.LT), "<");
        chk(t.get(9).is(TokenType.GT), ">");
        chk(t.get(10).is(TokenType.LTE), "<=");
        chk(t.get(11).is(TokenType.GTE), ">=");
        chk(t.get(12).is(TokenType.CONCAT), "||");
        chk(t.get(13).is(TokenType.DOT), ".");
        chk(t.get(14).is(TokenType.COMMA), ",");
        chk(t.get(15).is(TokenType.SEMI), ";");
        chk(t.get(16).is(TokenType.LPAREN), "(");
        chk(t.get(17).is(TokenType.RPAREN), ")");
    }

    static void testComments() {
        List<Token> t = tokenize("SELECT /* block */ 1");
        chk(t.get(0).is(TokenType.SELECT), "block comment: SELECT");
        chk(t.get(1).is(TokenType.INT_LITERAL), "block comment: 1");

        t = tokenize("-- line comment\nSELECT 1");
        chk(t.get(0).is(TokenType.SELECT), "line comment: SELECT");
        chk(t.get(1).is(TokenType.INT_LITERAL), "line comment: 1");
    }

    static void testComplexSQL() {
        List<Token> t = tokenize("SELECT name, COUNT(*) FROM users WHERE age > 18 GROUP BY name LIMIT 10");
        chk(t.get(0).is(TokenType.SELECT), "complex: SELECT");
        chk(t.get(1).is(TokenType.IDENTIFIER), "complex: name");
        chk(t.get(2).is(TokenType.COMMA), "complex: comma");
        chk(t.get(3).is(TokenType.IDENTIFIER), "complex: COUNT");
        chk(t.stream().anyMatch(tok -> tok.is(TokenType.LIMIT)), "complex: LIMIT");

        // DDL
        t = tokenize("CREATE TABLE t (id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(100))");
        chk(t.stream().anyMatch(tok -> tok.is(TokenType.CREATE)), "DDL: CREATE");
        chk(t.stream().anyMatch(tok -> tok.is(TokenType.AUTO_INCREMENT)), "DDL: AUTO_INCREMENT");

        // TCL
        t = tokenize("BEGIN; COMMIT; ROLLBACK;");
        chk(t.stream().anyMatch(tok -> tok.is(TokenType.BEGIN)), "TCL: BEGIN");
        chk(t.stream().anyMatch(tok -> tok.is(TokenType.COMMIT)), "TCL: COMMIT");
        chk(t.stream().anyMatch(tok -> tok.is(TokenType.ROLLBACK)), "TCL: ROLLBACK");
    }

    static List<Token> tokenize(String sql) { return new HandLexer(sql).tokenize(); }
    static void chk(boolean c, String m) { if(c) pass++; else { fail++; System.err.println("  ❌ "+m); } }
}
