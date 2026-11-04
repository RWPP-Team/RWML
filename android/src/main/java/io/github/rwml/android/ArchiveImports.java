/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.AtomicFile;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Properties;

/** Durable, private snapshots of files received from Android's document providers or share intents. */
public final class ArchiveImports {
    private static final String PREFIX = "import-";
    private static final String SUFFIX = ".bin";

    public enum Kind { EXTENSION, MOD, MAP }

    private ArchiveImports() {}

    public static Intent pickerIntent() {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
            .addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    public static Uri incomingUri(Intent intent) {
        if (intent == null) return null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) return intent.getData();
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null) return stream;
            ClipData clip = intent.getClipData();
            return clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).getUri() : null;
        }
        return null;
    }

    private static File directory(Context context) { return new File(context.getFilesDir(), "rwml/imports"); }
    private static AtomicFile marker(Context context) { return new AtomicFile(new File(directory(context), "pending.properties")); }

    public static final class Request {
        public final File file;
        public final String name;
        public final Kind kind;
        public final String id;
        public final String sha256;
        private final String hash;
        private final String source;
        private Request(File file, String name, Kind kind, String id, String source, String hash) {
            this.file = file; this.name = name; this.kind = kind; this.id = id; this.source = source;
            this.sha256 = hash; this.hash = hash;
        }
        public InputStream open() throws IOException { return new FileInputStream(file); }
        public boolean isGameFile() { return kind == Kind.MOD || kind == Kind.MAP; }
    }

    private static String displayName(Context context, Uri uri) {
        String provided = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) provided = cursor.getString(0);
            } catch (RuntimeException unavailable) {
                HostLog.warn("Import provider has no display name: " + unavailable);
            }
        }
        if (provided != null && hasSupportedSuffix(provided)) return provided;
        String last = uri.getLastPathSegment();
        if (last != null && hasSupportedSuffix(last)) return last;
        String type = null;
        try { type = context.getContentResolver().getType(uri); }
        catch (RuntimeException unavailable) { HostLog.warn("Import provider has no MIME type: " + unavailable); }
        if ("application/x-rwmod".equalsIgnoreCase(type) || "application/vnd.rustedwarfare.mod".equalsIgnoreCase(type))
            return "shared-mod.rwmod";
        if ("application/x-tmx".equalsIgnoreCase(type) || "application/vnd.rustedwarfare.map".equalsIgnoreCase(type)
            || "application/xml".equalsIgnoreCase(type) || "text/xml".equalsIgnoreCase(type)) return "shared-map.tmx";
        if ("application/java-archive".equalsIgnoreCase(type) || "application/x-java-archive".equalsIgnoreCase(type))
            return "shared-extension.jar";
        if ("application/zip".equalsIgnoreCase(type) || "application/x-zip-compressed".equalsIgnoreCase(type))
            return "shared-extension.zip";
        return provided != null ? provided : last == null ? "" : last;
    }

    private static boolean hasSupportedSuffix(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".rwmod") || lower.endsWith(".tmx") || lower.endsWith(".jar") || lower.endsWith(".zip");
    }

    /** Strips path components and platform-invalid characters before a name reaches a destination. */
    public static String safeFileName(String displayName) {
        if (displayName == null) return "";
        String name = displayName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[\\u0000-\\u001f\\u007f<>:\\x22|?*]", "_").trim();
        while (name.startsWith(".")) name = name.substring(1);
        while (name.endsWith(".") || name.endsWith(" ")) name = name.substring(0, name.length() - 1);
        if (name.length() > 140) {
            int dot = name.lastIndexOf('.');
            String extension = dot > 0 && name.length() - dot <= 12 ? name.substring(dot) : "";
            name = name.substring(0, Math.min(140 - extension.length(), dot > 0 ? dot : name.length())) + extension;
        }
        return name;
    }

    private static Kind kindOf(String name) throws IOException {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".rwmod")) return Kind.MOD;
        if (lower.endsWith(".tmx")) return Kind.MAP;
        if (lower.endsWith(".jar") || lower.endsWith(".zip")) return Kind.EXTENSION;
        throw new IOException("Unsupported file type. Choose a .rwmod, .tmx, .jar, or .zip file.");
    }

    public static synchronized Request stage(Context context, Uri uri) throws IOException {
        if (uri == null || !("content".equals(uri.getScheme()) || "file".equals(uri.getScheme())))
            throw new IOException("Unsupported document URI");
        File directory = directory(context);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot prepare private import storage");
        removeInterruptedTemps(directory);

        String name = safeFileName(displayName(context, uri));
        Kind kind = kindOf(name);
        if (kind == Kind.MOD && !name.endsWith(".rwmod")) name = name.substring(0, name.length() - 6) + ".rwmod";
        if (kind == Kind.MAP && !name.endsWith(".tmx")) name = name.substring(0, name.length() - 4) + ".tmx";
        Request previous = pending(context);
        if (previous != null && uri.toString().equals(previous.source)) return previous;

        File temporary = File.createTempFile("incoming-", ".part", directory);
        boolean complete = false;
        try {
            MessageDigest digest = sha256();
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                if (input == null) throw new IOException("The document provider could not open the file");
                byte[] buffer = new byte[32768];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    output.write(buffer, 0, length);
                    digest.update(buffer, 0, length);
                }
                output.getFD().sync();
            }
            String hash = hex(digest.digest());
            if (previous != null) {
                if (previous.name.equals(name) && previous.hash.equals(hash)) {
                    temporary.delete();
                    return previous;
                }
                throw new IOException("Another file is waiting to be imported. Finish or cancel it before opening a different file.");
            }

            String id = hash.substring(0, 24);
            File snapshot = new File(directory, PREFIX + id + SUFFIX);
            if (snapshot.exists() && !snapshot.delete()) throw new IOException("Cannot replace a stale import snapshot");
            if (!temporary.renameTo(snapshot)) {
                try (InputStream input = new FileInputStream(temporary); FileOutputStream output = new FileOutputStream(snapshot)) {
                    byte[] buffer = new byte[32768]; int length;
                    while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
                    output.getFD().sync();
                }
                if (!temporary.delete()) throw new IOException("Cannot finish private import snapshot");
            }

            Properties properties = new Properties();
            properties.setProperty("file", snapshot.getName());
            properties.setProperty("name", name);
            properties.setProperty("kind", kind.name());
            properties.setProperty("id", id);
            properties.setProperty("hash", hash);
            properties.setProperty("source", uri.toString());
            AtomicFile marker = marker(context);
            FileOutputStream output = marker.startWrite();
            try { properties.store(output, "Pending external file import"); marker.finishWrite(output); }
            catch (IOException | RuntimeException failure) { marker.failWrite(output); snapshot.delete(); throw failure; }
            complete = true;
            return new Request(snapshot, name, kind, id, uri.toString(), hash);
        } finally { if (!complete) temporary.delete(); }
    }

    public static synchronized Request pending(Context context) throws IOException {
        AtomicFile marker = marker(context);
        Properties properties = new Properties();
        try (InputStream input = marker.openRead()) { properties.load(input); }
        catch (FileNotFoundException absent) { return null; }
        String filename = properties.getProperty("file", "");
        File root = directory(context).getCanonicalFile();
        File file = new File(root, filename).getCanonicalFile();
        if (!root.equals(file.getParentFile()) || !filename.startsWith(PREFIX) || !filename.endsWith(SUFFIX) || !file.isFile()) {
            marker.delete(); return null;
        }
        try {
            Kind kind = Kind.valueOf(properties.getProperty("kind", ""));
            String name = safeFileName(properties.getProperty("name", ""));
            if (!name.equals(properties.getProperty("name", "")) || kind != kindOf(name)) {
                marker.delete(); return null;
            }
            String id = properties.getProperty("id", "");
            String hash = properties.getProperty("hash", "");
            if (!filename.equals(PREFIX + id + SUFFIX) || hash.length() != 64 || !id.equals(hash.substring(0, 24))) {
                marker.delete(); return null;
            }
            return new Request(file, name, kind, id, properties.getProperty("source", ""), hash);
        } catch (IllegalArgumentException | IOException invalid) { marker.delete(); return null; }
    }

    public static synchronized void discard(Context context, Request request) throws IOException {
        Request current = pending(context);
        if (current != null && current.id.equals(request.id)) marker(context).delete();
        if (request.file.exists() && !request.file.delete()) throw new IOException("Cannot remove staged import");
    }

    private static MessageDigest sha256() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IOException("SHA-256 is unavailable", impossible); }
    }

    private static void removeInterruptedTemps(File directory) {
        File[] leftovers = directory.listFiles((parent, name) -> name.startsWith("incoming-") && name.endsWith(".part"));
        if (leftovers == null) return;
        for (File leftover : leftovers) leftover.delete();
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }
}
