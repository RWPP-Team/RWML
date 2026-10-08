/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import com.corrodinggames.rts.gameFramework.files.FileLoader;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Bringing a file into the game's own storage through the platform's file picker.
 *
 * <p>The game's own screens browse folders through the storage-access framework, which the archive
 * RWML mounts does not carry. The platform's picker is the way in instead: a screen asks for one
 * file, and this copies what came back into a folder of the game's storage.</p>
 *
 * <p>The name is the one the platform reports, not the last path segment of the URI, because a
 * picker hands out a synthetic URI whose tail has nothing to do with the file. The copy goes
 * through the engine's own file layer so it lands where the engine reads from, whichever storage
 * mode the settings choose.</p>
 */
final class FileImport {

    private static final String TAG = "RWML";

    private FileImport() {
    }

    /**
     * Asks the platform for one file.
     *
     * @return whether a picker answered
     */
    static boolean choose(Activity from, int requestCode) {
        try {
            Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
            pick.setType("*/*");
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            from.startActivityForResult(pick, requestCode);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "No file picker answered.", t);
            return false;
        }
    }

    /** The name the platform knows a picked file by, or {@code null} when it has none. */
    static String nameOf(Context context, Uri uri) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) {
                    String name = cursor.getString(column);
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "The picked file's name could not be read.", t);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        String path = uri.getLastPathSegment();
        return path == null ? null : path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * Copies a picked file into the game's storage.
     *
     * @param targetPath the abstract storage path to write, as {@link FileLoader} names it
     * @return {@code null} on success, otherwise what went wrong
     */
    static String copyInto(Context context, Uri source, String targetPath) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = context.getContentResolver().openInputStream(source);
            if (in == null) {
                return "the chosen file could not be opened";
            }
            out = FileLoader.openOutput(targetPath, false);
            if (out == null) {
                return "the destination could not be written";
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "The file could not be copied to " + targetPath, t);
            return t.getMessage();
        } finally {
            close(out);
            close(in);
        }
    }

    /** Joins a folder and a file name in the shape the engine's file layer expects. */
    static String join(String folder, String name) {
        return folder.endsWith("/") ? folder + name : folder + "/" + name;
    }

    private static void close(java.io.Closeable stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (Throwable t) {
            Log.w(TAG, "A stream could not be closed.", t);
        }
    }
}
