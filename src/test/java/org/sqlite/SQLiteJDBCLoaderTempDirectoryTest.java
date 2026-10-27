package org.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
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
                .as(
                        "The public filesystem-provider guard must reject the controlled enumeration.\n%s",
                        result.output)
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
                .as(
                        "Default JDBC startup must succeed without enumerating java.io.tmpdir.\n%s",
                        result.output)
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
                .as(
                        "A fresh JVM should reuse the default directory and keep cleanup rules.\n%s",
                        secondStart.output)
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
                .as(
                        "A distinct user name should get a writable SQLite directory.\n%s",
                        secondUser.output)
                .isZero();
        List<Path> afterSecondUser = childDirectories(tempRoot);
        assertThat(afterSecondUser).hasSize(2);

        ChildResult firstUserAgain = runChild("jdbc", tempRoot, null, "first/user");
        assertThat(firstUserAgain.exitCode)
                .as(
                        "A fresh JVM for the first user should reuse its SQLite directory.\n%s",
                        firstUserAgain.output)
                .isZero();
        assertThat(childDirectories(tempRoot)).hasSize(2);
    }

    @Test
    public void defaultDirectoryDoesNotFollowPreExistingSymlink(@TempDir Path tempRoot)
            throws Exception {
        ChildResult firstStart = runChild("jdbc", tempRoot, null);
        assertThat(firstStart.exitCode)
                .as("The first default-directory startup must succeed.\n%s", firstStart.output)
                .isZero();
        assertThat(firstStart.output).contains("JDBC_STARTUP_OK");

        List<Path> directories = childDirectories(tempRoot);
        assertThat(directories)
                .as("The first startup should create one default directory below java.io.tmpdir")
                .hasSize(1);
        Path defaultDirectory = directories.get(0);
        Path targetDirectory =
                Files.createTempDirectory(tempRoot.getParent(), "sqlite-jdbc-symlink-target-");
        byte[] sentinelBytes = new byte[] {83, 89, 77, 76, 73, 78, 75};
        Path sentinel =
                targetDirectory.resolve(
                        "sqlite-" + SQLiteJDBCLoader.getVersion() + "-redirect-sentinel.so");

        try {
            // Remove any native files the child left behind before replacing its directory.
            clearDirectory(defaultDirectory);
            Files.delete(defaultDirectory);
            Files.write(sentinel, sentinelBytes);
            List<Path> targetEntriesBefore = directoryEntries(targetDirectory);
            assertThat(targetEntriesBefore).containsExactly(sentinel);

            Files.createSymbolicLink(defaultDirectory, targetDirectory);
            assertThat(Files.isSymbolicLink(defaultDirectory)).isTrue();

            ChildResult secondStart = runChild("jdbc", tempRoot, null);
            String refusal =
                    "Cannot use default native library directory '"
                            + defaultDirectory
                            + "': expected a directory without symbolic links.";
            assertExpectedDefaultPathOutcome(secondStart, refusal);

            assertThat(sentinel)
                    .as(
                            "SQLite startup must preserve the sentinel file in symlink target: %s. Child output:\n%s",
                            targetDirectory, secondStart.output)
                    .exists();
            assertThat(Files.readAllBytes(sentinel))
                    .as(
                            "SQLite startup must preserve sentinel bytes in symlink target: %s. Child output:\n%s",
                            targetDirectory, secondStart.output)
                    .containsExactly(sentinelBytes);
            assertThat(directoryEntries(targetDirectory))
                    .as("SQLite startup must not add native files in the symlink target")
                    .containsExactlyElementsOf(targetEntriesBefore);
        } finally {
            // Remove the link itself before deleting the sibling target directory.
            if (Files.isSymbolicLink(defaultDirectory)) {
                Files.delete(defaultDirectory);
            }
            deleteTree(targetDirectory);
        }
    }

    @Test
    public void explicitDirectorySymlinkRemainsSupported(@TempDir Path tempRoot) throws Exception {
        Path targetDirectory = Files.createDirectory(tempRoot.resolve("explicit-symlink-target"));
        Path explicitDirectory = tempRoot.resolve("explicit-native-directory");
        Files.createSymbolicLink(explicitDirectory, targetDirectory);

        String prefix = "sqlite-" + SQLiteJDBCLoader.getVersion();
        Path staleNativeFile = targetDirectory.resolve(prefix + "-explicit-symlink-stale.so");
        Path unrelatedFile = targetDirectory.resolve("unrelated-explicit-symlink.so");
        byte[] unrelatedBytes = new byte[] {4, 5, 6};
        Files.write(staleNativeFile, new byte[] {1});
        Files.write(unrelatedFile, unrelatedBytes);

        try {
            ChildResult result = runChild("jdbc", tempRoot, explicitDirectory);
            assertThat(result.exitCode)
                    .as(
                            "An explicit native directory symlink should remain supported.\n%s",
                            result.output)
                    .isZero();
            assertThat(result.output).contains("JDBC_STARTUP_OK");
            assertThat(Files.isSymbolicLink(explicitDirectory)).isTrue();
            assertThat(staleNativeFile).doesNotExist();
            assertThat(Files.readAllBytes(unrelatedFile)).containsExactly(unrelatedBytes);
        } finally {
            if (Files.isSymbolicLink(explicitDirectory)) {
                Files.delete(explicitDirectory);
            }
            deleteTree(targetDirectory);
        }
    }

    @Test
    public void defaultDirectoryRegularFileIsPreservedAndRefused(@TempDir Path tempRoot)
            throws Exception {
        ChildResult firstStart = runChild("jdbc", tempRoot, null);
        assertThat(firstStart.exitCode)
                .as("The first default-directory startup must succeed.\n%s", firstStart.output)
                .isZero();
        assertThat(firstStart.output).contains("JDBC_STARTUP_OK");

        List<Path> directories = childDirectories(tempRoot);
        assertThat(directories)
                .as("The first startup should create one default directory below java.io.tmpdir")
                .hasSize(1);
        Path defaultPath = directories.get(0);
        clearDirectory(defaultPath);
        Files.delete(defaultPath);
        byte[] sentinelBytes = new byte[] {70, 73, 76, 69};
        Files.write(defaultPath, sentinelBytes);

        try {
            ChildResult secondStart = runChild("jdbc", tempRoot, null);
            String refusal =
                    "Cannot use default native library directory '"
                            + defaultPath
                            + "': expected a directory without symbolic links.";
            assertExpectedDefaultPathOutcome(secondStart, refusal);
            assertThat(defaultPath).isRegularFile();
            assertThat(Files.readAllBytes(defaultPath)).containsExactly(sentinelBytes);
        } finally {
            if (Files.isRegularFile(defaultPath, LinkOption.NOFOLLOW_LINKS)) {
                Files.delete(defaultPath);
            }
        }
    }

    @Test
    public void newlyCreatedDefaultDirectoryIsOwnerOnly(@TempDir Path tempRoot) throws Exception {
        ChildResult result = runChild("jdbc", tempRoot, null);
        assertThat(result.exitCode)
                .as("The first default-directory startup must succeed.\n%s", result.output)
                .isZero();
        assertThat(result.output).contains("JDBC_STARTUP_OK");

        List<Path> directories = childDirectories(tempRoot);
        assertThat(directories)
                .as("The first startup should create one default directory below java.io.tmpdir")
                .hasSize(1);
        Path defaultDirectory = directories.get(0);
        assertThat(
                        PosixFilePermissions.toString(
                                Files.getPosixFilePermissions(
                                        defaultDirectory, LinkOption.NOFOLLOW_LINKS)))
                .as(
                        "A default directory created by the driver must be accessible only by its owner: %s",
                        defaultDirectory)
                .isEqualTo("rwx------");
    }

    @Test
    public void groupWritableDefaultDirectoryIsRefused(@TempDir Path tempRoot) throws Exception {
        assertSharedWritableDefaultDirectoryIsRefused(tempRoot, "rwxrwx---");
    }

    @Test
    public void worldWritableDefaultDirectoryIsRefused(@TempDir Path tempRoot) throws Exception {
        assertSharedWritableDefaultDirectoryIsRefused(tempRoot, "rwx---rwx");
    }

    private static void assertSharedWritableDefaultDirectoryIsRefused(
            Path tempRoot, String sharedPermissions) throws Exception {
        ChildResult firstStart = runChild("jdbc", tempRoot, null);
        assertThat(firstStart.exitCode)
                .as("The first default-directory startup must succeed.\n%s", firstStart.output)
                .isZero();
        assertThat(firstStart.output).contains("JDBC_STARTUP_OK");

        List<Path> directories = childDirectories(tempRoot);
        assertThat(directories)
                .as("The first startup should create one default directory below java.io.tmpdir")
                .hasSize(1);
        Path defaultDirectory = directories.get(0);
        clearDirectory(defaultDirectory);
        byte[] sentinelBytes = new byte[] {83, 72, 65, 82, 69, 68};
        Path staleNativeFile =
                defaultDirectory.resolve(
                        "sqlite-" + SQLiteJDBCLoader.getVersion() + "-shared-directory-stale.so");
        Files.write(staleNativeFile, sentinelBytes);
        Set<PosixFilePermission> permissions = PosixFilePermissions.fromString(sharedPermissions);
        Files.setPosixFilePermissions(defaultDirectory, permissions);
        UserPrincipal owner = Files.getOwner(defaultDirectory, LinkOption.NOFOLLOW_LINKS);
        List<Path> entriesBefore = directoryEntries(defaultDirectory);

        ChildResult secondStart = runChild("jdbc", tempRoot, null);

        String refusal =
                "Cannot use default native library directory '"
                        + defaultDirectory
                        + "': expected a directory owned by the current user that is not writable"
                        + " by group or others, but found owner '"
                        + owner.getName()
                        + "' and permissions "
                        + sharedPermissions
                        + ".";
        assertThat(secondStart.output)
                .as("The child must report why the shared directory was refused.")
                .contains(refusal);
        assertExpectedDefaultPathOutcome(secondStart, refusal);
        assertThat(directoryEntries(defaultDirectory))
                .as(
                        "SQLite startup must neither clean up nor extract into a %s directory. Child output:\n%s",
                        sharedPermissions, secondStart.output)
                .containsExactlyElementsOf(entriesBefore);
        assertThat(Files.readAllBytes(staleNativeFile)).containsExactly(sentinelBytes);
        assertThat(
                        PosixFilePermissions.toString(
                                Files.getPosixFilePermissions(
                                        defaultDirectory, LinkOption.NOFOLLOW_LINKS)))
                .as("SQLite startup must not change the permissions of a refused directory")
                .isEqualTo(sharedPermissions);
    }

    @Test
    public void defaultDirectoryOwnedByAnotherAccountIsRefused(@TempDir Path tempRoot)
            throws Exception {
        ChildResult firstStart = runChild("jdbc", tempRoot, null);
        assertThat(firstStart.exitCode)
                .as("The first default-directory startup must succeed.\n%s", firstStart.output)
                .isZero();
        assertThat(firstStart.output).contains("JDBC_STARTUP_OK");

        List<Path> directories = childDirectories(tempRoot);
        assertThat(directories)
                .as("The first startup should create one default directory below java.io.tmpdir")
                .hasSize(1);
        Path defaultDirectory = directories.get(0);
        clearDirectory(defaultDirectory);
        byte[] sentinelBytes = new byte[] {79, 87, 78, 69, 82};
        Path staleNativeFile =
                defaultDirectory.resolve(
                        "sqlite-" + SQLiteJDBCLoader.getVersion() + "-foreign-owner-stale.so");
        Files.write(staleNativeFile, sentinelBytes);
        Files.setPosixFilePermissions(
                defaultDirectory, PosixFilePermissions.fromString("rwx------"));
        List<Path> entriesBefore = directoryEntries(defaultDirectory);

        ChildResult secondStart =
                runChild(
                        "jdbc",
                        tempRoot,
                        null,
                        null,
                        "-Dorg.sqlite.test.foreignOwnerDirectory=" + defaultDirectory);

        String refusal =
                "Cannot use default native library directory '"
                        + defaultDirectory
                        + "': expected a directory owned by the current user that is not writable"
                        + " by group or others, but found owner '"
                        + GuardedFileSystemProvider.FOREIGN_OWNER_NAME
                        + "' and permissions rwx------.";
        assertThat(secondStart.output)
                .as("The child must report why the other account's directory was refused.")
                .contains(refusal);
        assertExpectedDefaultPathOutcome(secondStart, refusal);
        assertThat(directoryEntries(defaultDirectory))
                .as(
                        "SQLite startup must neither clean up nor extract into another account's directory. Child output:\n%s",
                        secondStart.output)
                .containsExactlyElementsOf(entriesBefore);
        assertThat(Files.readAllBytes(staleNativeFile)).containsExactly(sentinelBytes);
        assertThat(
                        PosixFilePermissions.toString(
                                Files.getPosixFilePermissions(
                                        defaultDirectory, LinkOption.NOFOLLOW_LINKS)))
                .as("SQLite startup must not change the permissions of a refused directory")
                .isEqualTo("rwx------");
    }

    @Test
    public void reusingDefaultDirectoryLeavesNoOwnerCheckFilesInTemporaryRoot(
            @TempDir Path tempRoot) throws Exception {
        ChildResult firstStart = runChild("jdbc", tempRoot, null);
        assertThat(firstStart.exitCode)
                .as("The first default-directory startup must succeed.\n%s", firstStart.output)
                .isZero();
        List<Path> directories = childDirectories(tempRoot);
        assertThat(directories).hasSize(1);
        Path defaultDirectory = directories.get(0);
        List<Path> rootEntriesBefore = directoryEntries(tempRoot);

        ChildResult secondStart = runChild("jdbc", tempRoot, null);
        assertThat(secondStart.exitCode)
                .as(
                        "A fresh JVM should reuse the owner-only default directory.\n%s",
                        secondStart.output)
                .isZero();
        assertThat(secondStart.output).contains("JDBC_STARTUP_OK");

        List<Path> addedRootEntries = directoryEntries(tempRoot);
        addedRootEntries.removeAll(rootEntriesBefore);
        assertThat(addedRootEntries)
                .as(
                        "Reusing %s must leave only the child log in java.io.tmpdir. Child output:\n%s",
                        defaultDirectory, secondStart.output)
                .hasSize(1)
                .allSatisfy(
                        entry ->
                                assertThat(entry.getFileName().toString())
                                        .startsWith("sqlite-startup-child-"));
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
                .as(
                        "Explicit native directory startup must succeed and initialize repeatedly.\n%s",
                        result.output)
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
            String mode,
            Path tempRoot,
            Path explicitDirectory,
            String userName,
            String... extraJvmOptions)
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
        command.add(
                "-Dorg.sqlite.test.forbiddenDirectory=" + tempRoot.toAbsolutePath().normalize());
        command.add(
                "-Djava.nio.file.spi.DefaultFileSystemProvider=org.sqlite.GuardedFileSystemProvider");
        if (explicitDirectory != null) {
            command.add("-Dorg.sqlite.tmpdir=" + explicitDirectory.toAbsolutePath().normalize());
        }
        if (userName != null) {
            command.add("-Duser.name=" + userName);
        }
        for (String option : extraJvmOptions) {
            command.add(option);
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
                            "Child wait was interrupted; terminated="
                                    + terminated
                                    + "; output:\n"
                                    + output));
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        assertThat(terminated)
                .as(
                        "Child process did not terminate after forced shutdown: %s\n%s",
                        command, output)
                .isTrue();
        assertThat(completed)
                .as(
                        "Child process exceeded %s seconds. Command: %s\n%s",
                        CHILD_TIMEOUT_SECONDS, command, output)
                .isTrue();
        return new ChildResult(process.exitValue(), output);
    }

    private static List<Path> childDirectories(Path tempRoot) throws IOException {
        try (Stream<Path> entries = Files.list(tempRoot)) {
            return entries.filter(Files::isDirectory).collect(Collectors.toList());
        }
    }

    private static void assertExpectedDefaultPathOutcome(ChildResult result, String refusal) {
        boolean started = result.exitCode == 0 && result.output.contains("JDBC_STARTUP_OK");
        boolean refusedWithoutFallback =
                result.exitCode == 1
                        && result.output.contains(refusal)
                        && result.output.contains(
                                "Exception in thread \"main\" org.sqlite.NativeLibraryNotFoundException:");
        assertThat(started || refusedWithoutFallback)
                .as(
                        "The child must either complete JDBC startup or end with the expected refusal and native-library failure.\n%s",
                        result.output)
                .isTrue();
    }

    private static List<Path> directoryEntries(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.collect(Collectors.toList());
        }
    }

    private static void clearDirectory(Path directory) throws IOException {
        for (Path entry : directoryEntries(directory)) {
            Files.delete(entry);
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> entries = Files.walk(directory)) {
            for (Path entry :
                    entries.sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                            .collect(Collectors.toList())) {
                Files.deleteIfExists(entry);
            }
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
