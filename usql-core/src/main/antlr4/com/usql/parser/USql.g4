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
    | createProcedureStatement
    | createFunctionStatement
    | callStatement
    | dropTableStatement
    | dropIndexStatement
    | dropDatabaseStatement
    | truncateStatement
    | alterTableStatement
    | createViewStatement
    | createSchemaStatement
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
    : ROLLUP LPAREN expr (COMMA expr)* RPAREN          # RollupGroupBy
    | CUBE LPAREN expr (COMMA expr)* RPAREN            # CubeGroupBy
    | GROUPING SETS LPAREN expr (COMMA expr)* RPAREN   # GroupingSetsGroupBy
    | expr                                             # PlainGroupBy
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
      keepClause?
      overClause?
    ;

keepClause
    : KEEP LPAREN DENSE_RANK (FIRST | LAST) orderByClause RPAREN
    ;

overClause
    : OVER LPAREN
        (PARTITION BY expr (COMMA expr)*)?
        orderByClause?
        frameClause?
      RPAREN
    ;

frameClause
    : ROWS frameBound
    | RANGE frameBound
    ;

frameBound
    : BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW              # frameBetweenUbAndCurrent
    | BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING      # frameBetweenUbAndUb
    | BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING              # frameBetweenCurrentAndUb
    | UNBOUNDED PRECEDING                                       # frameUnboundedPreceding
    | CURRENT ROW                                               # frameCurrentRow
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
    | TINYTEXT | TEXT | MEDIUMTEXT | LONGTEXT
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
    | OVER | PARTITION
    | KEEP | DENSE_RANK | RANK
    | PRECEDING | FOLLOWING | UNBOUNDED | CURRENT
    | DATE | TIMESTAMP | INTERVAL | TIME | YEAR | MONTH | DAY | HOUR | MINUTE | SECOND
    | TINYINT | SMALLINT | INT | INTEGER | BIGINT | DECIMAL | NUMERIC
    | FLOAT | REAL | DOUBLE | PRECISION | CHAR | VARCHAR | TINYTEXT | TEXT | MEDIUMTEXT | LONGTEXT
    | BOOLEAN | DATETIME | JSON | XML | UUID | BINARY | VARBINARY | BLOB | CLOB | BIT | ENUM
    | LATERAL | IGNORE | ENGINE | TABLESPACE | CHARACTER | COLLATE | COMMENT
    | WITH | TIME | ZONE
    | PROCEDURE | FUNCTION | CALL | REPLACE | OUT | INOUT | RETURNS | LANGUAGE
    | DROP | TRUNCATE | ALTER | ADD | COLUMN | TYPE | RENAME | TO | VIEW | SCHEMA | DATABASE
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
RANGE:        R A N G E;
ONLY:         O N L Y;
PRECEDING:    P R E C E D I N G;
FOLLOWING:    F O L L O W I N G;
UNBOUNDED:    U N B O U N D E D;
CURRENT:      C U R R E N T;
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
OVER:         O V E R;
PARTITION:    P A R T I T I O N;
KEEP:         K E E P;
DENSE_RANK:   D E N S E '_' R A N K;
RANK:         R A N K;
PRECISION:    P R E C I S I O N;
CHAR:         C H A R;
VARCHAR:      V A R C H A R;
TINYTEXT:     T I N Y T E X T;
TEXT:         T E X T;
MEDIUMTEXT:   M E D I U M T E X T;
LONGTEXT:     L O N G T E X T;
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
PROCEDURE:    P R O C E D U R E;
FUNCTION:     F U N C T I O N;
CALL:         C A L L;
REPLACE:      R E P L A C E;
OUT:          O U T;
INOUT:        I N O U T;
RETURNS:      R E T U R N S;
LANGUAGE:     L A N G U A G E;
DROP:         D R O P;
TRUNCATE:     T R U N C A T E;
ALTER:        A L T E R;
ADD:          A D D;
TYPE:         T Y P E;
RENAME:       R E N A M E;
TO:           T O;
COLUMN:       C O L U M N;
VIEW:         V I E W;
SCHEMA:       S C H E M A;
DATABASE:     D A T A B A S E;
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

// ══════════════════════════════════════════════════
//  DROP / TRUNCATE / ALTER TABLE
// ══════════════════════════════════════════════════

dropTableStatement
    : DROP TABLE (IF EXISTS)? tableName=identifier (CASCADE | RESTRICT)?
    ;

dropIndexStatement
    : DROP INDEX (IF EXISTS)? indexName=identifier (ON tableName=identifier)?
    ;

dropDatabaseStatement
    : DROP DATABASE (IF EXISTS)? dbName=identifier
    ;

createViewStatement
    : CREATE VIEW viewName=identifier AS selectStatement
    ;

createSchemaStatement
    : CREATE SCHEMA schemaName=identifier
    ;

truncateStatement
    : TRUNCATE (TABLE)? tableName=identifier
    ;

alterTableStatement
    : ALTER TABLE tableName=identifier alterAction
    ;

alterAction
    : ADD (COLUMN)? columnDef                     # AddColumn
    | DROP (COLUMN)? identifier                    # DropColumn
    | ALTER (COLUMN)? col=identifier TYPE dataType  # AlterColumnType
    | ALTER (COLUMN)? col=identifier SET DEFAULT expr # AlterColumnSetDefault
    | ALTER (COLUMN)? col=identifier DROP DEFAULT    # AlterColumnDropDefault
    | RENAME COLUMN old=identifier TO newName=identifier # RenameColumn
    ;

// ══════════════════════════════════════════════════
//  CREATE PROCEDURE / FUNCTION
// ══════════════════════════════════════════════════

createProcedureStatement
    : CREATE (OR REPLACE)? PROCEDURE identifier
      LPAREN (procedureParam (COMMA procedureParam)*)? RPAREN
      (AS body=STRING_LITERAL)?
    ;

createFunctionStatement
    : CREATE (OR REPLACE)? FUNCTION identifier
      LPAREN (procedureParam (COMMA procedureParam)*)? RPAREN
      RETURNS dataType
      (AS body=STRING_LITERAL)?
    ;

callStatement
    : CALL identifier LPAREN (expr (COMMA expr)*)? RPAREN
    ;

procedureParam
    : (IN | OUT | INOUT)? paramName=identifier dataType
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
