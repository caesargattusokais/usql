# USQL 执行计划

创建日期: 2026-06-28
最后更新: 2026-07-11
当前版本: v3.0.0

---

## 进度总览

- Phase 1-6: 全部 100% ✅
- Phase 1-7: 全部 100% ✅
- Phase 8 待评估: 0% (0/6)
- Phase 9 后续方向: 0% (0/5)

---

## Phase 7 - 扩展方向

| # | 任务 | 状态 | 说明 |
|---|------|------|------|
| 7.1 | 单测覆盖率提升 | 40% | Backend 层真实库已全覆盖，纯单元空间有限 |
| 7.2 | IROptimizer Level 3 高级优化 | ✅ | 谓词下推 + 投影裁剪，L2/L3 结果一致性验证通过 |
| 7.3 | 更多数据库支持 | ✅ | SQLite + MariaDB + TiDB，8→8 数据库全部回归通过 |
| 7.4 | DDL 扩展（VIEW / SCHEMA / DROP DATABASE） | ✅ | CREATE VIEW + CREATE SCHEMA + DROP DATABASE |
| 7.5 | 语法增强（LATERAL / TCL 事务 / ARRAY / EXISTS） | ✅ | LATERAL 8 方言 + TCL 事务透传 |
| 7.6 | 性能测试 / 大查询编译基准 | ✅ | 11 种查询 × 5 方言，13-1186 μs/query |

### 7.2 IROptimizer Level 3 — 详情

- **谓词下推**: `SELECT * FROM (SELECT ... FROM t) s WHERE s.age > 18` → WHERE 推入子查询
- **投影裁剪**: 外查询只用到的列才保留，子查询去掉不需要的 SELECT 列
- **正确性验证**: L2(无优化) vs L3(有优化) 真实数据库结果比对，772 全通过

### 7.3 数据库扩展 — 详情

| 数据库 | Backend | 回归 |
|--------|---------|:--:|
| MySQL / PG / Oracle / DM / SQL Server | 各自独立 Backend | ✅ |
| MariaDB / TiDB | 复用 MySqlBackend | ✅ |
| SQLite | 独立 SqliteBackend (338行) | ✅ |

---

## Phase 8 - 待评估（低优先级）

| # | 任务 | 说明 |
|---|------|------|
| 8.1 | DB2 / ClickHouse / DuckDB 支持 | 需求不明确 |
| 8.2 | CREATE VIEW / CREATE SCHEMA | 语法 + IR + Backend |
| 8.3 | DROP DATABASE | 语法 + IR + Backend |
| 8.4 | ALTER TABLE RENAME / 完整 ALTER 语法 | SQL Server 已完成，其他方言部分支持 |
| 8.5 | LATERAL JOIN | 语法已部分支持，需补全 Backend |
| 8.6 | ARRAY 类型映射 | 只需 PG/SQL Server（其他方言无原生数组） |

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

| 能力 | MySQL | PG | Oracle | DM | SQL Server |
|------|-------|----|--------|-----|-----------|
| LIMIT/OFFSET | ✅ | ✅ | polyfill | polyfill | ✅ |
| WINDOW_FUNCTION | ✅ 8.0+ | ✅ | ✅ | ✅ | ✅ |
| RECURSIVE_CTE | ✅ 8.0+ | ✅ | ✅ | ✅ | ✅ |
| MERGE_INTO | polyfill | polyfill | ✅ | ✅ | ✅ |
| BOOLEAN_TYPE | — | ✅ | — | — | — |
| FULL_OUTER_JOIN | — | ✅ | ✅ | ✅ | ✅ |
| LATERAL_JOIN | — | ✅ | ✅ | — | ✅ |
| ARRAY_TYPE | — | ✅ | — | — | — |

---

## Phase 9 - 后续方向

| # | 任务 | 说明 |
|---|------|------|
| 9.1 | DuckDB 支持 | 嵌入式分析库，PG 兼容语法，加 Backend 即可 |
| 9.2 | ClickHouse 支持 | 列存分析库，语法差异大，需独立完整 Backend |
| 9.3 | DB2 支持 | IBM 商业库，使用面窄 |
| 9.4 | MySQL CHANGE COLUMN | ALTER COLUMN TYPE 用 `CHANGE old new TYPE` 语法 |
| 9.5 | 深度性能 Profiling | 定位慢路径（PG KEEP polyfill、INSERT 多行等），针对性优化 |

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