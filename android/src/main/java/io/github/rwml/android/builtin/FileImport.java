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
import android.net.Uri;
import android.util.Log;

import com.corrodinggames.rts.gameFramework.files.FileLoader;

import java.io.InputStream;
import java.io.OutputStream;

/** Import system-selected document streams into the game's direct filesystem backend. */
final class FileImport {

    private static final String TAG = "RWML";

    private FileImport() {
    }

    /**
     * 请求平台选择一个文件。
     *
     * @return 是否成功打开文件选择器
     */
    static boolean choose(Activity from, int requestCode) {
        try {
            from.startActivityForResult(io.github.rwml.android.ArchiveImports.pickerIntent(), requestCode);
            return true;
        } catch (android.content.ActivityNotFoundException unavailable) {
            Log.w(TAG, "No system file picker answered", unavailable);
            return false;
        }
    }

    /** 平台报告的选中文件名称；平台未提供名称时返回 {@code null}。 */
    static String nameOf(Context context, Uri uri) {
        if ("content".equals(uri.getScheme())) {
            try (android.database.Cursor cursor=context.getContentResolver().query(uri,
                    new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)) {
                if(cursor!=null && cursor.moveToFirst() && !cursor.isNull(0)) return new java.io.File(cursor.getString(0)).getName();
            } catch(RuntimeException unavailable) { Log.w(TAG,"No provider filename",unavailable); }
        }
        return uri.getLastPathSegment();
    }

    /**
     * 将选中的文件复制到游戏存储空间。
     *
     * @param targetPath {@link FileLoader} 使用的抽象存储路径
     * @return 成功时返回 {@code null}，否则返回失败原因
     */
    static String copyInto(Context context, Uri source, String targetPath) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = "file".equals(source.getScheme()) ? new java.io.FileInputStream(new java.io.File(source.getPath()))
                : context.getContentResolver().openInputStream(source);
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

    /** 按引擎文件层所需的格式拼接目录和文件名。 */
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
