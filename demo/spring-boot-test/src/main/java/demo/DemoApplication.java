package demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext ctx = SpringApplication.run(DemoApplication.class, args);
        JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);

        System.out.println("\n=== Spring Boot + USQL — 纯 application.yml 配置 ===");

        // 写 U-SQL，编译器自动翻译成 MySQL SQL 执行
        System.out.println("\n1. 基本查询:");
        jdbc.queryForList(
            "SELECT LENGTH('hello') AS len, UPPER('world') AS up, ROUND(3.14159,2) AS pi"
        ).forEach(System.out::println);

        System.out.println("\n2. 聚合 + 分页:");
        jdbc.queryForList(
            "SELECT dept_id, COUNT(*) AS cnt FROM employees GROUP BY dept_id ORDER BY cnt DESC LIMIT 3"
        ).forEach(row -> System.out.println("  " + row));

        System.out.println("\n3. JOIN:");
        jdbc.queryForList(
            "SELECT d.name AS dept, e.name AS emp FROM departments d JOIN employees e ON d.id = e.dept_id ORDER BY d.name LIMIT 5"
        ).forEach(row -> System.out.println("  " + row));

        System.out.println("\n✅ Spring Boot + USQL 集成成功！");
        SpringApplication.exit(ctx);
    }
}
