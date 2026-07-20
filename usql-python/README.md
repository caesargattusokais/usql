# USQL Python — Universal SQL Compiler v4.0.0

写一次 SQL，在 MySQL、PostgreSQL、Oracle、达梦 DM、SQL Server、MariaDB、TiDB、SQLite、DuckDB、OceanBase、ClickHouse 上正确执行。

---

## 目录

- [快速开始](#快速开始)
- [安装](#安装)
- [Python API](#python-api)
- [SQLAlchemy 集成](#sqlalchemy-集成)
- [命令行工具](#命令行工具)
- [支持的语法](#支持的语法)
- [方言映射表](#方言映射表)
- [编译管线](#编译管线)
- [项目结构](#项目结构)
- [测试](#测试)
- [与 Java 版的关系](#与-java-版的关系)

---

## 快速开始

```python
from usql import USqlCompiler, Dialect

compiler = USqlCompiler()

# 翻译到 Oracle（LIMIT → ROWNUM 子查询包裹）
result = compiler.compile("SELECT name FROM users LIMIT 10", Dialect.ORACLE)
print(result.sql)
# SELECT * FROM (SELECT "name" FROM "users") WHERE ROWNUM <= 10

# 翻译到 SQL Server（LIMIT → OFFSET/FETCH）
result = compiler.compile("SELECT name FROM users LIMIT 10", Dialect.SQLSERVER)
print(result.sql)
# SELECT [name] FROM [users] ORDER BY (SELECT NULL) OFFSET 0 ROWS FETCH NEXT 10 ROWS ONLY

# 翻译到 ClickHouse（INT → Int32，添加 ENGINE）
result = compiler.compile("CREATE TABLE t1 (id INT PRIMARY KEY)", Dialect.CLICKHOUSE)
print(result.sql)
# CREATE TABLE `t1` (
#   `id` Int32 PRIMARY KEY
# ) ENGINE = MergeTree() ORDER BY `id`

# 翻译到 SQLite（TRUNCATE → DELETE FROM）
result = compiler.compile("TRUNCATE TABLE t1", Dialect.SQLITE)
print(result.sql)
# DELETE FROM "t1"
```

---

## 安装

### 前提

- Python 3.10+
- pip

### 从源码安装

```bash
cd usql-python
pip install -e .
```

### 依赖

| 依赖 | 用途 | 必需 |
|------|------|:----:|
| click >= 8.0 | CLI 命令行 | 运行时 |
| pyyaml >= 6.0 | 函数目录 YAML 加载 | 运行时 |
| pytest >= 8.0 | 测试 | 开发 |
| mypy | 类型检查 | 开发 |
| ruff | 代码风格 | 开发 |

开发依赖一键安装：

```bash
pip install -e ".[dev]"
```

---

## Python API

### USqlCompiler

编译器主入口，线程安全（无共享可变状态）。

```python
from usql import USqlCompiler, Dialect

compiler = USqlCompiler()
```

#### 构造参数

```python
compiler = USqlCompiler(
    schema=None,           # SchemaProvider — 表结构元数据（可选）
    verify=False,          # 是否生成 H2 参考SQL用于对比
    optimize_level=1,      # IR优化级别: 0=关闭, 1=常量折叠, 2=表达式简化, 3=谓词下推
    default_dialect=Dialect.MYSQL,  # 默认方言
    cache_enabled=True,    # 编译缓存
    cache_size=256,        # 缓存条目上限
)
```

#### compile() — 从 U-SQL 文本编译

```python
result = compiler.compile(
    usql="SELECT name, COUNT(*) AS cnt FROM users GROUP BY name LIMIT 10",
    target=Dialect.ORACLE,
)
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `usql` | `str` | U-SQL 语句 |
| `target` | `Dialect` | 目标方言 |
| `options` | `GenerateOptions \| None` | 生成选项（可选） |

**返回** `CompilationResult`：

```python
result.success       # bool — 是否编译成功
result.sql           # str | None — 生成的方言SQL
result.errors        # tuple[Error, ...] — 错误列表
result.warnings      # tuple[Warning, ...] — 警告列表
result.reference_sql # str | None — H2参考SQL（verify=True时）
result.report()      # str — 人类可读报告
```

#### compile_from_ir() — 从 IR 直接编译

跳过词法分析和语法分析，直接从 IR 生成 SQL：

```python
from usql.ir.statement import IRSelect, SelectCore, IRExprSelect
from usql.ir.expr import IRLiteral
from usql.ir.types import IntType

ir = IRSelect(core=SelectCore(projections=(
    IRExprSelect(expr=IRLiteral(value=1, type=IntType(32))),
)))
result = compiler.compile_from_ir(ir, Dialect.MYSQL)
```

#### 缓存管理

```python
compiler.cache_size   # int — 当前缓存条目数
compiler.clear_cache()  # 清空缓存
```

### Dialect 枚举

```python
from usql.dialect.dialect import Dialect

# 11 个可用方言
Dialect.MYSQL        # MySQL
Dialect.POSTGRESQL   # PostgreSQL
Dialect.ORACLE       # Oracle
Dialect.DM           # 达梦 DM
Dialect.SQLSERVER    # SQL Server
Dialect.MARIADB      # MariaDB
Dialect.TIDB         # TiDB
Dialect.SQLITE       # SQLite
Dialect.OCEANBASE    # OceanBase
Dialect.CLICKHOUSE   # ClickHouse
Dialect.DUCKDB       # DuckDB

# 方言属性
Dialect.MYSQL.display_name     # "MySQL"
Dialect.MYSQL.capabilities     # frozenset[Capability]
Dialect.MYSQL.supports(Capability.LIMIT_OFFSET)  # True
```

### GenerateOptions

```python
from usql.backend.generate_options import GenerateOptions, QuoteStyle

# 默认选项（美化输出）
GenerateOptions.DEFAULTS
# GenerateOptions(quote_style=QuoteStyle.DEFAULT, pretty_print=True, indent="  ")

# 精简输出（无缩进）
GenerateOptions.MINIMAL
# GenerateOptions(quote_style=QuoteStyle.DEFAULT, pretty_print=False, indent="")

# 自定义
opts = GenerateOptions(
    quote_style=QuoteStyle.ALWAYS,    # ALWAYS / DEFAULT / RESERVED_ONLY / NEVER
    pretty_print=True,
    indent="    ",
    emit_comments=False,
)
result = compiler.compile("SELECT 1", Dialect.MYSQL, options=opts)
```

### CompilationResult

```python
result = compiler.compile("SELECT 1", Dialect.MYSQL)

if result.success:
    print(result.sql)         # 生成的SQL
    print(result.warnings)    # 警告列表（tuple[Warning, ...]）
else:
    print(result.errors)      # 错误列表（tuple[Error, ...]）
    print(result.report())    # 格式化报告
```

---

## SQLAlchemy 集成

USQL 提供与 SQLAlchemy 2.0+ 的透明集成，基于 `before_cursor_execute` 事件钩子。所有基于 SQLAlchemy 的框架自动受益：Alembic、Flask-SQLAlchemy、FastAPI、pandas `read_sql` 等。

### 安装

```bash
pip install -e ".[sqlalchemy]"
```

### usql_engine() — 一行创建（最简单）

```python
from usql.sqlalchemy_ext import usql_engine
from sqlalchemy import text

engine = usql_engine("oracle+oracledb://user:pass@host/db")
with engine.connect() as conn:
    result = conn.execute(text("SELECT name FROM users LIMIT 10"))
    # 自动翻译为: SELECT * FROM (SELECT "name" FROM "users") WHERE ROWNUM <= 10
```

### listen_engine() — 包装已有 Engine

```python
from sqlalchemy import create_engine
from usql.sqlalchemy_ext import listen_engine

engine = create_engine("postgresql+psycopg2://user:pass@host/db")
listen_engine(engine)  # 自动检测方言，注册翻译

with engine.connect() as conn:
    conn.execute(text("SELECT name FROM users LIMIT 10"))
```

### 显式指定方言

```python
from usql.sqlalchemy_ext import listen_engine
from usql.dialect.dialect import Dialect

engine = create_engine("oracle+oracledb://...")
listen_engine(engine, dialect=Dialect.ORACLE)
```

### unlisten_engine() — 移除翻译

```python
from usql.sqlalchemy_ext import unlisten_engine

unlisten_engine(engine)  # SQL 不再翻译，直接传给驱动
```

### 方言自动检测

USQL 从 SQLAlchemy URL 自动检测目标方言：

| SQLAlchemy URL | USQL Dialect |
|----------------|-------------|
| `mysql+...` | MYSQL |
| `postgresql+...` | POSTGRESQL |
| `oracle+...` | ORACLE |
| `mssql+...` | SQLSERVER |
| `mariadb+...` | MARIADB |
| `sqlite+...` | SQLITE |
| `duckdb+...` | DUCKDB |
| `clickhouse+...` | CLICKHOUSE |

也可手动调用：

```python
from usql.sqlalchemy_ext import detect_dialect

dialect = detect_dialect("postgresql+psycopg2://host/db")  # Dialect.POSTGRESQL
dialect = detect_dialect(engine)  # 从 Engine 对象检测
```

### 框架集成示例

#### Flask-SQLAlchemy

```python
from flask import Flask
from flask_sqlalchemy import SQLAlchemy
from usql.sqlalchemy_ext import listen_engine

app = Flask(__name__)
app.config["SQLALCHEMY_DATABASE_URI"] = "oracle+oracledb://..."
db = SQLAlchemy(app)
listen_engine(db.engine)
```

#### FastAPI + SQLAlchemy

```python
from fastapi import FastAPI, Depends
from sqlalchemy import text
from usql.sqlalchemy_ext import usql_engine

engine = usql_engine("oracle+oracledb://user:pass@host/db")

def get_db():
    with engine.connect() as conn:
        yield conn

app = FastAPI()

@app.get("/users")
def list_users(db=Depends(get_db)):
    rows = db.execute(text("SELECT name FROM users LIMIT 10")).fetchall()
    return [{"name": r[0]} for r in rows]
```

#### pandas read_sql

```python
import pandas as pd
from usql.sqlalchemy_ext import usql_engine

engine = usql_engine("oracle+oracledb://user:pass@host/db")
df = pd.read_sql("SELECT name, COUNT(*) AS cnt FROM users GROUP BY name LIMIT 10", engine)
```

---

## 命令行工具

安装后可使用 `usql` 命令：

```bash
usql --version
usql --help
```

### translate — 翻译单条 SQL

```bash
usql translate --sql "SELECT name FROM users LIMIT 10" --to oracle
# SELECT "name" FROM "users" WHERE ROWNUM <= 10

usql translate --sql "SELECT name FROM users LIMIT 10" --to sqlserver
# SELECT [name] FROM [users] ORDER BY (SELECT NULL) OFFSET 0 ROWS FETCH NEXT 10 ROWS ONLY

usql translate --sql "CREATE TABLE t1 (id INT PRIMARY KEY AUTO_INCREMENT)" --to postgresql
# CREATE TABLE "t1" ("id" INT PRIMARY KEY GENERATED ALWAYS AS IDENTITY)
```

`--to` 可选值：`mysql`, `postgresql`, `oracle`, `dm`, `sqlserver`, `mariadb`, `tidb`, `sqlite`, `oceanbase`, `clickhouse`, `duckdb`

### migrate — 批量迁移 SQL 文件

```bash
usql migrate --to postgresql --input ./mysql-sql/ --output ./pg-sql/
```

将 `--input` 目录下所有 `.sql` 文件翻译到目标方言，输出到 `--output` 目录。

### verify — 验证 SQL 兼容性

```bash
usql verify --sql "SELECT name FROM users LIMIT 10" --to oracle
# ✅ Valid
```

### dialects — 列出支持的方言

```bash
usql dialects
```

输出：

```
Supported dialects:
  MYSQL        — MySQL
  POSTGRESQL   — PostgreSQL
  ORACLE       — Oracle
  DM           — 达梦DM
  SQLSERVER    — SQL Server
  MARIADB      — MariaDB
  TIDB         — TiDB
  SQLITE       — SQLite
  OCEANBASE    — OceanBase
  CLICKHOUSE   — ClickHouse
  DUCKDB       — DuckDB
```

---

## 支持的语法

### DDL — 数据定义

```sql
CREATE TABLE users (
    id INT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(200),
    salary DECIMAL(10,2) DEFAULT 0.00,
    active BOOLEAN DEFAULT TRUE,
    created_date DATE,
    bio TEXT,
    data JSON
)

CREATE TABLE IF NOT EXISTS users (id INT PRIMARY KEY)
CREATE INDEX idx_name ON users (name)
CREATE UNIQUE INDEX idx_email ON users (email)
DROP TABLE users
DROP TABLE IF EXISTS users
TRUNCATE TABLE users

ALTER TABLE users ADD COLUMN email VARCHAR(255)
ALTER TABLE users DROP COLUMN email
ALTER TABLE users RENAME COLUMN name TO full_name
```

### DML — 数据操作

```sql
INSERT INTO users (name, email) VALUES ('Alice', 'alice@test.com')
INSERT INTO users (name, email) VALUES ('Bob', 'b@t.com'), ('Carol', 'c@t.com')
UPDATE users SET salary = 80000 WHERE name = 'Bob'
DELETE FROM users WHERE name = 'Bob'
```

### DQL — 查询

```sql
SELECT name, age FROM users WHERE age > 18
SELECT DISTINCT dept_id FROM users
SELECT name FROM users WHERE name LIKE 'A%'
SELECT name FROM users WHERE salary BETWEEN 50000 AND 80000
SELECT name FROM users WHERE dept_id IN (1, 2, 3)
SELECT name FROM users WHERE email IS NOT NULL

-- 分页：统一用 LIMIT / OFFSET
SELECT name FROM users ORDER BY name LIMIT 10
SELECT name FROM users ORDER BY name LIMIT 10 OFFSET 20

-- 聚合
SELECT dept_id, COUNT(*) AS cnt, AVG(salary) AS avg_sal
FROM employees GROUP BY dept_id HAVING COUNT(*) > 5

-- JOIN
SELECT d.name, e.salary FROM departments d JOIN employees e ON d.id = e.dept_id
SELECT d.name, COUNT(e.id) FROM departments d
LEFT JOIN employees e ON d.id = e.dept_id GROUP BY d.name

-- CASE / 函数 / CAST
SELECT CASE WHEN salary > 70000 THEN 'High' ELSE 'Low' END AS lvl FROM t1
SELECT UPPER(name) FROM users
SELECT CAST(salary AS VARCHAR(10)) FROM users
SELECT 1 + 1 AS two
```

### TCL — 事务控制

```sql
BEGIN
START TRANSACTION
COMMIT
ROLLBACK
SAVEPOINT sp
RELEASE SAVEPOINT sp
```

---

## 方言映射表

### 标识符引用

| 方言 | 引用风格 | 示例 |
|------|---------|------|
| MySQL / MariaDB / TiDB / OceanBase / ClickHouse | 反引号 | `` `name` `` |
| PostgreSQL / Oracle / DM / SQLite / DuckDB | 双引号 | `"name"` |
| SQL Server | 方括号 | `[name]` |

### 分页

| 方言 | U-SQL | 方言输出 |
|------|-------|---------|
| MySQL / PG / MariaDB / TiDB / SQLite / DuckDB / OceanBase / ClickHouse | `LIMIT 10 OFFSET 5` | 原生 LIMIT/OFFSET |
| Oracle | `LIMIT 10` | `WHERE ROWNUM <= 10` |
| SQL Server | `LIMIT 10 OFFSET 5` | `OFFSET 5 ROWS FETCH NEXT 10 ROWS ONLY` |

### 类型映射

| U-SQL | MySQL | PG | Oracle | ClickHouse | SQLite |
|-------|-------|----|--------|------------|--------|
| INT | INT | INT | NUMBER(10) | Int32 | INTEGER |
| BIGINT | BIGINT | BIGINT | NUMBER(19) | Int64 | INTEGER |
| BOOLEAN | TINYINT(1) | BOOLEAN | NUMBER(1) | UInt8 | INTEGER |
| VARCHAR(n) | VARCHAR(n) | VARCHAR(n) | VARCHAR2(n) | String | TEXT |
| TEXT | TEXT | TEXT | CLOB | String | TEXT |
| DECIMAL(p,s) | DECIMAL(p,s) | DECIMAL(p,s) | NUMBER(p,s) | Decimal(p,s) | REAL |
| DATE | DATE | DATE | DATE | Date | TEXT |

### 特殊 Polyfill

| 特性 | U-SQL | MySQL | Oracle | SQL Server | SQLite |
|------|-------|-------|--------|-----------|--------|
| 无 FROM | `SELECT 1` | 原生 | `FROM DUAL` | 原生 | 原生 |
| TRUNCATE | `TRUNCATE TABLE t1` | 原生 | 原生 | 原生 | `DELETE FROM t1` |
| 布尔默认值 | `DEFAULT TRUE` | `DEFAULT 1` | `DEFAULT 1` | `DEFAULT 1` | `DEFAULT 1` |
| 自增 | `AUTO_INCREMENT` | 原生 | `GENERATED ... AS IDENTITY` | `IDENTITY(1,1)` | `AUTOINCREMENT` |

---

## 编译管线

```
SQL 输入
   │
   ▼
Lexer.tokenize()        → Token[]
   │
   ▼
Parser.parse_program()  → AST (SelectStmt, InsertStmt, ...)
   │
   ▼
SemanticAnalyzer        → IR (IRSelect, IRInsert, ...)
   │
   ▼
IROptimizer             → 优化后的 IR
   │
   ▼
CapabilityChecker       → CapabilityReport（缺失能力 + 严重性）
   │
   ▼
PolyfillEngine          → 填充后的 IR（如 FULL JOIN → UNION）
   │
   ▼
DialectBackend.generate() → 方言 SQL
   │
   ▼
CompilationResult
```

---

## 项目结构

```
usql-python/
├── pyproject.toml                # 项目配置
├── README.md                     # 本文档
├── src/usql/
│   ├── __init__.py               # 版本号 + 公开 API
│   ├── compiler.py               # USqlCompiler 主入口
│   ├── result.py                 # CompilationResult
│   ├── schema.py                 # SchemaProvider 接口
│   │
│   ├── dialect/
│   │   ├── dialect.py            # Dialect 枚举 (11 方言 + 能力集)
│   │   └── capability.py         # Capability 枚举 (28 能力标志)
│   │
│   ├── ir/
│   │   ├── types.py              # DataType 层次 (IntType, VarcharType, ...)
│   │   ├── expr.py               # IRExpr 层次 (IRLiteral, IRBinaryOp, ...)
│   │   ├── statement.py          # IRStatement 层次 (IRSelect, IRInsert, ...)
│   │   └── semantic.py           # SemanticIR 包装
│   │
│   ├── ast/
│   │   └── nodes.py              # AST 节点定义
│   │
│   ├── parser/
│   │   ├── lexer.py              # 手写词法分析器 (~490行)
│   │   └── parser.py             # 递归下降 + Pratt 解析器 (~1365行)
│   │
│   ├── analyzer/
│   │   └── semantic.py           # SemanticAnalyzer (AST → IR)
│   │
│   ├── capability/
│   │   ├── checker.py            # CapabilityChecker
│   │   └── polyfill.py           # PolyfillEngine
│   │
│   ├── backend/
│   │   ├── base.py               # AbstractDialectBackend 基类
│   │   ├── generate_options.py   # GenerateOptions
│   │   ├── mysql.py              # MySqlBackend
│   │   ├── postgresql.py         # PgBackend
│   │   ├── oracle.py             # OracleBackend
│   │   ├── dm.py                 # DmBackend
│   │   ├── sqlserver.py          # SqlServerBackend
│   │   ├── mariadb.py            # MariaDbBackend
│   │   ├── tidb.py               # TiDbBackend
│   │   ├── sqlite.py             # SqliteBackend
│   │   ├── oceanbase.py          # OceanBaseBackend
│   │   ├── clickhouse.py         # ClickHouseBackend
│   │   └── duckdb.py             # DuckDbBackend
│   │
│   ├── catalog/
│   │   └── function.py           # FunctionCatalog
│   │
│   ├── sqlalchemy_ext/           # SQLAlchemy 2.0+ 集成
│   │   ├── __init__.py           # 公开 API
│   │   ├── engine.py             # usql_engine() 工厂
│   │   ├── listener.py           # before_cursor_execute 事件
│   │   └── dialect_map.py        # 方言自动检测
│   │
│   ├── optimizer/
│   │   └── optimizer.py          # IROptimizer
│   │
│   └── cli/
│       └── main.py               # Click CLI
│
├── resources/
│   └── functions.yaml            # 函数目录
│
└── tests/
    └── test_regression.py        # 86 个回归测试
```

---

## 测试

### 运行全部测试

```bash
cd usql-python
set PYTHONPATH=src
python -m pytest tests/ -v
```

### 运行特定测试类

```bash
python -m pytest tests/test_regression.py::TestDialectSpecific -v
python -m pytest tests/test_regression.py::TestQuery -v
```

### 测试覆盖

| 测试类 | 覆盖范围 | 测试数 |
|--------|---------|--------|
| TestDDL | CREATE TABLE / INDEX / DROP / TRUNCATE / ALTER × 11 方言 | 12 |
| TestDML | INSERT / UPDATE / DELETE × 11 方言 | 4 |
| TestQuery | SELECT 各种特性 × 11 方言 | 20 |
| TestTCL | BEGIN / COMMIT / ROLLBACK × 11 方言 | 3 |
| TestDialectSpecific | Oracle DUAL/ROWNUM、SQL Server OFFSET/FETCH、SQLite TRUNCATE→DELETE 等 | 12 |
| TestParser | Lexer + Parser 直接调用 | 4 |
| TestCompilerAPI | compile / compile_from_ir / cache / dialects | 4 |
| TestSQLAlchemyExt | 方言检测 / usql_engine / listen / 翻译 / 错误处理 | 28 |
| **总计** | | **114** |

### 在代码中快速验证

```bash
python -c "from usql import USqlCompiler, Dialect; c = USqlCompiler(); r = c.compile('SELECT COUNT(*) FROM users LIMIT 10', Dialect.ORACLE); print(r.sql)"
```

---

## 与 Java 版的关系

| 对比项 | Java 版 | Python 版 |
|--------|---------|----------|
| 解析器 | 手写递归下降 (HandLexer + HandParser) | 移植自 Java，相同算法 |
| IR 模型 | sealed interface + record | `@dataclass(frozen=True)` + `Union[]` |
| 模式匹配 | switch expression | `match` 语句 (PEP 634) |
| 后端继承 | AbstractDialectBackend → 11 子类 | 相同继承层次 |
| 能力系统 | CapabilityChecker + PolyfillEngine | 移植，语义等价 |
| 函数目录 | functions.yaml (110+ 函数) | 复用同一 YAML |
| 性能 | ~100,000 SQL/秒 | 适合脚本/ETL/AI pipeline 场景 |
| 数据库执行 | JDBC Driver + DataSource 包装 | 纯编译器（不执行 SQL） |
| Spring Boot | 自动 DataSource 包装 | 不适用 |
| CLI | usql-cli.jar | `usql` 命令 (Click) |

Python 版是编译器（SQL → SQL），不连数据库执行。如需执行翻译后的 SQL，请配合 SQLAlchemy、psycopg2、mysql-connector 等数据库驱动使用。

---

## 版本

当前版本: **v4.0.0**（与 Java 版保持版本号同步）
