# USQL 执行计划

创建日期: 2026-06-28
最后更新: 2026-07-10
当前版本: v1.0.0 (72 commits)

---

## 进度总览

- Phase 1 MVP: 100% ✅
- Phase 2 扩展: 100% ✅
- Phase 3 交付: 100% ✅
- Phase 4 高级: 50% (3/6)
- Phase 5 优化: 44% (4/9)

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
| 4.4 | 子查询优化 | TODO |
| 4.5 | 验证数据自动生成 | TODO |
| 4.6 | 存储过程 IR | TODO |

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
| 5.5 | IROptimizer 常量折叠 Level 1 实现 | TODO |
| 5.6 | PolyfillEngine 补全 IR rewrite 逻辑 | TODO |
| 5.7 | SemanticVerifier 集成到 CI/编译流程 | TODO |
| 5.8 | CapabilityChecker 补全 27 能力分级 | TODO |
| 5.9 | SemanticAnalyzer 职责拆分 | TODO |

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
| **总计** | | **396** |

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