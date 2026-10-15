/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import java.util.zip.Adler32;

/**
 * Compiles class files into dex on the device, with {@code dx} - the dexer of the Android Open Source
 * Project, which the APK carries as the {@code dalvik-dx} dependency.
 *
 * <p>This is what replaces the JVM agent on Android: the classes are rewritten first and handed to
 * the runtime as dex. The result is cached, so a launch that finds a valid cache compiles nothing.</p>
 *
 * <p>{@code --multi-dex} is not optional: a dex file indexes at most 65 536 method references and the
 * game's classes exceed that on their own. Every dex written here is sealed read-only before it is
 * returned, because the platform refuses to open one it could still write.</p>
 */
final class Dexer {

    /** The newest class file version dx accepts; see {@link #lowerClassVersions}. */
    static final int MAX_CLASS_VERSION = 51;

    /** Name of the copy handed to dx, inside the output directory and deleted again right after. */
    private static final String INPUT_FILE = "input.jar";

    /** The static lists dx accumulates its output in and never clears; see {@link #clearDxState}. */
    private static final String[] DX_ACCUMULATORS = {"dexOutputFutures", "dexOutputArrays"};

    private static final int CLASS_MAGIC = 0xCAFEBABE;

    private Dexer() {
    }

    /**
     * Compiles every class file of {@code jar} into dex files inside {@code outputDirectory}.
     *
     * @return the dex files that were written, in load order
     */
    static List<Path> toDex(Path jar, Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        deleteDexFiles(outputDirectory);

        Path input = outputDirectory.resolve(INPUT_FILE);
        lowerClassVersions(jar, input);
        try {
            clearDxState();
            // dx is an old command line tool: its only supported entry point is a parsed argument
            // list. It decides that the output is a directory by calling File.isDirectory on the
            // name it was given, so the directory must already exist - which it does, above.
            com.android.dx.command.dexer.Main.Arguments arguments =
                new com.android.dx.command.dexer.Main.Arguments();
            arguments.parse(new String[]{
                "--multi-dex",
                "--output=" + outputDirectory.toAbsolutePath(),
                input.toAbsolutePath().toString()
            });

            int result = com.android.dx.command.dexer.Main.run(arguments);
            if (result != 0) {
                throw new IOException("dx could not compile " + jar.getFileName()
                    + " (exit code " + result + ")");
            }
        } finally {
            Files.deleteIfExists(input);
        }

        List<Path> produced = dexFiles(outputDirectory);
        if (produced.isEmpty()) {
            throw new IOException("dx produced no dex for " + jar.getFileName());
        }
        for (Path dex : produced) {
            makeReadOnly(dex);
        }
        return produced;
    }

    /**
     * Puts the lists dx keeps its output in back to what they are in a fresh process.
     * <p>dx holds its result in static fields, and the two multi-dex accumulators are never cleared. A
     * second run in the same process writes out the first run's shards as well, and load order puts
     * those first, so a class a rebuild meant to change is decided by the build before it.</p>
     */
    private static void clearDxState() {
        for (String name : DX_ACCUMULATORS) {
            try {
                Field field = com.android.dx.command.dexer.Main.class.getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof Collection) {
                    ((Collection<?>) value).clear();
                }
            } catch (Throwable t) {
                System.err.println("[rwml-dex] Could not reset dx." + name + ": " + t);
            }
        }
    }

    /**
     * Copies {@code source} to {@code target}, stamping every class file with a version dx accepts: dx
     * precedes Java 8 and refuses a file stamped 52 or higher. The stamp does not survive the
     * compilation, so lowering it cannot change what the code does.
     */
    private static void lowerClassVersions(Path source, Path target) throws IOException {
        try (JarFile in = new JarFile(source.toFile());
             OutputStream file = Files.newOutputStream(target);
             JarOutputStream out = new JarOutputStream(new BufferedOutputStream(file))) {
            Enumeration<JarEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                byte[] bytes;
                try (InputStream stream = in.getInputStream(entry)) {
                    bytes = JarResources.readFully(stream);
                }
                out.putNextEntry(new JarEntry(entry.getName()));
                out.write(accepted(bytes));
                out.closeEntry();
            }
        }
    }

    /** @return {@code classBytes} unchanged when dx accepts its version, lowered otherwise */
    private static byte[] accepted(byte[] classBytes) {
        if (classBytes.length < 8 || magic(classBytes) != CLASS_MAGIC) {
            return classBytes;
        }
        if (major(classBytes) <= MAX_CLASS_VERSION) {
            return classBytes;
        }
        byte[] lowered = classBytes.clone();
        // Version 51 is only legal with a minor version of 0, and so is every version below it.
        lowered[4] = 0;
        lowered[5] = 0;
        lowered[6] = (byte) (MAX_CLASS_VERSION >>> 8);
        lowered[7] = (byte) MAX_CLASS_VERSION;
        return lowered;
    }

    private static int magic(byte[] classBytes) {
        return ((classBytes[0] & 0xFF) << 24) | ((classBytes[1] & 0xFF) << 16)
            | ((classBytes[2] & 0xFF) << 8) | (classBytes[3] & 0xFF);
    }

    private static int major(byte[] classBytes) {
        return ((classBytes[6] & 0xFF) << 8) | (classBytes[7] & 0xFF);
    }

    /** @return the dex files of a directory, in the order {@code classes.dex}, {@code classes2.dex}… */
    static List<Path> dexFiles(Path directory) {
        if (!Files.isDirectory(directory)) {
            return Collections.emptyList();
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile)
                .filter(Dexer::isDex)
                .forEach(files::add);
        } catch (IOException e) {
            return Collections.emptyList();
        }
        Collections.sort(files, Dexer::byDexIndex);
        return files;
    }

    /**
     * Copies the dex an extension archive already carries into {@code outputDirectory}. Only the game
     * is compiled on the device: the dexer belongs to the application, and compiling every extension
     * at installation would put that cost on the launch.
     *
     * @return the dex files that were written, in load order
     * @throws IOException when the archive carries no dex at all
     */
    static List<Path> carriedDex(Path archive, Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        deleteDexFiles(outputDirectory);

        List<Path> produced = new ArrayList<>();
        try (JarFile in = new JarFile(archive.toFile())) {
            Enumeration<JarEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !isDexName(entry.getName())) {
                    continue;
                }
                // The archive names its dex the way the platform does, so a multi-dex extension
                // needs no manifest of its own.
                Path target = outputDirectory.resolve(entry.getName());
                try (InputStream stream = in.getInputStream(entry);
                     OutputStream file = Files.newOutputStream(target)) {
                    byte[] bytes = JarResources.readFully(stream);
                    file.write(bytes);
                }
                makeReadOnly(target);
                produced.add(target);
            }
        }
        if (produced.isEmpty()) {
            throw new IOException(archive.getFileName() + " carries no dex");
        }
        Collections.sort(produced, Dexer::byDexIndex);
        return produced;
    }

    /** @return whether an archive carries dex of its own, and so can be mounted */
    static boolean carriesDex(Path archive) {
        try (JarFile in = new JarFile(archive.toFile())) {
            Enumeration<JarEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.isDirectory() && isDexName(entry.getName())) {
                    return true;
                }
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    /** Checks carried dex before an import replaces an installed archive. No classes are mounted. */
    static void validateCarriedDex(Path archive) throws IOException {
        try (JarFile jar = new JarFile(archive.toFile())) {
            if (jar.getJarEntry("classes.dex") == null) {
                throw new IOException("Android extensions must contain classes.dex and class metadata");
            }
            Enumeration<JarEntry> entries = jar.entries();
            long total = 0;
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!isDexName(entry.getName())) { continue; }
                if (!entry.getName().matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")) {
                    throw new IOException("Invalid dex name: " + entry.getName());
                }
                total += entry.getSize();
                if (entry.getSize() < 112 || total > 128L * 1024 * 1024) {
                    throw new IOException("Invalid or oversized dex: " + entry.getName());
                }
                try (DataInputStream input = new DataInputStream(jar.getInputStream(entry))) {
                    byte[] header = new byte[112];
                    input.readFully(header);
                    if (header[0] != 'd' || header[1] != 'e' || header[2] != 'x' || header[3] != '\n'
                        || header[7] != 0 || header[4] < '0' || header[4] > '9'
                        || header[5] < '0' || header[5] > '9' || header[6] < '0' || header[6] > '9') {
                        throw new IOException("Invalid dex header: " + entry.getName());
                    }
                    ByteBuffer fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                    if (fields.getInt(32) != entry.getSize() || fields.getInt(36) < 112
                        || fields.getInt(36) > entry.getSize()) {
                        throw new IOException("Invalid dex size: " + entry.getName());
                    }
                    Adler32 checksum = new Adler32();
                    MessageDigest signature;
                    try { signature = MessageDigest.getInstance("SHA-1"); }
                    catch (NoSuchAlgorithmException missing) { throw new IOException(missing); }
                    checksum.update(header, 12, 100);
                    signature.update(header, 32, 80);
                    byte[] buffer = new byte[16384];
                    long length = header.length;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        checksum.update(buffer, 0, count);
                        signature.update(buffer, 0, count);
                        length += count;
                    }
                    if (length != entry.getSize() || (int) checksum.getValue() != fields.getInt(8)
                        || !Arrays.equals(signature.digest(), Arrays.copyOfRange(header, 12, 32))) {
                        throw new IOException("Corrupt dex: " + entry.getName());
                    }
                }
            }
        }
    }

    /** @return whether a jar entry name is a dex file the platform would load */
    private static boolean isDexName(String name) {
        if (name.indexOf('/') >= 0) {
            return false;
        }
        return name.equals("classes.dex")
            || (name.startsWith("classes") && name.endsWith(".dex"));
    }

    private static boolean isDex(Path path) {
        return isDexName(path.getFileName().toString());
    }

    /** Orders {@code classes.dex} before {@code classes2.dex}, and that before {@code classes10.dex}. */
    private static int byDexIndex(Path left, Path right) {
        return index(left) - index(right);
    }

    private static int index(Path path) {
        String name = path.getFileName().toString();
        String digits = name.substring("classes".length(), name.length() - ".dex".length());
        if (digits.isEmpty()) {
            return 1;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static void deleteDexFiles(Path directory) throws IOException {
        for (Path dex : dexFiles(directory)) {
            makeWritable(dex);
            Files.deleteIfExists(dex);
        }
    }

    /**
     * Clears every write bit of a dex file: from Android 14 the platform refuses to open one that can
     * still be written, because a file the app could change would let verified bytes be replaced. The
     * directory keeps its write bit, which is where the runtime puts the optimized copy.
     *
     * @throws IOException when the file is still writable afterwards
     */
    static void makeReadOnly(Path dex) throws IOException {
        if (dex.toFile().setReadOnly() || !Files.isWritable(dex)) {
            return;
        }
        throw new IOException("cannot make " + dex.getFileName()
            + " read-only, and the platform refuses to load a writable dex");
    }

    /**
     * Puts back the write bit {@link #makeReadOnly} cleared.
     *
     * <p>Sealing is for the platform's benefit and is in the way of ordinary file handling: whether a
     * read-only file can be removed is not the same on every host, so the bit is restored wherever a
     * sealed file is about to be deleted. A file that cannot be removed cannot be rebuilt either,
     * which would leave the cache with no way out.</p>
     */
    static void makeWritable(Path dex) {
        dex.toFile().setWritable(true, true);
    }
}
