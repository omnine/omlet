package com.example;

import java.sql.*;

/**
 * Reproduces: SQLException "Can not call getNString() when field's charset isn't UTF-8"
 *
 * Root cause: MySQL Connector/J 8 forbids calling ResultSet.getNString() on columns
 * whose charset is not UTF-8 (e.g. latin1). Keycloak 18 / Hibernate 5.6 started
 * calling getNString() for NVARCHAR-mapped columns, which triggers the error when
 * the database or table was created with a non-UTF-8 default charset.
 *
 * Prerequisites:
 *   - MySQL 5.7 running on localhost:3306
 *   - A database named 'testdb' (CREATE DATABASE testdb CHARACTER SET latin1;)
 *   - User 'root' with password 'root' (adjust DB_* constants below as needed)
 */
public class NStringReproducer {

    // --- adjust these to match your MySQL setup ---
    private static final String DB_HOST = "192.168.103.138";
    private static final String DB_PORT = "3306";
    private static final String DB_NAME = "testdb";
    private static final String DB_USER = "nanoart";
    private static final String DB_PASSWORD = "changeit";

    // German umlauts test string
    private static final String UMLAUT_TEXT =
        "Ärger mit Öl und Überraschungen – Schöne Grüße aus München! (ä ö ü Ä Ö Ü ß)";

    public static void main(String[] args) throws Exception {
        System.out.println(
            "=== MySQL Connector/J 8 + getNString() reproduction ===\n"
        );

        // --- Step 1: connect WITHOUT enforcing UTF-8 on the connection ---
        // This simulates the default / misconfigured setup where the DB is latin1.
        String urlNoUtf8 = String.format(
            "jdbc:mysql://%s:%s/%s?useSSL=false&allowPublicKeyRetrieval=true",
            DB_HOST,
            DB_PORT,
            DB_NAME
        );

        try (
            Connection conn = DriverManager.getConnection(
                urlNoUtf8,
                DB_USER,
                DB_PASSWORD
            )
        ) {
            System.out.println("Connected (no explicit charset): " + urlNoUtf8);
            setupTable(conn, "latin1"); // table column is latin1
            insertRow(conn, UMLAUT_TEXT);

            System.out.println(
                "\n--- Attempt 1: ResultSet.getString() [always works] ---"
            );
            readWithGetString(conn);

            System.out.println(
                "\n--- Attempt 2: ResultSet.getNString() on latin1 column [WILL THROW] ---"
            );
            try {
                readWithGetNString(conn);
            } catch (SQLException e) {
                System.out.println(
                    "REPRODUCED SQLException: " + e.getMessage()
                );
                System.out.println("  SQLState : " + e.getSQLState());
                System.out.println("  ErrorCode: " + e.getErrorCode());
            }

            dropTable(conn);
        }

        System.out.println();

        // --- Step 2: same call but with characterEncoding=UTF-8 on the connection ---
        // This is one of the documented workarounds: force the connection to UTF-8.
        String urlUtf8 = String.format(
            "jdbc:mysql://%s:%s/%s?useSSL=false&allowPublicKeyRetrieval=true" +
                "&characterEncoding=UTF-8&characterSetResults=UTF-8",
            DB_HOST,
            DB_PORT,
            DB_NAME
        );

        try (
            Connection conn = DriverManager.getConnection(
                urlUtf8,
                DB_USER,
                DB_PASSWORD
            )
        ) {
            System.out.println("Connected (UTF-8 forced): " + urlUtf8);
            // Even though the *table* column is latin1, the driver now treats the
            // connection as UTF-8 and allows getNString().
            setupTable(conn, "latin1");
            insertRow(conn, UMLAUT_TEXT);

            System.out.println(
                "\n--- Attempt 3: ResultSet.getNString() with characterEncoding=UTF-8 [WORKAROUND] ---"
            );
            readWithGetNString(conn);

            dropTable(conn);
        }

        System.out.println("\n=== Done ===");
    }

    // -------------------------------------------------------------------------

    private static void setupTable(Connection conn, String charset)
        throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS umlaut_test");
            // Explicitly create the column with a non-UTF-8 charset to trigger the bug
            st.executeUpdate(
                "CREATE TABLE umlaut_test (" +
                    "  id   INT PRIMARY KEY AUTO_INCREMENT," +
                    "  name VARCHAR(255) CHARACTER SET " +
                    charset +
                    ") ENGINE=InnoDB"
            );
            System.out.println(
                "Table created with column charset = " + charset
            );
        }
    }

    private static void insertRow(Connection conn, String text)
        throws SQLException {
        try (
            PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO umlaut_test (name) VALUES (?)"
            )
        ) {
            ps.setString(1, text);
            ps.executeUpdate();
            System.out.println("Inserted: " + text);
        }
    }

    private static void readWithGetString(Connection conn) throws SQLException {
        try (
            Statement st = conn.createStatement();
            ResultSet rs = st.executeQuery("SELECT id, name FROM umlaut_test")
        ) {
            while (rs.next()) {
                System.out.println(
                    "  getString()  -> [" + rs.getString("name") + "]"
                );
            }
        }
    }

    private static void readWithGetNString(Connection conn)
        throws SQLException {
        try (
            Statement st = conn.createStatement();
            ResultSet rs = st.executeQuery("SELECT id, name FROM umlaut_test")
        ) {
            while (rs.next()) {
                // getNString() is what Hibernate 5.6 / Keycloak 18 calls for NVARCHAR columns.
                // On a latin1 column with Connector/J 8, this throws:
                // "Can not call getNString() when field's charset isn't UTF-8"
                System.out.println(
                    "  getNString() -> [" + rs.getNString("name") + "]"
                );
            }
        }
    }

    private static void dropTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS umlaut_test");
        }
    }
}
