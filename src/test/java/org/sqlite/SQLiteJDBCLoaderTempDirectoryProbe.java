package org.sqlite;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/** Child process entry point for SQLiteJDBCLoaderTempDirectoryTest. */
public final class SQLiteJDBCLoaderTempDirectoryProbe {
    private SQLiteJDBCLoaderTempDirectoryProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected one probe mode");
        }

        if ("guard-control".equals(args[0])) {
            Files.newDirectoryStream(new File(System.getProperty("java.io.tmpdir")).toPath()).close();
            throw new AssertionError("The provider guard did not reject the temporary root");
        }
        if (!"jdbc".equals(args[0])) {
            throw new IllegalArgumentException("Unknown probe mode: " + args[0]);
        }

        SQLiteJDBCLoader.initialize();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT 1")) {
            if (!result.next() || result.getInt(1) != 1) {
                throw new AssertionError("SQLite returned an unexpected SELECT 1 result");
            }
        }
        SQLiteJDBCLoader.initialize();
        System.out.println("JDBC_STARTUP_OK");
    }
}
