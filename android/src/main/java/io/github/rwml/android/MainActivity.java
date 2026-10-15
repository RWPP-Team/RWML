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
import android.widget.TextView;

import io.github.rwml.android.builtin.LauncherActivity;
import io.github.rwml.api.i18n.Messages;
import io.github.rwml.api.ui.UiFramework;
import io.github.rwml.api.ui.UiHooks;

import java.io.File;
import java.io.IOException;
import java.util.Collections;

/** Platform entry point. UI ownership belongs to the selected ui-framework extension. */
public final class MainActivity extends Activity {
    private Messages messages;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            messages = new Messages(new File(getFilesDir(), "rwml/data").toPath(),
                new UiHooks(failure -> HostLog.error("UI callback failed", failure)));
        } catch (IOException failure) { throw new IllegalStateException(failure); }
        TextView status = new TextView(this);
        status.setText(messages.text("launcher.loading"));
        status.setGravity(android.view.Gravity.CENTER);
        setContentView(status);
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
                .setTitle(messages.text("permission.title"))
                .setMessage(messages.text("permission.storage"))
                .setPositiveButton(messages.text("permission.grant"), (dialog, which) -> checkStoragePermission())
                .setNegativeButton(messages.text("launcher.exit"), (dialog, which) -> finish())
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
            if (Intent.ACTION_VIEW.equals(getIntent().getAction()) && getIntent().getData() != null) {
                openExtensionManager(getIntent());
                return;
            }
            if (runtime.ui().extensions().versionSelectionRequired()) {
                openExtensionManager(new Intent());
                return;
            }
            UiFramework framework = runtime.uiFramework();
            if (framework != null) {
                try { framework.start(new AndroidUiHost(this, Collections.emptyList())); }
                catch (Throwable failure) {
                    runtime.manager().quarantine(runtime.uiFrameworkExtensionId(), failure);
                    runtime.ui().hooks().publish(UiHooks.EXTENSIONS_CHANGED, null);
                    throw failure;
                }
            } else {
                // Management remains reachable even when every UI extension was disabled.
                runOnUiThread(() -> {
                    startActivity(new Intent(this, LauncherActivity.class));
                    finish();
                });
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

    private void openExtensionManager(Intent source) {
        runOnUiThread(() -> {
            startActivity(new Intent(this, io.github.rwml.android.builtin.ExtensionManagerActivity.class)
                .setAction(Intent.ACTION_VIEW).setData(source.getData())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            finish();
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (launched && Intent.ACTION_VIEW.equals(intent.getAction())) {
            try {
                AndroidBootstrap.get(this).ui();
                openExtensionManager(intent);
            } catch (IllegalStateException preparing) {
                // The running launcher worker reads the latest intent after preparation.
            }
        }
    }
}
