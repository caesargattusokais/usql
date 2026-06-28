package com.usql;

import com.usql.ast.USqlAst.Statement;
import com.usql.dialect.Dialect;
import com.usql.parser.AstBuilder;

import java.sql.*;

/**
 * ENUM verification across all 4 databases.
 * Run: mvn test-compile exec:java -Dexec.mainClass=com.usql.EnumTest
 */
public class EnumTest {
    public static void main(String[] args) throws Exception {
        var compiler = USqlCompiler.builder().build();
        String usql = "CREATE TABLE test_enum (id INT PRIMARY KEY, status ENUM('active','inactive','pending') NOT NULL DEFAULT 'active')";

        for (var entry : new Object[][]{
            {"MySQL", Dialect.MYSQL, "com.mysql.cj.jdbc.Driver",
                "jdbc:mysql://localhost:3306/login_db?useSSL=false&allowPublicKeyRetrieval=true", "login_user", "login123"},
            {"PG", Dialect.POSTGRESQL, "org.postgresql.Driver",
                "jdbc:postgresql://localhost:5432/mydb", "postgres", "postgres123"},
            {"Oracle", Dialect.ORACLE, "oracle.jdbc.OracleDriver",
                "jdbc:oracle:thin:@localhost:1521/orclpdb1", "system", "oracle123"},
            {"DM", Dialect.DM, "dm.jdbc.driver.DmDriver",
                "jdbc:dm://localhost:5236", "SYSDBA", "dm12345678"},
        }) {
            String name = (String) entry[0];
            Dialect d = (Dialect) entry[1];
            try {
                Class.forName((String) entry[2]);
                try (Connection c = DriverManager.getConnection((String) entry[3], (String) entry[4], (String) entry[5])) {
                    boolean quoted = d == Dialect.ORACLE || d == Dialect.DM;
                    String tbl = quoted ? "\"test_enum\"" : "test_enum";
                    try (java.sql.Statement s = c.createStatement()) {
                        try { s.execute("DROP TABLE " + tbl); } catch (SQLException ignored) {}
                        try { s.execute("DROP TABLE test_enum"); } catch (SQLException ignored) {}

                        Statement ast = AstBuilder.buildSingle(usql);
                        String sql = compiler.compileFromAst(ast, d).getSql();
                        System.out.println(name + " DDL: " + sql);
                        s.execute(sql);

                        // Verify ENUM works: insert valid, reject invalid
                        String c1 = quoted ? "\"id\"" : "id";
                        String c2 = quoted ? "\"status\"" : "status";
                        s.execute("INSERT INTO " + tbl + " (" + c1 + ", " + c2 + ") VALUES (1, 'active')");
                        s.execute("INSERT INTO " + tbl + " (" + c1 + ", " + c2 + ") VALUES (2, 'inactive')");
                        try {
                            s.execute("INSERT INTO " + tbl + " (" + c1 + ", " + c2 + ") VALUES (3, 'badvalue')");
                            System.out.println("  FAIL " + name + ": should have rejected 'badvalue'");
                        } catch (SQLException expected) {
                            System.out.println("  PASS " + name + ": ENUM rejects invalid value");
                        }
                        s.execute("DROP TABLE " + tbl);
                    }
                }
            } catch (ClassNotFoundException e) {
                System.out.println("  SKIP " + name + ": driver not found");
            }
        }
    }
}
