package com.corrodinggames.rts.appFramework.android;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import com.corrodinggames.rts.appFramework.common.SAFInterface.FileRow;
import java.io.*;
import java.util.ArrayList;

/** Binary name required by the game archive. All operations use java.io; no SAF/provider API. */
public final class AndroidSAF {
    public static final String TAG = "RustedWarfare";
    private static final AndroidSAF INSTANCE = new AndroidSAF();
    private static final Object LOCK = new Object();
    private AndroidSAF() {}
    public static AndroidSAF getInstance() { return INSTANCE; }
    public static Object getLockObject() { return LOCK; }
    public static void setGlobalLocking(boolean value) {}
    public static void log(String message) { Log.d(TAG, message); }
    public static void writeStdOut(String message) { log(message); }

    private static File file(Uri uri) {
        if (uri == null) throw new IllegalArgumentException("Missing file path");
        if ("file".equals(uri.getScheme()) || uri.getScheme() == null) return new File(uri.getPath());
        // Migrate a historical local document path without contacting a document provider.
        if ("com.android.externalstorage.documents".equals(uri.getAuthority())) {
            String path = uri.getPath();
            int marker = path.lastIndexOf("/document/");
            String id = marker >= 0 ? path.substring(marker + 10) : path.substring(path.indexOf("/tree/") + 6);
            int colon = id.indexOf(':');
            if (colon > 0) {
                String volume = id.substring(0, colon);
                File root = volume.equalsIgnoreCase("primary") ? Environment.getExternalStorageDirectory() : new File("/storage", volume);
                File resolved = new File(root, id.substring(colon + 1));
                try {
                    if (!resolved.getCanonicalPath().startsWith(root.getCanonicalPath() + "/") && !resolved.getCanonicalFile().equals(root.getCanonicalFile()))
                        throw new IllegalArgumentException("File path escapes storage volume");
                } catch (IOException failure) { throw new IllegalArgumentException(failure); }
                return resolved;
            }
        }
        throw new IllegalArgumentException("Only local file paths are supported: " + uri);
    }
    private static File child(Uri parent, String name) throws FileNotFoundException {
        File root = file(parent);
        File value = new File(root, name);
        try {
            if (!root.getCanonicalFile().equals(value.getCanonicalFile().getParentFile())) throw new FileNotFoundException("Invalid file name");
        } catch (IOException failure) { throw new FileNotFoundException(failure.toString()); }
        return value;
    }
    public Uri buildDocumentUriUsingTree(Uri uri) { return Uri.fromFile(file(uri)); }
    public Uri createDirectory(Context context, Uri uri, String name) throws FileNotFoundException {
        File value = child(uri, name);
        if (!value.isDirectory() && !value.mkdir()) throw new FileNotFoundException("Cannot create " + value);
        return Uri.fromFile(value);
    }
    public Uri createFile(Context context, Uri uri, String mime, String name) throws FileNotFoundException {
        if ("vnd.android.document/directory".equals(mime)) return createDirectory(context, uri, name);
        File value = child(uri, name);
        try { if (!value.exists() && !value.createNewFile()) throw new IOException("Cannot create " + value); }
        catch (IOException failure) { throw new FileNotFoundException(failure.toString()); }
        return Uri.fromFile(value);
    }
    public Uri createTextFile(Context context, Uri uri, String name) throws FileNotFoundException { return createFile(context, uri, "", name); }
    public boolean deleteFile(Context context, Uri uri) { return file(uri).delete(); }
    public boolean exists(Context context, Uri uri) { return file(uri).exists(); }
    public Uri getChildUri(Uri uri, String id) { return Uri.fromFile(new File(id)); }
    public long getFileSize(Context context, Uri uri) { return file(uri).length(); }
    public long getLastModified(Context context, Uri uri) { return file(uri).lastModified(); }
    public String getName(Context context, Uri uri) { return file(uri).getName(); }
    public ParcelFileDescriptor getParcelFileDescriptor(Context context, Uri uri) throws IOException { return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY); }
    public String getReadablePath(Context context, Uri uri) { return file(uri).getAbsolutePath(); }
    public String getTreeDocumentId(Uri uri) { return file(uri).getAbsolutePath(); }
    public boolean isDirectory(Context context, Uri uri) { return file(uri).isDirectory(); }
    private static File[] children(Uri uri) throws IOException {
        File[] values = file(uri).listFiles();
        if (values == null) throw new IOException("Cannot list " + file(uri));
        return values;
    }
    public ArrayList<String> listByName(Context context, Uri uri) throws IOException {
        ArrayList<String> values = new ArrayList<>();
        for (File child : children(uri)) values.add(child.getName());
        return values;
    }
    public ArrayList<String> listRaw(Context context, Uri uri) throws IOException {
        ArrayList<String> values = new ArrayList<>();
        for (File child : children(uri)) values.add(child.getAbsolutePath());
        return values;
    }
    public ArrayList<FileRow> listWithDetails(Context context, Uri uri) throws IOException {
        ArrayList<FileRow> values = new ArrayList<>();
        for (File child : children(uri)) {
            FileRow row = new FileRow(); row.id = child.getAbsolutePath(); row.name = child.getName(); row.isDirectory = child.isDirectory(); values.add(row);
        }
        return values;
    }
    public InputStream read(Context context, Uri uri) throws IOException { return new FileInputStream(file(uri)); }
    public OutputStream write(Context context, Uri uri, String mode) throws IOException { return new FileOutputStream(file(uri), mode != null && mode.contains("a")); }
    public Uri renameFile(Context context, Uri uri, String name) throws FileNotFoundException {
        File original = file(uri); File target = child(Uri.fromFile(original.getParentFile()), name);
        if (target.exists() || !original.renameTo(target)) throw new FileNotFoundException("Cannot rename " + original);
        return Uri.fromFile(target);
    }
    public void takePersistableUriPermissionRead(Context context, Uri uri) { file(uri); }
    public void takePersistableUriPermissionReadAndWrite(Context context, Uri uri) { file(uri); }
}
