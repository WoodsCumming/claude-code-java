package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Simplified filesystem operations interface and default Node-backed implementation.
 * Translated from src/utils/fsOperations.ts
 *
 * Provides a unified interface for filesystem operations, allowing alternative
 * implementations (mock, virtual) for testing.
 */
@Slf4j
public class FsOperations {



    // ---------------------------------------------------------------------------
    // FsOperations interface (inner interface mirrors the TS type)
    // ---------------------------------------------------------------------------

    /**
     * Filesystem operations interface.
     * Translated from the FsOperations type in fsOperations.ts
     */
    public interface Fs {
        String cwd();
        boolean existsSync(String path);
        CompletableFuture<BasicFileAttributes> stat(String path);
        CompletableFuture<List<File>> readdir(String path);
        CompletableFuture<Void> unlink(String path);
        CompletableFuture<Void> rmdir(String path);
        CompletableFuture<Void> rm(String path, boolean recursive, boolean force);
        CompletableFuture<Void> mkdir(String path, Integer mode);
        CompletableFuture<String> readFile(String path, Charset encoding);
        CompletableFuture<Void> rename(String oldPath, String newPath);
        BasicFileAttributes statSync(String path) throws IOException;
        BasicFileAttributes lstatSync(String path) throws IOException;
        String readFileSync(String path, Charset encoding) throws IOException;
        byte[] readFileBytesSync(String path) throws IOException;
        ReadResult readSync(String path, int length) throws IOException;
        void appendFileSync(String path, String data, Integer mode) throws IOException;
        void copyFileSync(String src, String dest) throws IOException;
        void unlinkSync(String path) throws IOException;
        void renameSync(String oldPath, String newPath) throws IOException;
        void mkdirSync(String path, Integer mode) throws IOException;
        List<File> readdirSync(String path) throws IOException;
        List<String> readdirStringSync(String path) throws IOException;
        boolean isDirEmptySync(String path) throws IOException;
        void rmdirSync(String path) throws IOException;
        void rmSync(String path, boolean recursive, boolean force) throws IOException;
        String realpathSync(String path) throws IOException;
        String readlinkSync(String path) throws IOException;
        CompletableFuture<byte[]> readFileBytes(String path, Long maxBytes);
    }

    /**
     * Result of a readSync operation.
     */
    public record ReadResult(byte[] buffer, int bytesRead) {}

    // ---------------------------------------------------------------------------
    // Default Node-backed implementation
    // ---------------------------------------------------------------------------

    public static final Fs NODE_FS = new NodeFsImpl();

    private static class NodeFsImpl implements Fs {

        @Override
        public String cwd() {
            return System.getProperty("user.dir");
        }

        @Override
        public boolean existsSync(String path) {
            return path != null && new File(path).exists();
        }

        @Override
        public CompletableFuture<BasicFileAttributes> stat(String path) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return Files.readAttributes(Paths.get(path), BasicFileAttributes.class);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        @Override
        public CompletableFuture<List<File>> readdir(String path) {
            return CompletableFuture.supplyAsync(() -> {
                File dir = new File(path);
                File[] files = dir.listFiles();
                if (files == null) return List.of();
                return List.of(files);
            });
        }

        @Override
        public CompletableFuture<Void> unlink(String path) {
            return CompletableFuture.runAsync(() -> {
                try { Files.delete(Paths.get(path)); }
                catch (IOException e) { throw new RuntimeException(e); }
            });
        }

        @Override
        public CompletableFuture<Void> rmdir(String path) {
            return CompletableFuture.runAsync(() -> {
                try { Files.delete(Paths.get(path)); }
                catch (IOException e) { throw new RuntimeException(e); }
            });
        }

        @Override
        public CompletableFuture<Void> rm(String path, boolean recursive, boolean force) {
            return CompletableFuture.runAsync(() -> {
                try {
                    Path p = Paths.get(path);
                    if (!Files.exists(p) && force) return;
                    if (recursive) {
                        Files.walkFileTree(p, new SimpleFileVisitor<>() {
                            @Override
                            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                                Files.delete(f); return FileVisitResult.CONTINUE;
                            }
                            @Override
                            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                                if (e != null) throw e;
                                Files.delete(d); return FileVisitResult.CONTINUE;
                            }
                        });
                    } else {
                        Files.delete(p);
                    }
                } catch (IOException e) { throw new RuntimeException(e); }
            });
        }

        @Override
        public CompletableFuture<Void> mkdir(String path, Integer mode) {
            return CompletableFuture.runAsync(() -> {
                try { Files.createDirectories(Paths.get(path)); }
                catch (IOException e) { throw new RuntimeException(e); }
            });
        }

        @Override
        public CompletableFuture<String> readFile(String path, Charset encoding) {
            return CompletableFuture.supplyAsync(() -> {
                try { return Files.readString(Paths.get(path), encoding); }
                catch (IOException e) { throw new RuntimeException(e); }
            });
        }

        @Override
        public CompletableFuture<Void> rename(String oldPath, String newPath) {
            return CompletableFuture.runAsync(() -> {
                try { Files.move(Paths.get(oldPath), Paths.get(newPath),
                        StandardCopyOption.REPLACE_EXISTING); }
                catch (IOException e) { throw new RuntimeException(e); }
            });
        }

        @Override
        public BasicFileAttributes statSync(String path) throws IOException {
            return Files.readAttributes(Paths.get(path), BasicFileAttributes.class);
        }

        @Override
        public BasicFileAttributes lstatSync(String path) throws IOException {
            return Files.readAttributes(Paths.get(path), BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        }

        @Override
        public String readFileSync(String path, Charset encoding) throws IOException {
            return Files.readString(Paths.get(path), encoding);
        }

        @Override
        public byte[] readFileBytesSync(String path) throws IOException {
            return Files.readAllBytes(Paths.get(path));
        }

        @Override
        public ReadResult readSync(String path, int length) throws IOException {
            try (RandomAccessFile raf = new RandomAccessFile(path, "r")) {
                byte[] buffer = new byte[length];
                int bytesRead = raf.read(buffer, 0, length);
                return new ReadResult(buffer, Math.max(0, bytesRead));
            }
        }

        @Override
        public void appendFileSync(String path, String data, Integer mode) throws IOException {
            Files.writeString(Paths.get(path), data,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        }

        @Override
        public void copyFileSync(String src, String dest) throws IOException {
            Files.copy(Paths.get(src), Paths.get(dest), StandardCopyOption.REPLACE_EXISTING);
        }

        @Override
        public void unlinkSync(String path) throws IOException {
            Files.delete(Paths.get(path));
        }

        @Override
        public void renameSync(String oldPath, String newPath) throws IOException {
            Files.move(Paths.get(oldPath), Paths.get(newPath), StandardCopyOption.REPLACE_EXISTING);
        }

        @Override
        public void mkdirSync(String path, Integer mode) throws IOException {
            Files.createDirectories(Paths.get(path));
        }

        @Override
        public List<File> readdirSync(String path) throws IOException {
            File dir = new File(path);
            if (!dir.isDirectory()) throw new IOException("Not a directory: " + path);
            File[] files = dir.listFiles();
            return files != null ? List.of(files) : List.of();
        }

        @Override
        public List<String> readdirStringSync(String path) throws IOException {
            File dir = new File(path);
            if (!dir.isDirectory()) throw new IOException("Not a directory: " + path);
            String[] names = dir.list();
            return names != null ? List.of(names) : List.of();
        }

        @Override
        public boolean isDirEmptySync(String path) throws IOException {
            return readdirSync(path).isEmpty();
        }

        @Override
        public void rmdirSync(String path) throws IOException {
            Files.delete(Paths.get(path));
        }

        @Override
        public void rmSync(String path, boolean recursive, boolean force) throws IOException {
            Path p = Paths.get(path);
            if (!Files.exists(p) && force) return;
            if (recursive) {
                Files.walkFileTree(p, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                        Files.delete(f); return FileVisitResult.CONTINUE;
                    }
                    @Override
                    public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                        if (e != null) throw e;
                        Files.delete(d); return FileVisitResult.CONTINUE;
                    }
                });
            } else {
                Files.delete(p);
            }
        }

        @Override
        public String realpathSync(String path) throws IOException {
            return Paths.get(path).toRealPath().toString();
        }

        @Override
        public String readlinkSync(String path) throws IOException {
            return Files.readSymbolicLink(Paths.get(path)).toString();
        }

        @Override
        public CompletableFuture<byte[]> readFileBytes(String path, Long maxBytes) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    if (maxBytes == null) {
                        return Files.readAllBytes(Paths.get(path));
                    }
                    try (FileChannel ch = FileChannel.open(Paths.get(path),
                            StandardOpenOption.READ)) {
                        long size = ch.size();
                        int readSize = (int) Math.min(size, maxBytes);
                        ByteBuffer buf = ByteBuffer.allocate(readSize);
                        int total = 0;
                        while (total < readSize) {
                            int n = ch.read(buf);
                            if (n <= 0) break;
                            total += n;
                        }
                        buf.flip();
                        byte[] result = new byte[buf.limit()];
                        buf.get(result);
                        return result;
                    }
                } catch (IOException e) { throw new RuntimeException(e); }
            });
        }
    }

    // ---------------------------------------------------------------------------
    // Active implementation registry (mirrors TS module-level `activeFs`)
    // ---------------------------------------------------------------------------

    private static volatile Fs activeFs = NODE_FS;

    public static void setFsImplementation(Fs impl) { activeFs = impl; }
    public static Fs getFsImplementation() { return activeFs; }
    public static void setOriginalFsImplementation() { activeFs = NODE_FS; }

    // ---------------------------------------------------------------------------
    // Utility functions (translated from fsOperations.ts top-level exports)
    // ---------------------------------------------------------------------------

    /**
     * Result of safeResolvePath.
     */
    public record ResolveResult(String resolvedPath, boolean isSymlink, boolean isCanonical) {}

    /**
     * Safely resolves a file path, handling symlinks and errors gracefully.
     *
     * Translated from safeResolvePath() in fsOperations.ts
     */
    public static ResolveResult safeResolvePath(Fs fs, String filePath) {
        if (filePath == null) return new ResolveResult("", false, false);
        if (filePath.startsWith("//") || filePath.startsWith("\\\\")) {
            return new ResolveResult(filePath, false, false);
        }
        try {
            BasicFileAttributes attrs = fs.lstatSync(filePath);
            // Detect special file types that can cause issues
            if (attrs.isOther()) {
                return new ResolveResult(filePath, false, false);
            }
            String resolved = fs.realpathSync(filePath);
            return new ResolveResult(resolved, !resolved.equals(filePath), true);
        } catch (Exception e) {
            return new ResolveResult(filePath, false, false);
        }
    }

    /**
     * Check if a file path is a duplicate; adds it to loadedPaths if not.
     *
     * Translated from isDuplicatePath() in fsOperations.ts
     *
     * @return true if the file should be skipped (is a duplicate)
     */
    public static boolean isDuplicatePath(Fs fs, String filePath, Set<String> loadedPaths) {
        ResolveResult result = safeResolvePath(fs, filePath);
        if (loadedPaths.contains(result.resolvedPath())) {
            return true;
        }
        loadedPaths.add(result.resolvedPath());
        return false;
    }

    /**
     * Read up to maxBytes from a file starting at offset.
     * Returns null if the file is smaller than the offset.
     *
     * Translated from readFileRange() in fsOperations.ts
     */
    public static ReadFileRangeResult readFileRange(String path, long offset, int maxBytes)
            throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(path, "r")) {
            long size = raf.length();
            if (size <= offset) return null;
            int bytesToRead = (int) Math.min(size - offset, maxBytes);
            byte[] buffer = new byte[bytesToRead];
            raf.seek(offset);
            int totalRead = 0;
            while (totalRead < bytesToRead) {
                int n = raf.read(buffer, totalRead, bytesToRead - totalRead);
                if (n <= 0) break;
                totalRead += n;
            }
            return new ReadFileRangeResult(
                    new String(buffer, 0, totalRead, StandardCharsets.UTF_8),
                    totalRead,
                    size);
        }
    }

    /**
     * Result of readFileRange / tailFile.
     */
    public record ReadFileRangeResult(String content, long bytesRead, long bytesTotal) {}

    /**
     * Read the last maxBytes of a file.
     *
     * Translated from tailFile() in fsOperations.ts
     */
    public static ReadFileRangeResult tailFile(String path, int maxBytes) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(path, "r")) {
            long size = raf.length();
            if (size == 0) return new ReadFileRangeResult("", 0, 0);
            long offset = Math.max(0, size - maxBytes);
            int bytesToRead = (int) (size - offset);
            byte[] buffer = new byte[bytesToRead];
            raf.seek(offset);
            int totalRead = 0;
            while (totalRead < bytesToRead) {
                int n = raf.read(buffer, totalRead, bytesToRead - totalRead);
                if (n <= 0) break;
                totalRead += n;
            }
            return new ReadFileRangeResult(
                    new String(buffer, 0, totalRead, StandardCharsets.UTF_8),
                    totalRead,
                    size);
        }
    }

    /**
     * Yields lines from a file in reverse order.
     *
     * Translated from readLinesReverse() in fsOperations.ts
     * Returns a List (Java doesn't have async generators, but preserves semantics).
     */
    public static List<String> readLinesReverse(String path) throws IOException {
        List<String> lines = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(path, "r")) {
            long pos = raf.length();
            if (pos == 0) return lines;
            StringBuilder sb = new StringBuilder();
            while (pos > 0) {
                pos--;
                raf.seek(pos);
                char c = (char) raf.read();
                if (c == '\n' && sb.length() > 0) {
                    lines.add(sb.reverse().toString());
                    sb.setLength(0);
                } else if (c != '\n') {
                    sb.append(c);
                }
            }
            if (sb.length() > 0) {
                lines.add(sb.reverse().toString());
            }
        }
        return lines;
    }

    // Convenience delegation wrappers using the active implementation

    public static String getCwd()                         { return activeFs.cwd(); }
    public static boolean existsSync(String path)         { return activeFs.existsSync(path); }

    public static String readFileSync(String path) throws IOException {
        return activeFs.readFileSync(path, StandardCharsets.UTF_8);
    }

    public static void mkdirSync(String path) throws IOException {
        activeFs.mkdirSync(path, null);
    }

    public static List<String> readdirStringSync(String path) throws IOException {
        return activeFs.readdirStringSync(path);
    }

    public static void unlinkSync(String path) throws IOException {
        activeFs.unlinkSync(path);
    }

    public static BasicFileAttributes statSync(String path) throws IOException {
        return activeFs.statSync(path);
    }

    private FsOperations() {}
}
