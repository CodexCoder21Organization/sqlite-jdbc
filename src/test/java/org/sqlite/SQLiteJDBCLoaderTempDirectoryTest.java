package org.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies SQLite startup does not enumerate unrelated files in the JVM temporary directory. */
public class SQLiteJDBCLoaderTempDirectoryTest {
    private static final String GUARD_MESSAGE =
            "SQLite startup enumerated the unrelated temporary root: ";
    private static final long CHILD_TIMEOUT_SECONDS = 30;

    @Test
    public void providerControlRejectsOnlyTemporaryRootEnumeration(@TempDir Path tempRoot)
            throws Exception {
        ChildResult result = runChild("guard-control", tempRoot, null);

        assertThat(result.exitCode)
                .as("The public filesystem-provider guard must reject the controlled enumeration.\n%s", result.output)
                .isEqualTo(1);
        assertThat(result.output)
                .as("The control must fail at the exact filesystem boundary.\n%s", result.output)
                .contains(GUARD_MESSAGE + tempRoot.toAbsolutePath().normalize());
    }

    @Test
    public void defaultJdbcStartupDoesNotEnumerateTemporaryRoot(@TempDir Path tempRoot)
            throws Exception {
        Files.write(tempRoot.resolve("unrelated-sentinel.txt"), new byte[] {1});

        ChildResult result = runChild("jdbc", tempRoot, null);

        assertThat(result.exitCode)
                .as("Default JDBC startup must succeed without enumerating java.io.tmpdir.\n%s", result.output)
                .isZero();
        assertThat(result.output).contains("JDBC_STARTUP_OK");
        assertThat(Files.readAllBytes(tempRoot.resolve("unrelated-sentinel.txt")))
                .containsExactly((byte) 1);
    }

    @Test
    public void defaultDirectoryCleanupRemainsStableAcrossFreshJvms(@TempDir Path tempRoot)
            throws Exception {
        ChildResult firstStart = runChild("jdbc", tempRoot, null);
        assertThat(firstStart.exitCode)
                .as("The first default-directory startup must succeed.\n%s", firstStart.output)
                .isZero();
        assertThat(firstStart.output).contains("JDBC_STARTUP_OK");

        List<Path> childDirectories = childDirectories(tempRoot);
        assertThat(childDirectories)
                .as("Default startup should create one child directory under java.io.tmpdir")
                .hasSize(1);
        Path nativeDirectory = childDirectories.get(0);
        String prefix = "sqlite-" + SQLiteJDBCLoader.getVersion();
        Path staleNativeFile = nativeDirectory.resolve(prefix + "-stale-restart-test.so");
        Path lockedNativeFile = nativeDirectory.resolve(prefix + "-locked-restart-test.so");
        Path lockFile = nativeDirectory.resolve(prefix + "-locked-restart-test.so.lck");
        Path unrelatedFile = nativeDirectory.resolve("unrelated-restart-test.so");
        Files.write(staleNativeFile, new byte[] {1});
        Files.write(lockedNativeFile, new byte[] {2});
        Files.write(lockFile, new byte[] {3});
        Files.write(unrelatedFile, new byte[] {4});

        ChildResult secondStart = runChild("jdbc", tempRoot, null);
        assertThat(secondStart.exitCode)
                .as("A fresh JVM should reuse the default directory and keep cleanup rules.\n%s", secondStart.output)
                .isZero();
        assertThat(secondStart.output).contains("JDBC_STARTUP_OK");
        assertThat(staleNativeFile).doesNotExist();
        assertThat(lockedNativeFile).exists();
        assertThat(lockFile).exists();
        assertThat(unrelatedFile).exists();
    }

    @Test
    public void defaultDirectorySeparatesUserNamesAndReusesEachDirectory(@TempDir Path tempRoot)
            throws Exception {
        ChildResult firstUser = runChild("jdbc", tempRoot, null, "first/user");
        assertThat(firstUser.exitCode)
                .as("The first user should be able to initialize SQLite.\n%s", firstUser.output)
                .isZero();
        List<Path> afterFirstUser = childDirectories(tempRoot);
        assertThat(afterFirstUser).hasSize(1);

        ChildResult secondUser = runChild("jdbc", tempRoot, null, "second\\user");
        assertThat(secondUser.exitCode)
                .as("A distinct user name should get a writable SQLite directory.\n%s", secondUser.output)
                .isZero();
        List<Path> afterSecondUser = childDirectories(tempRoot);
        assertThat(afterSecondUser).hasSize(2);

        ChildResult firstUserAgain = runChild("jdbc", tempRoot, null, "first/user");
        assertThat(firstUserAgain.exitCode)
                .as("A fresh JVM for the first user should reuse its SQLite directory.\n%s", firstUserAgain.output)
                .isZero();
        assertThat(childDirectories(tempRoot)).hasSize(2);
    }

    @Test
    public void explicitDirectoryKeepsCleanupRulesAndRepeatedInitialization(@TempDir Path tempRoot)
            throws Exception {
        Path explicitDirectory = Files.createDirectory(tempRoot.resolve("sqlite-native"));
        String prefix = "sqlite-" + SQLiteJDBCLoader.getVersion();
        Path staleNativeFile = explicitDirectory.resolve(prefix + "-stale-test.so");
        Path lockedNativeFile = explicitDirectory.resolve(prefix + "-locked-test.so");
        Path lockFile = explicitDirectory.resolve(prefix + "-locked-test.so.lck");
        Path unrelatedFile = explicitDirectory.resolve("unrelated-native-test.so");
        Files.write(staleNativeFile, new byte[] {1});
        Files.write(lockedNativeFile, new byte[] {2});
        Files.write(lockFile, new byte[] {3});
        Files.write(unrelatedFile, new byte[] {4});

        ChildResult result = runChild("jdbc", tempRoot, explicitDirectory);

        assertThat(result.exitCode)
                .as("Explicit native directory startup must succeed and initialize repeatedly.\n%s", result.output)
                .isZero();
        assertThat(result.output).contains("JDBC_STARTUP_OK");
        assertThat(staleNativeFile).doesNotExist();
        assertThat(lockedNativeFile).exists();
        assertThat(lockFile).exists();
        assertThat(unrelatedFile).exists();
    }

    private static ChildResult runChild(String mode, Path tempRoot, Path explicitDirectory)
            throws IOException, InterruptedException {
        return runChild(mode, tempRoot, explicitDirectory, null);
    }

    private static ChildResult runChild(
            String mode, Path tempRoot, Path explicitDirectory, String userName)
            throws IOException, InterruptedException {
        Path outputFile = Files.createTempFile(tempRoot, "sqlite-startup-child-", ".log");
        List<String> command = new ArrayList<>();
        command.add(new File(System.getProperty("java.home"), "bin/java").getAbsolutePath());
        command.add("-Xmx128m");
        command.add("-XX:ActiveProcessorCount=1");
        command.add("-XX:ParallelGCThreads=1");
        command.add("-XX:ConcGCThreads=1");
        command.add("-XX:CICompilerCount=1");
        command.add("-XX:TieredStopAtLevel=1");
        command.add("-Djava.io.tmpdir=" + tempRoot.toAbsolutePath().normalize());
        command.add("-Dorg.sqlite.test.forbiddenDirectory=" + tempRoot.toAbsolutePath().normalize());
        command.add("-Djava.nio.file.spi.DefaultFileSystemProvider=org.sqlite.GuardedFileSystemProvider");
        if (explicitDirectory != null) {
            command.add("-Dorg.sqlite.tmpdir=" + explicitDirectory.toAbsolutePath().normalize());
        }
        if (userName != null) {
            command.add("-Duser.name=" + userName);
        }
        String classPath = System.getProperty("surefire.test.class.path");
        if (classPath == null || classPath.trim().isEmpty()) {
            classPath = System.getProperty("java.class.path");
        }
        command.add("-cp");
        command.add(classPath);
        command.add("org.sqlite.SQLiteJDBCLoaderTempDirectoryProbe");
        command.add(mode);

        Process process =
                new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(outputFile.toFile())
                        .start();
        boolean completed = false;
        boolean terminated;
        InterruptedException interrupted = null;
        try {
            try {
                completed = process.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException waitInterrupted) {
                interrupted = waitInterrupted;
            }
        } finally {
            if (!completed && process.isAlive()) {
                process.destroyForcibly();
                long terminationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (process.isAlive()) {
                    long remainingNanos = terminationDeadline - System.nanoTime();
                    if (remainingNanos <= 0) {
                        break;
                    }
                    try {
                        process.waitFor(remainingNanos, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException cleanupInterrupted) {
                        if (interrupted == null) {
                            interrupted = cleanupInterrupted;
                        } else {
                            interrupted.addSuppressed(cleanupInterrupted);
                        }
                    }
                }
            }
            terminated = !process.isAlive();
        }
        String output = new String(Files.readAllBytes(outputFile), StandardCharsets.UTF_8);
        if (interrupted != null) {
            interrupted.addSuppressed(
                    new IllegalStateException(
                            "Child wait was interrupted; terminated=" + terminated + "; output:\n" + output));
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        assertThat(terminated)
                .as("Child process did not terminate after forced shutdown: %s\n%s", command, output)
                .isTrue();
        assertThat(completed)
                .as("Child process exceeded %s seconds. Command: %s\n%s", CHILD_TIMEOUT_SECONDS, command, output)
                .isTrue();
        return new ChildResult(process.exitValue(), output);
    }

    private static List<Path> childDirectories(Path tempRoot) throws IOException {
        try (Stream<Path> entries = Files.list(tempRoot)) {
            return entries.filter(Files::isDirectory).collect(Collectors.toList());
        }
    }

    private static final class ChildResult {
        private final int exitCode;
        private final String output;

        private ChildResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
