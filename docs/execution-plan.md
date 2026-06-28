# USQL 执行计划

创建日期: 2026-06-28
当前版本: v1.0.0 (4 commits)

---

## 进度总览

- Phase 1 MVP: 100%
- Phase 2 扩展: 100%
- Phase 3 交付: 100%
- Phase 4 高级: 0%

---

## Phase 1 - MVP (核心链路)

| # | 任务 | 状态 |
|---|------|------|
| 1.1 | antlr4 语法文件 USql.g4 569行 | OK |
| 1.2 | Lexer + Parser 到 AST (AstBuilder) | OK |
| 1.3 | 语义分析 + 类型推导 (SemanticAnalyzer) | OK |
| 1.4 | MySQL Backend | OK |
| 1.5 | PostgreSQL Backend | OK |
| 1.6 | Oracle Backend (ROWNUM包裹) | OK |
| 1.7 | 达梦 DM Backend | OK |
| 1.8 | 类型映射表 TypeCatalog | OK |
| 1.9 | 能力检查 + Polyfill | OK |
| 1.10 | 函数目录补全 35 个函数 | OK |
| 1.11 | H2 双执行语义验证 (MySQL+PG 14/14) | OK |
| 1.12 | compile(String) 文本入口 | OK |

---

## Phase 2 - 扩展覆盖

| # | 任务 | 状态 |
|---|------|------|
| 2.1 | Oracle + 达梦 Backend | OK |
| 2.2 | 函数目录扩展到 103 个 | OK |
| 2.3 | DDL 全链路测试 (5 tests x 4 DBs = 20/20) | OK |
| 2.4 | AUTO_INCREMENT → IDENTITY (Oracle/PG/DM) | OK |
| 2.5 | ENUM 跨库适配 (MySQL原生/PG+Oracle+DM CHECK) | OK |
| 2.6 | Docker CI 验证 (7 suites x 4 DBs) | OK |
| 2.7 | 完整 polyfill (16 capabilities documented) | OK |

---

## Phase 3 - 交付形态

| # | 任务 | 状态 |
|---|------|------|
| 3.1 | usql-jdbc Driver 实现 | OK |
| 3.2 | usql-jdbc DataSource 包装 | OK |
| 3.3 | usql-cli translate 命令 | OK |
| 3.4 | usql-cli migrate 命令 | OK |
| 3.5 | usql-cli verify 命令 | OK |
| 3.6 | usql-proxy MySQL Wire Protocol | OK |
| 3.7 | usql-proxy COM_QUERY 翻译转发 | OK |

---

## Phase 4 - 高级特性

| # | 任务 | 状态 |
|---|------|------|
| 4.1 | 窗口函数 (ROW_NUMBER/RANK/LAG/LEAD) | TODO |
| 4.2 | CTE + 递归CTE | TODO |
| 4.3 | MERGE INTO / UPSERT | TODO |
| 4.4 | 子查询优化 | TODO |
| 4.5 | 验证数据自动生成 | TODO |
| 4.6 | 存储过程 IR | TODO |

---

## 当前 P0 优先

1. 1.10 函数目录补全到 30+
2. 1.11 H2 双执行语义验证跑通
3. 1.12 compile(String) 文本入口打通

---

## Git 历史

```
89d649d Add execution plan with progress tracking
c14aa38 Fix JOIN alias resolution
7f3579c Phase 1-2 connected: antlr4 parser to text input
662d317 (tag: v1.0.0) Initial commit
```
