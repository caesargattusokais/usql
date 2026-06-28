package com.usql.jdbc;

import com.usql.USqlCompiler;
import com.usql.dialect.Dialect;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.*;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * USQL DataSource wrapper — for Spring Boot / connection pool integration.
 *
 * Wraps a real JDBC DataSource and transparently compiles U-SQL to the
 * target dialect before execution.
 *
 * Usage with Spring Boot application.yml:
 * <pre>
 * spring:
 *   datasource:
 *     url: jdbc:mysql://localhost:3306/mydb
 *     username: user
 *     password: pass
 *     driver-class-name: com.mysql.cj.jdbc.Driver
 *
 * # Then in a @Configuration class:
 * @Bean
 * public DataSource dataSource() {
 *     return new USqlDataSource(
 *         DataSourceBuilder.create().build(),  // the real MySQL DataSource
 *         Dialect.ORACLE                       // compile U-SQL → Oracle SQL
 *     );
 * }
 * </pre>
 *
 * Or use the factory method with JDBC URL:
 * <pre>
 * DataSource ds = USqlDataSource.create(
 *     "jdbc:mysql://localhost:3306/mydb", "user", "pass",
 *     Dialect.POSTGRESQL);
 * </pre>
 */
public class USqlDataSource implements DataSource {

    private final DataSource real;
    private final Dialect dialect;
    private final USqlCompiler compiler;

    /**
     * Wrap an existing DataSource.
     */
    public USqlDataSource(DataSource real, Dialect dialect) {
        this.real = real;
        this.dialect = dialect;
        this.compiler = USqlCompiler.builder().build();
    }

    /**
     * Factory: create from JDBC URL, auto-detect dialect from URL.
     * jdbc:mysql://... → MYSQL
     * jdbc:postgresql://... → POSTGRESQL
     * jdbc:oracle:thin:@... → ORACLE
     * jdbc:dm://... → DM
     */
    public static USqlDataSource create(String jdbcUrl, String user, String password) throws SQLException {
        Dialect dialect = detectDialect(jdbcUrl);
        var ds = new SimpleDriverDataSource(jdbcUrl, user, password);
        return new USqlDataSource(ds, dialect);
    }

    /**
     * Detect database dialect from JDBC URL.
     */
    public static Dialect detectDialect(String jdbcUrl) {
        String url = jdbcUrl.toLowerCase();
        if (url.contains(":mysql:") || url.contains(":mariadb:") || url.contains(":mysql-")) return Dialect.MYSQL;
        if (url.contains(":postgresql:") || url.contains(":pgsql:")) return Dialect.POSTGRESQL;
        if (url.contains(":oracle:")) return Dialect.ORACLE;
        if (url.contains(":dm:")) return Dialect.DM;
        throw new IllegalArgumentException("Cannot detect dialect from JDBC URL: " + jdbcUrl +
            ". Supported: mysql, postgresql, oracle, dm");
    }

    public Dialect dialect() { return dialect; }

    @Override
    public Connection getConnection() throws SQLException {
        return new USqlConnection(real.getConnection(), dialect, compiler);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return new USqlConnection(real.getConnection(username, password), dialect, compiler);
    }

    @Override public PrintWriter getLogWriter() throws SQLException { return real.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { real.setLogWriter(out); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { real.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return real.getLoginTimeout(); }
    @Override public Logger getParentLogger() { return Logger.getLogger("com.usql.jdbc"); }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        return real.unwrap(iface);
    }
    @Override public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || real.isWrapperFor(iface);
    }

    // ══════════════════════════════════════════════════
    //  Simple Driver-based DataSource (fallback)
    // ══════════════════════════════════════════════════

    private static class SimpleDriverDataSource implements DataSource {
        private final String url;
        private final Properties props;

        SimpleDriverDataSource(String url, String user, String password) {
            this.url = url;
            this.props = new Properties();
            if (user != null) props.setProperty("user", user);
            if (password != null) props.setProperty("password", password);
        }

        @Override public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, props);
        }

        @Override public Connection getConnection(String u, String p) throws SQLException {
            var p2 = new Properties();
            p2.putAll(props);
            if (u != null) p2.setProperty("user", u);
            if (p != null) p2.setProperty("password", p);
            return DriverManager.getConnection(url, p2);
        }

        @Override public PrintWriter getLogWriter() { return DriverManager.getLogWriter(); }
        @Override public void setLogWriter(PrintWriter out) { DriverManager.setLogWriter(out); }
        @Override public void setLoginTimeout(int s) { DriverManager.setLoginTimeout(s); }
        @Override public int getLoginTimeout() { return DriverManager.getLoginTimeout(); }
        @Override public Logger getParentLogger() { return Logger.getLogger("com.usql.jdbc"); }
        @Override public <T> T unwrap(Class<T> iface) { throw new UnsupportedOperationException(); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
