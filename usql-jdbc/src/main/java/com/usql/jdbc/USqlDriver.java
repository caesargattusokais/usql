package com.usql.jdbc;

import com.usql.USqlCompiler;
import com.usql.dialect.Dialect;

import java.sql.*;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * USQL JDBC Driver — the single entry point for all USQL connections.
 *
 * Two URL formats are supported:
 *
 * 1. Explicit: jdbc:usql:mysql://host:port/db
 *    Dialect from the prefix (mysql/postgresql/oracle/dm).
 *
 * 2. Implicit: jdbc:mysql://host:port/db
 *    Dialect auto-detected from URL pattern.
 *
 * USqlDataSource delegates here for both dialect detection and
 * connection creation.
 */
public class USqlDriver implements java.sql.Driver {

    private static final String USQL_PREFIX = "jdbc:usql:";
    private static final USqlCompiler compiler = USqlDataSource.compiler();

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

        Connection realConn = DriverManager.getConnection(realUrl, info);
        return new USqlConnection(realConn, parsed.dialect(), compiler);
    }

    /** Only accepts jdbc:usql:* URLs. Standard URLs go to real drivers. */
    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith(USQL_PREFIX);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override public int getMajorVersion() { return 1; }
    @Override public int getMinorVersion() { return 0; }
    @Override public boolean jdbcCompliant() { return false; }
    @Override public Logger getParentLogger() { return Logger.getLogger("com.usql.jdbc"); }

    // ══════════════════════════════════════════════════
    //  URL parsing — shared by Driver and DataSource
    // ══════════════════════════════════════════════════

    /**
     * Detect dialect from JDBC URL.
     * Public so USqlDataSource can use it too.
     */
    public static Dialect detectDialect(String jdbcUrl) {
        // If usql: prefix, extract from there
        if (jdbcUrl.startsWith(USQL_PREFIX)) {
            String rest = jdbcUrl.substring(USQL_PREFIX.length());
            int colon = rest.indexOf(':');
            if (colon > 0) {
                return Dialect.valueOf(rest.substring(0, colon).toUpperCase());
            }
        }
        // Otherwise detect from URL pattern
        String url = jdbcUrl.toLowerCase();
        if (url.contains(":mysql:")) return Dialect.MYSQL;
        if (url.contains(":mariadb:")) return Dialect.MARIADB;
        if (url.contains(":tidb:")) return Dialect.TIDB;
        if (url.contains(":postgresql:") || url.contains(":pgsql:")) return Dialect.POSTGRESQL;
        if (url.contains(":oracle:")) return Dialect.ORACLE;
        if (url.contains(":dm:")) return Dialect.DM;
        if (url.contains(":sqlserver:")) return Dialect.SQLSERVER;
        if (url.contains(":sqlite:")) return Dialect.SQLITE;
        if (url.contains(":duckdb:")) return Dialect.DUCKDB;
        throw new IllegalArgumentException("Cannot detect dialect from: " + jdbcUrl);
    }

    /** Parse URL into dialect + real JDBC URL part (without jdbc: prefix). */
    static ParsedUrl parseUrl(String url) {
        if (url.startsWith(USQL_PREFIX)) {
            // jdbc:usql:mysql://host/db → rest = mysql://host/db
            String rest = url.substring(USQL_PREFIX.length());
            int colon = rest.indexOf(':');
            String dialectName = rest.substring(0, colon).toUpperCase();
            Dialect dialect = Dialect.valueOf(dialectName);
            return new ParsedUrl(dialect, rest);
        }
        // Standard JDBC URL: jdbc:mysql://host/db → rest = mysql://host/db
        String rest = url.substring("jdbc:".length());
        Dialect dialect = detectDialect(url);
        return new ParsedUrl(dialect, rest);
    }

    record ParsedUrl(Dialect dialect, String realPart) {}
}
