# USQL — Universal SQL Compiler v3.0.0

写一次 SQL，在 MySQL、PostgreSQL、Oracle、达梦 DM、SQL Server、MariaDB、TiDB、SQLite、DuckDB、OceanBase 上正确执行。

---

## 目录

- [快速开始](#快速开始)
- [支持的语法](#支持的语法)
- [方言映射表](#方言映射表)
- [DDL 映射](#ddl-映射)
- [SQL 编写规则](#sql-编写规则)
- [Spring Boot 集成](#spring-boot-集成)
- [Java 项目集成](#java-项目集成)
- [命令行工具](#命令行工具)
- [代理模式](#代理模式)
- [构建与测试](#构建与测试)
- [项目结构](#项目结构)

---

## 快速开始

```xml
<dependency>
    <groupId>com.usql</groupId>
    <artifactId>usql-jdbc</artifactId>
    <version>2.0.0-SNAPSHOT</version>
</dependency>
```

```java
@Autowired JdbcTemplate jdbc;

jdbc.queryForList(
    "SELECT name, COUNT(*) AS cnt FROM users GROUP BY name LIMIT 10"
);
// 自动翻译为目标数据库方言执行
```

---

## 支持的语法

### DDL — 数据定义

#### CREATE TABLE

```sql
CREATE TABLE users (
    id INT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(200),
    salary DECIMAL(10,2) DEFAULT 0.00,
    active BOOLEAN DEFAULT TRUE,
    created_date DATE,
    updated DATETIME,
    bio TEXT,
    data JSON
)
```

| 特性 | 支持 |
|------|:--:|
| 列约束 | NOT NULL, NULL, PRIMARY KEY, UNIQUE, CHECK, DEFAULT, REFERENCES |
| 表约束 | PRIMARY KEY, UNIQUE, FOREIGN KEY, CHECK |
| 自增 | AUTO_INCREMENT / IDENTITY |
| 类型 | INT / BIGINT / SMALLINT / TINYINT / DECIMAL / FLOAT / DOUBLE / VARCHAR / CHAR / TEXT / BOOLEAN / DATE / DATETIME / TIMESTAMP / TIME / JSON / UUID / BINARY / VARBINARY / BLOB / CLOB / BIT / ENUM |
| 选项 | ENGINE, TABLESPACE, CHARACTER SET, COLLATE, COMMENT |
| IF NOT EXISTS | 全部方言支持，Oracle/DM 用 PL/SQL wrapper，SQL Server 用 OBJECT_ID guard |
| ENUM | MySQL 原生 ENUM，其他方言 VARCHAR + CHECK |

#### ALTER TABLE

```sql
-- 添加列
ALTER TABLE users ADD score DECIMAL(10,2) DEFAULT 0

-- 删除列
ALTER TABLE users DROP bio

-- 修改列类型
ALTER TABLE users ALTER score TYPE INT

-- 设置/删除默认值
ALTER TABLE users ALTER score SET DEFAULT 100
ALTER TABLE users ALTER score DROP DEFAULT

-- 重命名列
ALTER TABLE users RENAME COLUMN name TO full_name
```

#### DROP / TRUNCATE

```sql
DROP TABLE users
DROP TABLE IF EXISTS users
DROP TABLE users CASCADE
TRUNCATE TABLE users
```

#### 索引

```sql
CREATE INDEX idx_name ON users (name)
CREATE UNIQUE INDEX idx_email ON users (email)
CREATE INDEX IF NOT EXISTS idx_name ON users (name)
DROP INDEX idx_name ON users

-- 视图
CREATE VIEW active_users AS SELECT id, name FROM users WHERE active = TRUE

-- Schema / Database
CREATE SCHEMA my_app
DROP DATABASE my_db
```

---

### DML — 数据操作

```sql
-- INSERT 单行
INSERT INTO users (name, email) VALUES ('Alice', 'alice@test.com')

-- INSERT 多行
INSERT INTO users (name, email) VALUES ('Bob', 'b@t.com'), ('Carol', 'c@t.com')

-- INSERT ... SELECT
INSERT INTO users (name, email) SELECT name, email FROM temp_users

-- UPDATE
UPDATE users SET salary = 80000 WHERE name = 'Bob'
UPDATE users SET salary = salary * 1.1, active = TRUE WHERE dept_id = 1

-- DELETE
DELETE FROM users WHERE name = 'Bob'
```

#### MERGE / UPSERT

```sql
MERGE INTO users t USING new_users s ON t.id = s.id
WHEN MATCHED THEN UPDATE SET name = s.name, email = s.email
WHEN NOT MATCHED THEN INSERT (id, name, email) VALUES (s.id, s.name, s.email)
```

---

### DQL — 查询

#### 基本查询

```sql
SELECT name, age FROM users WHERE age > 18
SELECT DISTINCT dept_id FROM users
SELECT name FROM users WHERE name LIKE 'A%'
SELECT name FROM users WHERE salary BETWEEN 50000 AND 80000
SELECT name FROM users WHERE dept_id IN (1, 2, 3)
SELECT name FROM users WHERE email IS NULL
SELECT name FROM users WHERE email IS NOT NULL
```

#### 分页

```sql
-- 统一用 LIMIT / OFFSET — 全部数据库
SELECT name FROM users ORDER BY name LIMIT 10
SELECT name FROM users ORDER BY name LIMIT 10 OFFSET 20
```

#### 聚合 + GROUP BY

```sql
SELECT dept_id, COUNT(*) AS cnt, AVG(salary) AS avg_sal
FROM employees GROUP BY dept_id HAVING COUNT(*) > 5
```

#### ORDER BY

```sql
SELECT name, salary FROM employees ORDER BY salary DESC
SELECT name, salary FROM employees ORDER BY dept_id ASC, salary DESC
```

#### JOIN

```sql
-- INNER JOIN（裸 JOIN）
SELECT d.name, e.salary FROM departments d JOIN employees e ON d.id = e.dept_id

-- LEFT / RIGHT / FULL JOIN
SELECT d.name, COUNT(e.id) FROM departments d
LEFT JOIN employees e ON d.id = e.dept_id GROUP BY d.name
```

#### 子查询

```sql
SELECT name FROM employees
WHERE dept_id IN (SELECT id FROM departments WHERE name = 'Engineering')

SELECT name FROM employees
WHERE EXISTS (SELECT 1 FROM departments d WHERE d.id = employees.dept_id)
```

#### 集合操作

```sql
SELECT name FROM employees_a UNION SELECT name FROM employees_b
SELECT name FROM employees_a UNION ALL SELECT name FROM employees_b
SELECT name FROM employees_a INTERSECT SELECT name FROM employees_b
SELECT name FROM employees_a EXCEPT SELECT name FROM employees_b
```

#### CTE（公共表表达式）

```sql
-- 非递归
WITH high_salary AS (SELECT name, salary FROM employees WHERE salary > 70000)
SELECT name FROM high_salary

-- 递归
WITH RECURSIVE nums AS (
  SELECT 1 AS n
  UNION ALL
  SELECT n + 1 FROM nums WHERE n < 10
) SELECT n FROM nums
```

#### 窗口函数

```sql
-- 排名
SELECT ROW_NUMBER() OVER (ORDER BY salary DESC) AS rn FROM employees
SELECT RANK() OVER (PARTITION BY dept_id ORDER BY salary DESC) FROM employees
SELECT DENSE_RANK() OVER (PARTITION BY dept_id ORDER BY salary DESC) FROM employees

-- 偏移
SELECT LAG(salary) OVER (ORDER BY hire_date) FROM employees
SELECT LEAD(salary) OVER (ORDER BY hire_date) FROM employees

-- 首尾值
SELECT FIRST_VALUE(salary) OVER (PARTITION BY dept_id ORDER BY hire_date) FROM employees
SELECT LAST_VALUE(salary) OVER (PARTITION BY dept_id ORDER BY hire_date
  ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) FROM employees

-- 分桶 + 分布
SELECT NTILE(4) OVER (ORDER BY salary) AS quartile FROM employees
SELECT PERCENT_RANK() OVER (ORDER BY salary) FROM employees
SELECT CUME_DIST() OVER (ORDER BY salary) FROM employees
```

#### 窗口帧（Frame Clause）

```sql
ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING
RANGE BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
```

#### KEEP 聚合扩展

```sql
-- Oracle 原生，其他方言 DENSE_RANK polyfill
SELECT dept_id,
  MAX(salary) KEEP (DENSE_RANK LAST ORDER BY hire_date) AS last_salary
FROM employees GROUP BY dept_id

SELECT MIN(salary) KEEP (DENSE_RANK FIRST ORDER BY hire_date) FROM employees
```

#### ROLLUP / CUBE

```sql
SELECT dept, city, SUM(sales) FROM t GROUP BY ROLLUP(dept, city)
SELECT dept, city, SUM(sales) FROM t GROUP BY CUBE(dept, city)
```

> MySQL CUBE 不支持；MySQL ROLLUP 使用 `GROUP BY ... WITH ROLLUP` 语法。

#### 表达式 + 函数

```sql
-- 无 FROM 查询
SELECT 1 + 1 AS two
SELECT LENGTH('hello'), UPPER('world'), ROUND(3.14159, 2)

-- CASE
SELECT CASE WHEN salary > 70000 THEN 'High'
            WHEN salary > 50000 THEN 'Medium' ELSE 'Low' END FROM employees

-- CAST
SELECT CAST(salary AS VARCHAR(10)) FROM employees

-- COALESCE / NULLIF / NVL / GREATEST / LEAST
SELECT COALESCE(email, 'N/A') FROM users
SELECT NVL(phone, 'no-phone') FROM users
```

---

### 存储过程

```sql
-- 创建
CREATE PROCEDURE hello() AS 'BEGIN SELECT 1; END;'
CREATE PROCEDURE greet(IN p_name VARCHAR(100)) AS 'BEGIN SELECT CONCAT(''Hello '', p_name); END;'
CREATE OR REPLACE PROCEDURE reload() AS 'BEGIN DELETE FROM cache; INSERT INTO cache SELECT * FROM src; END;'

-- 创建函数
CREATE FUNCTION add_one(x INT) RETURNS INT AS 'BEGIN RETURN x + 1; END;'

-- 调用
CALL hello();
CALL greet('World');

-- 参数模式: IN (默认), OUT, INOUT
CREATE PROCEDURE transfer(IN from_id INT, OUT status VARCHAR(20)) AS '...'
```

> Body 为方言原生 SQL，不经过 USQL 翻译。MySQL 需要 `()` 括起空参数；PG 使用 `$$...$$ LANGUAGE plpgsql`；SQL Server 使用 `@param` 语法。

---

### 事务控制（TCL）

事务语句是标准 SQL，直接透传，不翻译。

```sql
BEGIN                    -- 开始事务（PG）
START TRANSACTION        -- 开始事务（MySQL 风格）
COMMIT                   -- 提交
ROLLBACK                 -- 回滚
ROLLBACK TO SAVEPOINT sp -- 回滚到保存点
SAVEPOINT sp             -- 创建保存点
RELEASE SAVEPOINT sp     -- 释放保存点
```

---

### LATERAL JOIN

```sql
SELECT * FROM users u, LATERAL GENERATE_SERIES(1, u.count) AS s
```

| 方言 | 语法 |
|------|------|
| MySQL/MariaDB/TiDB/OceanBase/PG/DM/SQLite/DuckDB | `LATERAL func(...)` |
| Oracle | `LATERAL TABLE(func(...))` |
| SQL Server | `CROSS APPLY func(...)` |

---

### CREATE VIEW / CREATE SCHEMA / DROP DATABASE

```sql
CREATE VIEW my_view AS SELECT name, val FROM t WHERE val > 10
CREATE SCHEMA my_schema
DROP DATABASE my_db
```

> Oracle/DM 没有独立 Schema 概念（Schema=User），CREATE SCHEMA 会自然报错；SQLite 无 DATABASE 概念。

---

## 编译器优化

| Level | 名称 | 示例 |
|-------|------|------|
| 1 | 常量折叠 | `1+2→3`, `TRUE AND FALSE→FALSE` |
| 2 | 表达式简化 + 子查询扁平 | `x*1→x`, `NOT NOT x→x` |
| 3 | 谓词下推 + 投影裁剪 | `WHERE s.age>18` 推入子查询，裁剪未用列 |

> Level 2/3 优化结果经真实数据库验证与未优化版本完全一致。

---

## 方言映射表

### 支持的数据库

| 方言 | 标识符 | 分页 | 布尔 | 自增 | 字符串拼接 |
|------|--------|------|------|------|-----------|
| MySQL | `` `name` `` | `LIMIT/OFFSET` | TINYINT(1) | AUTO_INCREMENT | CONCAT() |
| PostgreSQL | `"name"` | `LIMIT/OFFSET` | BOOLEAN | GENERATED AS IDENTITY | `\|\|` |
| Oracle | `"name"` | ROWNUM 包裹 | NUMBER(1) | GENERATED AS IDENTITY | `\|\|` |
| 达梦 DM | `"name"` | `LIMIT/OFFSET` | BIT | IDENTITY | `\|\|` |
| SQL Server | `[name]` | `OFFSET/FETCH` | BIT | IDENTITY(1,1) | `+` |
| SQLite | `"name"` | `LIMIT/OFFSET` | INTEGER | AUTOINCREMENT | `\|\|` |
| MariaDB | `` `name` `` | `LIMIT/OFFSET` | TINYINT(1) | AUTO_INCREMENT | CONCAT() |
| TiDB | `` `name` `` | `LIMIT/OFFSET` | TINYINT(1) | AUTO_INCREMENT | CONCAT() |
| DuckDB | `"name"` | `LIMIT/OFFSET` | BOOLEAN | DEFAULT | `\|\|` |
| OceanBase | `` `name` `` | `LIMIT/OFFSET` | TINYINT(1) | AUTO_INCREMENT | CONCAT() |

### 语法差异速查

| 特性 | U-SQL | MySQL | PG | Oracle | DM | SQL Server | MariaDB | TiDB | SQLite | DuckDB | OceanBase | ClickHouse |
|------|-------|-------|----|--------|-----|-----------|---------|------|--------|-------|----------|-----------|
| 分页 | `LIMIT/OFFSET` | 原生 | 原生 | ROWNUM | 原生 | OFFSET/FETCH | 原生 | 原生 | 原生 | 原生 | 原生 | 原生 |
| 布尔 | `TRUE`/`FALSE` | TINYINT(1) | 原生 | NUMBER(1) | BIT | BIT | TINYINT(1) | TINYINT(1) | INTEGER | 原生 | TINYINT(1) | UInt8 |
| 自增 | `AUTO_INCREMENT` | 原生 | IDENTITY | IDENTITY | IDENTITY | IDENTITY | 原生 | 原生 | AUTOINCREMENT | DEFAULT | 原生 | — |
| 无FROM | `SELECT expr` | 原生 | 原生 | FROM DUAL | FROM DUAL | 原生 | 原生 | 原生 | 原生 | 原生 | 原生 | 原生 |
| 空值 | `NVL(a,b)` | IFNULL | COALESCE | 原生 | 原生 | ISNULL | IFNULL | IFNULL | IFNULL | COALESCE | IFNULL | IFNULL |
| 字符串长度 | `LENGTH(s)` | 原生 | 原生 | 原生 | 原生 | LEN | 原生 | 原生 | 原生 | 原生 | 原生 | 原生 |
| 子串 | `SUBSTR(s,pos,len)` | 原生 | 原生 | 原生 | 原生 | SUBSTRING | 原生 | 原生 | 原生 | 原生 | 原生 | 原生 |
| 字符串拼接 | `CONCAT(a,b)` | 原生 | 原生 | 原生 | 原生 | polyfill | 原生 | 原生 | \|\| | \|\| | 原生 | 原生 |
| EXCEPT | `EXCEPT` | 原生 | 原生 | MINUS | 原生 | 原生 | 原生 | 原生 | 原生 | 原生 | 原生 | 原生 |
| FULL JOIN | `FULL JOIN` | polyfill | 原生 | 原生 | 原生 | 原生 | polyfill | — | — | 原生 | polyfill | — |
| CREATE IF NOT EXISTS | `IF NOT EXISTS` | 原生 | 原生 | PL/SQL | PL/SQL | OBJECT_ID | 原生 | 原生 | 原生 | 原生 | 原生 | 原生 |
| DROP IF EXISTS | `IF EXISTS` | 原生 | 原生 | PL/SQL | PL/SQL | 原生 | 原生 | 原生 | 原生 | 原生 | 原生 | 原生 |
| ROLLUP | `ROLLUP(a,b)` | WITH ROLLUP | 原生 | 原生 | 原生 | 原生 | 原生 | — | — | 原生 | 原生 | — |
| CUBE | `CUBE(a,b)` | — | 原生 | 原生 | 原生 | 原生 | — | — | — | 原生 | — | — |
| 递归CTE | `WITH RECURSIVE` | 原生 | 原生 | WITH | WITH | WITH | 原生 | 原生 | 原生 | 原生 | 原生 | 原生 |

### 函数目录

110+ 函数支持跨方言映射。详见 `usql-core/src/main/resources/functions.yaml`。

---

## DDL 映射

### 自增主键

```sql
-- U-SQL: id INT PRIMARY KEY AUTO_INCREMENT
-- MySQL:     id INT PRIMARY KEY AUTO_INCREMENT
-- PG:        id INT PRIMARY KEY GENERATED ALWAYS AS IDENTITY
-- Oracle:    id NUMBER(10) GENERATED BY DEFAULT ON NULL AS IDENTITY PRIMARY KEY
-- DM:        id INT PRIMARY KEY IDENTITY
-- SQL Server: id INT IDENTITY(1,1) PRIMARY KEY
```

### 布尔类型

```sql
-- U-SQL: active BOOLEAN DEFAULT TRUE
-- MySQL:  active TINYINT(1) DEFAULT 1
-- PG:     active BOOLEAN DEFAULT TRUE
-- Oracle: active NUMBER(1) DEFAULT 1
-- DM:     active BIT DEFAULT 1
-- SQL Server: active BIT DEFAULT 1
```

### 枚举类型

```sql
-- U-SQL: status ENUM('active','inactive','pending')
-- MySQL:  status ENUM('active','inactive','pending')
-- 其他:   status VARCHAR(255) CHECK (status IN ('active','inactive','pending'))
```

---

## SQL 编写规则

1. **分页统一用 `LIMIT n OFFSET m`**
2. **布尔值用 `TRUE` / `FALSE`**
3. **自增列用 `AUTO_INCREMENT`**
4. **字符串拼接用 `CONCAT(a,b)`**
5. **不写 `FROM DUAL`**，编译器自动补
6. **标识符大小写不敏感**
7. **KEEP 聚合**：Oracle 原生，其他方言 DENSE_RANK polyfill
8. **窗口函数**：标准 SQL 语法，支持 ROWS/RANGE 帧
9. **FULL OUTER JOIN**：MySQL 自动 polyfill 为 LEFT JOIN UNION RIGHT JOIN
10. **递归 CTE**：MySQL/PG 用 `WITH RECURSIVE`，Oracle/DM/SQL Server 用 `WITH`

---

## Spring Boot 集成

### 架构

```
application.yml (不变)
       │
       ▼
Spring Boot 创建 HikariCP/Druid DataSource
       │
       ▼
BeanPostProcessor 用 USqlDataSource 包装
       │
       ▼
JdbcTemplate / MyBatis / JPA 透明使用
       │
       ▼
USqlStatement → compiler.compile(sql, dialect) → 翻译 → 执行
```

### 配置

```java
@Configuration
public class UsqlConfig {
    @Bean
    static BeanPostProcessor usqlWrapper(Environment env) {
        return new BeanPostProcessor() {
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof DataSource ds && !(bean instanceof USqlDataSource)) {
                    String url = env.getProperty("spring.datasource.url");
                    return new USqlDataSource(ds, USqlDriver.detectDialect(url));
                }
                return bean;
            }
        };
    }
}
```

```yaml
# application.yml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/mydb
    username: user
    password: pass
```

| 连接池 | 支持 |
|--------|:--:|
| HikariCP | ✅ |
| Druid | ✅ |
| Tomcat CP | ✅ |
| DBCP2 | ✅ |

---

## Java 项目集成

```java
// JDBC Driver 模式
Connection conn = DriverManager.getConnection(
    "jdbc:usql:oracle://localhost:1521/orclpdb1", "user", "pass");

// 代码内嵌编译
USqlCompiler compiler = USqlCompiler.builder().build();
CompilationResult r = compiler.compile(
    "SELECT name FROM users WHERE active = TRUE LIMIT 10",
    Dialect.ORACLE
);
System.out.println(r.getSql());

// 手动创建 DataSource（包装已有连接池）
DataSource usqlDs = new USqlDataSource(hikariDS,
    USqlDriver.detectDialect("jdbc:mysql://localhost:3306/mydb"));

// 显式指定方言
DataSource usqlDs = new USqlDataSource(pooled, Dialect.ORACLE);
```

### 方言检测

```
jdbc:mysql://...       → MYSQL
jdbc:postgresql://...  → POSTGRESQL
jdbc:oracle:thin:@...  → ORACLE
jdbc:dm://...          → DM
jdbc:sqlserver://...   → SQLSERVER
jdbc:usql:oracle://... → ORACLE (强制)
```

---

## 命令行工具

```bash
# 单条翻译
java -jar usql-cli.jar translate --sql "SELECT ... LIMIT 10" --to oracle

# 批量迁移
java -jar usql-cli.jar migrate --to postgresql --input ./sql/ --output ./pg-sql/

# 管道到真实数据库
java -jar usql-cli.jar translate --sql "SELECT ..." --to mysql | mysql -u user -p db
```

---

## 代理模式

```bash
java -jar usql-proxy.jar --mode text --port 3312 \
  --dialect oracle --backend jdbc:oracle:thin:@localhost:1521/orclpdb1

echo "SELECT ... LIMIT 10" | nc localhost 3312
```

---

## 构建与测试

```bash
mvn compile
mvn test-compile

# 无数据库单元测试（覆盖率 ~40%）
mvn exec:java -pl usql-core -Dexec.mainClass=com.usql.FunctionCatalogTest -Dexec.classpathScope=test

# 全量回归测试（需要 Docker 五库在线）
mvn exec:java -pl usql-core -Dexec.mainClass=com.usql.RegressionTest -Dexec.classpathScope=test
```

## 测试覆盖

| 套件 | 内容 | 测试数 |
|------|------|--------|
| RegressionTest | DDL/DML/Query/MERGE/CTE/窗口/KEEP/ENUM/存储过程 × 8 方言 | 267 |
| FunctionCatalogTest | YAML 加载 + 110+ 函数查找 | 20 |
| TypeInferrerTest | 类型推导 | 21 |
| IROptimizerTest | 常量折叠 + 表达式简化 | 31 |
| CapabilityCheckerTest | 27 能力 polyfill 判定 | 45 |
| DialectTest | 8 方言能力集 | 40 |
| BackendTest | Backend SQL 生成 | 112 |
| IROptimizerTest | 常量折叠 + 表达式简化 + 谓词下推 | 34 |
| CompilationResultTest | 错误/警告报告 | 29 |
| PolyfillEngineTest | FULL JOIN polyfill | 15 |
| StoredProcedureTest | 存储过程编译 | 53 |
| SemanticAnalyzerTest | 语义分析 | 20 |
| BenchmarkTest | 编译性能基准（11 查询 × 5 方言） | — |
| **总计** | | **800+** |

全部在 MySQL 8.0 / PostgreSQL 16 / Oracle 19c / 达梦 DM8 / SQL Server 2022 / MariaDB 11 / TiDB 8.1 / SQLite 3 Docker 容器上验证通过。

---

## 项目结构

```
usql/
├── usql-core/      核心编译器（Grammar、AST、IR、语义分析、Backend、优化器）
├── usql-jdbc/      JDBC 驱动 + DataSource 包装
├── usql-cli/       命令行工具 (translate/migrate/verify)
├── usql-proxy/     数据库代理 (文本模式 + MySQL Wire Protocol)
├── docker/         Docker Compose (MySQL/PG/Oracle/DM/SQL Server)
├── demo/           Spring Boot 集成示例
└── docs/           执行计划
```

## 版本

当前版本: **v3.0.0**
