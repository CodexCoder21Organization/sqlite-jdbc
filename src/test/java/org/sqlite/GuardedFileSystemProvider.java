package org.sqlite;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Default-provider decorator that denies only enumeration of the configured unrelated temp root.
 */
public final class GuardedFileSystemProvider extends FileSystemProvider {
    private final FileSystemProvider delegate;
    private final GuardFileSystem fileSystem;

    /** Invoked by the JDK default-provider SPI with the system provider. */
    public GuardedFileSystemProvider(FileSystemProvider delegate) {
        this.delegate = delegate;
        this.fileSystem = wrapFileSystem(delegate.getFileSystem(URI.create("file:///")));
    }

    private GuardFileSystem wrapFileSystem(FileSystem fileSystem) {
        if (fileSystem instanceof GuardFileSystem) return (GuardFileSystem) fileSystem;
        return new GuardFileSystem(this, fileSystem);
    }

    private Path unwrap(Path path) {
        return path instanceof GuardPath ? ((GuardPath) path).delegate : path;
    }

    private GuardPath wrap(Path path) {
        return path == null ? null : new GuardPath(fileSystem, path);
    }

    private String forbiddenDirectory() {
        String configured = System.getProperty("org.sqlite.test.forbiddenDirectory");
        if (configured == null) return null;
        return fileSystem.delegate.getPath(configured).toAbsolutePath().normalize().toString();
    }

    @Override
    public String getScheme() {
        return delegate.getScheme();
    }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
        return wrapFileSystem(delegate.newFileSystem(uri, env));
    }

    @Override
    public FileSystem getFileSystem(URI uri) {
        if ("file".equalsIgnoreCase(uri.getScheme())) return fileSystem;
        return wrapFileSystem(delegate.getFileSystem(uri));
    }

    @Override
    public Path getPath(URI uri) {
        return wrap(delegate.getPath(uri));
    }

    @Override
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        return delegate.newInputStream(unwrap(path), options);
    }

    @Override
    public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        return delegate.newOutputStream(unwrap(path), options);
    }

    @Override
    public FileChannel newFileChannel(
            Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs)
            throws IOException {
        return delegate.newFileChannel(unwrap(path), options, attrs);
    }

    @Override
    public AsynchronousFileChannel newAsynchronousFileChannel(
            Path path,
            Set<? extends OpenOption> options,
            ExecutorService executor,
            FileAttribute<?>... attrs)
            throws IOException {
        return delegate.newAsynchronousFileChannel(unwrap(path), options, executor, attrs);
    }

    @Override
    public SeekableByteChannel newByteChannel(
            Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs)
            throws IOException {
        return delegate.newByteChannel(unwrap(path), options, attrs);
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(
            Path path, DirectoryStream.Filter<? super Path> filter) throws IOException {
        Path rawPath = unwrap(path);
        String forbidden = forbiddenDirectory();
        String actual = rawPath.toAbsolutePath().normalize().toString();
        if (forbidden != null && forbidden.equals(actual)) {
            throw new AssertionError(
                    "SQLite startup enumerated the unrelated temporary root: " + actual);
        }

        DirectoryStream<Path> stream =
                delegate.newDirectoryStream(rawPath, entry -> filter.accept(wrap(entry)));
        return new DirectoryStream<Path>() {
            @Override
            public Iterator<Path> iterator() {
                Iterator<Path> iterator = stream.iterator();
                return new Iterator<Path>() {
                    @Override
                    public boolean hasNext() {
                        return iterator.hasNext();
                    }

                    @Override
                    public Path next() {
                        return wrap(iterator.next());
                    }

                    @Override
                    public void remove() {
                        iterator.remove();
                    }
                };
            }

            @Override
            public void close() throws IOException {
                stream.close();
            }
        };
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        delegate.createDirectory(unwrap(dir), attrs);
    }

    @Override
    public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attrs)
            throws IOException {
        delegate.createSymbolicLink(unwrap(link), unwrap(target), attrs);
    }

    @Override
    public void createLink(Path link, Path existing) throws IOException {
        delegate.createLink(unwrap(link), unwrap(existing));
    }

    @Override
    public void delete(Path path) throws IOException {
        delegate.delete(unwrap(path));
    }

    @Override
    public boolean deleteIfExists(Path path) throws IOException {
        return delegate.deleteIfExists(unwrap(path));
    }

    @Override
    public Path readSymbolicLink(Path link) throws IOException {
        return wrap(delegate.readSymbolicLink(unwrap(link)));
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        delegate.copy(unwrap(source), unwrap(target), options);
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        delegate.move(unwrap(source), unwrap(target), options);
    }

    @Override
    public boolean isSameFile(Path path, Path path2) throws IOException {
        return delegate.isSameFile(unwrap(path), unwrap(path2));
    }

    @Override
    public boolean isHidden(Path path) throws IOException {
        return delegate.isHidden(unwrap(path));
    }

    @Override
    public FileStore getFileStore(Path path) throws IOException {
        return delegate.getFileStore(unwrap(path));
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        delegate.checkAccess(unwrap(path), modes);
    }

    @Override
    public <V extends FileAttributeView> V getFileAttributeView(
            Path path, Class<V> type, LinkOption... options) {
        return delegate.getFileAttributeView(unwrap(path), type, options);
    }

    @Override
    public <A extends BasicFileAttributes> A readAttributes(
            Path path, Class<A> type, LinkOption... options) throws IOException {
        return delegate.readAttributes(unwrap(path), type, options);
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
            throws IOException {
        return delegate.readAttributes(unwrap(path), attributes, options);
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options)
            throws IOException {
        delegate.setAttribute(unwrap(path), attribute, value, options);
    }

    private static final class GuardFileSystem extends FileSystem {
        private final GuardedFileSystemProvider provider;
        private final FileSystem delegate;

        private GuardFileSystem(GuardedFileSystemProvider provider, FileSystem delegate) {
            this.provider = provider;
            this.delegate = delegate;
        }

        @Override
        public FileSystemProvider provider() {
            return provider;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public boolean isReadOnly() {
            return delegate.isReadOnly();
        }

        @Override
        public String getSeparator() {
            return delegate.getSeparator();
        }

        @Override
        public Iterable<Path> getRootDirectories() {
            List<Path> roots = new ArrayList<>();
            for (Path root : delegate.getRootDirectories()) roots.add(provider.wrap(root));
            return roots;
        }

        @Override
        public Iterable<FileStore> getFileStores() {
            return delegate.getFileStores();
        }

        @Override
        public Set<String> supportedFileAttributeViews() {
            return delegate.supportedFileAttributeViews();
        }

        @Override
        public Path getPath(String first, String... more) {
            return provider.wrap(delegate.getPath(first, more));
        }

        @Override
        public PathMatcher getPathMatcher(String syntaxAndPattern) {
            PathMatcher matcher = delegate.getPathMatcher(syntaxAndPattern);
            return path -> matcher.matches(provider.unwrap(path));
        }

        @Override
        public UserPrincipalLookupService getUserPrincipalLookupService() {
            return delegate.getUserPrincipalLookupService();
        }

        @Override
        public WatchService newWatchService() throws IOException {
            return delegate.newWatchService();
        }

        @Override
        public String toString() {
            return "GuardFileSystem[" + delegate + "]";
        }
    }

    private static final class GuardPath implements Path {
        private final GuardFileSystem fileSystem;
        private final Path delegate;

        private GuardPath(GuardFileSystem fileSystem, Path delegate) {
            this.fileSystem = fileSystem;
            this.delegate = delegate;
        }

        private Path raw(Path path) {
            return fileSystem.provider.unwrap(path);
        }

        private Path wrapped(Path path) {
            return fileSystem.provider.wrap(path);
        }

        @Override
        public FileSystem getFileSystem() {
            return fileSystem;
        }

        @Override
        public boolean isAbsolute() {
            return delegate.isAbsolute();
        }

        @Override
        public Path getRoot() {
            return wrapped(delegate.getRoot());
        }

        @Override
        public Path getFileName() {
            return wrapped(delegate.getFileName());
        }

        @Override
        public Path getParent() {
            return wrapped(delegate.getParent());
        }

        @Override
        public int getNameCount() {
            return delegate.getNameCount();
        }

        @Override
        public Path getName(int index) {
            return wrapped(delegate.getName(index));
        }

        @Override
        public Path subpath(int beginIndex, int endIndex) {
            return wrapped(delegate.subpath(beginIndex, endIndex));
        }

        @Override
        public boolean startsWith(Path other) {
            return delegate.startsWith(raw(other));
        }

        @Override
        public boolean startsWith(String other) {
            return delegate.startsWith(other);
        }

        @Override
        public boolean endsWith(Path other) {
            return delegate.endsWith(raw(other));
        }

        @Override
        public boolean endsWith(String other) {
            return delegate.endsWith(other);
        }

        @Override
        public Path normalize() {
            return wrapped(delegate.normalize());
        }

        @Override
        public Path resolve(Path other) {
            return wrapped(delegate.resolve(raw(other)));
        }

        @Override
        public Path resolve(String other) {
            return wrapped(delegate.resolve(other));
        }

        @Override
        public Path resolveSibling(Path other) {
            return wrapped(delegate.resolveSibling(raw(other)));
        }

        @Override
        public Path resolveSibling(String other) {
            return wrapped(delegate.resolveSibling(other));
        }

        @Override
        public Path relativize(Path other) {
            return wrapped(delegate.relativize(raw(other)));
        }

        @Override
        public URI toUri() {
            return delegate.toUri();
        }

        @Override
        public Path toAbsolutePath() {
            return wrapped(delegate.toAbsolutePath());
        }

        @Override
        public Path toRealPath(LinkOption... options) throws IOException {
            return wrapped(delegate.toRealPath(options));
        }

        @Override
        public File toFile() {
            return new File(delegate.toString());
        }

        @Override
        public WatchKey register(
                WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers)
                throws IOException {
            return delegate.register(watcher, events, modifiers);
        }

        @Override
        public WatchKey register(WatchService watcher, WatchEvent.Kind<?>... events)
                throws IOException {
            return delegate.register(watcher, events);
        }

        @Override
        public Iterator<Path> iterator() {
            Iterator<Path> iterator = delegate.iterator();
            return new Iterator<Path>() {
                @Override
                public boolean hasNext() {
                    return iterator.hasNext();
                }

                @Override
                public Path next() {
                    return wrapped(iterator.next());
                }

                @Override
                public void remove() {
                    iterator.remove();
                }
            };
        }

        @Override
        public int compareTo(Path other) {
            return delegate.compareTo(raw(other));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof GuardPath && delegate.equals(((GuardPath) other).delegate);
        }

        @Override
        public int hashCode() {
            return delegate.hashCode();
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
