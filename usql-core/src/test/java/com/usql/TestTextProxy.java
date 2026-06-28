package com.usql;

import java.io.*;
import java.net.*;

/** Quick test client for the text-mode proxy. */
public class TestTextProxy {
    public static void main(String[] args) throws Exception {
        try (Socket s = new Socket("localhost", 3312);
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
             PrintWriter out = new PrintWriter(s.getOutputStream(), true)) {

            System.out.println("Connected: " + s.isConnected());
            String banner = in.readLine();
            System.out.println("Banner: " + banner);

            out.println("SELECT LENGTH('hello') AS len, UPPER('world') AS up, ROUND(3.14159,2) AS pi");
            String line;
            while (!"--".equals(line = in.readLine())) {
                System.out.println("Row: " + line);
            }
            System.out.println("OK");

            out.println("SELECT name, salary FROM employees WHERE salary > 50000 LIMIT 3");
            while (!"--".equals(line = in.readLine())) {
                System.out.println("Row: " + line);
            }
            System.out.println("OK");

            out.println("exit");
        }
    }
}
