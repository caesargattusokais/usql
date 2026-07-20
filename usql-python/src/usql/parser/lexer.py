"""Hand-written lexer for U-SQL — Python port of Java HandLexer."""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum, auto


class TokenType(Enum):
    # Keywords
    SELECT = auto()
    FROM = auto()
    WHERE = auto()
    AND = auto()
    OR = auto()
    NOT = auto()
    IN = auto()
    IS = auto()
    NULL = auto()
    TRUE = auto()
    FALSE = auto()
    JOIN = auto()
    LEFT = auto()
    RIGHT = auto()
    INNER = auto()
    CROSS = auto()
    FULL = auto()
    ON = auto()
    GROUP = auto()
    BY = auto()
    HAVING = auto()
    ORDER = auto()
    ASC = auto()
    DESC = auto()
    NULLS = auto()
    FIRST = auto()
    LAST = auto()
    LIMIT = auto()
    OFFSET = auto()
    FETCH = auto()
    NEXT = auto()
    ROWS = auto()
    ONLY = auto()
    INSERT = auto()
    INTO = auto()
    VALUES = auto()
    UPDATE = auto()
    SET = auto()
    DELETE = auto()
    MERGE = auto()
    USING = auto()
    MATCHED = auto()
    CREATE = auto()
    TABLE = auto()
    INDEX = auto()
    IF = auto()
    EXISTS = auto()
    PRIMARY = auto()
    KEY = auto()
    FOREIGN = auto()
    REFERENCES = auto()
    CHECK = auto()
    UNIQUE = auto()
    DEFAULT = auto()
    GENERATED = auto()
    ALWAYS = auto()
    AS = auto()
    VIRTUAL = auto()
    STORED = auto()
    AUTO_INCREMENT = auto()
    IDENTITY = auto()
    CASCADE = auto()
    RESTRICT = auto()
    ACTION = auto()
    WITH = auto()
    RECURSIVE = auto()
    DISTINCT = auto()
    ALL = auto()
    BETWEEN = auto()
    LIKE = auto()
    CASE = auto()
    WHEN = auto()
    THEN = auto()
    ELSE = auto()
    END = auto()
    CAST = auto()
    UNION = auto()
    INTERSECT = auto()
    EXCEPT = auto()
    ROLLUP = auto()
    CUBE = auto()
    GROUPING = auto()
    SETS = auto()
    OVER = auto()
    PARTITION = auto()
    KEEP = auto()
    DENSE_RANK = auto()
    RANK = auto()
    PRECEDING = auto()
    FOLLOWING = auto()
    UNBOUNDED = auto()
    CURRENT = auto()
    DROP = auto()
    TRUNCATE = auto()
    ALTER = auto()
    ADD = auto()
    COLUMN = auto()
    TYPE = auto()
    RENAME = auto()
    TO = auto()
    VIEW = auto()
    SCHEMA = auto()
    DATABASE = auto()
    PROCEDURE = auto()
    FUNCTION = auto()
    CALL = auto()
    REPLACE = auto()
    INOUT = auto()
    OUT = auto()
    OUTER = auto()
    RETURNS = auto()
    LANGUAGE = auto()
    LATERAL = auto()
    IGNORE = auto()
    ENGINE = auto()
    TABLESPACE = auto()
    CHARACTER = auto()
    COLLATE = auto()
    COMMENT = auto()
    BEGIN = auto()
    COMMIT = auto()
    ROLLBACK = auto()
    SAVEPOINT = auto()
    RELEASE = auto()
    TRANSACTION = auto()
    WORK = auto()
    START = auto()
    NO = auto()
    ROW = auto()
    CONSTRAINT = auto()
    # Data types
    TINYINT = auto()
    SMALLINT = auto()
    INT = auto()
    INTEGER = auto()
    BIGINT = auto()
    DECIMAL = auto()
    NUMERIC = auto()
    FLOAT = auto()
    REAL = auto()
    DOUBLE = auto()
    PRECISION = auto()
    CHAR = auto()
    VARCHAR = auto()
    TEXT = auto()
    TINYTEXT = auto()
    MEDIUMTEXT = auto()
    LONGTEXT = auto()
    BOOLEAN = auto()
    DATETIME = auto()
    TIMESTAMP = auto()
    DATE = auto()
    TIME = auto()
    YEAR = auto()
    MONTH = auto()
    DAY = auto()
    HOUR = auto()
    MINUTE = auto()
    SECOND = auto()
    INTERVAL = auto()
    JSON = auto()
    XML = auto()
    UUID = auto()
    BINARY = auto()
    VARBINARY = auto()
    BLOB = auto()
    CLOB = auto()
    BIT = auto()
    ENUM = auto()
    ZONE = auto()
    # Symbols
    LPAREN = auto()
    RPAREN = auto()
    COMMA = auto()
    SEMI = auto()
    DOT = auto()
    STAR = auto()
    DIV = auto()
    MOD = auto()
    PLUS = auto()
    MINUS = auto()
    EQ = auto()
    NEQ = auto()
    LT = auto()
    GT = auto()
    LTE = auto()
    GTE = auto()
    CONCAT = auto()
    COLON = auto()
    QMARK = auto()
    # Literals
    IDENTIFIER = auto()
    STRING_LITERAL = auto()
    INT_LITERAL = auto()
    FLOAT_LITERAL = auto()
    # Special
    EOF = auto()


@dataclass(frozen=True)
class Token:
    type: TokenType
    text: str
    line: int
    col: int

    def is_(self, t: TokenType) -> bool:
        return self.type is t

    def __repr__(self) -> str:
        text_part = f"[{self.text}]" if self.text else ""
        return f"{self.type.name}{text_part}"


# Build keyword lookup: all token types before LPAREN are keywords
_SYMBOL_START = TokenType.LPAREN
_KEYWORDS: dict[str, TokenType] = {
    t.name: t for t in TokenType if t.value < _SYMBOL_START.value
}


class LexerError(RuntimeError):
    """Raised on invalid input during tokenization."""


class Lexer:
    """Hand-written lexer for U-SQL.

    Usage::

        tokens = Lexer("SELECT * FROM t").tokenize()
    """

    def __init__(self, input: str) -> None:
        self._input = input
        self._pos = 0
        self._line = 1
        self._col = 1

    # ── public API ──────────────────────────────────

    def tokenize(self) -> list[Token]:
        tokens: list[Token] = []
        while True:
            tok = self._next_token()
            tokens.append(tok)
            if tok.type is TokenType.EOF:
                break
        return tokens

    # ── internals ───────────────────────────────────

    def _next_token(self) -> Token:
        self._skip_whitespace()
        if self._pos >= len(self._input):
            return self._tok(TokenType.EOF, "")

        c = self._peek()

        # String literal
        if c == "'":
            return self._read_string()

        # Number literal
        if c.isdigit():
            return self._read_number()

        # Identifier or keyword
        if c.isalpha() or c == "_" or c == '"' or c == "`":
            return self._read_identifier()

        # Symbols
        match c:
            case "(":
                self._advance()
                return self._tok(TokenType.LPAREN, "(")
            case ")":
                self._advance()
                return self._tok(TokenType.RPAREN, ")")
            case ",":
                self._advance()
                return self._tok(TokenType.COMMA, ",")
            case ";":
                self._advance()
                return self._tok(TokenType.SEMI, ";")
            case ".":
                self._advance()
                return self._tok(TokenType.DOT, ".")
            case "*":
                self._advance()
                return self._tok(TokenType.STAR, "*")
            case "/":
                self._advance()
                if self._peek_or_none() == "*":
                    self._skip_block_comment()
                    return self._next_token()
                return self._tok(TokenType.DIV, "/")
            case "%":
                self._advance()
                return self._tok(TokenType.MOD, "%")
            case "+":
                self._advance()
                return self._tok(TokenType.PLUS, "+")
            case ":":
                self._advance()
                return self._tok(TokenType.COLON, ":")
            case "?":
                self._advance()
                return self._tok(TokenType.QMARK, "?")
            case "|":
                self._advance()
                if self._peek_or_none() == "|":
                    self._advance()
                    return self._tok(TokenType.CONCAT, "||")
                raise LexerError(self._error("Unexpected '|'"))
            case "-":
                self._advance()
                if self._peek_or_none() == "-":
                    self._skip_line_comment()
                    return self._next_token()
                return self._tok(TokenType.MINUS, "-")
            case "=":
                self._advance()
                return self._tok(TokenType.EQ, "=")
            case "!":
                self._advance()
                if self._peek_or_none() == "=":
                    self._advance()
                    return self._tok(TokenType.NEQ, "!=")
                raise LexerError(self._error("Unexpected '!'"))
            case "<":
                self._advance()
                if self._peek_or_none() == "=":
                    self._advance()
                    return self._tok(TokenType.LTE, "<=")
                if self._peek_or_none() == ">":
                    self._advance()
                    return self._tok(TokenType.NEQ, "<>")
                return self._tok(TokenType.LT, "<")
            case ">":
                self._advance()
                if self._peek_or_none() == "=":
                    self._advance()
                    return self._tok(TokenType.GTE, ">=")
                return self._tok(TokenType.GT, ">")
            case _:
                raise LexerError(self._error(f"Unexpected character: {c!r}"))

    # ── string literal ──────────────────────────────

    def _read_string(self) -> Token:
        start_col = self._col
        self._advance()  # skip opening '
        parts: list[str] = []
        while self._pos < len(self._input):
            c = self._peek()
            if c == "'":
                if self._pos + 1 < len(self._input) and self._input[self._pos + 1] == "'":
                    self._advance()
                    self._advance()
                    parts.append("'")  # escaped ''
                else:
                    break  # closing quote
            else:
                parts.append(c)
                self._advance()
        if self._pos >= len(self._input):
            raise LexerError(self._error("Unterminated string"))
        self._advance()  # skip closing '
        return Token(TokenType.STRING_LITERAL, "".join(parts), self._line, start_col)

    # ── number literal ──────────────────────────────

    def _read_number(self) -> Token:
        start_col = self._col
        parts: list[str] = []
        is_float = False
        while self._pos < len(self._input) and (self._peek().isdigit() or self._peek() == "."):
            c = self._peek()
            if c == ".":
                if is_float:
                    break
                is_float = True
            parts.append(c)
            self._advance()
        text = "".join(parts)
        tt = TokenType.FLOAT_LITERAL if is_float else TokenType.INT_LITERAL
        return Token(tt, text, self._line, start_col)

    # ── identifier / keyword ────────────────────────

    def _read_identifier(self) -> Token:
        start_col = self._col
        c = self._peek()

        # Quoted identifier
        if c == '"':
            return self._read_quoted_identifier('"', start_col)
        if c == "`":
            return self._read_quoted_identifier("`", start_col)

        # Regular identifier
        parts: list[str] = []
        while self._pos < len(self._input) and (self._peek().isalnum() or self._peek() == "_"):
            parts.append(self._peek())
            self._advance()
        text = "".join(parts)
        kw = _KEYWORDS.get(text.upper())
        if kw is not None:
            return Token(kw, text, self._line, start_col)
        return Token(TokenType.IDENTIFIER, text, self._line, start_col)

    def _read_quoted_identifier(self, quote: str, start_col: int) -> Token:
        self._advance()  # skip opening quote
        parts: list[str] = []
        while self._pos < len(self._input):
            c = self._peek()
            if c == quote:
                if self._pos + 1 < len(self._input) and self._input[self._pos + 1] == quote:
                    self._advance()
                    self._advance()
                    parts.append(quote)  # escaped quote
                else:
                    break  # closing quote
            else:
                parts.append(c)
                self._advance()
        if self._pos >= len(self._input):
            raise LexerError(self._error("Unterminated quoted identifier"))
        self._advance()  # skip closing quote
        return Token(TokenType.IDENTIFIER, "".join(parts), self._line, start_col)

    # ── whitespace / comments ───────────────────────

    def _skip_whitespace(self) -> None:
        while self._pos < len(self._input) and self._peek().isspace():
            if self._peek() == "\n":
                self._line += 1
                self._col = 1
            else:
                self._col += 1
            self._advance()

    def _skip_line_comment(self) -> None:
        while self._pos < len(self._input) and self._peek() != "\n":
            self._advance()

    def _skip_block_comment(self) -> None:
        self._advance()  # skip *
        while self._pos < len(self._input):
            if self._peek() == "*" and self._pos + 1 < len(self._input) and self._input[self._pos + 1] == "/":
                self._advance()
                self._advance()
                return
            if self._peek() == "\n":
                self._line += 1
                self._col = 1
            else:
                self._col += 1
            self._advance()

    # ── low-level helpers ───────────────────────────

    def _peek(self) -> str:
        return self._input[self._pos]

    def _peek_or_none(self) -> str | None:
        if self._pos < len(self._input):
            return self._input[self._pos]
        return None

    def _advance(self) -> None:
        self._pos += 1
        self._col += 1

    def _tok(self, tt: TokenType, text: str) -> Token:
        return Token(tt, text, self._line, self._col)

    def _error(self, msg: str) -> str:
        return f"Lexer error at line {self._line}:{self._col} — {msg}"
