/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import io.github.rwml.android.builtin.LauncherActivity;
import io.github.rwml.api.ui.UiFramework;

import java.util.Collections;

/** Platform entry point. UI ownership belongs to the selected ui-framework extension. */
public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        EngineContext.obtain(getApplicationContext());
        checkStoragePermission();
    }

    private boolean launched;
    private boolean requesting;
    private void checkStoragePermission() {
        if (launched || requesting) { return; }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            if (!android.os.Environment.isExternalStorageManager()) {
                requesting = true;
                Intent request = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName()));
                try { startActivityForResult(request, 401); }
                catch (android.content.ActivityNotFoundException ignored) {
                    startActivityForResult(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), 401);
                }
                return;
            }
        } else if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                   != android.content.pm.PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                   != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requesting = true;
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 401);
            return;
        }
        launched = true;
        Thread worker = new Thread(this::launch, "rwml-ui-loader");
        worker.setDaemon(true);
        worker.start();
    }

    private void permissionReturned() {
        requesting = false;
        boolean granted = android.os.Build.VERSION.SDK_INT >= 30
            ? android.os.Environment.isExternalStorageManager()
            : checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED
              && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (granted) { checkStoragePermission(); }
        else {
            new android.app.AlertDialog.Builder(this)
                .setMessage("RWML 需要存储权限来读取 rustedWarfare/extensions 中的扩展。")
                .setPositiveButton("授予权限", (dialog, which) -> checkStoragePermission())
                .setNegativeButton("退出", (dialog, which) -> finish())
                .setCancelable(false).show();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 401) { permissionReturned(); }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 401) { permissionReturned(); }
    }

    private void launch() {
        try {
            AndroidBootstrap runtime = AndroidBootstrap.get(this);
            if (!runtime.prepareLauncher()) { return; }
            UiFramework framework = runtime.uiFramework();
            if (framework != null) {
                framework.start(new AndroidUiHost(this, Collections.emptyList()));
            } else {
                HostLog.add("Headless: no enabled extension declares a ui-framework");
                if (!runtime.prepare()) { throw new IllegalStateException("Headless preparation failed: " + runtime.log()); }
                runtime.load((percent, message) -> HostLog.add(percent + "% " + message));
                runOnUiThread(this::finish);
            }
        } catch (Throwable failure) {
            HostLog.error("The launcher could not start", failure);
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    startActivity(new Intent(this, LauncherActivity.class)
                        .putExtra(LauncherActivity.EXTRA_ERROR, android.util.Log.getStackTraceString(failure)));
                    finish();
                }
            });
        }
    }
}
