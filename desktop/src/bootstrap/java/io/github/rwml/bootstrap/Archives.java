/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.bootstrap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Archive extraction for the JDK provisioner.
 *
 * <p>Adoptium publishes {@code .zip} for Windows and {@code .tar.gz} everywhere else, so both formats
 * are handled here. There is no tar support in the JDK, which is why the reader below is written by
 * hand - it covers exactly the subset GNU tar uses for a JDK image: ustar prefix names, GNU long
 * names ({@code L} entries), directories, regular files and symlinks.</p>
 *
 * <p>Every entry path is resolved through {@link #safeChild} and rejected if it would escape the
 * destination directory, so a hostile or corrupt archive cannot write outside the cache.</p>
 */
public final class Archives {

    private static final int TAR_BLOCK = 512;
    private static final int BUFFER_SIZE = 64 * 1024;

    private Archives() {
    }

    /** Extracts {@code archive} into {@code destination}, choosing the format by file extension. */
    public static void extract(File archive, File destination) throws IOException {
        String name = archive.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip")) {
            extractZip(archive, destination);
        } else if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            extractTarGz(archive, destination);
        } else {
            throw new IOException("Unsupported archive format: " + archive.getName());
        }
    }

    // ------------------------------------------------------------------
    // zip
    // ------------------------------------------------------------------

    private static void extractZip(File archive, File destination) throws IOException {
        mkdirs(destination);
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                File out = safeChild(destination, entry.getName());
                if (entry.isDirectory()) {
                    mkdirs(out);
                    continue;
                }
                mkdirs(out.getParentFile());
                try (InputStream in = zip.getInputStream(entry);
                     OutputStream os = new BufferedOutputStream(new FileOutputStream(out), BUFFER_SIZE)) {
                    copy(in, os);
                }
                if (entry.getTime() > 0) {
                    out.setLastModified(entry.getTime());
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // tar.gz
    // ------------------------------------------------------------------

    private static void extractTarGz(File archive, File destination) throws IOException {
        mkdirs(destination);
        try (InputStream raw = new BufferedInputStream(new FileInputStream(archive), BUFFER_SIZE);
             GZIPInputStream gz = new GZIPInputStream(raw, BUFFER_SIZE)) {

            byte[] header = new byte[TAR_BLOCK];
            String longName = null;

            while (true) {
                if (!readBlock(gz, header)) {
                    break; // Clean end of stream.
                }
                if (isZeroBlock(header)) {
                    break; // End-of-archive marker.
                }

                String name = stringField(header, 0, 100);
                long size = octalField(header, 124, 12);
                int type = header[156] & 0xFF;
                String prefix = stringField(header, 345, 155);
                String linkName = stringField(header, 157, 100);
                long padded = paddedSize(size);

                // GNU long name: the payload is the real name of the *next* entry.
                if (type == 'L') {
                    if (size < 0 || size > 64 * 1024) {
                        throw new IOException("Unreasonable tar long-name length: " + size);
                    }
                    byte[] data = new byte[(int) size];
                    readExact(gz, data, (int) size);
                    skipExact(gz, padded - size);
                    longName = nulTerminated(data, 0, data.length);
                    continue;
                }
                // pax global/extended headers and GNU long link names carry no files we need.
                if (type == 'x' || type == 'g' || type == 'K') {
                    skipExact(gz, padded);
                    continue;
                }

                String effective = longName != null
                    ? longName
                    : (prefix.isEmpty() ? name : prefix + "/" + name);
                longName = null;

                File out = safeChild(destination, effective);
                switch (type) {
                    case 0:
                    case '0':
                        mkdirs(out.getParentFile());
                        try (InputStream in = new BoundedInputStream(gz, size);
                             OutputStream os = new BufferedOutputStream(new FileOutputStream(out), BUFFER_SIZE)) {
                            copy(in, os);
                        }
                        break;
                    case '2': // symbolic link
                        createSymlink(out, linkName);
                        break;
                    case '5': // directory
                        mkdirs(out);
                        break;
                    default:
                        // Hard links ('1') and device nodes are not used by a JDK image.
                        break;
                }
                if (padded > size) {
                    skipExact(gz, padded - size);
                }
            }
        }
    }

    private static boolean readBlock(InputStream in, byte[] block) throws IOException {
        int total = 0;
        while (total < TAR_BLOCK) {
            int read = in.read(block, total, TAR_BLOCK - total);
            if (read == -1) {
                if (total == 0) {
                    return false;
                }
                throw new IOException("Truncated tar header");
            }
            total += read;
        }
        return true;
    }

    /** Tries to create a symlink; a platform that refuses falls back to a copy of the target. */
    private static void createSymlink(File link, String target) {
        if (target.isEmpty()) {
            return;
        }
        try {
            mkdirs(link.getParentFile());
            Files.createSymbolicLink(link.toPath(), new File(target).toPath());
        } catch (Throwable t) {
            // Windows without the right privileges, or a target that does not exist yet. The JDK is
            // validated by running it afterwards, so a missing link surfaces as a clear failure
            // rather than a silent corruption.
            BootstrapLog.debug("Could not create symlink " + link + " -> " + target + " ("
                + BootstrapLog.describe(t) + ")");
        }
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte value : block) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static String stringField(byte[] block, int offset, int length) {
        return nulTerminated(block, offset, length);
    }

    private static String nulTerminated(byte[] bytes, int offset, int length) {
        int end = offset;
        int limit = Math.min(offset + length, bytes.length);
        while (end < limit && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, offset, end - offset, StandardCharsets.UTF_8).trim();
    }

    /** Reads a NUL/space terminated octal number, as used by the tar size field. */
    private static long octalField(byte[] block, int offset, int length) {
        String text = nulTerminated(block, offset, length);
        if (text.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(text, 8);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static long paddedSize(long size) {
        return size <= 0 ? 0 : ((size + TAR_BLOCK - 1) / TAR_BLOCK) * TAR_BLOCK;
    }

    private static void readExact(InputStream in, byte[] buffer, int length) throws IOException {
        int total = 0;
        while (total < length) {
            int read = in.read(buffer, total, length - total);
            if (read == -1) {
                throw new IOException("Unexpected end of tar stream");
            }
            total += read;
        }
    }

    private static void skipExact(InputStream in, long count) throws IOException {
        long remaining = count;
        byte[] scratch = new byte[8192];
        while (remaining > 0) {
            int read = in.read(scratch, 0, (int) Math.min(scratch.length, remaining));
            if (read == -1) {
                throw new IOException("Unexpected end of tar stream while skipping padding");
            }
            remaining -= read;
        }
    }

    // ------------------------------------------------------------------
    // Layout helpers
    // ------------------------------------------------------------------

    /**
     * Locates the JDK home inside an extracted archive.
     *
     * <p>Temurin archives wrap everything in a versioned directory ({@code jdk-25+1/}), and the macOS
     * build nests it again under {@code Contents/Home}, so the search descends a few levels looking
     * for the {@code bin/java} that identifies a JDK home.</p>
     */
    public static File findJavaHome(File root) {
        return findJavaHome(root, 0);
    }

    private static File findJavaHome(File root, int depth) {
        if (root == null || !root.isDirectory()) {
            return null;
        }
        if (javaExecutable(root) != null) {
            return root;
        }
        if (depth >= 3) {
            return null;
        }
        File macBundle = new File(root, "Contents" + File.separator + "Home");
        if (javaExecutable(macBundle) != null) {
            return macBundle;
        }
        File[] children = root.listFiles();
        if (children == null) {
            return null;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                File found = findJavaHome(child, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return findJavaHome(macBundle, depth + 1);
    }

    /** @return the {@code bin/java} launcher of a JDK home, or {@code null}. */
    public static File javaExecutable(File home) {
        if (home == null) {
            return null;
        }
        File binary = new File(home, "bin" + File.separator + (isWindows() ? "java.exe" : "java"));
        return binary.isFile() ? binary : null;
    }

    /**
     * Restores the executable bit on the JDK launchers.
     *
     * <p>ZIP archives do not carry POSIX permissions, and tar extraction through the JDK's own
     * streams does not apply them either, so on Unix the {@code bin/} directory has to be marked
     * executable by hand or the freshly downloaded JDK cannot be started.</p>
     */
    public static void makeExecutable(File home) {
        if (isWindows()) {
            return;
        }
        File bin = new File(home, "bin");
        if (bin.isDirectory()) {
            markExecutable(bin);
        }
        File spawnHelper = new File(home, "lib" + File.separator + "jspawnhelper");
        if (spawnHelper.isFile()) {
            spawnHelper.setExecutable(true, false);
        }
    }

    private static void markExecutable(File directory) {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                markExecutable(child);
            } else if (!child.setExecutable(true, false)) {
                BootstrapLog.debug("Could not mark " + child + " executable.");
            }
        }
    }

    /** Moves a directory, falling back to a recursive copy when the target is on another device. */
    public static void moveOrCopy(File source, File target) throws IOException {
        mkdirs(target.getParentFile());
        try {
            Files.move(source.toPath(), target.toPath());
            return;
        } catch (IOException | UnsupportedOperationException e) {
            BootstrapLog.debug("Directory move failed (" + BootstrapLog.describe(e) + "); copying instead.");
        }
        copyRecursively(source, target);
        deleteRecursively(source);
    }

    public static void copyRecursively(File source, File target) throws IOException {
        if (source.isDirectory()) {
            mkdirs(target);
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyRecursively(child, new File(target, child.getName()));
                }
            }
            return;
        }
        mkdirs(target.getParentFile());
        try (InputStream in = new BufferedInputStream(new FileInputStream(source), BUFFER_SIZE);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(target), BUFFER_SIZE)) {
            copy(in, out);
        }
        target.setExecutable(source.canExecute(), false);
    }

    public static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!file.delete()) {
            BootstrapLog.debug("Could not delete " + file);
        }
    }

    public static void mkdirs(File directory) throws IOException {
        if (directory == null || directory.isDirectory()) {
            return;
        }
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Could not create directory " + directory);
        }
    }

    /** Resolves an archive entry against the destination, refusing anything that escapes it. */
    private static File safeChild(File destination, String entryName) throws IOException {
        String normalized = entryName.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        File candidate = new File(destination, normalized);
        String rootPath = destination.getCanonicalPath();
        String candidatePath = candidate.getCanonicalPath();
        if (!candidatePath.equals(rootPath) && !candidatePath.startsWith(rootPath + File.separator)) {
            throw new IOException("Refusing to extract outside the target directory: " + entryName);
        }
        return candidate;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** An input stream that stops after a fixed number of bytes, used for tar entry payloads. */
    private static final class BoundedInputStream extends InputStream {

        private final InputStream delegate;
        private long remaining;

        private BoundedInputStream(InputStream delegate, long limit) {
            this.delegate = delegate;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int value = delegate.read();
            if (value != -1) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int capped = (int) Math.min(length, remaining);
            int read = delegate.read(buffer, offset, capped);
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }
    }
}
