package com.usql.proxy;

import com.usql.USqlCompiler;
import com.usql.dialect.Dialect;

import java.io.*;
import java.net.*;
import java.sql.*;
import java.util.concurrent.*;

/**
 * USQL Database Proxy — MySQL Wire Protocol frontend, any JDBC backend.
 *
 * Applications connect to this proxy as if it were a MySQL server,
 * write U-SQL, and the proxy compiles and forwards to the real database.
 *
 * Usage:
 *   java -cp usql-proxy.jar com.usql.proxy.ProxyServer \
 *     --port 3307 \
 *     --dialect oracle \
 *     --backend jdbc:oracle:thin:@localhost:1521/orclpdb1 \
 *     --user system --password oracle123
 *
 * Supports: MySQL, PostgreSQL, Oracle, DM as backends.
 */
public class ProxyServer {

    private final int port;
    private final String backendUrl;
    private final String backendUser;
    private final String backendPassword;
    private final Dialect dialect;
    private final USqlCompiler compiler;
    private final ExecutorService threadPool;
    private volatile boolean running = true;

    public ProxyServer(int port, Dialect dialect, String backendUrl,
                       String backendUser, String backendPassword) {
        this.port = port;
        this.dialect = dialect;
        this.backendUrl = backendUrl;
        this.backendUser = backendUser;
        this.backendPassword = backendPassword;
        this.compiler = USqlCompiler.builder().build();
        this.threadPool = Executors.newCachedThreadPool();
    }

    public void start() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("USQL Proxy listening on port " + port);
            System.out.println("  Dialect: " + dialect.displayName());
            System.out.println("  Backend: " + backendUrl);

            while (running) {
                Socket clientSocket = serverSocket.accept();
                threadPool.submit(() -> handleClient(clientSocket));
            }
        }
    }

    private void handleClient(Socket clientSocket) {
        try (clientSocket) {
            // Simplified MySQL handshake: send fake greeting, receive login, send OK
            InputStream in = clientSocket.getInputStream();
            OutputStream out = clientSocket.getOutputStream();

            // Send MySQL 8.0 greeting packet
            sendGreeting(out);
            // Read client login (skip for simplicity)
            readLogin(in);
            // Send OK
            sendOk(out);

            System.out.println("Client connected: " + clientSocket.getInetAddress());

            // Open backend connection
            try (Connection backendConn = DriverManager.getConnection(backendUrl, backendUser, backendPassword)) {

                // Read command packets from client
                while (true) {
                    int packetLen = readPacketLength(in);
                    if (packetLen < 0) break;
                    byte[] packet = readNBytes(in, packetLen);

                    if (packet.length > 0 && packet[0] == 0x03) {
                        // COM_QUERY
                        String usql = new String(packet, 1, packet.length - 1, "UTF-8").trim();
                        System.out.println("  Query: " + usql);

                        try {
                            // Compile U-SQL → target dialect
                            var result = compiler.compile(usql, dialect);
                            if (!result.isSuccess()) {
                                sendError(out, result.report());
                                continue;
                            }
                            String targetSql = result.getSql();
                            System.out.println("  → " + targetSql);

                            // Execute on backend
                            try (java.sql.Statement stmt = backendConn.createStatement()) {
                                if (targetSql.trim().toUpperCase().startsWith("SELECT")
                                    || targetSql.trim().toUpperCase().startsWith("WITH")
                                    || targetSql.trim().toUpperCase().startsWith("SHOW")) {
                                    try (ResultSet rs = stmt.executeQuery(targetSql)) {
                                        sendResultSet(out, rs);
                                    }
                                } else {
                                    int updateCount = stmt.executeUpdate(targetSql);
                                    sendOk(out, updateCount);
                                }
                            }
                        } catch (SQLException e) {
                            sendError(out, e.getMessage());
                        }
                    } else if (packet.length > 0 && packet[0] == 0x01) {
                        // COM_QUIT
                        System.out.println("Client disconnected");
                        break;
                    } else {
                        // Other commands: pass through or ignore
                        sendOk(out);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Client error: " + e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════
    //  MySQL Wire Protocol helpers
    // ══════════════════════════════════════════════════

    private void sendGreeting(OutputStream out) throws IOException {
        // Simplified MySQL 8.0 greeting: protocol v10, server version, connection id, auth data, capabilities
        byte[] greeting = new byte[] {
            // Packet header (length 68, seq 0)
            0x44, 0x00, 0x00, 0x00,
            // Protocol version 10
            0x0a,
            // Server version "8.0.32-usql-proxy" (null-terminated)
            0x38, 0x2e, 0x30, 0x2e, 0x33, 0x32, 0x2d, 0x75,
            0x73, 0x71, 0x6c, 0x2d, 0x70, 0x72, 0x6f, 0x78, 0x79, 0x00,
            // Connection ID (4 bytes)
            0x01, 0x00, 0x00, 0x00,
            // Auth plugin data part 1 (8 bytes)
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            // Filler
            0x00,
            // Capability flags lower 2 bytes
            0x00, 0x00,
            // Character set (utf8)
            0x21,
            // Status flags
            0x00, 0x00,
            // Capability flags upper 2 bytes
            0x00, 0x00,
            // Auth plugin data length
            0x00,
            // Reserved (10 bytes)
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            // Auth plugin data part 2 (12 bytes)
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            // Auth plugin name "mysql_native_password" + null
            0x6d, 0x79, 0x73, 0x71, 0x6c, 0x5f, 0x6e, 0x61,
            0x74, 0x69, 0x76, 0x65, 0x5f, 0x70, 0x61, 0x73,
            0x73, 0x77, 0x6f, 0x72, 0x64, 0x00
        };
        out.write(greeting);
        out.flush();
    }

    private void readLogin(InputStream in) throws IOException {
        int len = readPacketLength(in);
        if (len > 0) readNBytes(in, len); // skip login packet
    }

    private void sendOk(OutputStream out) throws IOException {
        sendOk(out, 0);
    }

    private void sendOk(OutputStream out, int affectedRows) throws IOException {
        byte[] ok = new byte[] {
            0x07, 0x00, 0x00, 0x01, // length 7, seq 1
            0x00,                     // OK header
            (byte) affectedRows,      // affected rows
            (byte) (affectedRows>>8), // affected rows high
            0x00, 0x00,              // last insert id
            0x02, 0x00,              // status flags
            0x00, 0x00               // warnings
        };
        out.write(ok);
        out.flush();
    }

    private void sendError(OutputStream out, String message) throws IOException {
        byte[] msgBytes = message.getBytes("UTF-8");
        int len = 1 + 2 + msgBytes.length; // header(1) + errno(2) + message
        out.write(len & 0xff);
        out.write((len >> 8) & 0xff);
        out.write((len >> 16) & 0xff);
        out.write(0x01); // seq
        out.write(0xff); // ERR header
        out.write(0x84); // errno 1064 (syntax error)
        out.write(0x04);
        out.write(new byte[]{'2', '8', '0', '0', '0'}); // SQL state
        out.write(msgBytes);
        out.flush();
    }

    private void sendResultSet(OutputStream out, ResultSet rs) throws SQLException, IOException {
        var meta = rs.getMetaData();
        int colCount = meta.getColumnCount();

        // Column count packet
        byte[] colCountPkt = new byte[] {
            (byte) (colCount & 0xff), 0x00, 0x00, 0x01, // length, seq
            (byte) colCount
        };
        colCountPkt[0] = (byte) (1 + countVarintLength(colCount)); // length of the varint
        out.write(colCountPkt);

        int seq = 2;
        // Column definitions
        for (int i = 1; i <= colCount; i++) {
            byte[] def = buildColumnDef(meta.getColumnName(i), seq++);
            out.write(def);
        }

        // EOF after columns (if not CLIENT_DEPRECATE_EOF)
        byte[] eof = new byte[] { 0x05, 0x00, 0x00, (byte) seq++, (byte) 0xfe, 0x00, 0x00, 0x02, 0x00 };
        out.write(eof);

        // Row data
        while (rs.next()) {
            byte[] row = buildRowData(rs, meta, seq++);
            out.write(row);
        }

        // EOF after rows
        byte[] eof2 = new byte[] { 0x05, 0x00, 0x00, (byte) seq++, (byte) 0xfe, 0x00, 0x00, 0x02, 0x00 };
        out.write(eof2);
        out.flush();
    }

    private byte[] buildColumnDef(String name, int seq) {
        byte[] nameBytes = ("def\0" + "db\0" + "tbl\0" + "tbl\0" + name + "\0" +
            name + "\0" + "\014\0\0" + "\010\0\0\0\0" + "\003\003\0\0\0" +
            "\0\0\0\0\0\0\0\0\0\0" + "\0\003\0\0\0" + "\374\377\377\377\377\377\377\377" +
            "\0\0\0").getBytes();
        int len = nameBytes.length;
        byte[] packet = new byte[4 + len];
        packet[0] = (byte) (len & 0xff);
        packet[1] = (byte) ((len >> 8) & 0xff);
        packet[2] = (byte) ((len >> 16) & 0xff);
        packet[3] = (byte) seq;
        System.arraycopy(nameBytes, 0, packet, 4, len);
        return packet;
    }

    private byte[] buildRowData(ResultSet rs, java.sql.ResultSetMetaData meta, int seq) throws SQLException {
        var buf = new java.io.ByteArrayOutputStream();
        // NULL bitmap placeholder — skip for simplicity, put 0 bits
        buf.write(0); // null bitmap = 0 (no NULLs)

        for (int i = 1; i <= meta.getColumnCount(); i++) {
            Object val = rs.getObject(i);
            String str = val == null ? "" : val.toString();
            byte[] bytes = str.getBytes();
            // Write length-encoded string
            buf.write(bytes, 0, bytes.length);
        }

        byte[] data = buf.toByteArray();
        byte[] packet = new byte[4 + data.length];
        int len = data.length;
        packet[0] = (byte) (len & 0xff);
        packet[1] = (byte) ((len >> 8) & 0xff);
        packet[2] = (byte) ((len >> 16) & 0xff);
        packet[3] = (byte) seq;
        System.arraycopy(data, 0, packet, 4, len);
        return packet;
    }

    // ══════════════════════════════════════════════════
    //  Protocol helpers
    // ══════════════════════════════════════════════════

    private int readPacketLength(InputStream in) throws IOException {
        byte[] header = readNBytes(in, 4);
        if (header.length < 4) return -1;
        return (header[0] & 0xff) | ((header[1] & 0xff) << 8) | ((header[2] & 0xff) << 16);
    }

    private byte[] readNBytes(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) break;
            off += r;
        }
        return off == n ? buf : java.util.Arrays.copyOf(buf, off);
    }

    private static int countVarintLength(int value) {
        if (value < 251) return 1;
        if (value < 65536) return 3;
        if (value < 16777216) return 4;
        return 9;
    }

    // ══════════════════════════════════════════════════
    //  Main
    // ══════════════════════════════════════════════════

    // ══════════════════════════════════════════════════
    //  Text-mode proxy (telnet/nc friendly)
    // ══════════════════════════════════════════════════
    static class TextProxy {
        private final int port;
        private final Dialect dialect;
        private final String url, user, pw;
        private final USqlCompiler compiler = USqlCompiler.builder().build();

        TextProxy(int port, Dialect d, String url, String user, String pw) {
            this.port = port; this.dialect = d; this.url = url; this.user = user; this.pw = pw;
        }

        void start() throws IOException {
            try (ServerSocket ss = new ServerSocket(port)) {
                System.out.println("USQL Text Proxy on port " + port + " (dialect: " + dialect + ")");
                while (true) {
                    try (Socket s = ss.accept();
                         BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
                         PrintWriter out = new PrintWriter(s.getOutputStream(), true)) {
                        try (Connection c = DriverManager.getConnection(url, user, pw)) {
                            out.println("USQL Text Proxy — dialect: " + dialect.displayName());
                            String line;
                            while ((line = in.readLine()) != null) {
                                line = line.trim();
                                if (line.isEmpty() || line.equals("exit")) break;
                                try {
                                    var r = compiler.compile(line, dialect);
                                    if (!r.isSuccess()) { out.println("ERROR: " + r.report()); continue; }
                                    try (java.sql.Statement st = c.createStatement()) {
                                        if (r.getSql().trim().toUpperCase().startsWith("SELECT")) {
                                            try (ResultSet rs = st.executeQuery(r.getSql())) {
                                                var meta = rs.getMetaData();
                                                int cols = meta.getColumnCount();
                                                while (rs.next()) {
                                                    var row = new StringBuilder();
                                                    for (int i = 1; i <= cols; i++) {
                                                        if (i > 1) row.append('\t');
                                                        row.append(rs.getString(i) != null ? rs.getString(i) : "NULL");
                                                    }
                                                    out.println(row);
                                                }
                                            }
                                        } else { out.println("OK rows=" + st.executeUpdate(r.getSql())); }
                                    }
                                } catch (Exception e) { out.println("ERROR: " + e.getMessage()); }
                                out.println("--");
                            }
                        }
                    } catch (Exception e) { System.err.println("Error: " + e.getMessage()); }
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        String mode = "text"; // text | mysql
        int port = 3307;
        Dialect dialect = Dialect.MYSQL;
        String url = "jdbc:mysql://localhost:3306/login_db?useSSL=false&allowPublicKeyRetrieval=true";
        String user = "login_user";
        String password = "login123";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--mode"     -> mode = args[++i];
                case "--port"     -> port = Integer.parseInt(args[++i]);
                case "--dialect"  -> dialect = Dialect.valueOf(args[++i].toUpperCase());
                case "--backend"  -> url = args[++i];
                case "--user"     -> user = args[++i];
                case "--password" -> password = args[++i];
            }
        }

        if ("text".equals(mode)) {
            new TextProxy(port, dialect, url, user, password).start();
        } else {
            new ProxyServer(port, dialect, url, user, password).start();
        }
    }
}
