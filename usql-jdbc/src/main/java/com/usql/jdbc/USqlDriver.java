package com.usql.jdbc;

import com.usql.USqlCompiler;
import com.usql.dialect.Dialect;

import java.sql.*;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * USQL JDBC Driver.
 *
 * Wraps a real JDBC driver and transparently compiles U-SQL to the target
 * dialect before execution.
 *
 * URL format: jdbc:usql:<dialect>:<real-jdbc-url>
 *
 * Examples:
 *   jdbc:usql:mysql://localhost:3306/mydb
 *   jdbc:usql:postgresql://localhost:5432/mydb
 *   jdbc:usql:oracle:thin:@localhost:1521/orclpdb1
 *   jdbc:usql:dm://localhost:5236
 *
 * The driver strips the "jdbc:usql:<dialect>:" prefix and replaces it
 * with "jdbc:" to construct the real JDBC URL, then delegates to the
 * real driver (mysql-connector-j, postgresql, ojdbc, DmJdbcDriver).
 */
public class USqlDriver implements java.sql.Driver {

    private static final String PREFIX = "jdbc:usql:";
    private static final USqlCompiler compiler = USqlCompiler.builder().build();

    static {
        try {
            DriverManager.registerDriver(new USqlDriver());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to register USqlDriver", e);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) return null;

        ParsedUrl parsed = parseUrl(url);
        String realUrl = "jdbc:" + parsed.realPart();

        // Load the real driver
        Connection realConn;
        try {
            realConn = DriverManager.getConnection(realUrl, info);
        } catch (SQLException e) {
            throw new SQLException("Cannot connect to real database: " + realUrl + " — " + e.getMessage(), e);
        }

        return new USqlConnection(realConn, parsed.dialect(), compiler);
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith(PREFIX);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() { return 1; }

    @Override
    public int getMinorVersion() { return 0; }

    @Override
    public boolean jdbcCompliant() { return false; }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger("com.usql.jdbc");
    }

    /** Parse jdbc:usql:<dialect>:<rest> into dialect + real JDBC URL part. */
    static ParsedUrl parseUrl(String url) {
        // url = "jdbc:usql:mysql://host:port/db"
        String rest = url.substring(PREFIX.length());
        // rest = "mysql://host:port/db"  (this IS the real JDBC URL without the "jdbc:" prefix)
        int colon = rest.indexOf(':');
        if (colon < 0) throw new IllegalArgumentException("Invalid USQL URL: " + url +
            " — expected jdbc:usql:<dialect>:<real-jdbc-url>");

        String dialectName = rest.substring(0, colon).toUpperCase();
        String realPart = rest; // keep the full URL including dialect prefix

        Dialect dialect;
        try {
            dialect = Dialect.valueOf(dialectName);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown dialect: " + dialectName +
                ". Supported: MYSQL, POSTGRESQL, ORACLE, DM");
        }

        return new ParsedUrl(dialect, realPart);
    }

    record ParsedUrl(Dialect dialect, String realPart) {}
}
