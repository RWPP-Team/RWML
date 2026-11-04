/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/** Direct-storage import for game-owned files; deliberately has no game DEX or engine dependency. */
public final class GameFileImports {
    private GameFileImports() {}

    /** Relative to Android's shared-storage root, matching the game's native FileLoaderBackend. */
    static String relativeDirectory(ArchiveImports.Kind kind) throws IOException {
        if (kind == ArchiveImports.Kind.MOD) return "rustedWarfare/units";
        if (kind == ArchiveImports.Kind.MAP) return "rustedWarfare/rusted_warfare_maps";
        throw new IOException("Only .rwmod and .tmx files belong in the game directories");
    }

    public static File importFile(Context context, ArchiveImports.Request request) throws IOException {
        return importFileInRoot(context, Environment.getExternalStorageDirectory(), request, false);
    }

    static synchronized File importFileInRoot(Context context, File externalRoot, ArchiveImports.Request request) throws IOException {
        return importFileInRoot(context, externalRoot, request, false);
    }

    static synchronized File importFileInRoot(Context context, File externalRoot, ArchiveImports.Request request,
                                              boolean forceCreateNew) throws IOException {
        if (request == null || !request.isGameFile()) throw new IOException("This is not a game mod or map");
        ArchiveImports.Request current = ArchiveImports.pending(context);
        if (current == null || !current.id.equals(request.id)) return null;
        String name = ArchiveImports.safeFileName(request.name);
        if (name.isEmpty() || !name.equals(request.name)) throw new IOException("The staged file name is invalid");

        File root = externalRoot.getCanonicalFile();
        File directory = new File(root, relativeDirectory(request.kind));
        Files.createDirectories(directory.toPath());
        directory = directory.getCanonicalFile();
        String rootPrefix = root.getPath().endsWith(File.separator) ? root.getPath() : root.getPath() + File.separator;
        if (!directory.getPath().startsWith(rootPrefix) || !directory.isDirectory())
            throw new IOException("The game import directory is outside shared storage");
        removeInterruptedTemps(directory);

        File temporary = File.createTempFile(".rwml-import-", ".part", directory);
        boolean committed = false;
        try {
            String copiedHash;
            MessageDigest digest = sha256();
            try (InputStream input = request.open(); FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[32768]; int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                }
                output.getFD().sync();
                copiedHash = hex(digest.digest());
            }
            if (!request.sha256.equals(copiedHash)) throw new IOException("The private import snapshot failed its SHA-256 check");

            for (int suffix = 0; suffix < 10000; suffix++) {
                String candidateName = suffix == 0 ? name : numbered(name, suffix);
                Path candidate = new File(directory, candidateName).toPath();
                if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                        && request.sha256.equals(hash(candidate))) {
                        if (!temporary.delete()) HostLog.warn("Could not remove duplicate game import temp: " + temporary);
                        committed = true;
                        discardSnapshot(context, request);
                        return candidate.toFile();
                    }
                    continue;
                }
                boolean linked = false;
                if (!forceCreateNew) {
                    try {
                        // Atomic no-replace path, supported by ordinary filesystems.
                        Files.createLink(candidate, temporary.toPath());
                        linked = true;
                    } catch (FileAlreadyExistsException concurrentWriter) {
                        continue;
                    } catch (UnsupportedOperationException | IOException unavailable) {
                        // FUSE-backed shared storage commonly rejects hard links. Fall back below to
                        // CREATE_NEW, which prevents replacement but exposes the final name while copying.
                    }
                }
                if (!linked) {
                    try { copyExclusive(temporary, candidate, request.sha256); }
                    catch (FileAlreadyExistsException concurrentWriter) { continue; }
                }
                committed = true;
                if (temporary.exists() && !temporary.delete())
                    HostLog.warn("Imported game file but could not remove its completed temp link: " + temporary);
                discardSnapshot(context, request);
                return candidate.toFile();
            }
            throw new IOException("No unused destination name is available for " + name);
        } finally {
            if (!committed && temporary.exists() && !temporary.delete())
                HostLog.warn("Could not remove incomplete game import temp file: " + temporary);
        }
    }

    /** Non-atomic fallback for providers that reject hard links; CREATE_NEW never truncates an existing file. */
    static void copyExclusive(File source, Path destination, String expectedHash) throws IOException {
        boolean created = false;
        Object fileKey = null;
        try (FileChannel output = FileChannel.open(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            created = true;
            try { fileKey = Files.readAttributes(destination, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey(); }
            catch (IOException unavailable) { HostLog.warn("Could not read exclusive import file identity: " + unavailable); }
            MessageDigest digest = sha256();
            try (InputStream input = Files.newInputStream(source.toPath())) {
                byte[] bytes = new byte[32768]; int count;
                while ((count = input.read(bytes)) != -1) {
                    digest.update(bytes, 0, count);
                    ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, count);
                    while (buffer.hasRemaining()) output.write(buffer);
                }
            }
            output.force(true);
            if (!expectedHash.equals(hex(digest.digest()))) throw new IOException("The copied game file failed its SHA-256 check");
        } catch (FileAlreadyExistsException concurrentWriter) {
            throw concurrentWriter;
        } catch (IOException | RuntimeException failure) {
            if (created) removeOwnedIncomplete(destination, fileKey);
            if (failure instanceof IOException) throw (IOException) failure;
            throw new IOException("The game file could not be copied", failure);
        }
    }

    private static void removeOwnedIncomplete(Path destination, Object ownedFileKey) {
        try {
            if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) return;
            Object currentKey = Files.readAttributes(destination, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).fileKey();
            if (ownedFileKey != null && Objects.equals(ownedFileKey, currentKey)) {
                Files.delete(destination);
            } else {
                HostLog.warn("A failed CREATE_NEW import remains at " + destination
                    + "; its file identity could not be verified, so it was preserved.");
            }
        } catch (IOException cleanupFailure) {
            HostLog.warn("Could not remove incomplete CREATE_NEW import at " + destination + ": " + cleanupFailure);
        }
    }

    private static String numbered(String name, int suffix) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name + " (" + suffix + ")"
            : name.substring(0, dot) + " (" + suffix + ")" + name.substring(dot);
    }

    private static void removeInterruptedTemps(File directory) {
        File[] leftovers = directory.listFiles((parent, name) -> name.startsWith(".rwml-import-") && name.endsWith(".part"));
        if (leftovers == null) return;
        for (File leftover : leftovers) {
            if (!leftover.delete()) HostLog.warn("Could not remove interrupted game import temp file: " + leftover);
        }
    }

    private static void discardSnapshot(Context context, ArchiveImports.Request request) {
        try { ArchiveImports.discard(context, request); }
        catch (IOException failure) { HostLog.warn("Imported game file but could not remove its private snapshot: " + failure); }
    }

    private static String hash(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[32768]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private static MessageDigest sha256() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IOException("SHA-256 is unavailable", impossible); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            int unsigned = value & 0xff;
            if (unsigned < 16) result.append('0');
            result.append(Integer.toHexString(unsigned));
        }
        return result.toString();
    }
}
