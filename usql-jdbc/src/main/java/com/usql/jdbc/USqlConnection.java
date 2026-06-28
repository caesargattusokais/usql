package com.usql.jdbc;

import com.usql.USqlCompiler;
import com.usql.dialect.Dialect;

import java.sql.*;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

/**
 * JDBC Connection wrapper that intercepts SQL and compiles it through USQL.
 */
public class USqlConnection implements Connection {

    private final Connection real;
    private final Dialect dialect;
    private final USqlCompiler compiler;

    USqlConnection(Connection real, Dialect dialect, USqlCompiler compiler) {
        this.real = real;
        this.dialect = dialect;
        this.compiler = compiler;
    }

    Dialect dialect() { return dialect; }
    USqlCompiler compiler() { return compiler; }

    @Override
    public Statement createStatement() throws SQLException {
        return new USqlStatement(real.createStatement(), dialect, compiler);
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        String compiled = compiler.compile(sql, dialect).getSql();
        return real.prepareStatement(compiled);
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        String compiled = compiler.compile(sql, dialect).getSql();
        return real.prepareCall(compiled);
    }

    @Override
    public String nativeSQL(String sql) { return sql; }

    @Override public void setAutoCommit(boolean autoCommit) throws SQLException { real.setAutoCommit(autoCommit); }
    @Override public boolean getAutoCommit() throws SQLException { return real.getAutoCommit(); }
    @Override public void commit() throws SQLException { real.commit(); }
    @Override public void rollback() throws SQLException { real.rollback(); }
    @Override public void close() throws SQLException { real.close(); }
    @Override public boolean isClosed() throws SQLException { return real.isClosed(); }
    @Override public DatabaseMetaData getMetaData() throws SQLException { return real.getMetaData(); }
    @Override public void setReadOnly(boolean readOnly) throws SQLException { real.setReadOnly(readOnly); }
    @Override public boolean isReadOnly() throws SQLException { return real.isReadOnly(); }
    @Override public void setCatalog(String catalog) throws SQLException { real.setCatalog(catalog); }
    @Override public String getCatalog() throws SQLException { return real.getCatalog(); }
    @Override public void setTransactionIsolation(int level) throws SQLException { real.setTransactionIsolation(level); }
    @Override public int getTransactionIsolation() throws SQLException { return real.getTransactionIsolation(); }
    @Override public SQLWarning getWarnings() throws SQLException { return real.getWarnings(); }
    @Override public void clearWarnings() throws SQLException { real.clearWarnings(); }
    @Override public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        return new USqlStatement(real.createStatement(resultSetType, resultSetConcurrency), dialect, compiler);
    }
    @Override public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        String compiled = compiler.compile(sql, dialect).getSql();
        return real.prepareStatement(compiled, resultSetType, resultSetConcurrency);
    }
    @Override public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        String compiled = compiler.compile(sql, dialect).getSql();
        return real.prepareCall(compiled, resultSetType, resultSetConcurrency);
    }
    @Override public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        String compiled = compiler.compile(sql, dialect).getSql();
        return real.prepareCall(compiled, resultSetType, resultSetConcurrency, resultSetHoldability);
    }
    @Override public Map<String, Class<?>> getTypeMap() throws SQLException { return real.getTypeMap(); }
    @Override public void setTypeMap(Map<String, Class<?>> map) throws SQLException { real.setTypeMap(map); }
    @Override public void setHoldability(int holdability) throws SQLException { real.setHoldability(holdability); }
    @Override public int getHoldability() throws SQLException { return real.getHoldability(); }
    @Override public Savepoint setSavepoint() throws SQLException { return real.setSavepoint(); }
    @Override public Savepoint setSavepoint(String name) throws SQLException { return real.setSavepoint(name); }
    @Override public void rollback(Savepoint savepoint) throws SQLException { real.rollback(savepoint); }
    @Override public void releaseSavepoint(Savepoint savepoint) throws SQLException { real.releaseSavepoint(savepoint); }
    @Override public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return new USqlStatement(real.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability), dialect, compiler);
    }
    @Override public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        String compiled = compiler.compile(sql, dialect).getSql();
        return real.prepareStatement(compiled, resultSetType, resultSetConcurrency, resultSetHoldability);
    }
    @Override public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        String compiled = compiler.compile(sql, dialect).getSql();
        return real.prepareStatement(compiled, autoGeneratedKeys);
    }
    @Override public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        String compiled = compiler.compile(sql, dialect).getSql();
        return real.prepareStatement(compiled, columnIndexes);
    }
    @Override public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        String compiled = compiler.compile(sql, dialect).getSql();
        return real.prepareStatement(compiled, columnNames);
    }
    @Override public Clob createClob() throws SQLException { return real.createClob(); }
    @Override public Blob createBlob() throws SQLException { return real.createBlob(); }
    @Override public NClob createNClob() throws SQLException { return real.createNClob(); }
    @Override public SQLXML createSQLXML() throws SQLException { return real.createSQLXML(); }
    @Override public boolean isValid(int timeout) throws SQLException { return real.isValid(timeout); }
    @Override public void setClientInfo(String name, String value) { try { real.setClientInfo(name, value); } catch (SQLException e) { throw new RuntimeException(e); } }
    @Override public void setClientInfo(Properties properties) { try { real.setClientInfo(properties); } catch (SQLException e) { throw new RuntimeException(e); } }
    @Override public String getClientInfo(String name) { try { return real.getClientInfo(name); } catch (SQLException e) { throw new RuntimeException(e); } }
    @Override public Properties getClientInfo() { try { return real.getClientInfo(); } catch (SQLException e) { throw new RuntimeException(e); } }
    @Override public Array createArrayOf(String typeName, Object[] elements) throws SQLException { return real.createArrayOf(typeName, elements); }
    @Override public Struct createStruct(String typeName, Object[] attributes) throws SQLException { return real.createStruct(typeName, attributes); }
    @Override public void setSchema(String schema) throws SQLException { real.setSchema(schema); }
    @Override public String getSchema() throws SQLException { return real.getSchema(); }
    @Override public void abort(Executor executor) throws SQLException { real.abort(executor); }
    @Override public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException { real.setNetworkTimeout(executor, milliseconds); }
    @Override public int getNetworkTimeout() throws SQLException { return real.getNetworkTimeout(); }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { return real.unwrap(iface); }
    @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return real.isWrapperFor(iface); }
}
