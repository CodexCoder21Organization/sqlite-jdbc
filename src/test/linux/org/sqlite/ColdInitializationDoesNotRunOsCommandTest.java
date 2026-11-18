package org.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Observes commands received by a real executable during a fresh public JDBC connection. */
public class ColdInitializationDoesNotRunOsCommandTest {
    @Test
    void coldConnectionDoesNotRunOsCommand(@TempDir Path directory) throws Exception {
        List<Process> children = new ArrayList<>();
        try {
            Path bin = Files.createDirectory(directory.resolve("bin"));
            Path calls = directory.resolve("uname-calls.txt");
            Path uname = bin.resolve("uname");
            String quotedCalls = "'" + calls.toString().replace("'", "'\"'\"'") + "'";
            Files.write(
                    uname,
                    ("#!/bin/sh\nprintf '%s\\n' \"uname $*\" >> "
                                    + quotedCalls
                                    + "\nexec /usr/bin/uname \"$@\"\n")
                            .getBytes(StandardCharsets.UTF_8));
            Files.setPosixFilePermissions(uname, PosixFilePermissions.fromString("rwx------"));

            Path controlOutput = directory.resolve("control.log");
            Process control =
                    new ProcessBuilder(uname.toString(), "-o")
                            .redirectErrorStream(true)
                            .redirectOutput(controlOutput.toFile())
                            .start();
            children.add(control);
            assertTrue(
                    control.waitFor(5, TimeUnit.SECONDS),
                    "The uname observer control did not exit");
            assertEquals(
                    0,
                    control.exitValue(),
                    new String(Files.readAllBytes(controlOutput), StandardCharsets.UTF_8));
            assertEquals(
                    "uname -o\n",
                    new String(Files.readAllBytes(calls), StandardCharsets.UTF_8),
                    "The observer missed its real control call");
            Files.delete(calls);

            String classpath =
                    System.getProperty(
                            "surefire.test.class.path", System.getProperty("java.class.path"));
            Path output = directory.resolve("jdbc.log");
            ProcessBuilder builder =
                    new ProcessBuilder(
                            Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                            "-Xmx128m",
                            "-Djava.io.tmpdir="
                                    + Files.createDirectory(directory.resolve("native-tmp")),
                            "-Djava.library.path=",
                            "-cp",
                            classpath,
                            JdbcStartup.class.getName());
            builder.environment().put("PATH", bin.toString());
            builder.environment().remove("JAVA_TOOL_OPTIONS");
            builder.environment().remove("_JAVA_OPTIONS");
            builder.environment().remove("JDK_JAVA_OPTIONS");
            Process child =
                    builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
            children.add(child);
            assertTrue(
                    child.waitFor(20, TimeUnit.SECONDS),
                    "The public JDBC connection did not finish in twenty seconds");
            String text = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
            assertEquals(0, child.exitValue(), text);
            assertEquals("JDBC_STARTUP_OK\n", text);
            String received =
                    Files.exists(calls)
                            ? new String(Files.readAllBytes(calls), StandardCharsets.UTF_8)
                            : "";
            System.out.println("JDBC_OS_COMMAND_BOUNDARY_CALLS " + received.replace("\n", "\\n"));
            assertEquals(
                    "",
                    received,
                    "Cold JDBC initialization must not launch an external OS-detection command; "
                            + "the real uname executable received: "
                            + received.replace("\n", "\\n"));
        } finally {
            for (Process child : children) {
                if (child.isAlive()) child.destroyForcibly();
                assertTrue(
                        child.waitFor(5, TimeUnit.SECONDS),
                        "Could not collect the JDBC or observer subprocess after termination");
            }
        }
    }

    public static final class JdbcStartup {
        public static void main(String[] args) throws Exception {
            try (Connection connection =
                            new JDBC().connect("jdbc:sqlite::memory:", new Properties());
                    Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT 1")) {
                if (!rows.next() || rows.getInt(1) != 1 || rows.next()) {
                    throw new AssertionError(
                            "The real SQLite connection must return exactly one row containing 1");
                }
            }
            System.out.println("JDBC_STARTUP_OK");
        }
    }
}
