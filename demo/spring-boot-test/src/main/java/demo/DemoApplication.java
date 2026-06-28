package demo;

import com.usql.jdbc.USqlDataSource;
import com.usql.dialect.Dialect;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.DriverManager;

@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        var ctx = SpringApplication.run(DemoApplication.class, args);
        var jdbc = ctx.getBean(JdbcTemplate.class);

        // Run U-SQL queries through the USQL DataSource
        System.out.println("\n=== Query 1: Basic SELECT ===");
        jdbc.queryForList(
            "SELECT LENGTH('hello') AS len, UPPER('world') AS up, ROUND(3.14159, 2) AS pi"
        ).forEach(System.out::println);

        System.out.println("\n=== Query 2: Aggregate with LIMIT ===");
        jdbc.queryForList(
            "SELECT dept_id, COUNT(*) AS cnt FROM employees GROUP BY dept_id ORDER BY cnt DESC LIMIT 3"
        ).forEach(System.out::println);

        System.out.println("\n=== Query 3: JOIN ===");
        jdbc.queryForList(
            "SELECT d.name AS dept, e.name AS emp FROM departments d JOIN employees e ON d.id = e.dept_id ORDER BY d.name LIMIT 5"
        ).forEach(System.out::println);

        SpringApplication.exit(ctx);
    }

    /**
     * Configure USQL DataSource that compiles U-SQL to PostgreSQL,
     * executing against the real MySQL database.
     */
    @Bean
    public DataSource dataSource() throws Exception {
        return USqlDataSource.create(
            "jdbc:mysql://localhost:3306/login_db?useSSL=false&allowPublicKeyRetrieval=true",
            "login_user", "login123",
            Dialect.MYSQL  // U-SQL → MySQL SQL → runs on MySQL backend
        );
    }
}
