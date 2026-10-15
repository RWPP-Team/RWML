package com.corrodinggames.rts.appFramework.android;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build.VERSION;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.util.Log;

import com.corrodinggames.rts.appFramework.common.SAFInterface.FileRow;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * The Storage Access Framework helper the game's file readers talk to.
 *
 * <p>The platform-independent game archive does not carry this class - it belongs to the Android
 * build - yet three of its readers reference it by this exact name, and one of them calls
 * {@link #getInstance()} from its static initializer. This shim restores the class against the real
 * platform API, following the Android build's own implementation, so the archive the device mounts
 * resolves and the readers' calls behave as the game expects.</p>
 */
public class AndroidSAF {

    public static final String TAG = "RustedWarfare";

    private static final Object globalLock = new Object();
    private static final AndroidSAF instance = new AndroidSAF();
    private static boolean useGlobalLocking = false;

    private AndroidSAF() {
    }

    public static AndroidSAF getInstance() {
        return instance;
    }

    public static Object getLockObject() {
        return useGlobalLocking ? globalLock : new Object();
    }

    public static void setGlobalLocking(boolean locking) {
        useGlobalLocking = locking;
    }

    public static void log(String message) {
        Log.d(TAG, message);
    }

    public static void writeStdOut(String message) {
        Log.d(TAG, message);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                // Ignored, as the game's own helper does.
            }
        }
    }

    private static long queryForLong(Context context, Uri uri, String column, long fallback) {
        synchronized (getLockObject()) {
            Cursor cursor = null;
            try {
                cursor = context.getContentResolver()
                        .query(uri, new String[] {column}, null, null, null);
                if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                    return cursor.getLong(0);
                }
                return fallback;
            } catch (Exception e) {
                Log.w(TAG, "Failed query (column:" + column + "): " + e);
                return fallback;
            } finally {
                closeQuietly(cursor);
            }
        }
    }

    private static String queryForString(Context context, Uri uri, String column, String fallback) {
        synchronized (getLockObject()) {
            Cursor cursor = null;
            try {
                cursor = context.getContentResolver()
                        .query(uri, new String[] {column}, null, null, null);
                if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                    return cursor.getString(0);
                }
                return fallback;
            } catch (Exception e) {
                Log.w(TAG, "Failed query (column:" + column + "): " + e);
                return fallback;
            } finally {
                closeQuietly(cursor);
            }
        }
    }

    private String getRawType(Context context, Uri uri) {
        return queryForString(context, uri, "mime_type", null);
    }

    public Uri buildDocumentUriUsingTree(Uri uri) {
        return DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri));
    }

    public Uri createDirectory(Context context, Uri uri, String name) throws FileNotFoundException {
        return createFile(context, uri, "vnd.android.document/directory", name);
    }

    public Uri createFile(Context context, Uri uri, String mimeType, String name)
            throws FileNotFoundException {
        synchronized (getLockObject()) {
            return DocumentsContract.createDocument(context.getContentResolver(), uri, mimeType, name);
        }
    }

    public Uri createTextFile(Context context, Uri uri, String name) throws FileNotFoundException {
        return createFile(context, uri, "", name);
    }

    public boolean deleteFile(Context context, Uri uri) throws FileNotFoundException {
        synchronized (getLockObject()) {
            return DocumentsContract.deleteDocument(context.getContentResolver(), uri);
        }
    }

    public boolean exists(Context context, Uri uri) {
        synchronized (getLockObject()) {
            Cursor cursor = null;
            try {
                cursor = context.getContentResolver()
                        .query(uri, new String[] {"document_id"}, null, null, null);
                return cursor != null && cursor.getCount() > 0;
            } catch (Exception e) {
                Log.w(TAG, "Failed exists query: " + e);
                return false;
            } finally {
                closeQuietly(cursor);
            }
        }
    }

    public Uri getChildUri(Uri uri, String documentId) {
        return DocumentsContract.buildDocumentUriUsingTree(uri, documentId);
    }

    public long getFileSize(Context context, Uri uri) {
        return queryForLong(context, uri, "_size", -1L);
    }

    public long getLastModified(Context context, Uri uri) {
        return queryForLong(context, uri, "last_modified", 0L);
    }

    public String getName(Context context, Uri uri) {
        return queryForString(context, uri, "_display_name", null);
    }

    public ParcelFileDescriptor getParcelFileDescriptor(Context context, Uri uri) throws IOException {
        synchronized (getLockObject()) {
            return context.getContentResolver().openFileDescriptor(uri, "r");
        }
    }

    public String getReadablePath(Context context, Uri uri) {
        if (uri == null) {
            return null;
        }
        log("getReadablePath() uri: " + uri.toString());
        log("getReadablePath() uri authority: " + uri.getAuthority());
        log("getReadablePath() uri path: " + uri.getPath());
        if ("com.android.externalstorage.documents".equals(uri.getAuthority())) {
            String documentId = uri.getPath();
            String[] parts = documentId.split(":");
            String type = parts[0];
            log("getReadablePath() docId: " + documentId + ", split: " + parts.length + ", type: " + type);
            String root;
            if ("/tree/primary".equalsIgnoreCase(type)) {
                root = Environment.getExternalStorageDirectory() + "/";
            } else {
                root = "storage/";
            }
            if (parts.length > 1) {
                return root + parts[1];
            }
            return root;
        }
        log("getReadablePath() uri: " + uri.toString());
        return uri.toString();
    }

    public String getTreeDocumentId(Uri uri) {
        return DocumentsContract.getTreeDocumentId(uri);
    }

    public boolean isDirectory(Context context, Uri uri) {
        return "vnd.android.document/directory".equals(getRawType(context, uri));
    }

    public ArrayList<String> listByName(Context context, Uri uri) throws IOException {
        synchronized (getLockObject()) {
            Cursor cursor = null;
            try {
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                        uri, DocumentsContract.getDocumentId(uri));
                ArrayList<String> names = new ArrayList<String>();
                cursor = context.getContentResolver()
                        .query(children, new String[] {"_display_name"}, null, null, null);
                while (cursor != null && cursor.moveToNext()) {
                    names.add(cursor.getString(0));
                }
                return names;
            } catch (Exception e) {
                throw new IOException("Failed query: " + e, e);
            } finally {
                closeQuietly(cursor);
            }
        }
    }

    public ArrayList<String> listRaw(Context context, Uri uri) throws IOException {
        synchronized (getLockObject()) {
            Cursor cursor = null;
            try {
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                        uri, DocumentsContract.getDocumentId(uri));
                ArrayList<String> ids = new ArrayList<String>();
                cursor = context.getContentResolver()
                        .query(children, new String[] {"document_id"}, null, null, null);
                while (cursor != null && cursor.moveToNext()) {
                    ids.add(cursor.getString(0));
                }
                return ids;
            } catch (Exception e) {
                throw new IOException("Failed query (listRaw): " + e, e);
            } finally {
                closeQuietly(cursor);
            }
        }
    }

    public ArrayList<FileRow> listWithDetails(Context context, Uri uri) throws IOException {
        synchronized (getLockObject()) {
            Cursor cursor = null;
            try {
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                        uri, DocumentsContract.getDocumentId(uri));
                ArrayList<FileRow> rows = new ArrayList<FileRow>();
                cursor = context.getContentResolver().query(children,
                        new String[] {"document_id", "_display_name", "mime_type"}, null, null, null);
                while (cursor != null && cursor.moveToNext()) {
                    FileRow row = new FileRow();
                    row.id = cursor.getString(0);
                    row.name = cursor.getString(1);
                    row.isDirectory = "vnd.android.document/directory".equals(cursor.getString(2));
                    rows.add(row);
                    if (row.name == null || row.name.length() == 0) {
                        log("SAF: File had no name when listing:" + uri);
                    }
                }
                return rows;
            } catch (Exception e) {
                throw new IOException("Failed query (listWithDetails): " + e, e);
            } finally {
                closeQuietly(cursor);
            }
        }
    }

    public InputStream read(Context context, Uri uri) throws IOException {
        synchronized (getLockObject()) {
            return context.getContentResolver().openInputStream(uri);
        }
    }

    public Uri renameFile(Context context, Uri uri, String name) throws FileNotFoundException {
        synchronized (getLockObject()) {
            return DocumentsContract.renameDocument(context.getContentResolver(), uri, name);
        }
    }

    public void takePersistableUriPermissionRead(Context context, Uri uri) {
        if (VERSION.SDK_INT >= 19) {
            context.getContentResolver().takePersistableUriPermission(uri, 1);
        }
    }

    public void takePersistableUriPermissionReadAndWrite(Context context, Uri uri) {
        if (VERSION.SDK_INT >= 19) {
            context.getContentResolver().takePersistableUriPermission(uri, 3);
        }
    }

    public OutputStream write(Context context, Uri uri, String mode) throws IOException {
        synchronized (getLockObject()) {
            return context.getContentResolver().openOutputStream(uri, mode);
        }
    }
}
