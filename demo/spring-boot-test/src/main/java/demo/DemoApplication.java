package demo;

import com.usql.jdbc.USqlDataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        var ctx = SpringApplication.run(DemoApplication.class, args);
        JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);

        System.out.println("\n=== Spring Boot + USQLDataSource ===");

        // 写 U-SQL，编译器自动翻译成目标数据库 SQL 执行
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

        System.out.println("\n✅ Spring Boot + USQLDataSource 集成成功！");
        SpringApplication.exit(ctx);
    }

    /**
     * 正确方式：用 USQLDataSource 包装 Spring Boot 自动配置的 DataSource。
     * 连接池（HikariCP）正常工作，USQL 在 Connection/Statement 层透明拦截 SQL。
     */
    @Configuration
    static class UsqlConfig {
        @Bean
        @Primary
        public DataSource usqlDataSource(DataSourceProperties props) throws Exception {
            // USqlDataSource 包装连接，从 URL 自动识别方言，HikariCP 正常池化
            return USqlDataSource.create(props.getUrl(), props.getUsername(), props.getPassword());
        }
    }
}
