# USQL API 参考

版本: v4.0.0

## USqlCompiler

核心编译入口。线程安全，可全局单例使用。

### 创建

```java
// 默认配置
USqlCompiler compiler = USqlCompiler.builder().build();

// 完整配置
USqlCompiler compiler = USqlCompiler.builder()
    .withSchema(schemaProvider)        // 表结构（用于语义分析）
    .withVerify(true)                  // 生成 H2 参考 SQL
    .withOptimizeLevel(3)              // 0/1/2/3 优化级别
    .withCache(true)                   // 编译计划缓存（默认开启）
    .withCacheSize(512)                // 缓存大小（默认 256）
    .withDefaultDialect(Dialect.MYSQL) // 默认方言
    .build();
```

### 编译 SQL

```java
// 从 U-SQL 文本编译
CompilationResult r = compiler.compile(
    "SELECT name, COUNT(*) AS cnt FROM users GROUP BY name LIMIT 10",
    Dialect.ORACLE
);

if (r.isSuccess()) {
    String targetSql = r.getSql();            // Oracle SQL
    String refSql = r.getReferenceSql();      // H2 参考 SQL（verify=true 时）
    List<Warning> warnings = r.getWarnings(); // polyfill 警告
} else {
    List<Error> errors = r.getErrors();       // 编译错误
    String report = r.report();               // 格式化报告
}
```

### 从 IR 编译（跳过解析）

```java
// 手动构造 IR
IRSelect query = new IRSelect(...);
CompilationResult r = compiler.compileFromIR(query, Dialect.MYSQL);
```

### 管理缓存

```java
compiler.clearCache();     // 清空编译计划缓存
int size = compiler.cacheSize();  // 当前缓存条目数
```

### 配置 Schema

```java
SchemaProvider schema = USqlCompiler.schemaOf(
    new TableDef("users", null, List.of(
        new ColumnDef("id", DataType.IntType.INT, false, true, null),
        new ColumnDef("name", new DataType.VarcharType(100), false, false, null)
    ), List.of(), List.of())
);

USqlCompiler compiler = USqlCompiler.builder()
    .withSchema(schema)
    .build();
```

---

## CompilationResult

```java
CompilationResult r = compiler.compile(sql, dialect);

boolean ok = r.isSuccess();
String sql = r.getSql();                   // 目标 SQL
String ref = r.getReferenceSql();          // H2 参考 SQL
List<Warning> warnings = r.getWarnings();  // polyfill 警告
List<Error> errors = r.getErrors();        // 编译错误
String report = r.report();                // 格式化报告
```

### Error

```java
record Error(int line, int col, String message, String hint) {
    static Error of(int line, int col, String message);
    static Error of(int line, int col, String message, String hint);
}
```

### Warning

```java
record Warning(int line, int col, String message, String hint) {
    static Warning of(int line, int col, String message);
    static Warning of(int line, int col, String message, String hint);
}
```

---

## JDBC 集成

### DataSource 包装

```java
// 自动检测方言
DataSource usqlDs = new USqlDataSource(hikariDS,
    USqlDriver.detectDialect("jdbc:mysql://localhost:3306/mydb"));

// 显式指定
DataSource usqlDs = new USqlDataSource(hikariDS, Dialect.ORACLE);

// 创建新连接池
DataSource ds = USqlDataSource.create(
    "jdbc:mysql://localhost:3306/mydb", "user", "pass");
```

### Driver 模式

```java
// jdbc:usql: 前缀自动路由
Connection conn = DriverManager.getConnection(
    "jdbc:usql:oracle://localhost:1521/orclpdb1", "user", "pass");
Statement stmt = conn.createStatement();
ResultSet rs = stmt.executeQuery("SELECT ... LIMIT 10");
// U-SQL 自动翻译为 Oracle 语法
```

### Spring Boot

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

---

## Dialect 枚举

```java
Dialect.MYSQL       // MySQL 8.0+
Dialect.MARIADB     // MariaDB 11+
Dialect.TIDB        // TiDB 8.1+
Dialect.OCEANBASE   // OceanBase 4.2+
Dialect.POSTGRESQL  // PostgreSQL 16+
Dialect.ORACLE      // Oracle 19c+
Dialect.DM          // 达梦 DM8+
Dialect.SQLSERVER   // SQL Server 2022+
Dialect.SQLITE      // SQLite 3+
Dialect.DUCKDB      // DuckDB 1.2+
Dialect.CLICKHOUSE  // ClickHouse 24.8+
Dialect.H2          // H2 (测试用)

// 检测
Dialect d = USqlDriver.detectDialect("jdbc:mysql://localhost:3306/db");

// 能力查询
boolean supports = Dialect.ORACLE.supports(Capability.LIMIT_OFFSET); // false
Set<Capability> missing = Dialect.ORACLE.missingCapabilities(required);
```

---

## IROptimizer

```java
// Level 0: 不优化
USqlCompiler.builder().withOptimizeLevel(0).build();

// Level 1: 常量折叠（1+2→3）
USqlCompiler.builder().withOptimizeLevel(1).build();

// Level 2: +表达式简化（x*1→x）+ 子查询扁平
USqlCompiler.builder().withOptimizeLevel(2).build();

// Level 3: +谓词下推 + 投影裁剪
USqlCompiler.builder().withOptimizeLevel(3).build();

// 独立调用
SemanticIR optimized = IROptimizer.optimize(ir, 3);
```

---

## CLI 工具

```bash
# 编译单条
java -jar usql-cli.jar translate \
  --sql "SELECT ... LIMIT 10" --to oracle

# 批量迁移
java -jar usql-cli.jar migrate \
  --to postgresql --input ./sql/ --output ./pg-sql/

# 列出方言
java -jar usql-cli.jar dialects

# 管道
echo "SELECT ..." | nc localhost 3312
```

---

## 添加新方言

1. 在 `Dialect.java` 添加枚举值 + 能力声明
2. 创建 `XxxBackend.java` 继承 `AbstractDialectBackend`
3. 在 `USqlCompiler.java` 注册 Backend
4. 在 `USqlDriver.java` 添加 URL 检测
5. 添加 JDBC 驱动到 `pom.xml`
