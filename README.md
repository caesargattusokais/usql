# USQL — Universal SQL Compiler

写一次 SQL，在 MySQL、PostgreSQL、Oracle、达梦 DM 上正确执行。

---

## 目录

- [快速开始](#快速开始)
- [Spring Boot 集成](#spring-boot-集成)
- [Java 项目集成](#java-项目集成)
- [命令行工具](#命令行工具)
- [代理模式](#代理模式)
- [U-SQL 语法](#u-sql-语法)
- [方言映射表](#方言映射表)
- [DDL 映射](#ddl-映射)
- [SQL 编写规则](#sql-编写规则)
- [项目结构与构建](#项目结构与构建)

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.usql</groupId>
    <artifactId>usql-jdbc</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

usql-jdbc 已包含 MySQL/PG/Oracle/达梦 四库 JDBC 驱动。

### 2. 写配置（Spring Boot）

`application.yml` 不变，新增一个配置类：

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

### 3. 写代码

```java
@Autowired JdbcTemplate jdbc;

// 正常写 SQL，编译器自动翻译为目标数据库方言
jdbc.queryForList(
    "SELECT name, COUNT(*) AS cnt FROM users GROUP BY name LIMIT 10"
);
```

**完成。** 连接池 (HikariCP/Druid/Tomcat/DBCP2) 正常工作，SQL 透明编译。

---

## Spring Boot 集成

### 架构

```
application.yml (不变)
       │
       ▼
Spring Boot 创建 HikariCP/Druid DataSource (连接池)
       │
       ▼
BeanPostProcessor 用 USqlDataSource 包装
       │
       ▼
应用调用 JdbcTemplate / MyBatis / JPA
       │
       ▼
USqlConnection.createStatement() → USqlStatement
       │
       ▼
executeQuery(sql) → compiler.compile(sql, dialect) → 翻译后 SQL
       │
       ▼
池中的真实 Connection 执行翻译后 SQL → 返回结果
```

### 连接池兼容性

| 连接池 | 支持 | 验证 |
|--------|------|------|
| HikariCP | ✅ | Spring Boot 默认，实测通过 |
| Druid | ✅ | 实测通过 (druid-spring-boot-3-starter) |
| Tomcat CP | ✅ | 实测通过 (tomcat-jdbc) |
| DBCP2 | ✅ | 同架构， `DataSource` 包装即可 |

所有连接池统一通过 `USqlDataSource` 包装池化 DataSource，连接池在包装层下方正常工作。

#### HikariCP（Spring Boot 默认，无需额外配置）

```yaml
# application.yml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/mydb
    username: user
    password: pass
```

#### Druid

```xml
<!-- pom.xml -->
<dependency>
    <groupId>com.alibaba</groupId>
    <artifactId>druid-spring-boot-3-starter</artifactId>
    <version>1.2.23</version>
</dependency>
```

```yaml
# application.yml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/mydb
    username: user
    password: pass
    driver-class-name: com.mysql.cj.jdbc.Driver
    type: com.alibaba.druid.pool.DruidDataSource
    druid:
      initial-size: 2
      max-active: 5
      min-idle: 2
      filters: stat,wall    # SQL 监控 + 防火墙
```

```java
// UsqlConfig.java — 同上，BeanPostProcessor 自动包装
```

#### Tomcat CP

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
    <exclusions>
        <exclusion><groupId>com.zaxxer</groupId><artifactId>HikariCP</artifactId></exclusion>
    </exclusions>
</dependency>
<dependency>
    <groupId>org.apache.tomcat</groupId>
    <artifactId>tomcat-jdbc</artifactId>
    <version>10.1.18</version>
</dependency>
```

```yaml
# application.yml — 同上，无需改
```

### 手动创建 DataSource

```java
// 方式一：从 URL 自动创建（无连接池）
DataSource ds = USqlDataSource.create(
    "jdbc:mysql://localhost:3306/mydb", "user", "pass");

// 方式二：包装已有的池化 DataSource
HikariDataSource pooled = new HikariDataSource();
pooled.setJdbcUrl("jdbc:mysql://localhost:3306/mydb");
pooled.setUsername("user");
pooled.setPassword("pass");
DataSource usqlDs = new USqlDataSource(pooled,
    USqlDriver.detectDialect("jdbc:mysql://localhost:3306/mydb"));

// 方式三：显式指定方言
DataSource usqlDs = new USqlDataSource(pooled, Dialect.ORACLE);
// → 应用写 U-SQL，自动翻译成 Oracle SQL，在 MySQL 上执行
```

### 方言检测规则

```
jdbc:mysql://...      → MYSQL
jdbc:postgresql://... → POSTGRESQL
jdbc:oracle:thin:@... → ORACLE
jdbc:dm://...         → DM
jdbc:usql:oracle://...→ ORACLE  (显式指定，优先)
```

---

## Java 项目集成

### JDBC Driver 模式

```java
// 连接串加 usql: 前缀
Connection conn = DriverManager.getConnection(
    "jdbc:usql:oracle://localhost:1521/orclpdb1", "user", "pass");
Statement stmt = conn.createStatement();
ResultSet rs = stmt.executeQuery("SELECT ... LIMIT 10");
```

### 代码内嵌编译

```java
USqlCompiler compiler = USqlCompiler.builder().build();
CompilationResult r = compiler.compile(
    "SELECT name FROM users WHERE active = TRUE LIMIT 10",
    Dialect.ORACLE
);
System.out.println(r.getSql());
// → SELECT * FROM (SELECT "name" FROM "users" WHERE "active" = 1)
//   WHERE ROWNUM <= 10
```

---

## 命令行工具

```bash
# 编译
cd usql && mvn package -pl usql-cli -am -DskipTests

# 单条翻译
java -jar usql-cli/target/usql-cli-1.0.0-SNAPSHOT.jar translate \
  --sql "SELECT name, COUNT(*) AS cnt FROM users GROUP BY name LIMIT 10" \
  --to oracle

# 批量迁移
java -jar usql-cli/target/usql-cli-1.0.0-SNAPSHOT.jar migrate \
  --to postgresql --input ./sql/ --output ./pg-sql/

# 列出支持的方言
java -jar usql-cli/target/usql-cli-1.0.0-SNAPSHOT.jar dialects

# 管道到真实数据库
java -jar usql-cli.jar translate --sql "SELECT ..." --to mysql | mysql -u user -p db
```

---

## 代理模式

```bash
# 文本模式（telnet/nc 可连）
java -jar usql-proxy/target/usql-proxy-1.0.0-SNAPSHOT.jar \
  --mode text --port 3312 \
  --dialect oracle \
  --backend jdbc:oracle:thin:@localhost:1521/orclpdb1 \
  --user system --password oracle123

# 任何语言通过 TCP 发 U-SQL
echo "SELECT name, COUNT(*) AS cnt FROM users GROUP BY name LIMIT 10" | nc localhost 3312
```

---

## U-SQL 语法

### SELECT

```sql
-- 基本查询
SELECT name, age FROM users WHERE age > 18

-- 分页 — 统一用 LIMIT / OFFSET（所有数据库）
SELECT name FROM users ORDER BY name LIMIT 10 OFFSET 0

-- JOIN（裸 JOIN = INNER JOIN）
SELECT d.name, e.salary FROM departments d JOIN employees e ON d.id = e.dept_id

-- LEFT / RIGHT / FULL JOIN
SELECT d.name, COUNT(e.id) AS cnt
FROM departments d LEFT JOIN employees e ON d.id = e.dept_id
GROUP BY d.name

-- 聚合
SELECT dept_id, COUNT(*) AS cnt, AVG(salary) AS avg_sal
FROM employees GROUP BY dept_id HAVING COUNT(*) > 5

-- KEEP (DENSE_RANK FIRST|LAST) — Oracle 聚合扩展
-- 在多行中按排序取第一个/最后一个值进行聚合
SELECT dept_id,
  MAX(salary) KEEP (DENSE_RANK LAST ORDER BY hire_date) AS last_salary,
  MIN(salary) KEEP (DENSE_RANK FIRST ORDER BY hire_date) AS first_salary
FROM employees GROUP BY dept_id

-- 简单 KEEP（无 GROUP BY）
SELECT MAX(name) KEEP (DENSE_RANK LAST ORDER BY salary DESC) AS top_earner
FROM employees

-- 结合 DESC 排序
SELECT SUM(amount) KEEP (DENSE_RANK LAST ORDER BY trans_date DESC) AS recent_total
FROM transactions

-- 表达式（不需要 FROM）
SELECT LENGTH('hello'), UPPER('world'), 1 + 2 AS sum

-- 子查询
SELECT name FROM employees
WHERE dept_id IN (SELECT id FROM departments WHERE name = 'Engineering')

SELECT name FROM employees
WHERE EXISTS (SELECT 1 FROM departments d WHERE d.id = employees.dept_id)

-- UNION
SELECT name FROM employees WHERE dept_id = 1
UNION ALL
SELECT name FROM employees WHERE dept_id = 2

-- CASE
SELECT name,
  CASE WHEN salary > 70000 THEN 'High'
       WHEN salary > 50000 THEN 'Medium' ELSE 'Low' END AS level
FROM employees

-- DISTINCT / ORDER BY / BETWEEN / LIKE / IS NULL
SELECT DISTINCT dept_id FROM employees ORDER BY dept_id
SELECT name FROM employees WHERE salary BETWEEN 50000 AND 80000
SELECT name FROM employees WHERE name LIKE 'A%'
SELECT name FROM employees WHERE dept_id IS NULL
```

### INSERT / UPDATE / DELETE

```sql
INSERT INTO users (name, email) VALUES ('Alice', 'alice@test.com')
INSERT INTO users VALUES ('Bob', 'bob@test.com'), ('Carol', 'carol@test.com')
UPDATE users SET salary = 80000 WHERE name = 'Bob'
DELETE FROM users WHERE name = 'Bob'
```

### DDL

```sql
CREATE TABLE users (
    id INT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(200),
    salary DECIMAL(10,2) DEFAULT 0.00,
    active BOOLEAN DEFAULT TRUE,
    created_date DATE,
    bio TEXT
)

CREATE UNIQUE INDEX idx_email ON users (email)
```

---

## 方言映射表

### 基本语法差异

| U-SQL | MySQL | PostgreSQL | Oracle | 达梦 DM |
|-------|-------|-----------|--------|---------|
| `LIMIT n OFFSET m` | `LIMIT n OFFSET m` | `LIMIT n OFFSET m` | ROWNUM 包裹 | `LIMIT n OFFSET m` |
| `AUTO_INCREMENT` | `AUTO_INCREMENT` | `GENERATED AS IDENTITY` | `GENERATED BY DEFAULT AS IDENTITY` | `IDENTITY` |
| `BOOLEAN` | `TINYINT(1)` | `BOOLEAN` | `NUMBER(1)` | `BIT` |
| `AGG(x) KEEP (DENSE_RANK FIRST\|LAST ORDER BY y)` | ❌ 不支持（报错提示用窗口函数） | ❌ 不支持（报错提示用窗口函数） | 原生 KEEP | ❌ 不支持（报错提示用窗口函数） |
| `SELECT expr` (无 FROM) | `SELECT expr` | `SELECT expr` | `SELECT expr FROM DUAL` | `SELECT expr FROM DUAL` |
| 标识符引号 | `` `name` `` | `"name"` | `"name"` | `"name"` |

### 字符串函数

| U-SQL | MySQL | PostgreSQL | Oracle | 达梦 DM |
|-------|-------|-----------|--------|---------|
| `LENGTH(s)` | `LENGTH(s)` | `LENGTH(s)` | `LENGTH(s)` | `LENGTH(s)` |
| `UPPER(s)` / `LOWER(s)` | 同名 | 同名 | 同名 | 同名 |
| `TRIM(s)` / `LTRIM(s)` / `RTRIM(s)` | 同名 | 同名 | 同名 | 同名 |
| `SUBSTR(s,start,len)` | `SUBSTR` | `SUBSTR` | `SUBSTR` | `SUBSTR` |
| `REPLACE(s,old,new)` | 同名 | 同名 | 同名 | 同名 |
| `CONCAT(a,b)` | `CONCAT(a,b)` | `CONCAT(a,b)` | `CONCAT(a,b)` | `CONCAT(a,b)` |
| `LEFT(s,n)` | `LEFT` | `LEFT` | `SUBSTR(s,1,n)` | `LEFT` |
| `RIGHT(s,n)` | `RIGHT` | `RIGHT` | `SUBSTR(s,-n)` | `RIGHT` |
| `INSTR(s,sub)` | `INSTR` | `POSITION(sub IN s)` | `INSTR` | `INSTR` |
| `LPAD(s,n,pad)` / `RPAD` | 同名 | 同名 | 同名 | 同名 |
| `REPEAT(s,n)` | `REPEAT` | `REPEAT` | `RPAD(s,LENGTH(s)*n,s)` | `REPEAT` |
| `REVERSE(s)` | 同名 | 同名 | 同名 | 同名 |
| `CHAR_LENGTH(s)` | 同名 | 同名 | `LENGTH(s)` | 同名 |
| `SPACE(n)` | `SPACE(n)` | `REPEAT(' ',n)` | `RPAD(' ',n,' ')` | `SPACE(n)` |
| `INITCAP(s)` | `CONCAT(UPPER(LEFT(s,1)),LOWER(SUBSTR(s,2)))` | 同名 | 同名 | 同名 |

### 数值函数

| U-SQL | MySQL | PostgreSQL | Oracle | 达梦 DM |
|-------|-------|-----------|--------|---------|
| `ABS(n)` / `ROUND(n,d)` / `CEIL(n)` / `FLOOR(n)` | 同名 | 同名 | 同名 | 同名 |
| `MOD(a,b)` / `POWER(x,y)` / `SQRT(x)` | 同名 | 同名 | 同名 | 同名 |
| `EXP(x)` / `LN(x)` | 同名 | 同名 | 同名 | 同名 |
| `TRUNC(n,d)` | `TRUNCATE(n,d)` | `TRUNC(n,d)` | `TRUNC(n,d)` | `TRUNC(n,d)` |
| `PI()` | `PI()` | `PI()` | `ACOS(-1)` | `PI()` |
| `SIN` / `COS` / `TAN` | 同名 | 同名 | 同名 | 同名 |

### 日期函数

| U-SQL | MySQL | PostgreSQL | Oracle | 达梦 DM |
|-------|-------|-----------|--------|---------|
| `CURRENT_TIMESTAMP()` | `NOW()` | `NOW()` | `SYSDATE` | `SYSDATE` |
| `CURRENT_DATE` | `CURDATE` | `CURRENT_DATE` | `TRUNC(SYSDATE)` | `CURDATE` |
| `EXTRACT(YEAR FROM d)` | 同名 | 同名 | 同名 | 同名 |
| `DATE_ADD(d,INTERVAL n unit)` | 同名 | 同名 | 同名 | 同名 |
| `DATE_FORMAT(d,fmt)` | `DATE_FORMAT` | `TO_CHAR` | `TO_CHAR` | `TO_CHAR` |
| `DATE_DIFF(d1,d2)` | `TIMESTAMPDIFF` | `DATE_DIFF` | `MONTHS_BETWEEN` | `DATEDIFF` |

### 条件 / 空值

| U-SQL | MySQL | PostgreSQL | Oracle | 达梦 DM |
|-------|-------|-----------|--------|---------|
| `COALESCE(a,b,...)` | 同名 | 同名 | 同名 | 同名 |
| `NULLIF(a,b)` | 同名 | 同名 | 同名 | 同名 |
| `NVL(a,default)` | `IFNULL(a,default)` | `COALESCE(a,default)` | `NVL(a,default)` | `NVL(a,default)` |
| `GREATEST(a,b)` / `LEAST(a,b)` | 同名 | 同名 | 同名 | 同名 |

### 聚合函数

| U-SQL | MySQL | PostgreSQL | Oracle | 达梦 DM |
|-------|-------|-----------|--------|---------|
| `COUNT` / `SUM` / `AVG` / `MIN` / `MAX` | 同名 | 同名 | 同名 | 同名 |
| `STDDEV` / `VARIANCE` | 同名 | 同名 | 同名 | 同名 |
| `GROUP_CONCAT(x,sep)` | `GROUP_CONCAT` | `STRING_AGG` | `LISTAGG` | `LISTAGG` |

---

## DDL 映射

### 自增主键

```sql
-- U-SQL
id INT PRIMARY KEY AUTO_INCREMENT

-- MySQL:     id INT PRIMARY KEY AUTO_INCREMENT
-- PostgreSQL: id INTEGER PRIMARY KEY GENERATED ALWAYS AS IDENTITY
-- Oracle:     id NUMBER(10) GENERATED BY DEFAULT ON NULL AS IDENTITY PRIMARY KEY
-- DM:         id INT PRIMARY KEY IDENTITY
```

### 布尔类型

```sql
-- U-SQL
active BOOLEAN DEFAULT TRUE

-- MySQL:  active TINYINT(1) DEFAULT 1
-- PG:     active BOOLEAN DEFAULT TRUE
-- Oracle: active NUMBER(1) DEFAULT 1
-- DM:     active BIT DEFAULT 1
```

### 枚举类型

```sql
-- U-SQL
status ENUM('active','inactive','pending')

-- MySQL:  status ENUM('active','inactive','pending')              — 原生 ENUM
-- PG:     status VARCHAR(255) CHECK (status IN ('active',...))    — VARCHAR + CHECK
-- Oracle: status VARCHAR2(255) CHECK (status IN ('active',...))   — VARCHAR2 + CHECK
-- DM:     status VARCHAR(255) CHECK (status IN ('active',...))    — VARCHAR + CHECK
```

---

## SQL 编写规则

1. **分页统一用 `LIMIT n OFFSET m`**，不写 `TOP`、`ROWNUM`、`FETCH FIRST`
2. **布尔值用 `TRUE` / `FALSE`**，编译器自动映射
3. **自增列用 `AUTO_INCREMENT`**，编译器转换为各库 IDENTITY 语法
4. **字符串拼接用 `CONCAT(a,b)`**
5. **日期函数用标准名**：`CURRENT_DATE`、`CURRENT_TIMESTAMP()`、`DATE_ADD`、`DATE_DIFF`
6. **不写 `FROM DUAL`**，编译器自动补
7. **标识符大小写不敏感**，编译器使用正确的引号风格

---

## 项目结构

```
usql/
├── usql-core/      核心编译器（IR、语法、语义分析、Backend）
├── usql-jdbc/      JDBC 驱动 + DataSource 包装
├── usql-cli/       命令行工具
├── usql-proxy/     数据库代理（文本模式 + MySQL Wire Protocol）
├── docker/         Docker Compose（MySQL/PG/Oracle/DM 四库环境）
├── demo/           Spring Boot 集成示例
└── docs/           设计文档 + 执行计划
```

## 构建与测试

```bash
# 编译
mvn compile

# 安装到本地 Maven 仓库（供其他项目依赖）
mvn install -DskipTests

# 运行全部测试（需要 Docker 四库在线）
mvn exec:java -pl usql-core -Dexec.mainClass=com.usql.CiRunner

# 打包 CLI / Proxy
mvn package -pl usql-cli,usql-proxy -am -DskipTests
```

## 测试覆盖

| 测试套件 | 内容 | 数据库 |
|----------|------|--------|
| SemanticVerification | 25 种查询 × 4 库 | 100 |
| FunctionVerification | 50 个函数 × 4 库 | 200 |
| DdlVerification | DDL/DML 操作 × 4 库 | 20 |
| EnumTest | ENUM 约束验证 | 4 |
| CompilerE2E / TextInput | 编译器单元 | 32 |
| **总计** | | **356** |

全部在 MySQL 8.0 / PostgreSQL 16 / Oracle 19c / 达梦 DM8 Docker 容器上真实执行验证。
