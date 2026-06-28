package com.usql.jdbc;

import com.usql.USqlCompiler;
import com.usql.dialect.Dialect;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.*;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * USQL DataSource wrapper — transparent U-SQL compilation.
 *
 * Wraps any DataSource (HikariCP, Druid, Tomcat, DBCP2, etc.) and
 * intercepts SQL at the Connection/Statement level. Connection pooling
 * works normally because the pool sits inside this wrapper.
 *
 * Usage — manual:
 * <pre>
 *   DataSource pooled = new HikariDataSource(config);  // any pool
 *   DataSource usql   = new USqlDataSource(pooled, dialect);
 * </pre>
 *
 * Usage — Spring Boot auto-config (BeanPostProcessor):
 * <pre>
 *   @Bean static BeanPostProcessor usqlWrapper(Environment env) {
 *       return new BeanPostProcessor() {
 *           public Object postProcessAfterInitialization(Object bean, String name) {
 *               if (bean instanceof DataSource ds && !(bean instanceof USqlDataSource))
 *                   return new USqlDataSource(ds, USqlDriver.detectDialect(env.getProperty("spring.datasource.url")));
 *               return bean;
 *           }
 *       };
 *   }
 * </pre>
 */
public class USqlDataSource implements DataSource {

    private final DataSource real;      // the pooled DataSource (HikariCP/Druid/etc)
    private final Dialect dialect;
    private final USqlCompiler compiler;

    public USqlDataSource(DataSource real, Dialect dialect) {
        this.real = real;
        this.dialect = dialect;
        this.compiler = USqlCompiler.builder().build();
    }

    /** Factory: auto-detect dialect from JDBC URL. */
    public static USqlDataSource create(String jdbcUrl, String user, String password) throws SQLException {
        return new USqlDataSource(new SimpleDriverDataSource(jdbcUrl, user, password),
            USqlDriver.detectDialect(jdbcUrl));
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

    // ── Standard DataSource delegation ──

    @Override public PrintWriter getLogWriter() throws SQLException { return real.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { real.setLogWriter(out); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { real.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return real.getLoginTimeout(); }
    @Override public Logger getParentLogger() { return Logger.getLogger("com.usql.jdbc"); }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException {
        return iface.isInstance(this) ? iface.cast(this) : real.unwrap(iface);
    }
    @Override public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || real.isWrapperFor(iface);
    }

    // ══════════════════════════════════════════════════
    //  Fallback (non-pooled) DataSource
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

        @Override public Connection getConnection() throws SQLException { return DriverManager.getConnection(url, props); }
        @Override public Connection getConnection(String u, String p) throws SQLException {
            var p2 = new Properties(); p2.putAll(props);
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
