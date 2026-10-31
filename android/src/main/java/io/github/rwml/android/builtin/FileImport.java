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
 * 通过平台文件选择器将文件导入游戏自己的存储空间。
 *
 * <p>游戏自身的界面通过存储访问框架浏览目录，但 RWML 挂载的归档并未包含该框架。改用平台文件选择器：界面请求
 * 选择一个文件，然后将选中的文件复制到游戏存储目录中。</p>
 *
 * <p>文件名采用平台报告的名称，而不是 URI 的最后一段，因为选择器返回的是合成 URI，其末尾与文件名无关。复制操作
 * 通过引擎自身的文件层完成，因此文件会写入引擎读取的位置，无论设置中选择了哪种存储模式。</p>
 */
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

    /** 平台报告的选中文件名称；平台未提供名称时返回 {@code null}。 */
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
     * 将选中的文件复制到游戏存储空间。
     *
     * @param targetPath {@link FileLoader} 使用的抽象存储路径
     * @return 成功时返回 {@code null}，否则返回失败原因
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
