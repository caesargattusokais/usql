# USQL — Universal SQL 编译器设计方案

**版本**: 1.0  
**日期**: 2026-06-26  
**状态**: 设计阶段

---

## 1. 目标

定义一个标准 SQL 超集（U-SQL），提供完整编译器工具链，将 U-SQL 翻译到任意目标数据库方言并保证语义等价。

用户只需写一次 U-SQL，即可在 MySQL、Oracle、PostgreSQL、达梦等数据库上正确执行。集成方式对应用层零侵入。

---

## 2. 架构总览

```
用户 U-SQL 文本
     │
     ▼
┌─────────────────────────────────────────────────────┐
│                  USQL 编译器 (usql-core)             │
│                                                     │
│  Phase 1: Lexer      → Token 流                    │
│  Phase 2: Parser     → CST (具体语法树)              │
│  Phase 3: AST Builder → USqlAst (规范 AST)           │
│  Phase 4: 语义分析    → SemanticIR + 类型推导 + 能力标记 │
│  Phase 5: 优化       → 常量折叠 / 表达式简化          │
│  Phase 6: 能力检查   → Polyfill 触发                │
│  Phase 7: Backend    → 目标数据库 SQL               │
│  Phase 8: 语义验证   → 双执行对比 → 通过 / 失败       │
│                                                     │
│  支撑模块:                                           │
│  ├── TypeCatalog     (类型映射表)                    │
│  ├── FunctionCatalog (函数目录)                     │
│  ├── CapabilityRegistry (能力注册表 + Polyfill)     │
│  └── SemanticVerifier (双执行语义验证)               │
└─────────────────────────────────────────────────────┘
     │
     ▼
  目标 SQL 文本 (MySQL / Oracle / PG / 达梦)
```

### 交付物

```
usql-core          核心编译器库（纯 Java，零外部依赖）
    │
    ├── usql-jdbc   JDBC Driver 封装（Java 项目改连接串即用）
    ├── usql-cli    命令行工具（存量 SQL 批量迁移）
    └── usql-proxy  数据库协议代理（独立部署，跨语言零侵入）
```

| 交付物 | 适用场景 | 侵入性 |
|--------|---------|--------|
| usql-jdbc | Java 项目 | 只改 JDBC 连接串 |
| usql-cli | 存量 SQL 批量翻译 | 命令行调用 |
| usql-proxy | 任何语言的项目 | 只改数据库连接地址 |

---

## 3. Semantic IR 设计

### 3.1 IR 三层模型

```
Layer 3: Logical Plan    操作意图（查询/DML/DDL）
Layer 2: Expression IR   表达式树，每个节点带类型
Layer 1: Capability IR   能力标记，驱动 Polyfill
```

### 3.2 Layer 1 — Capability（能力标记）

| 能力 | 含义 | 缺失时 Polyfill |
|------|------|----------------|
| LIMIT_OFFSET | 分页 | ROWNUM 子查询包裹 (Oracle) |
| WINDOW_FUNCTION | 窗口函数 | 自连接 + 子查询 |
| RECURSIVE_CTE | 递归 CTE | 存储过程 + 临时表 |
| MERGE_INTO | 合并写入 | INSERT ON DUPLICATE KEY / IF UPDATE ELSE INSERT |
| BOOLEAN_TYPE | 原生布尔 | 映射为 TINYINT / NUMBER(1) |
| AUTO_INCREMENT | 自增列 | SEQUENCE + TRIGGER |
| CONCAT_WITH_NULL | CONCAT NULL 行为 | COALESCE 包裹 |
| INTERVAL_ARITHMETIC | 间隔运算 | 展开为数值运算 |
| LATERAL_JOIN | 横向连接 | CROSS APPLY / 子查询 |
| FULL_OUTER_JOIN | 全外连接 | LEFT JOIN UNION RIGHT JOIN |
| PARTIAL_INDEX | 部分索引 | 函数索引 / 不支持则报错 |
| ARRAY_TYPE | 数组类型 | JSON 存储 / 多表模拟 |
| DEFERRABLE_FK | 延迟外键 | 不支持则报错 |

### 3.3 Layer 2 — 表达式 IR

每个节点强类型标注。核心节点类型：

```
IRExpr
├── IRLiteral      (value, DataType)
├── IRColumnRef    (name, qualifier, DataType)
├── IRBinaryOp     (left, op, right, DataType)
├── IRUnaryOp      (op, operand, DataType)
├── IRFunctionCall (funcName, args[], DataType)
├── IRCase         (condition, trueVal, falseVal, DataType)
├── IRCast         (expr, targetType)
└── IRSubquery     (IRSelect, DataType)

DataType
├── IntType(bits)              TINYINT / SMALLINT / INT / BIGINT
├── DecimalType(precision,scale)
├── VarcharType(length)
├── CharType(length)
├── BooleanType                (三值逻辑)
├── DateType
├── TimeType(frac)
├── DatetimeType(frac)
├── TimestampType(frac)
├── NullType
└── ArrayType(elemType)
```

### 3.4 Layer 3 — 逻辑计划

```java
// 顶层语句
IRStatement = IRSelect | IRInsert | IRUpdate | IRDelete | IRMerge | IRCreateTable | IRCreateIndex ...

// SELECT 结构
IRSelect {
    SelectCore core        // projections, from, where, groupBy, having, withClause, setOp, distinct
    List<OrderBy> orderBy  // expr + ASC/DESC + NULLS FIRST/LAST
    FetchClause fetch      // limit + offset (语义分页，非语法分页)
}

// 表引用
IRTableRef = IRTableName | IRJoin | IRSubqueryTable | IRFunctionTable

// JOIN
IRJoin {
    IRTableRef left, right
    JoinType type          // INNER / LEFT / RIGHT / CROSS / FULL
    IRExpr onCondition
}

// 分页（关键：存意图不存语法）
FetchClause { IRExpr limit, IRExpr offset }
```

### 3.5 IR 示例

输入 U-SQL：
```sql
SELECT d.name, COUNT(*) AS cnt
FROM departments d
JOIN employees e ON d.id = e.dept_id
WHERE e.salary > 50000
GROUP BY d.name
HAVING COUNT(*) > 3
ORDER BY cnt DESC
LIMIT 10 OFFSET 0
```

对应 IR：
```
IRSelect {
  core: SelectCore {
    projections: [
      IRExprSelect(IRColumnRef("name","d",Varchar(100))),
      IRExprSelect(IRFunctionCall("COUNT",[*],BigInt), alias:"cnt")
    ],
    from: [
      IRJoin(
        left:  IRTableName("departments","d"),
        type:  INNER,
        right: IRTableName("employees","e"),
        on:    IRBinaryOp(IRColumnRef("id","d",Int32), EQ, IRColumnRef("dept_id","e",Int32), Bool)
      )
    ],
    where: IRBinaryOp(IRColumnRef("salary","e",Decimal(10,2)), GT, IRLiteral(50000), Bool),
    groupBy: [IRGroupBy(IRColumnRef("name","d",Varchar(100)), PLAIN)],
    having: IRBinaryOp(IRFunctionCall("COUNT",[*],BigInt), GT, IRLiteral(3), Bool)
  },
  orderBy: [OrderBy(IRColumnRef("cnt",null,BigInt), DESC, NULLS_LAST)],
  fetch: FetchClause(IRLiteral(10), IRLiteral(0)),
  capabilities: {LIMIT_OFFSET, AGGREGATE, HAVING}
}
```

→ Oracle Backend 生成（自动 ROWNUM 包裹）：

```sql
SELECT inner__."name", inner__."cnt"
FROM (
    SELECT inner__.*, ROWNUM AS rn__
    FROM (
        SELECT d."name", COUNT(*) AS "cnt"
        FROM departments d
        JOIN employees e ON d."id" = e."dept_id"
        WHERE e."salary" > 50000
        GROUP BY d."name"
        HAVING COUNT(*) > 3
        ORDER BY COUNT(*) DESC
    ) inner__
    WHERE ROWNUM <= 10
) WHERE rn__ > 0
```

---

## 4. 类型映射表

### 4.1 枢纽设计

```
MySQL 类型 ←→ U-SQL 规范类型 ←→ Oracle 类型
                              ←→ PG 类型
                              ←→ 达梦类型
```

每个数据库只维护到 U-SQL 枢纽的单向映射，不需要 N×(N-1)。

### 4.2 四大数据库映射

| U-SQL | MySQL | PostgreSQL | Oracle | 达梦 |
|-------|-------|-----------|--------|------|
| TINYINT | TINYINT | SMALLINT | NUMBER(3) | TINYINT |
| SMALLINT | SMALLINT | SMALLINT | NUMBER(5) | SMALLINT |
| INT | INT | INTEGER | NUMBER(10) | INT |
| BIGINT | BIGINT | BIGINT | NUMBER(19) | BIGINT |
| DECIMAL(p,s) | DECIMAL(p,s) | NUMERIC(p,s) | NUMBER(p,s) | DECIMAL(p,s) |
| FLOAT | FLOAT | REAL | BINARY_FLOAT | FLOAT |
| DOUBLE | DOUBLE | DOUBLE PRECISION | BINARY_DOUBLE | DOUBLE |
| CHAR(n) | CHAR(n) | CHAR(n) | CHAR(n) | CHAR(n) |
| VARCHAR(n) | VARCHAR(n) | VARCHAR(n) | VARCHAR2(n CHAR) | VARCHAR(n) |
| TEXT | LONGTEXT | TEXT | CLOB | TEXT/CLOB |
| BOOLEAN | TINYINT(1) ⚠️ | BOOLEAN ✅ | NUMBER(1) ⚠️ | BIT ⚠️ |
| DATE | DATE ✅ | DATE ✅ | TRUNC(DATE) ⚠️ | DATE ⚠️ |
| TIME(p) | TIME(p) | TIME(p) | INTERVAL DAY TO SECOND(p) ⚠️ | TIME(p) ✅ |
| DATETIME(p) | DATETIME(p) | TIMESTAMP(p) | TIMESTAMP(p) | TIMESTAMP(p) |
| TIMESTAMP(p) | TIMESTAMP(p) ⚠️2038 | TIMESTAMPTZ(p) | TIMESTAMP(p) WITH TIME ZONE | TIMESTAMP(p) WITH TIME ZONE |
| INTERVAL Y-M | VARCHAR(32) ⚠️ | INTERVAL YEAR TO MONTH | INTERVAL YEAR TO MONTH | INTERVAL YEAR TO MONTH |
| INTERVAL D-S | VARCHAR(32) ⚠️ | INTERVAL DAY TO SECOND | INTERVAL DAY TO SECOND | INTERVAL DAY TO SECOND |
| JSON | JSON | JSONB | CLOB CHECK IS JSON | TEXT+JSON函数 |
| UUID | CHAR(36) | UUID ✅ | RAW(16) | VARCHAR(36) |
| BINARY(n) | BINARY(n) | BYTEA | RAW(n) | BINARY(n) |
| BLOB | LONGBLOB | BYTEA | BLOB | BLOB/IMAGE |
| ARRAY(T) | JSON ❌ | T[] ✅ | VARRAY/NESTED TABLE | ❌ |

### 4.3 重灾区处理

**BOOLEAN**
```
存储: MySQL TINYINT(1) / Oracle NUMBER(1) / 达梦 BIT / PG BOOLEAN
比较: WHERE is_active = TRUE
  → MySQL:  WHERE is_active = 1
  → Oracle: WHERE is_active = 1
  → PG:     WHERE is_active IS TRUE
  → 达梦:   WHERE is_active = 1
```

**DATE 语义差异**
```
Oracle/达梦的 DATE 包含时间部分
U-SQL DATE (纯日期) 生成时:
  → Oracle: 显式 TRUNC 或 DATE 字面量
  → 达梦:   同 Oracle 处理
  → MySQL/PG: 原生纯日期
```

---

## 5. 函数目录

### 5.1 设计

函数映射不硬编码在编译器，而是维护外部配置文件：

```yaml
STRING_CONCAT:
  u_sql: CONCAT(args...)
  mysql: CONCAT(args...)
  pg: args || args || ...
  oracle: args || args || ...
  dm: CONCAT(args...)
  polyfill:
    null_behavior: "treat_null_as_empty"

DATE_ADD:
  u_sql: DATE_ADD(date, INTERVAL n unit)
  mysql: DATE_ADD(date, INTERVAL n unit)
  pg: date + n * INTERVAL '1 unit'
  oracle: date + NUMTODSINTERVAL(n, unit)
  dm: date + n
  polyfill:
    unit_conversion:
      MONTH: ADD_MONTHS(date, n)
      YEAR:  ADD_MONTHS(date, n * 12)
```

### 5.2 核心函数分类

| 类别 | 函数 | 跨库难点 |
|------|------|---------|
| 字符串 | CONCAT, SUBSTR, REPLACE, UPPER/LOWER, TRIM | CONCAT 的 NULL 行为差异 |
| 数值 | ABS, ROUND, CEIL/FLOOR, MOD | 取模负数行为不一致 |
| 日期 | NOW, DATE_ADD, DATE_DIFF, DATE_FORMAT | 函数名和参数顺序完全不同 |
| 比较 | COALESCE, NULLIF, GREATEST/LEAST | Oracle 的 NVL vs 标准 COALESCE |
| 聚合 | COUNT, SUM, AVG, MIN/MAX | AVG 返回类型差异 |
| 窗口 | ROW_NUMBER, RANK, LAG/LEAD | 性能差异大，旧版本不支持 |
| 类型转换 | CAST, CONVERT | Oracle 的 TO_CHAR/TO_NUMBER/TO_DATE |

---

## 6. 编译器流水线

### 6.1 八个阶段

| Phase | 名称 | 输入 | 输出 | 是否 Fatal |
|-------|------|------|------|-----------|
| 1 | Lexer | 文本 | Token 流 | ✅ |
| 2 | Parser | Token 流 | CST | ✅ |
| 3 | AST Builder | CST | USqlAst | ❌ |
| 4 | 语义分析 | USqlAst | SemanticIR | ❌ |
| 5 | IR 优化 | SemanticIR | SemanticIR | ❌ |
| 6 | 能力检查 | SemanticIR | SemanticIR + Polyfill 日志 | ⚠️ |
| 7 | Backend 生成 | SemanticIR | 目标 SQL 文本 | ❌ |
| 8 | 语义验证 | 目标 SQL | 通过/失败 报告 | ❌ |

⚠️ Phase 6: 部分能力缺失 fatal（如目标数据库不支持且 polyfill 无法覆盖）

### 6.2 主调度

```java
class USqlCompiler {
    CompilationResult compile(
        String usql,
        Dialect target,
        CompileOptions options  // schema, optimizeLevel, verify, testData
    ) {
        // Phase 1-2: Lex + Parse (fatal on error)
        TokenStream tokens = lex(usql);
        CstNode cst = parse(tokens);
        if (cst.hasErrors()) return failed(cst.errors());
        
        // Phase 3: AST
        USqlAst ast = AstBuilder.build(cst);
        
        // Phase 4: Semantic analysis
        SemanticIR ir = SemanticAnalyzer.analyze(ast, buildContext(options));
        if (ir.hasErrors()) return failed(ir.errors());
        
        // Phase 5: Optimize
        ir = IROptimizer.optimize(ir, options.optimizeLevel());
        
        // Phase 6: Capability check + polyfill
        CapabilityReport cap = CapabilityChecker.check(ir, target);
        if (cap.hasFatal()) return failed(cap.errors());
        if (cap.hasMissing()) ir = polyfillEngine.apply(ir, cap);
        
        // Phase 7: Generate
        DialectBackend backend = dialectCatalog.get(target);
        GeneratedSQL sql = backend.generate(ir);
        
        // Phase 8: Verify (optional)
        VerificationReport report = null;
        if (options.verify()) {
            GeneratedSQL refSQL = dialectCatalog.get(REF_DIALECT).generate(ir);
            report = verifier.verify(refSQL, sql, options.testData());
        }
        
        return success(sql, report);
    }
}
```

---

## 7. 语义验证

### 7.1 架构

```
U-SQL → IR → 目标 SQL → 目标库执行 → 结果 A
              │
              ▼
         参考 SQL → 参考库(H2)执行 → 结果 B
              │
              ▼
         类型感知比较器 → 通过 / 失败 (含差异报告)
```

### 7.2 参考库

首选 **H2**（嵌入式、零依赖、标准 SQL 合规、支持多种兼容模式），最终 CI 用 PostgreSQL 容器做最终验证。

### 7.3 比较策略

```java
enum ComparisonVerdict {
    MATCH,              // 完全一致
    ACCEPTABLE_DIFF,    // 可容忍差异（浮点精度、时区表示、精度截断）
    HARD_MISMATCH       // 硬错误，必须修复
}
```

**类型感知比较规则**：

| 类型 | MATCH 条件 | ACCEPTABLE_DIFF 条件 |
|------|-----------|---------------------|
| 整型 | 值严格相等 | 无 |
| 浮点 | 相对误差 < 1e-5 | 精度截断差异 |
| DECIMAL | BigDecimal.compareTo == 0 | 标度差异（1.00 vs 1.000） |
| VARCHAR | 严格相等 | 无 |
| CHAR(n) | 严格相等 或 尾随空白填充后相等 | 空白填充差异 |
| DATETIME | 规范化到 UTC 后相等 | 毫秒/微秒精度截断 |
| BOOLEAN | 逻辑值一致（含 NULL） | 无 |
| NULL | IS NOT DISTINCT FROM | 无 |

### 7.4 测试数据生成

每列自动生成：典型值 + NULL + 边界值 + 0/空字符串 + 多字节字符。

### 7.5 验证流水线

```
CI Pipeline:
  1. 编译期静态检查（秒级）  — 类型兼容 / 能力覆盖
  2. H2 嵌入式全用例（秒级）
  3. Docker 容器关键用例（分钟级） — MySQL 8.0 / PG 16 / Oracle 23c / 达梦
  4. 差异报告
  5. 失败 → 阻止合入
```

---

## 8. DDL IR 设计

### 8.1 建表

```java
IRCreateTable {
    IRTableName name
    boolean ifNotExists
    List<IRColumnDef> columns
    List<IRTableConstraint> constraints
    IRTableOptions options
}

IRColumnDef {
    String name
    DataType type            // U-SQL 规范类型
    List<IRColumnConstraint> constraints
    IRExpr defaultValue
}

// 列级约束
IRColumnConstraint = ColNotNull | ColUnique | ColPrimaryKey(自增?) | ColCheck | ColReferences(FK) | ColGenerated

// 表级约束
IRTableConstraint = TBUnique | TBPrimaryKey | TBForeignKey | TBCheck | TBIndex
```

### 8.2 关键跨库适配

| 约束 | MySQL | PG | Oracle | 达梦 | 策略 |
|------|-------|----|--------|------|------|
| AUTO_INCREMENT | ✅ | SEQUENCE+TRIGGER | SEQUENCE+TRIGGER | ✅ | 前置+后置 DDL |
| ENUM 列类型 | ✅ | CREATE TYPE AS ENUM | VARCHAR+CHECK | VARCHAR+CHECK | 前置 DDL |
| DEFERRABLE FK | ❌ | ✅ | ✅ | ❌ | 默认 NOT DEFERRABLE |
| 部分索引(WHERE) | ❌ | ✅ | 函数索引替代 | ❌ | 报错或替代 |
| GENERATED VIRTUAL | ✅(5.7+) | 仅 STORED | ✅ | 版本依赖 | 检查后决定 |

---

## 9. 错误处理

### 9.1 策略

非 fatal 阶段收集所有错误（不中断），一次性报告：

```
Phase 1-2: Lex/Parse 错误 → Fatal，停止
Phase 4:   语义错误      → 收集全部，继续分析
Phase 6:   能力缺失      → 部分 fatal, 部分触发 polyfill
Phase 7:   生成警告      → 非 fatal
Phase 8:   验证失败      → 非 fatal（标记为有风险可用）
```

### 9.2 错误报告格式

```
Error: 列 'salary' 在表 'employees' 中不存在
  → main.sql:12:15
  → 提示: employees 表可用列为 [id, emp_name, dept_id, pay]

Error: 类型不匹配 — VARCHAR 不能与 INT 比较
  → main.sql:8:22
  → WHERE name > 100
  → 提示: 列 name 类型为 VARCHAR(100)，比较值 100 类型为 INT
```

---

## 10. 集成方式

### 10.1 usql-core

纯 Java 库，零外部依赖，包含 Phase 1-7 全部能力 + 类型映射 + 函数目录。

Public API：
```java
// 方式 A: 直接编译
USqlCompiler.compile("SELECT ... LIMIT 10", Dialect.ORACLE);

// 方式 B: 批量翻译
USql.transpile(sqlText, Dialect.MYSQL, Dialect.POSTGRES);

// 方式 C: 包装 DataSource
USqlDataSource.wrap(originalDataSource, Dialect.MYSQL);
```

### 10.2 usql-jdbc

实现 `java.sql.Driver`：
```
连接串: jdbc:usql:mysql://localhost:3306/mydb
            或
        jdbc:usql:oracle://localhost:1521/XEPDB1

应用写: jdbc:usql:oracle://host/db → 用户写 U-SQL → Driver 翻译 → 转发 Oracle Driver
```

对 Spring Boot / MyBatis / JPA 零侵入。

### 10.3 usql-proxy

实现 MySQL Wire Protocol 前端，任何语言零侵入：
```
应用程序 ── MySQL Protocol ──► USql Proxy ── 翻译后 SQL ──► 真实 DB
(写 U-SQL)                    (编译器)                      (Oracle/PG/达梦)
```

前端实现：
- MySQL 握手协议（伪装为 MySQL 8.0 服务器）
- 解析客户端发送的 COM_QUERY 包
- SQL 经编译器翻译
- 翻译后 SQL 发往真实数据库
- 结果集原路返回

### 10.4 usql-cli

```bash
# 单条翻译
usql translate --from usql --to oracle --sql "SELECT ..."

# 批量迁移
usql migrate --from mysql --to postgres --input ./sql/ --output ./pg-sql/

# 验证模式
usql verify --sql "SELECT ..." --target oracle
```

---

## 11. 目录结构

```
usql/
├── usql-core/                     # 核心编译器
│   ├── src/main/java/com/usql/
│   │   ├── lexer/                 # Phase 1: 词法分析
│   │   ├── parser/                # Phase 2: 语法分析 (antlr4 grammar)
│   │   ├── ast/                   # Phase 3: AST 定义 + Builder
│   │   ├── ir/                    # IR 定义 (SemanticIR, Expr, Capability)
│   │   ├── analyzer/              # Phase 4: 语义分析 + 类型推导
│   │   ├── optimizer/             # Phase 5: IR 优化
│   │   ├── capability/            # Phase 6: 能力检查 + Polyfill 引擎
│   │   ├── backend/               # Phase 7: 方言后端
│   │   │   ├── Backend.java       # 接口
│   │   │   ├── MySqlBackend.java
│   │   │   ├── PgBackend.java
│   │   │   ├── OracleBackend.java
│   │   │   └── DmBackend.java
│   │   ├── catalog/               # 类型映射 + 函数目录
│   │   │   ├── TypeCatalog.java
│   │   │   ├── FunctionCatalog.java
│   │   │   └── resources/functions.yaml
│   │   ├── verify/                # Phase 8: 语义验证
│   │   ├── dialect/               # 方言定义
│   │   └── USqlCompiler.java      # 主入口
│   └── src/test/
│
├── usql-jdbc/                     # JDBC Driver
│   └── src/main/java/com/usql/jdbc/
│       ├── USqlDriver.java
│       └── USqlDataSource.java
│
├── usql-cli/                      # CLI 工具
│   └── src/main/java/com/usql/cli/
│       └── Main.java
│
├── usql-proxy/                    # 协议代理
│   └── src/main/java/com/usql/proxy/
│       ├── ProxyServer.java       # TCP 监听
│       ├── MySQLProtocol.java     # MySQL Wire Protocol 实现
│       └── QueryInterceptor.java  # SQL 拦截 + 翻译
│
└── docs/
    └── usql-design.md
```

---

## 12. 技术选型

| 模块 | 选型 | 原因 |
|------|------|------|
| 语言 | Java 17+ | 生态成熟、JDBC 标准、企业级部署 |
| Lexer/Parser | antlr4 | 成熟的语法分析生成器，社区有 SQL grammar 积累 |
| 构建 | Maven (多模块) | 多模块管理清晰 |
| 参考数据库 | H2 | 嵌入式、零依赖、标准 SQL 合规 |
| 类型比较 | BigDecimal 精确比较 + 相对误差浮点比较 | 定点数精确、浮点容忍 |
| 函数目录 | YAML 文件 | 可热加载、可被运维修改 |
| 验证数据库 | Docker + Testcontainers | CI 中管理容器生命周期 |

---

## 13. 开发路线图

### Phase 1 — MVP（核心链路）
- [ ] antlr4 语法文件定义 U-SQL 语法
- [ ] Lexer + Parser → AST
- [ ] 语义分析 + 类型推导
- [ ] MySQL + PG Backend
- [ ] 函数目录 (核心 30 个函数)
- [ ] 类型映射表
- [ ] H2 语义验证
- [ ] 能力检查 + LIMIT_OFFSET / BOOLEAN polyfill

### Phase 2 — 扩展覆盖
- [ ] Oracle + 达梦 Backend
- [ ] 函数目录扩展到 100+ 函数
- [ ] DDL (CREATE TABLE / INDEX) 全支持
- [ ] Docker 多库 CI 验证
- [ ] 完整 polyfill 引擎

### Phase 3 — 交付形态
- [ ] usql-jdbc
- [ ] usql-cli
- [ ] usql-proxy (MySQL Wire Protocol)

### Phase 4 — 高级特性
- [ ] 窗口函数 / CTE / 递归 CTE
- [ ] 存储过程 IR (最小支持)
- [ ] MERGE INTO / UPSERT 语义
- [ ] 子查询优化
- [ ] 验证数据自动生成增强

---

## 14. 设计原则

1. **IR 是心脏** — 所有翻译基于 IR，不做 SQL → SQL 的直接规则匹配
2. **每个节点带类型** — 类型信息贯穿整个流水线，是正确生成的基础
3. **能力优先检查** — 生成前先检查目标库能力，不支持的走 polyfill 或报错
4. **不信任翻译结果** — 每条 SQL 必须经过双执行语义验证
5. **加新方言只需一个 Backend** — 不需要 N×(N-1) 规则组合
6. **零侵入集成** — usql-jdbc 改连接串 / usql-proxy 改连接地址
7. **错误先行** — 收集所有错误一次性报告，不做"改一处报一处"

---

## 15. 风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| Oracle/达梦的 DATE 含时间语义 | 日期比较结果不一致 | 语义分析阶段标记 DATE 使用场景，生成时加显式 TRUNC |
| MySQL TIMESTAMP 2038 问题 | 大时间戳溢出 | 编译器警告 + 建议用 DATETIME |
| 达梦文档不全，功能边界模糊 | 某些能力不确定是否支持 | 运行时能力探测 + 保守降级 |
| Polyfill 性能开销 | ROWNUM 三层包裹可能慢 | 记录性能分析数据，标记慢查询 |
| LLM 翻译准确率（如果引入 LLM） | 翻译可能出错 | LLM 结果必过语义验证，不通过回退规则引擎 |

---

## 附录 A: U-SQL BNF 核心语法片段

```antlr
selectStatement:
    SELECT (DISTINCT)? selectItem (',' selectItem)*
    FROM tableRef (',' tableRef)*
    (WHERE expr)?
    (GROUP BY expr (',' expr)*)?
    (HAVING expr)?
    (ORDER BY orderByItem (',' orderByItem)*)?
    (LIMIT expr (OFFSET expr)?)?
    ;

selectItem:
    expr (AS? alias)?
    | '*'
    | qualifier '.' '*'
    ;

tableRef:
    tableName (AS? alias)?
    | tableRef (INNER | LEFT | RIGHT | CROSS | FULL) JOIN tableRef ON expr
    | '(' selectStatement ')' AS? alias
    ;

expr:
    literal
    | columnRef
    | functionCall
    | '(' expr ')'
    | expr op expr                         // + - * / = <> < > <= >= AND OR || LIKE IN
    | NOT expr
    | expr IS (NOT)? NULL
    | expr IS (NOT)? TRUE | FALSE
    | expr BETWEEN expr AND expr
    | CASE WHEN expr THEN expr (WHEN expr THEN expr)* (ELSE expr)? END
    | CAST '(' expr AS dataType ')'
    ;

functionCall:
    functionName '(' (expr (',' expr)*)? ')'
    | functionName '(' '*' ')'            // COUNT(*)
    ;
```
