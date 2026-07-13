package com.usql.parser;

import java.util.*;

/**
 * Hand-written lexer for U-SQL.
 * Tokenizes U-SQL text into a list of Token objects.
 * Replaces the ANTLR-generated lexer.
 */
public class HandLexer {

    public enum TokenType {
        // Keywords
        SELECT, FROM, WHERE, AND, OR, NOT, IN, IS, NULL, TRUE, FALSE,
        JOIN, LEFT, RIGHT, INNER, CROSS, FULL, ON,
        GROUP, BY, HAVING, ORDER, ASC, DESC, NULLS, FIRST, LAST,
        LIMIT, OFFSET, FETCH, NEXT, ROWS, ONLY,
        INSERT, INTO, VALUES, UPDATE, SET, DELETE, MERGE, USING, MATCHED,
        CREATE, TABLE, INDEX, IF, EXISTS, PRIMARY, KEY, FOREIGN,
        REFERENCES, CHECK, UNIQUE, DEFAULT, GENERATED, ALWAYS, AS,
        VIRTUAL, STORED, AUTO_INCREMENT, IDENTITY,
        CASCADE, RESTRICT, ACTION,
        WITH, RECURSIVE, DISTINCT, ALL,
        BETWEEN, LIKE, CASE, WHEN, THEN, ELSE, END, CAST,
        UNION, INTERSECT, EXCEPT,
        ROLLUP, CUBE, GROUPING, SETS,
        OVER, PARTITION, KEEP, DENSE_RANK, RANK,
        PRECEDING, FOLLOWING, UNBOUNDED, CURRENT,
        DROP, TRUNCATE, ALTER, ADD, COLUMN, TYPE, RENAME, TO,
        VIEW, SCHEMA, DATABASE,
        PROCEDURE, FUNCTION, CALL, REPLACE, INOUT, OUT, RETURNS, LANGUAGE,
        LATERAL, IGNORE, ENGINE, TABLESPACE, CHARACTER, COLLATE, COMMENT,
        BEGIN, COMMIT, ROLLBACK, SAVEPOINT, RELEASE, TRANSACTION, WORK, START,
        // Data types
        TINYINT, SMALLINT, INT, INTEGER, BIGINT, DECIMAL, NUMERIC,
        FLOAT, REAL, DOUBLE, PRECISION, CHAR, VARCHAR, TEXT, TINYTEXT, MEDIUMTEXT, LONGTEXT,
        BOOLEAN, DATETIME, TIMESTAMP, DATE, TIME, YEAR, MONTH, DAY, HOUR, MINUTE, SECOND,
        INTERVAL, JSON, XML, UUID, BINARY, VARBINARY, BLOB, CLOB, BIT, ENUM,
        ZONE,
        // Symbols
        LPAREN, RPAREN, COMMA, SEMI, DOT, STAR, DIV, MOD, PLUS, MINUS,
        EQ, NEQ, LT, GT, LTE, GTE, CONCAT, COLON, QMARK,
        // Literals
        IDENTIFIER, STRING_LITERAL, INT_LITERAL, FLOAT_LITERAL,
        // Special
        EOF
    }

    public static class Token {
        public final TokenType type;
        public final String text;
        public final int line, col;

        Token(TokenType type, String text, int line, int col) {
            this.type = type;
            this.text = text;
            this.line = line;
            this.col = col;
        }

        public boolean is(TokenType t) { return type == t; }
        public String toString() { return type + (text != null ? "[" + text + "]" : ""); }
    }

    private final String input;
    private int pos, line, col;

    private static final Map<String, TokenType> KEYWORDS = new HashMap<>();
    static {
        for (TokenType t : TokenType.values()) {
            if (t.ordinal() < TokenType.LPAREN.ordinal())
                KEYWORDS.put(t.name(), t);
        }
        // Override: BEGIN is a keyword but conflicts with stored proc
        // We'll resolve in the parser
    }

    public HandLexer(String input) {
        this.input = input;
        this.pos = 0;
        this.line = 1;
        this.col = 1;
    }

    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        Token t;
        do {
            t = nextToken();
            tokens.add(t);
        } while (t.type != TokenType.EOF);
        return tokens;
    }

    private Token nextToken() {
        skipWhitespace();
        if (pos >= input.length()) return tok(TokenType.EOF, null);

        char c = peek();

        // String literal
        if (c == '\'') return readString();

        // Number literal
        if (Character.isDigit(c)) return readNumber();

        // Identifier or keyword
        if (Character.isLetter(c) || c == '_' || c == '"' || c == '`')
            return readIdentifier();

        // Symbols
        switch (c) {
            case '(': advance(); return tok(TokenType.LPAREN, "(");
            case ')': advance(); return tok(TokenType.RPAREN, ")");
            case ',': advance(); return tok(TokenType.COMMA, ",");
            case ';': advance(); return tok(TokenType.SEMI, ";");
            case '.': advance(); return tok(TokenType.DOT, ".");
            case '*': advance(); return tok(TokenType.STAR, "*");
            case '/': advance();
                if (peek() == '*') { skipBlockComment(); return nextToken(); }
                return tok(TokenType.DIV, "/");
            case '%': advance(); return tok(TokenType.MOD, "%");
            case '+': advance(); return tok(TokenType.PLUS, "+");
            case ':': advance(); return tok(TokenType.COLON, ":");
            case '?': advance(); return tok(TokenType.QMARK, "?");
            case '|': advance();
                if (peek() == '|') { advance(); return tok(TokenType.CONCAT, "||"); }
                throw error("Unexpected '|'");
            case '-': advance();
                if (peek() == '-') { skipLineComment(); return nextToken(); }
                return tok(TokenType.MINUS, "-");
            case '=': advance(); return tok(TokenType.EQ, "=");
            case '!': advance();
                if (peek() == '=') { advance(); return tok(TokenType.NEQ, "!="); }
                throw error("Unexpected '!'");
            case '<': advance();
                if (peek() == '=') { advance(); return tok(TokenType.LTE, "<="); }
                if (peek() == '>') { advance(); return tok(TokenType.NEQ, "<>"); }
                return tok(TokenType.LT, "<");
            case '>': advance();
                if (peek() == '=') { advance(); return tok(TokenType.GTE, ">="); }
                return tok(TokenType.GT, ">");
            default:
                throw error("Unexpected character: " + c);
        }
    }

    private Token readString() {
        int start = pos, startCol = col;
        advance(); // skip opening '
        StringBuilder sb = new StringBuilder();
        while (pos < input.length() && peek() != '\'') {
            if (peek() == '\'' && pos + 1 < input.length() && input.charAt(pos + 1) == '\'') {
                advance(); advance(); sb.append('\'');
            } else {
                sb.append(peek()); advance();
            }
        }
        if (pos >= input.length()) throw error("Unterminated string");
        advance(); // skip closing '
        return new Token(TokenType.STRING_LITERAL, sb.toString(), line, startCol);
    }

    private Token readNumber() {
        int startCol = col;
        StringBuilder sb = new StringBuilder();
        boolean isFloat = false;
        while (pos < input.length() && (Character.isDigit(peek()) || peek() == '.')) {
            if (peek() == '.') {
                if (isFloat) break;
                isFloat = true;
            }
            sb.append(peek()); advance();
        }
        String text = sb.toString();
        return isFloat
            ? new Token(TokenType.FLOAT_LITERAL, text, line, startCol)
            : new Token(TokenType.INT_LITERAL, text, line, startCol);
    }

    private Token readIdentifier() {
        int startCol = col;
        char c = peek();

        // Quoted identifier
        if (c == '"') return readQuotedIdentifier('"', startCol);
        if (c == '`') return readQuotedIdentifier('`', startCol);

        // Regular identifier
        StringBuilder sb = new StringBuilder();
        while (pos < input.length() && (Character.isLetterOrDigit(peek()) || peek() == '_')) {
            sb.append(peek()); advance();
        }
        String text = sb.toString();
        TokenType kw = KEYWORDS.get(text.toUpperCase());
        if (kw != null) return tok(kw, text);
        return new Token(TokenType.IDENTIFIER, text, line, startCol);
    }

    private Token readQuotedIdentifier(char quote, int startCol) {
        advance(); // skip opening quote
        StringBuilder sb = new StringBuilder();
        while (pos < input.length() && peek() != quote) {
            if (peek() == quote && pos + 1 < input.length() && input.charAt(pos + 1) == quote) {
                advance(); advance(); sb.append(quote);
            } else {
                sb.append(peek()); advance();
            }
        }
        if (pos >= input.length()) throw error("Unterminated quoted identifier");
        advance(); // skip closing quote
        return new Token(TokenType.IDENTIFIER, sb.toString(), line, startCol);
    }

    private void skipWhitespace() {
        while (pos < input.length() && Character.isWhitespace(peek())) {
            if (peek() == '\n') { line++; col = 1; }
            else col++;
            advance();
        }
    }

    private void skipLineComment() {
        while (pos < input.length() && peek() != '\n') advance();
    }

    private void skipBlockComment() {
        advance(); // skip *
        while (pos < input.length()) {
            if (peek() == '*' && pos + 1 < input.length() && input.charAt(pos + 1) == '/') {
                advance(); advance(); return;
            }
            if (peek() == '\n') { line++; col = 1; }
            else col++;
            advance();
        }
    }

    private char peek() { return input.charAt(pos); }
    private void advance() { pos++; }

    private Token tok(TokenType type, String text) {
        return new Token(type, text != null ? text : type.name(), line, col);
    }

    private RuntimeException error(String msg) {
        return new RuntimeException("Lexer error at line " + line + ":" + col + " — " + msg);
    }
}
