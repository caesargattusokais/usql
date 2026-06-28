# USQL 执行计划

**创建日期**: 2026-06-28  
**当前版本**: v1.0.0 (3 commits)

---

## 进度总览

```
Phase 1 — MVP               [████████░░] 80%
Phase 2 — 扩展覆盖           [████░░░░░░] 40%
Phase 3 — 交付形态           [░░░░░░░░░░]  0%
Phase 4 — 高级特性           [░░░░░░░░░░]  0%
```

---

## Phase 1 — MVP（核心链路）

- [x] **1.1** antlr4 语法文件 — `USql.g4` 569 行
- [x] **1.2** Lexer + Parser → AST — `AstBuilder` 完整 visitor
- [x] **1.3** 语义分析 + 类型推导 — `SemanticAnalyzer` 作用域/符号/类型
- [x] **1.4** MySQL Backend — 反引号 / TINYINT(1) / LIMIT 原生
- [x] **1.5** PostgreSQL Backend — 双引号 / BOOLEAN / FULL JOIN / JSONB
- [x] **1.6** Oracle Backend — ROWNUM 包裹 / VARCHAR2 / NUMBER(1) / FROM DUAL
- [x] **1.7** 达梦 DM Backend — LIMIT 原生 / BIT / IDENTITY
- [x] **1.8** 类型映射表 — 4 库枢纽映射 `TypeCatalog`
- [x] **1.9** 能力检查 + Polyfill — `CapabilityChecker` + `PolyfillEngine`
- [ ] **1.10** 函数目录补全到 30+ 函数（当前只有 5 个）
- [ ] **1.11** H2 双执行语义验证跑通
- [ ] **1.12** 文本输入 `compile(String)` 直接走通（当前需手动调 `compileFromAst`）

---

## Phase 2 — 扩展覆盖

- [x] **2.1** Oracle + 达梦 Backend
- [ ] **2.2** 函数目录扩展到 100+
- [ ] **2.3** DDL 全链路测试（MySQL/PG/Oracle/达梦建表语法生成验证）
- [ ] **2.4** DDL 跨库适配：AUTO_INCREMENT → SEQUENCE + TRIGGER (Oracle/PG)
- [ ] **2.5** DDL 跨库适配：ENUM → CREATE TYPE (PG) / VARCHAR+CHECK (Oracle/达梦)
- [ ] **2.6** Docker 多库 CI 验证（MySQL 8.0 / PG 16 / Oracle 23c / 达梦）
- [ ] **2.7** 完整 polyfill 引擎（FULL OUTER JOIN、PARTIAL INDEX、RECURSIVE CTE 等）

---

## Phase 3 — 交付形态

- [ ] **3.1** usql-jdbc — `java.sql.Driver` 实现，改连接串即用
- [ ] **3.2** usql-jdbc — `USqlDataSource` 包装类
- [ ] **3.3** usql-cli — `translate` 单条翻译命令
- [ ] **3.4** usql-cli — `migrate` 批量迁移命令
- [ ] **3.5** usql-cli — `verify` 验证命令
- [ ] **3.6** usql-proxy — MySQL Wire Protocol 握手实现
- [ ] **3.7** usql-proxy — COM_QUERY 拦截 + 翻译 + 转发

---

## Phase 4 — 高级特性

- [ ] **4.1** 窗口函数 IR + Backend generation (ROW_NUMBER / RANK / LAG / LEAD)
- [ ] **4.2** CTE + 递归 CTE
- [ ] **4.3** MERGE INTO / UPSERT 语义
- [ ] **4.4** 子查询优化（去关联化等）
- [ ] **4.5** 验证数据自动生成增强
- [ ] **4.6** 存储过程 IR（最小支持）

---

## 当前优先执行（P0）

| # | 任务 | 状态 |
|---|------|------|
| 1.10 | 函数目录补全到 30+ 函数 | [ ] |
| 1.11 | H2 双执行语义验证跑通 | [ ] |
| 1.12 | `compile(String)` 文本入口打通 | [ ] |

---

## Git 历史

```
c14aa38 Fix JOIN alias resolution
7f3579c Phase 1-2 connected: antlr4 parser → text input pipeline working
662d317 (tag: v1.0.0) Initial commit
```
