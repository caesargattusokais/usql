import com.usql.*;
import com.usql.dialect.Dialect;
public class SqlServerDebug {
    public static void main(String[] args) {
        var c = USqlCompiler.builder().build();
        var r = c.compile("CREATE TABLE t (id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(100), score DECIMAL(10,2) DEFAULT 0.00, active BOOLEAN DEFAULT TRUE)", Dialect.SQLSERVER);
        System.out.println(r.isSuccess() ? r.getSql() : r.report());
    }
}
