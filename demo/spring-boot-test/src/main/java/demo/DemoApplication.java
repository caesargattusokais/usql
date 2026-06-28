package demo;

import com.usql.jdbc.USqlDataSource;
import com.usql.jdbc.USqlDriver;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        var ctx = SpringApplication.run(DemoApplication.class, args);
        JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);

        System.out.println("\n=== Spring Boot + USQL — 任何连接池 ===");

        jdbc.queryForList(
            "SELECT LENGTH('hello') AS len, UPPER('world') AS up, ROUND(3.14159,2) AS pi"
        ).forEach(System.out::println);

        jdbc.queryForList(
            "SELECT dept_id, COUNT(*) AS cnt FROM employees GROUP BY dept_id ORDER BY cnt DESC LIMIT 3"
        ).forEach(row -> System.out.println("  " + row));

        jdbc.queryForList(
            "SELECT d.name AS dept, e.name AS emp FROM departments d JOIN employees e ON d.id = e.dept_id ORDER BY d.name LIMIT 5"
        ).forEach(row -> System.out.println("  " + row));

        System.out.println("✅ 任何连接池 (HikariCP/Druid/Tomcat/DBCP2) 零配置集成");
        SpringApplication.exit(ctx);
    }

    /**
     * 自动包装 Spring Boot 创建的 DataSource（HikariCP/Druid/任何池）。
     * application.yml 完全不用改。
     */
    @Configuration
    static class UsqlConfig {
        @Bean
        static BeanPostProcessor usqlWrapper(Environment env) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof DataSource ds && !(bean instanceof USqlDataSource)) {
                        String url = env.getProperty("spring.datasource.url");
                        if (url != null) {
                            return new USqlDataSource(ds, USqlDriver.detectDialect(url));
                        }
                    }
                    return bean;
                }
            };
        }
    }
}
