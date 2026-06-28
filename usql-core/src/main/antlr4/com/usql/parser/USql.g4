grammar USql;

// ══════════════════════════════════════════════════
//  U-SQL Grammar
//  A portable SQL superset that compiles to
//  MySQL, PostgreSQL, Oracle, and 达梦 DM.
// ══════════════════════════════════════════════════

// ── Top-level entry point ──

program
    : statement (SEMI statement)* SEMI? EOF
    ;

statement
    : selectStatement
    | insertStatement
    | updateStatement
    | deleteStatement
    | mergeStatement
    | createTableStatement
    | createIndexStatement
    ;

// ══════════════════════════════════════════════════
//  SELECT
// ══════════════════════════════════════════════════

selectStatement
    : withClause?
      SELECT (DISTINCT | ALL)?
      selectItem (COMMA selectItem)*
      (FROM tableRef (COMMA tableRef)*)?
      whereClause?
      groupByClause?
      havingClause?
      orderByClause?
      fetchClause?
      setOpClause?
    ;

withClause
    : WITH (RECURSIVE)? cteDefinition (COMMA cteDefinition)*
    ;

cteDefinition
    : identifier (LPAREN columnList RPAREN)? AS LPAREN selectStatement RPAREN
    ;

selectItem
    : expr (AS? alias=identifier)?      # ExprSelectItem
    | STAR                              # StarSelectItem
    | identifier DOT STAR               # QualifiedStarSelectItem
    ;

tableRef
    : tableName=identifier (AS? alias=identifier)?                     # SimpleTableRef
    | LPAREN selectStatement RPAREN AS? alias=identifier               # SubqueryTableRef
    | tableRef (INNER | LEFT | RIGHT | CROSS | FULL)? JOIN tableRef
        (ON joinCondition=expr)?                                       # JoinTableRef
    | (LATERAL)? functionCall AS? alias=identifier                     # FunctionTableRef
    ;

whereClause
    : WHERE expr
    ;

groupByClause
    : GROUP BY groupByItem (COMMA groupByItem)*
    ;

groupByItem
    : expr                                             # PlainGroupBy
    | ROLLUP LPAREN expr (COMMA expr)* RPAREN          # RollupGroupBy
    | CUBE LPAREN expr (COMMA expr)* RPAREN            # CubeGroupBy
    | GROUPING SETS LPAREN expr (COMMA expr)* RPAREN   # GroupingSetsGroupBy
    ;

havingClause
    : HAVING expr
    ;

orderByClause
    : ORDER BY orderByItem (COMMA orderByItem)*
    ;

orderByItem
    : expr (ASC | DESC)? (NULLS (FIRST | LAST))?
    ;

fetchClause
    : LIMIT expr (OFFSET expr)?
    | OFFSET expr (LIMIT expr)?
    | FETCH (FIRST | NEXT) expr (ROW | ROWS) ONLY
    ;

setOpClause
    : (UNION | UNION ALL | INTERSECT | EXCEPT) selectStatement
    ;

// ══════════════════════════════════════════════════
//  INSERT
// ══════════════════════════════════════════════════

insertStatement
    : INSERT (IGNORE | INTO)? tableRef
      (LPAREN columnList RPAREN)?
      (VALUES LPAREN exprList RPAREN (COMMA LPAREN exprList RPAREN)*
      | selectStatement)
    ;

// ══════════════════════════════════════════════════
//  UPDATE
// ══════════════════════════════════════════════════

updateStatement
    : UPDATE tableRef
      SET setClause (COMMA setClause)*
      whereClause?
    ;

setClause
    : identifier EQ expr
    ;

// ══════════════════════════════════════════════════
//  DELETE
// ══════════════════════════════════════════════════

deleteStatement
    : DELETE FROM tableRef whereClause?
    ;

// ══════════════════════════════════════════════════
//  MERGE (UPSERT)
// ══════════════════════════════════════════════════

mergeStatement
    : MERGE INTO tableRef (AS? alias=identifier)?
      USING tableRef
      ON expr
      (mergeInsert | mergeUpdate | mergeDelete)*
    ;

mergeInsert
    : WHEN NOT MATCHED THEN INSERT (LPAREN columnList RPAREN)?
      VALUES LPAREN exprList RPAREN
    ;

mergeUpdate
    : WHEN MATCHED THEN UPDATE SET setClause (COMMA setClause)*
    ;

mergeDelete
    : WHEN MATCHED THEN DELETE
    ;

// ══════════════════════════════════════════════════
//  CREATE TABLE
// ══════════════════════════════════════════════════

createTableStatement
    : CREATE TABLE (IF NOT EXISTS)? tableName=identifier
      LPAREN
        columnDef (COMMA columnDef)*
        (COMMA tableConstraint)*
      RPAREN
      tableOptions?
    ;

columnDef
    : columnName=identifier dataType
      columnConstraint*
      (DEFAULT defaultValue=expr)?
    ;

columnConstraint
    : NOT NULL                     # NotNullConstraint
    | NULL                         # NullConstraint
    | PRIMARY KEY (AUTO_INCREMENT | IDENTITY)?  # PrimaryKeyConstraint
    | UNIQUE                       # UniqueConstraint
    | CHECK LPAREN expr RPAREN     # CheckConstraint
    | REFERENCES identifier LPAREN identifier RPAREN
        (ON UPDATE fkAction)? (ON DELETE fkAction)?  # ReferencesConstraint
    | GENERATED ALWAYS AS LPAREN expr RPAREN (VIRTUAL | STORED)?  # GeneratedConstraint
    ;

fkAction
    : CASCADE | SET NULL | RESTRICT | NO ACTION
    ;

tableConstraint
    : (CONSTRAINT name=identifier)? tableConstraintBody
    ;

tableConstraintBody
    : PRIMARY KEY LPAREN columnList RPAREN                                       # TbPrimaryKey
    | UNIQUE LPAREN columnList RPAREN                                            # TbUnique
    | FOREIGN KEY LPAREN columnList RPAREN
      REFERENCES refTable=identifier LPAREN columnList RPAREN
      (ON UPDATE fkAction)? (ON DELETE fkAction)?                                # TbForeignKey
    | CHECK LPAREN expr RPAREN                                                   # TbCheck
    ;

tableOptions
    : tableOption (COMMA? tableOption)*
    ;

tableOption
    : ENGINE EQ identifier
    | TABLESPACE EQ identifier
    | CHARACTER SET EQ identifier
    | COLLATE EQ identifier
    | COMMENT EQ STRING_LITERAL
    ;

// ══════════════════════════════════════════════════
//  CREATE INDEX
// ══════════════════════════════════════════════════

createIndexStatement
    : CREATE (UNIQUE)? INDEX (IF NOT EXISTS)? name=identifier
      ON tableName=identifier
      LPAREN indexColumn (COMMA indexColumn)* RPAREN
      whereClause?
    ;

indexColumn
    : identifier (ASC | DESC)? (NULLS (FIRST | LAST))?
    ;

// ══════════════════════════════════════════════════
//  Expressions
// ══════════════════════════════════════════════════

expr
    : literal                                         # LiteralExpr
    | identifier (DOT identifier)?                    # ColumnRefExpr
    | STAR                                            # StarExpr
    | identifier DOT STAR                             # QualifiedStarExpr
    | parameter                                       # ParameterExpr
    | functionCall                                    # FunctionCallExpr
    | LPAREN expr RPAREN                              # ParenExpr
    | LPAREN selectStatement RPAREN                   # SubqueryExpr
    | op=(MINUS | PLUS) expr                          # UnaryOpExpr
    | NOT expr                                        # NotExpr
    | EXISTS LPAREN selectStatement RPAREN            # ExistsExpr
    | expr op=(STAR | DIV | MOD) expr                 # MulDivExpr
    | expr op=(PLUS | MINUS) expr                     # AddSubExpr
    | expr CONCAT expr                                # ConcatExpr
    | expr op=(EQ | NEQ | LT | GT | LTE | GTE) expr   # ComparisonExpr
    | expr IS (NOT)? NULL                             # IsNullExpr
    | expr IS (NOT)? (TRUE | FALSE)                   # IsBoolExpr
    | expr (NOT)? BETWEEN expr AND expr               # BetweenExpr
    | expr (NOT)? IN LPAREN (exprList | selectStatement) RPAREN  # InExpr
    | expr (NOT)? LIKE expr                           # LikeExpr
    | expr AND expr                                   # AndExpr
    | expr OR expr                                    # OrExpr
    | CASE whenClause+ (ELSE expr)? END               # CaseExpr
    | CAST LPAREN expr AS dataType RPAREN             # CastExpr
    ;

whenClause
    : WHEN expr THEN expr
    ;

functionCall
    : funcName=identifier LPAREN (DISTINCT | ALL)?
      (exprList | STAR)? RPAREN
    ;

literal
    : INT_LITERAL
    | FLOAT_LITERAL
    | STRING_LITERAL
    | TRUE
    | FALSE
    | NULL
    | DATE STRING_LITERAL
    | TIMESTAMP STRING_LITERAL
    | INTERVAL STRING_LITERAL intervalUnit
    ;

parameter
    : COLON identifier
    | QMARK
    ;

intervalUnit
    : YEAR | MONTH | DAY | HOUR | MINUTE | SECOND
    ;

// ══════════════════════════════════════════════════
//  Data types
// ══════════════════════════════════════════════════

dataType
    : TINYINT
    | SMALLINT
    | INT | INTEGER
    | BIGINT
    | DECIMAL (LPAREN precision=INT_LITERAL (COMMA scale=INT_LITERAL)? RPAREN)?
    | NUMERIC (LPAREN precision=INT_LITERAL (COMMA scale=INT_LITERAL)? RPAREN)?
    | FLOAT (LPAREN bits=INT_LITERAL RPAREN)?
    | REAL
    | DOUBLE (PRECISION)?
    | CHAR (LPAREN length=INT_LITERAL RPAREN)?
    | VARCHAR LPAREN length=INT_LITERAL RPAREN
    | TEXT
    | BOOLEAN
    | DATE
    | TIME (LPAREN frac=INT_LITERAL RPAREN)?
    | DATETIME (LPAREN frac=INT_LITERAL RPAREN)?
    | TIMESTAMP (LPAREN frac=INT_LITERAL RPAREN)? (WITH TIME ZONE)?
    | INTERVAL YEAR (TO MONTH)?
    | INTERVAL DAY (TO SECOND (LPAREN frac=INT_LITERAL RPAREN)?)?
    | JSON
    | XML
    | UUID
    | BINARY (LPAREN length=INT_LITERAL RPAREN)?
    | VARBINARY LPAREN length=INT_LITERAL RPAREN
    | BLOB
    | CLOB
    | BIT
    | ENUM LPAREN STRING_LITERAL (COMMA STRING_LITERAL)* RPAREN
    ;

// ── Helper rules ──

columnList
    : identifier (COMMA identifier)*
    ;

exprList
    : expr (COMMA expr)*
    ;

identifier
    : IDENTIFIER
    | STRING_LITERAL          // Oracle-style 'identifier'
    | BACKTICK_ID             // MySQL-style `identifier`
    | keyword                 // Allow keywords as identifiers
    ;

keyword
    : SELECT | FROM | WHERE | AND | OR | NOT | IN | IS | NULL | TRUE | FALSE
    | JOIN | LEFT | RIGHT | INNER | CROSS | FULL | ON
    | GROUP | BY | HAVING | ORDER | ASC | DESC | NULLS | FIRST | LAST
    | LIMIT | OFFSET | FETCH | NEXT | ROWS | ONLY
    | INSERT | INTO | VALUES | UPDATE | SET | DELETE | MERGE | USING | MATCHED
    | CREATE | TABLE | INDEX | IF | EXISTS | PRIMARY | KEY | FOREIGN
    | REFERENCES | CHECK | UNIQUE | DEFAULT | GENERATED | ALWAYS | AS
    | VIRTUAL | STORED | AUTO_INCREMENT | IDENTITY
    | CASCADE | RESTRICT | ACTION
    | WITH | RECURSIVE | DISTINCT | ALL
    | BETWEEN | LIKE | CASE | WHEN | THEN | ELSE | END | CAST
    | UNION | INTERSECT | EXCEPT
    | ROLLUP | CUBE | GROUPING | SETS
    | DATE | TIMESTAMP | INTERVAL | TIME | YEAR | MONTH | DAY | HOUR | MINUTE | SECOND
    | TINYINT | SMALLINT | INT | INTEGER | BIGINT | DECIMAL | NUMERIC
    | FLOAT | REAL | DOUBLE | PRECISION | CHAR | VARCHAR | TEXT
    | BOOLEAN | DATETIME | JSON | XML | UUID | BINARY | VARBINARY | BLOB | CLOB | BIT | ENUM
    | LATERAL | IGNORE | ENGINE | TABLESPACE | CHARACTER | COLLATE | COMMENT
    | WITH | TIME | ZONE
    ;

// ══════════════════════════════════════════════════
//  Lexer Rules
// ══════════════════════════════════════════════════

// Keywords
SELECT:       S E L E C T;
FROM:         F R O M;
WHERE:        W H E R E;
AND:          A N D;
OR:           O R;
NOT:          N O T;
IN:           I N;
IS:           I S;
NULL:         N U L L;
TRUE:         T R U E;
FALSE:        F A L S E;
JOIN:         J O I N;
LEFT:         L E F T;
RIGHT:        R I G H T;
INNER:        I N N E R;
CROSS:        C R O S S;
FULL:         F U L L;
ON:           O N;
GROUP:        G R O U P;
BY:           B Y;
HAVING:       H A V I N G;
ORDER:        O R D E R;
ASC:          A S C;
DESC:         D E S C;
NULLS:        N U L L S;
FIRST:        F I R S T;
LAST:         L A S T;
LIMIT:        L I M I T;
OFFSET:       O F F S E T;
FETCH:        F E T C H;
NEXT:         N E X T;
ROWS:         R O W S;
ONLY:         O N L Y;
INSERT:       I N S E R T;
INTO:         I N T O;
VALUES:       V A L U E S;
UPDATE:       U P D A T E;
SET:          S E T;
DELETE:       D E L E T E;
MERGE:        M E R G E;
USING:        U S I N G;
MATCHED:      M A T C H E D;
CREATE:       C R E A T E;
TABLE:        T A B L E;
INDEX:        I N D E X;
IF:           I F;
EXISTS:       E X I S T S;
PRIMARY:      P R I M A R Y;
KEY:          K E Y;
FOREIGN:      F O R E I G N;
REFERENCES:   R E F E R E N C E S;
CHECK:        C H E C K;
UNIQUE:       U N I Q U E;
DEFAULT:      D E F A U L T;
GENERATED:    G E N E R A T E D;
ALWAYS:       A L W A Y S;
AS:           A S;
VIRTUAL:      V I R T U A L;
STORED:       S T O R E D;
AUTO_INCREMENT: A U T O '_' I N C R E M E N T;
IDENTITY:     I D E N T I T Y;
CASCADE:      C A S C A D E;
RESTRICT:     R E S T R I C T;
ACTION:       A C T I O N;
WITH:         W I T H;
RECURSIVE:    R E C U R S I V E;
DISTINCT:     D I S T I N C T;
ALL:          A L L;
BETWEEN:      B E T W E E N;
LIKE:         L I K E;
CASE:         C A S E;
WHEN:         W H E N;
THEN:         T H E N;
ELSE:         E L S E;
END:          E N D;
CAST:         C A S T;
UNION:        U N I O N;
INTERSECT:    I N T E R S E C T;
EXCEPT:       E X C E P T;
ROLLUP:       R O L L U P;
CUBE:         C U B E;
GROUPING:     G R O U P I N G;
SETS:         S E T S;
DATE:         D A T E;
TIMESTAMP:    T I M E S T A M P;
INTERVAL:     I N T E R V A L;
TIME:         T I M E;
YEAR:         Y E A R;
MONTH:        M O N T H;
DAY:          D A Y;
HOUR:         H O U R;
MINUTE:       M I N U T E;
SECOND:       S E C O N D;

// Type keywords
TINYINT:      T I N Y I N T;
SMALLINT:     S M A L L I N T;
INT:          I N T;
INTEGER:      I N T E G E R;
BIGINT:       B I G I N T;
DECIMAL:      D E C I M A L;
NUMERIC:      N U M E R I C;
FLOAT:        F L O A T;
REAL:         R E A L;
DOUBLE:       D O U B L E;
PRECISION:    P R E C I S I O N;
CHAR:         C H A R;
VARCHAR:      V A R C H A R;
TEXT:         T E X T;
BOOLEAN:      B O O L E A N;
DATETIME:     D A T E T I M E;
JSON:         J S O N;
XML:          X M L;
UUID:         U U I D;
BINARY:       B I N A R Y;
VARBINARY:    V A R B I N A R Y;
BLOB:         B L O B;
CLOB:         C L O B;
BIT:          B I T;
ENUM:         E N U M;
LATERAL:      L A T E R A L;
IGNORE:       I G N O R E;
ENGINE:       E N G I N E;
TABLESPACE:   T A B L E S P A C E;
CHARACTER:    C H A R A C T E R;
COLLATE:      C O L L A T E;
COMMENT:      C O M M E N T;
ZONE:         Z O N E;

// ── Operators ──
STAR:    '*';
DIV:     '/';
MOD:     '%';
PLUS:    '+';
MINUS:   '-';
DOT:     '.';
COMMA:   ',';
SEMI:    ';';
LPAREN:  '(';
RPAREN:  ')';
COLON:   ':';
QMARK:   '?';

EQ:      '=';
NEQ:     '!=' | '<>';
LT:      '<';
GT:      '>';
LTE:     '<=';
GTE:     '>=';
CONCAT:  '||';

// ── Literals ──
INT_LITERAL
    : DIGIT+
    ;

FLOAT_LITERAL
    : DIGIT+ DOT DIGIT* (EXPONENT)?
    | DOT DIGIT+ (EXPONENT)?
    | DIGIT+ EXPONENT
    ;

STRING_LITERAL
    : '\'' (~'\'' | '\'\'')* '\''
    ;

IDENTIFIER
    : LETTER (LETTER | DIGIT | '_')*
    | '"' (~'"' | '""')* '"'
    ;

BACKTICK_ID
    : '`' (~'`')* '`'
    ;

// ── Fragments ──
fragment LETTER: [a-zA-Z-￿];
fragment DIGIT:  [0-9];
fragment EXPONENT: [eE] [+\-]? DIGIT+;

// ── Whitespace and comments ──
WS
    : [ \t\r\n]+ -> channel(HIDDEN)
    ;

BLOCK_COMMENT
    : '/*' .*? '*/' -> channel(HIDDEN)
    ;

LINE_COMMENT
    : '--' ~[\r\n]* -> channel(HIDDEN)
    ;

// ── Case-insensitive fragments ──
fragment A: [aA]; fragment B: [bB]; fragment C: [cC];
fragment D: [dD]; fragment E: [eE]; fragment F: [fF];
fragment G: [gG]; fragment H: [hH]; fragment I: [iI];
fragment J: [jJ]; fragment K: [kK]; fragment L: [lL];
fragment M: [mM]; fragment N: [nN]; fragment O: [oO];
fragment P: [pP]; fragment Q: [qQ]; fragment R: [rR];
fragment S: [sS]; fragment T: [tT]; fragment U: [uU];
fragment V: [vV]; fragment W: [wW]; fragment X: [xX];
fragment Y: [yY]; fragment Z: [zZ];
