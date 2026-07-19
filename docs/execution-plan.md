# USQL 执行计划

创建日期: 2026-06-28
最后更新: 2026-07-19
当前版本: v4.0.0

---

## 进度总览

- Phase 1-7: 全部 100% ✅
- Phase 8 待评估: 0% (0/3)
- Phase 9 后续方向: 100% ✅ (5/5)
- Phase 10 Bug 修复: 69/46+28低 (🔴 10/10, 🟠 11/11, 🟡 21/25, 🟢 27/28)

---

## Phase 7 - 扩展方向

| # | 任务 | 状态 | 说明 |
|---|------|------|------|
| 7.1 | 单测覆盖率提升 | 40% | Backend 层真实库已全覆盖，纯单元空间有限 |
| 7.2 | IROptimizer Level 3 高级优化 | ✅ | 谓词下推 + 投影裁剪，L2/L3 结果一致性验证通过 |
| 7.3 | 更多数据库支持 | ✅ | +6 方言 (MariaDB/TiDB/SQLite/DuckDB/OceanBase/ClickHouse)，5→11 全部回归通过 |
| 7.4 | DDL 扩展（VIEW / SCHEMA / DROP DATABASE） | ✅ | CREATE VIEW + CREATE SCHEMA + DROP DATABASE |
| 7.5 | 语法增强（LATERAL / TCL 事务） | ✅ | LATERAL 11 方言 + TCL 事务透传 |
| 7.6 | 性能测试 / 编译基准 | ✅ | 吞吐 15000/s，11 种查询 × 4 方言，4-940 μs/query |

### 7.2 IROptimizer Level 3 — 详情

- **谓词下推**: `SELECT * FROM (SELECT ... FROM t) s WHERE s.age > 18` → WHERE 推入子查询
- **投影裁剪**: 外查询只用到的列才保留，子查询去掉不需要的 SELECT 列
- **正确性验证**: L2(无优化) vs L3(有优化) 真实数据库结果比对，772 全通过

### 7.3 数据库扩展 — 详情

| 数据库 | Backend | 回归 |
|--------|---------|:--:|
| MySQL / PG / Oracle / DM / SQL Server | 各自独立 Backend | ✅ |
| MariaDB / TiDB / OceanBase | 复用 MySqlBackend | ✅ |
| SQLite | 独立 SqliteBackend | ✅ |
| DuckDB | DuckDbBackend (extends PgBackend) | ✅ |
| ClickHouse | ClickHouseBackend (extends MySqlBackend) | ✅ |

---

## Phase 8 - 待评估（低优先级）

| # | 任务 | 说明 |
|---|------|------|
| 8.1 | DB2 支持 | IBM 商业库，使用面窄 |
| 8.2 | ARRAY 类型映射 | 只需 PG（其他方言无原生数组），低优先级 |
| 8.3 | ALTER TABLE RENAME 完整语法 | SQL Server 已完成，其他方言部分支持 |

---

## Phase 6 - 收尾修复

| # | 任务 | 状态 |
|---|------|------|
| 6.1 | 存储过程方言语法补全（Oracle/PG/SQL Server/DM 覆写） | ✅ |
| 6.2 | 存储过程语法解析（grammar + AstBuilder） | ✅ |
| 6.3 | 存储过程 SemanticAnalyzer + Capability 标记 | ✅ |
| 6.4 | IRCall 无参括号修复 | ✅ |
| 6.5 | 存储过程集成测试（含真实 DB） | ✅ |

---

## Phase 1 - MVP (核心链路)

| # | 任务 | 状态 |
|---|------|------|
| 1.1 | antlr4 语法文件 USql.g4 569行 | ✅ |
| 1.2 | Lexer + Parser 到 AST (AstBuilder) | ✅ |
| 1.3 | 语义分析 + 类型推导 (SemanticAnalyzer) | ✅ |
| 1.4 | MySQL Backend | ✅ |
| 1.5 | PostgreSQL Backend | ✅ |
| 1.6 | Oracle Backend (ROWNUM包裹) | ✅ |
| 1.7 | 达梦 DM Backend | ✅ |
| 1.8 | 类型映射表 TypeCatalog | ✅ |
| 1.9 | 能力检查 + Polyfill | ✅ |
| 1.10 | 函数目录补全 35 个函数 | ✅ |
| 1.11 | H2 双执行语义验证 (MySQL+PG 14/14) | ✅ |
| 1.12 | compile(String) 文本入口 | ✅ |

---

## Phase 2 - 扩展覆盖

| # | 任务 | 状态 |
|---|------|------|
| 2.1 | Oracle + 达梦 Backend | ✅ |
| 2.2 | 函数目录扩展到 103 个 | ✅ |
| 2.3 | DDL 全链路测试 (5 tests x 4 DBs = 20/20) | ✅ |
| 2.4 | AUTO_INCREMENT → IDENTITY (Oracle/PG/DM) | ✅ |
| 2.5 | ENUM 跨库适配 (MySQL原生/PG+Oracle+DM CHECK) | ✅ |
| 2.6 | Docker CI 验证 (7 suites x 4 DBs) | ✅ |
| 2.7 | 完整 polyfill (16 capabilities documented) | ✅ |

---

## Phase 3 - 交付形态

| # | 任务 | 状态 |
|---|------|------|
| 3.1 | usql-jdbc Driver 实现 | ✅ |
| 3.2 | usql-jdbc DataSource 包装 | ✅ |
| 3.3 | usql-cli translate 命令 | ✅ |
| 3.4 | usql-cli migrate 命令 | ✅ |
| 3.5 | usql-cli verify 命令 | ✅ |
| 3.6 | usql-proxy MySQL Wire Protocol | ✅ |
| 3.7 | usql-proxy COM_QUERY 翻译转发 | ✅ |

---

## Phase 4 - 高级特性

| # | 任务 | 状态 |
|---|------|------|
| 4.1 | 窗口函数 (ROW_NUMBER/RANK/LAG/LEAD 等 10 个 + WindowFrame ROWS/RANGE) | ✅ |
| 4.2 | CTE + 递归CTE | ✅ |
| 4.3 | MERGE INTO / UPSERT | ✅ |
| 4.4 | 子查询优化 | ✅ |
| 4.5 | 验证数据自动生成 | ✅ |
| 4.6 | 存储过程 IR | ✅ |

### 4.1 窗口函数 — 详情

- 10 个窗口函数: ROW_NUMBER, RANK, DENSE_RANK, LAG, LEAD, FIRST_VALUE, LAST_VALUE, NTILE, PERCENT_RANK, CUME_DIST
- WindowFrame 支持: `ROWS/RANGE BETWEEN … AND …` 及单边界 `ROWS n PRECEDING`
- KEEP (DENSE_RANK FIRST|LAST ORDER BY) Oracle 聚合扩展 + 跨库 polyfill
- IR 层: `IRWindowOver` (partitionBy, orderBy, frame) + `WindowFrame` sealed interface
- 5 方言全覆盖: MySQL 8.0+, PG, Oracle, DM8, SQL Server

### 4.2 CTE + 递归CTE — 详情

- IR 层: `IRCommonTable` (name, columns, query, recursive)
- `SelectCore.withClause` 承载 WITH 子句
- 5 方言均声明 `RECURSIVE_CTE` 能力

### 4.6 存储过程 IR (733a2f8, c5a0280)

- IR 新增: `IRCreateProcedure`, `IRCreateFunction`, `IRCall`, `ProcedureParam`, `ParamMode`
- 支持 IN/OUT/INOUT 参数, `OR REPLACE`, raw body 直传
- 5 方言 Backend 全部接入，各有方言特定覆写:

| 方言 | 语法风格 |
|------|---------|
| MySQL | 基类默认: `CREATE PROCEDURE name(params) body` |
| Oracle | `AS body;` / `RETURN type` / `IN OUT` / `BEGIN...END` 调用 |
| PostgreSQL | `$$ body $$ LANGUAGE plpgsql` / `RETURNS type` |
| SQL Server | `CREATE OR ALTER` / `@param` / `OUTPUT` / `EXEC name args` |
| DM | Oracle 兼容: `AS body;` / `RETURN type` / `IN OUT` |

- ❌ 缺失: 语法解析（不能从文本 CREATE PROCEDURE 编译）
- ❌ 缺失: SemanticAnalyzer 处理
- ❌ 缺失: CapabilityChecker 能力标记

### 4.5 验证数据自动生成 (1967c17)

- `TestDataGenerator` — 根据 CREATE TABLE 定义自动生成 INSERT 语句
- 支持所有类型: INT/VARCHAR/BOOLEAN/DATE/DATETIME/ENUM/JSON/UUID 等
- 外键列自动引用前序表的 PK 值，保持引用完整性
- 可空列最后一行生成 NULL，主键/自增列正确处理
- 确定性生成 (seeded PRNG, seed=42)，可配置行数

### 4.4 子查询优化 (019af31)

- IROptimizer Level 2: FROM 子查询扁平化
- 简单子查询内联: `SELECT * FROM (SELECT a,b FROM t) s` → `SELECT a,b FROM t`
- WHERE 合并: 内层和外层条件用 AND 组合
- 安全检查: DISTINCT/GROUP BY/HAVING/ORDER BY/LIMIT 不扁平化
- Level 2 链路: foldConstants → optimizeSubqueries

### 4.3 MERGE INTO / UPSERT — 详情

- IR 层: `IRMerge` + `MergeInsert` / `MergeUpdate` / `MergeDelete`
- MySQL: INSERT … ON DUPLICATE KEY UPDATE
- PostgreSQL: INSERT … ON CONFLICT DO UPDATE
- Oracle / DM: 原生 MERGE INTO
- SQL Server: 原生 MERGE

---

## Phase 5 - 代码质量优化

| # | 任务 | 状态 |
|---|------|------|
| 5.1 | KEEP polyfill 提取到 AbstractDialectBackend | ✅ |
| 5.2 | USqlCompiler 单例共享（消除重复实例） | ✅ |
| 5.3 | WindowFrame 结构化（sealed interface → toSql） | ✅ |
| 5.4 | `generateFunctionCall` 提取到 AbstractDialectBackend | ✅ |
| 5.5 | FunctionCatalog YAML 化 | ✅ |
| 5.6 | 错误信息优化 | ✅ |
| 5.7 | 单元测试补全 | ✅ |
| 5.8 | `IF NOT EXISTS` 跨库一致 | ✅ |
| 5.9 | 类型推导缺失修复 | ✅ |
| 5.10 | IROptimizer 常量折叠 Level 1 实现 | ✅ |
| 5.11 | PolyfillEngine 补全 IR rewrite 逻辑 | ✅ |
| 5.12 | SemanticVerifier 集成到 CI/编译流程 | ✅ |
| 5.13 | CapabilityChecker 补全 27 能力分级 | ✅ |
| 5.14 | SemanticAnalyzer 职责拆分 | ✅ |

### 5.1 KEEP polyfill 提取 (53ee6ba)

- 新建 `AbstractDialectBackend`，包含共享的 `scanKeep`、`polyfillKeep`、`scanKeepFromSelect`、`wrapFromWithKeep`、`partitionFromGroupBy`
- MySQL/PG/DM/SQL Server 从 `implements DialectBackend` 改为 `extends AbstractDialectBackend`
- 每个 Backend 消除约 100 行重复 KEEP 代码，`generateExpr` 从 private 改为 protected
- -238 行 / +126 行 = 净减 112 行

### 5.2 USqlCompiler 单例共享 (46bd570)

- `USqlDataSource` 用静态 `COMPILER` 单例代替每实例创建
- `USqlDriver` 复用 `USqlDataSource.compiler()` 而非自己持有副本
- 消除每个 DataSource 包装器上的重复 `FunctionCatalog`、`TypeCatalog`、5 个 Backend 实例

### 5.3 WindowFrame 结构化 (3390b73)

- `IRExpr.WindowFrame` sealed interface：`Unit`/`Bound` 枚举 + `Between`/`Single` 记录
- `toSql()` 方法从结构化类型生成 frame 文本
- 语法文件：`frameBound` 备选加标签，方便 IR 构建
- 5 个 Backend 统一用 `over.frame().toSql()` 替代原始字符串拼接

### 5.14 SemanticAnalyzer 拆分 (f6357b2)

- 新建 `TypeInferrer` 工具类，4 个静态方法
- `inferBinaryResultType` / `inferFunctionReturnType` / `inferExpressionType` / `parseTypeName`
- SemanticAnalyzer 委托给 TypeInferrer，~95 行纯逻辑提取
- SemanticAnalyzer: 740→645 行

### 5.13 CapabilityChecker 补全 (a22c86b)

- 27 个能力完整分级: ERROR(1) / WARNING(5) / INFO(14)
- ERROR: RECURSIVE_CTE（真正无法翻译）
- WARNING: ARRAY_TYPE, DEFERRABLE_FK, GENERATED_COLUMN, FULL_OUTER_JOIN, WINDOW_FUNCTION
- INFO: LIMIT_OFFSET, BOOLEAN_TYPE, MERGE_INTO 等 — clean polyfill
- PolyfillEngine: WINDOW_FUNCTION 标记为 polyfillable

### 5.12 SemanticVerifier 集成 (979e68b)

- `CompilationResult` 新增 `referenceSql` 字段和 `getReferenceSql()` getter
- Phase 8: verify 标志启用时生成 H2 参考 SQL，不再打 dead warning
- `report()` 输出参考 SQL 与目标 SQL 并列
- CiRunner 可用 `getReferenceSql()` 配合真实库做运行验证
- 移除 "not yet automated" 占位

### 5.11 PolyfillEngine IR rewrite (5520a1a)

- `polyfillFullOuterJoin` 实际实现: FULL JOIN → LEFT JOIN UNION RIGHT JOIN
- `replaceJoinType` 递归遍历 FROM 树替换 JOIN 类型
- 处理嵌套 JOIN 和子查询表
- 之前为空壳 "requires IR tree rewrite"，现在生效

### 5.10 IROptimizer 常量折叠 (7e2a62b)

- 二元运算折叠: `3*4→12`, `'a'||'b'→'ab'`, `TRUE AND FALSE→FALSE`
- 一元运算折叠: `NOT TRUE→FALSE`, `-5→-5`
- `IS NULL` 在 null 字面量上求值 → TRUE
- SELECT 简化: `WHERE TRUE`→移除, `OFFSET 0`→移除
- 递归折叠所有 IRStatement 类型和子表达式
- 37→340 行 (+303)

### 5.9 类型推导缺失修复 (a219fa3)

- 函数返回类型: catalog 未指定时按函数名+参数推断 (SUM→BIGINT/DOUBLE, AVG→DOUBLE, COUNT→BIGINT)
- 二元运算: BIGINT 参与运算保留 BIGINT (之前总是回退 INT)
- CASE 表达式: ELSE 优先，然后扫描 WHEN 分支 (之前只用第一个 WHEN)
- +46/-4 行

### 5.8 IF NOT EXISTS 跨库一致 (170d978)

- SQL Server CREATE TABLE: `IF OBJECT_ID(N'...', N'U') IS NULL` 守卫
- SQL Server CREATE INDEX: `IF NOT EXISTS (SELECT 1 FROM sys.indexes...)` 守卫
- Oracle/DM CREATE INDEX: PL/SQL `EXECUTE IMMEDIATE` + `SQLCODE = -955` 包装
- 5 方言全覆盖: MySQL/PG (native) | Oracle/DM (PL/SQL) | SQL Server (T-SQL guard)

### 5.7 单元测试补全 (2b8e7f1)

- `FunctionCatalogTest` — 14 项测试：YAML 加载、函数查找、polyfill 检测、方言映射、返回类型
- `CapabilityCheckerTest` — 10 项测试：跨 5 方言能力检查、polyfill vs fatal 判定
- 均为独立 main 方法，无需数据库

### 5.6 错误信息优化 (67a62f2)

- `CompilationResult.Warning` 添加 hint 字段（与 Error 对齐），`report()` 输出 hint
- `SemanticAnalyzer` 错误添加 actionable hint（未知表达式、未知别名建议可用 scope）
- 5 个 Backend fallback 错误从 `"Unknown: Xxx"` 改为列出支持的语句/表达式类型
- `CapabilityChecker` 消息包含 feature 名称和方言名
- `FunctionCatalog` YAML 错误包含文件名
- 9 files, +60/-19

### 5.5 FunctionCatalog YAML 化 (59771a9)

- ~110 函数定义从 Java 硬编码迁移到 `functions.yaml`
- `FunctionCatalog` 构造函数改为 `loadFromYaml()`，用 SnakeYAML 解析
- 新增 `parseReturnType()` 支持 `INT`/`VARCHAR`/`DATETIME`/`VARCHAR(N)` 等格式
- 删除 `registerCoreFunctions()` 及所有 helper 方法（`reg`, `regDialect`, `dm`, `allSame`, `dialectMap`）
- 保留 `FunctionDef`、`DialectMapping`、`PolyfillConfig` 不变
- Java: 545→117 行（-428），YAML: +646 行

### 5.4 generateFunctionCall 提取 — 详情

- 5 个 Backend 中 `generateFunctionCall` 几乎完全相同，唯一差异是 `forDialect(Dialect.XXX)` 参数
- 基类新增 `resolveFunctionCall()`（目录查找+模板渲染+OVER）和 `generateFunctionCall()`（默认 KEEP polyfill）
- MySQL/PG/DM/SQL Server 删除重复方法（各减 ~45 行）
- Oracle 覆盖，调用 `resolveFunctionCall` 后追加原生 `KEEP (DENSE_RANK ...)`（减 ~30 行）
- 净减 151 行，Backend 总量 2761→2610

---

## 计划外已完成

| # | 任务 | 状态 |
|---|------|------|
| — | SQL Server 方言 (SqlServerBackend + Dialect.SQLSERVER) | ✅ |
| — | KEEP Oracle 聚合扩展 + 跨库 polyfill | ✅ |
| — | 函数目录扩展至 ~110 个 | ✅ |
| — | Java 17 兼容 (从 21 降级) | ✅ |

---

## 测试覆盖

| 测试套件 | 内容 | 测试数 |
|----------|------|--------|
| SemanticVerification | 28 种查询（含 KEEP/窗口函数）× 5 方言 | 140 |
| FunctionVerification | 50 个函数 × 4 库 | 200 |
| DdlVerification | DDL/DML 操作 × 4 库 | 20 |
| EnumTest | ENUM 约束验证 | 4 |
| CompilerE2E / TextInput | 编译器单元 | 32 |
| FunctionCatalog | YAML 加载 / 函数查找 | 14 |
| CapabilityChecker | 能力检查 / polyfill 判定 | 10 |
| IROptimizer | 常量折叠 / 子查询扁平 | 11 |
| TypeInferrer | 类型推导 / 函数返回 | 21 |
| TestDataGenerator | 测试数据自动生成 | 4 |
| SemanticAnalyzer | 解析/WHERE/GROUP BY/CTE/窗口/CAST | 10 |
| PolyfillEngine | FULL JOIN polyfill + canPolyfill | 12 |
| CompilationResult | 错误/警告/报告/hint | 25 |
| CapabilityChecker | 27 能力 polyfill/fatal 判定 | 30+ |
| Dialect | 6 方言能力集 + displayName | 20+ |
| FunctionCatalog | 按类别验证 100+ 函数 | 20 |
| **总计** | | **620+** |

---

## 方言能力矩阵

| 能力 | MySQL | PG | Oracle | DM | SQL Server | MariaDB | TiDB | SQLite | DuckDB | OceanBase | ClickHouse |
|------|-------|----|--------|-----|-----------|---------|------|--------|-------|----------|-----------|
| LIMIT/OFFSET | ✅ | ✅ | polyfill | polyfill | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| WINDOW_FUNCTION | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| RECURSIVE_CTE | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| MERGE_INTO | polyfill | polyfill | ✅ | ✅ | ✅ | polyfill | — | — | polyfill | polyfill | — |
| BOOLEAN_TYPE | — | ✅ | — | — | — | — | — | — | ✅ | — | — |
| FULL_OUTER_JOIN | polyfill | ✅ | ✅ | ✅ | ✅ | polyfill | — | — | ✅ | polyfill | — |
| LATERAL_JOIN | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| ARRAY_TYPE | — | ✅ | — | — | — | — | — | — | — | — | — |
| AUTO_INCREMENT | ✅ | IDENTITY | IDENTITY | IDENTITY | IDENTITY | ✅ | ✅ | — | ⚠️ | ✅ | — |
| ENUM_TYPE | ✅ | polyfill | polyfill | polyfill | polyfill | ✅ | — | — | polyfill | ✅ | ✅ |
| DROP IF EXISTS | ✅ | ✅ | PL/SQL | PL/SQL | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| CREATE IF NOT EXISTS | ✅ | ✅ | PL/SQL | PL/SQL | T-SQL | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 存储过程 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | — | — | — | ✅ | — |
| TCL 事务 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

---

## Phase 10 - Bug 修复（代码审查发现）

> 2026-07-19 全量代码审查，覆盖 Parser/AST/IR/Optimizer/Backend/Polyfill/Compiler/Semantic 模块，
> 共发现 46 个问题，按严重程度分为 🔴严重 / 🟠高 / 🟡中等 / 🟢低 四级。

### 10.1 🔴 严重 — 生成错误 SQL 或崩溃（10 项）

| # | 模块 | 文件:行号 | 问题 | 状态 |
|---|------|----------|------|------|
| 10.1.1 | Lexer | HandLexer:153-158 | `readString()` 中 `''` 转义单引号是死代码，while 条件 `peek() != '\''` 使得内部 `if (peek() == '\'')` 永远不执行。所有含 `''` 的字符串（如 `'it''s'`）被截断 | ✅ |
| 10.1.2 | Lexer | HandLexer:204-209 | `readQuotedIdentifier()` 同上，`""` 转义引号标识符也是死代码 | ✅ |
| 10.1.3 | Backend | MySqlBackend:305 | `IS_DISTINCT_FROM` 映射为 `<=>`（null-safe equals），语义相反。应为 `NOT (a <=> b)` | ✅ |
| 10.1.4 | Backend | OracleBackend:347-364 | KEEP 子句追加在 OVER 之后，但 Oracle 语法要求 KEEP 在 OVER 之前。生成无效 SQL | ✅ |
| 10.1.5 | Backend | MySqlBackend:588-595 | `generateCreateProcedure` override 总是输出 `()` 忽略 `cp.params()`，带参数的存储过程丢失所有参数 | ✅ |
| 10.1.6 | Backend | OracleBackend:317 | `MOD` 作为中缀操作符 `a MOD b`，Oracle 只支持 `MOD(a, b)` 函数调用，语法错误 | ✅ |
| 10.1.7 | Backend | SqliteBackend:209 | 二元操作符不包裹括号，唯一不用括号的 backend，运算符优先级错误 | ✅ |
| 10.1.8 | Backend | SqliteBackend:214 | 一元操作符 IS_NULL/IS_NOT_NULL/EXISTS 等 default 分支生成 `"IS_NULL col"` 而非 `"col IS NULL"` | ✅ |
| 10.1.9 | Backend | OracleBackend:108-122 | ROWNUM 分页：OFFSET 为非字面量（如参数 `?`）时 `offsetVal=0`，内层 `ROWNUM <= limit + offset` 缺少 offset 部分 | ✅ |
| 10.1.10 | Semantic | SemanticAnalyzer:161-169 | WITH 子句在主 SELECT body 之后才分析，CTE 名称未在 FROM 分析前注册到作用域，`SELECT * FROM cte_name` 无法解析 | ✅ |

### 10.2 🟠 高 — 常见场景错误结果（11 项）

| # | 模块 | 文件:行号 | 问题 | 状态 |
|---|------|----------|------|------|
| 10.2.1 | Optimizer | IROptimizer:601,604,607,608,618,853 | 常量折叠/简化生成的 IRLiteral/IRBinaryOp/IRUnaryOp 携带 null DataType，违反 IR 设计约束，下游 Backend 可能 NPE | ✅ |
| 10.2.2 | Optimizer | IROptimizer:638-680 | 谓词下推不检查子查询是否有 GROUP BY/HAVING/聚合，推送谓词改变语义（先过滤再聚合 vs 先聚合再过滤） | ✅ |
| 10.2.3 | Optimizer | IROptimizer:807-810 | `collectColumns` 第二个 `else if` 匹配 `IRExprSelect` 永远不可达（已被第一个匹配），别名收集失败导致投影裁剪可能删错列 | ✅ |
| 10.2.4 | Parser | HandParser:209 | 一元减号 `parseExpr()` 无最小优先级，`-a + b` 解析为 `-(a + b)` 而非 `(-a) + b` | ✅ |
| 10.2.5 | Parser | HandParser:243 | 不支持简单 CASE 表达式 `CASE x WHEN 1 THEN ...`，会解析错误 | ✅ |
| 10.2.6 | Semantic | SemanticAnalyzer:340-347 | 歧义列引用静默返回第一个匹配，应报错 | ✅ |
| 10.2.7 | Backend | SqliteBackend:141-146 | RIGHT JOIN / FULL JOIN 静默降级为 INNER JOIN（SQLite 3.39+ 已支持） | ✅ |
| 10.2.8 | Backend | PgBackend:393-396 | MERGE → ON CONFLICT 只用第一个 INSERT 列作为冲突目标，多列唯一约束会失败 | ✅ |
| 10.2.9 | Backend | OracleBackend:83 | IntervalDaySecond 的 fractionalSeconds 被用作 DAY 精度而非 SECOND 精度 | ✅ |
| 10.2.10 | Compiler | USqlCompiler:152-155+281-283 | `compile()` 路径双重优化（先优化 IR 缓存，再调 compileFromIR 又优化），浪费性能且若优化器非幂等则不一致 | ✅ |
| 10.2.11 | Semantic | SemanticAnalyzer:238-241 | FunctionTable 列未注册到作用域，`SELECT c FROM table_func() AS t` 无法解析 | ✅ |

### 10.3 🟡 中等 — 边缘场景或设计缺陷（25 项）

| # | 模块 | 文件:行号 | 问题 | 状态 |
|---|------|----------|------|------|
| 10.3.1 | Lexer | HandLexer:241 | `advance()` 不递增 `col`，所有非空白 token 列号偏移 | ✅ |
| 10.3.2 | Parser | HandParser:184 | `IS NOT TRUE`/`IS NOT FALSE` 静默丢弃 token（`else { advance(); }`） | ✅ |
| 10.3.3 | Parser | HandParser:41 | CALL 语句文本被丢弃，返回空 `TCLStmt("")` | ✅ |
| 10.3.4 | Parser | HandParser:162 | CASCADE vs RESTRICT 都设置 `csc=true`，区别丢失 | ✅ |
| 10.3.5 | Parser | HandParser:142 | DEFAULT 出现在约束中间时（如 `NOT NULL DEFAULT 0 UNIQUE`），DEFAULT 后的约束丢失 | ✅ |
| 10.3.6 | AstBuilder | AstBuilder:376 | 一元 plus `+x` 错误创建 `NEG(x)` 节点（`+5` 变 `-5`） | ✅ |
| 10.3.7 | AstBuilder | AstBuilder:39-41 | Hand parser 异常被静默吞掉，fallback 到 ANTLR，调试困难 | ✅ |
| 10.3.8 | AstBuilder | AstBuilder:634-649 | Merge actions 重排序（INSERT/UPDATE/DELETE 分三批收集），丢失原始顺序 | ✅ |
| 10.3.9 | IR | IRExpr + IROptimizer | BETWEEN/IN/IS NULL 有两种 IR 表示（IRBinaryOp vs IRBetween 等），优化器只处理后者，前者被跳过 | ❌ |
| 10.3.10 | Optimizer | IROptimizer:428-441 | 子查询扁平化不重写外层 WHERE 中的别名引用，`s.col` 引用不存在的别名 | ✅ |
| 10.3.11 | Optimizer | IROptimizer:705,724 | `referencesOnly`/`stripQualifier` 不处理 BETWEEN 的 low/high 边界，跨表条件可能被错误推送 | ✅ |
| 10.3.12 | Optimizer | IROptimizer:506-512 | `simplifyExpressions`/`optimizeSubqueries` 只处理 DML，DDL 中的子查询和可折叠表达式被跳过 | ❌ |
| 10.3.13 | Semantic | SemanticAnalyzer:498 | INSERT IGNORE 错误映射为 MERGE_INTO capability（语义不同） | ✅ |
| 10.3.14 | Semantic | SemanticVerifier:76-77 | 列数不一致时不报告，静默比较重叠列 | ✅ |
| 10.3.15 | TypeInferrer | TypeInferrer:24 | CONCAT 类型为 `VarcharType(0)`，零长度 VARCHAR 语义错误 | ✅ |
| 10.3.16 | TypeInferrer | TypeInferrer:43 | COALESCE/NVL 只看第一个参数类型，`COALESCE(NULL, 42)` 返回 NullType | ✅ |
| 10.3.17 | Backend | DuckDbBackend:100-109 | `superGenerateExpr` 通过 `substring(7)` 截取表达式，脆弱（若 SELECT 前缀变化则出错） | ✅ |
| 10.3.18 | Backend | DuckDbBackend:37-38 | DuckDB sequence 名未 `quoteIdentifier()`，含特殊字符的列名会生成无效 SQL | ✅ |
| 10.3.19 | Backend | ClickHouseBackend:59 | ENUM 值未转义单引号（对比 MySqlBackend 用了 `replace("'", "''")`） | ✅ |
| 10.3.20 | Backend | ClickHouseBackend:81 | `chCreateTable` 列约束为 null 时 NPE（其他 Backend 有 null 守卫） | ✅ |
| 10.3.21 | Catalog | TypeCatalog:44-49 | `fromNative` 无法匹配参数化类型（`VARCHAR(255)` 等无法反向映射） | ❌ |
| 10.3.22 | Catalog | TypeCatalog vs Backend | TypeCatalog 和 Backend `mapType()` 重复且不一致，TypeCatalog 精度更低且缺 6 种方言 | ❌ |
| 10.3.23 | Backend | MySqlBackend:204 | FULL JOIN 静默降级为 LEFT JOIN（polyfill 可能未触发） | ✅ |
| 10.3.24 | Backend | OracleBackend:63 | `quoteIdentifier` 始终大写，破坏大小写敏感标识符（`"myCol"` → `"MYCOL"`） | ✅ |
| 10.3.25 | IR | IRStatement:156-159,232-235 | IRMerge/IRCreateIndex compact 构造器 `capabilities` 为 null 时 NPE | ✅ |

### 10.4 🟢 低 — 设计问题/死代码/小不一致

| # | 模块 | 问题 | 状态 |
|---|------|------|------|
| 10.4.1 | IROptimizer | 重复 import `java.util.Set` | ✅ |
| 10.4.2 | IROptimizer | 整数溢出未检测（常量折叠） | ✅ |
| 10.4.3 | IROptimizer | `addValues` 字符串+数字混合类型折叠（ADD≠CONCAT） | ✅ |
| 10.4.4 | IROptimizer | `foldEquals` 用 doubleValue 比较大数 — 精度丢失 | ✅ |
| 10.4.5 | IROptimizer | `tryEvaluateBinary` 吞掉异常，调试困难 | ✅ |
| 10.4.6 | IROptimizer | `simplifyBinary` 中 `x * 0 → 0` 对非数值类型不安全 | ✅ |
| 10.4.7 | IROptimizer | 投影裁剪只检查 alias 不检查 column name | ✅ |
| 10.4.8 | IROptimizer | 投影裁剪不检查子查询自身的 GROUP BY/HAVING/ORDER BY 需要的列 | ✅ |
| 10.4.9 | IROptimizer | `collectColumns` 不处理 IRIsNull/IRBetween/IRInList/IRCast/IRSubquery | ✅ |
| 10.4.10 | HandLexer | 不支持科学计数法/十六进制/`$`标识符 | ❌ 新特性 |
| 10.4.11 | DataType | DecimalType 允许 precision < scale；IntType 允许负数 bits | ✅ |
| 10.4.12 | USqlCompiler | `cacheSize()` 非线程安全读取 | ✅ |
| 10.4.13 | USqlCompiler | `compileFromAst` 和 `compileFromIR` 逻辑重复 | ❌ 需重构 |
| 10.4.14 | GenerateOptions | QuoteStyle 从未被任何 Backend 使用 | ✅ 文档标记 |
| 10.4.15 | PolyfillEngine | 3 个 polyfill 是空操作 | ✅ 文档标记为 intentional no-op |
| 10.4.16 | Dialect | `caseSensitive` 误导性且从未使用 | ✅ @Deprecated |
| 10.4.17 | SchemaProvider | `getTable(schema, name)` 忽略 schema 参数 | ✅ 文档标记 |
| 10.4.18 | SemanticAnalyzer | `SELECT *` 不解析列（StarItem 直接透传） | ❌ 需 schema |
| 10.4.19 | SemanticAnalyzer | `extractSubqueryColumns` 忽略 StarItem 投影 | ❌ 需 schema |
| 10.4.20 | SemanticAnalyzer | 歧义列引用静默选择第一个匹配 | ✅ 已在 10.2.6 修复 |
| 10.4.21 | TypeInferrer | DECIMAL 算术始终返回 `DecimalType(20,4)` | ✅ |
| 10.4.22 | TypeInferrer | FLOAT + INT 提升为 DOUBLE（应为 FLOAT） | ✅ |
| 10.4.23 | TypeInferrer | `inferExpressionType` 对 FunctionCall 返回 NullType | ✅ |
| 10.4.24 | CapabilityChecker | 多个 Capability 未注册 severity，默认 WARNING | ✅ |
| 10.4.25 | ClickHouse | chColumnDef 忽略所有约束（NOT NULL/PRIMARY KEY） | ✅ |
| 10.4.26 | Oracle | SELECT alias 省略 AS 关键字 | ✅ |
| 10.4.27 | Oracle | DROP TABLE IF EXISTS 吞掉所有错误 | ✅ |
| 10.4.28 | MySQL/PG | escapeString 替换顺序可能导致双重转义 | ✅ |

---

## Phase 9 - 后续方向

| # | 任务 | 说明 |
|---|------|------|
| 9.1 | DuckDB 支持 | ✅ Dialect + PgBackend + 检测已完成 |
| 9.2 | ClickHouse 支持 | ✅ ClickHouseBackend + MergeTree + 零失败回归 |
| 9.3 | DB2 支持 | IBM 商业库，使用面窄 |
| 9.4 | MySQL CHANGE COLUMN | ✅ 已正确使用 `MODIFY COLUMN` + `RENAME COLUMN` |
| 9.5 | OceanBase 支持 | ✅ Dialect + MySqlBackend + 检测 + docker-compose |
| 9.6 | 深度性能 Profiling | ✅ Parse 瓶颈 50-93%，吞吐 15000/s |

### 9.1 DuckDB — PG 兼容嵌入式分析库

- Dialect + DuckDbBackend (extends PgBackend)
- 覆写 CREATE TABLE（无 GENERATED AS IDENTITY）、存储过程跳过
- AUTO_INCREMENT 需显式 sequence（已知限制）

### 9.2 ClickHouse — 列存分析库

- Dialect + ClickHouseBackend (extends MySqlBackend)
- CREATE TABLE 生成 MergeTree 引擎 + ORDER BY
- 类型映射: Int32/64, Float32/64, String, UInt8, Date, DateTime, UUID
- 回归 0 失败

### 9.5 OceanBase — 蚂蚁分布式数据库

- Dialect + 复用 MySqlBackend（MySQL 完全兼容）
- Docker: oceanbase/oceanbase-ce
- 回归 179/180（仅存储过程清理残留）

### 9.6 性能 Profiling 结论

- Parse: 50-93% 时间（ANTLR 瓶颈）
- Semantic Analysis: 5-15%
- Optimizer: 3-10%
- Code Generate: 5-15%
- 吞吐: 15000 compiles/sec

---

## Git 历史

```
3390b73 refactor: structure window frame clause — WindowFrame replaces raw String
46bd570 perf: share single USqlCompiler instance across all DataSources
53ee6ba refactor: extract KEEP polyfill to AbstractDialectBackend base class
2d0c432 docs: update README — SQL Server dialect, window functions, frame clause
85f3050 feat: add window frame clause support (ROWS/RANGE BETWEEN)
847f764 feat: add SQL Server dialect support
f41adf3 Phase 4: MERGE INTO / UPSERT support across all 4 databases
b10c005 Phase 4: Window functions + CTE support
...
89d649d Add execution plan with progress tracking (Phase 1-4)
662d317 (tag: v1.0.0) Initial commit
```