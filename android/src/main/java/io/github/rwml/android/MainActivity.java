/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

import io.github.rwml.android.builtin.LauncherActivity;
import io.github.rwml.api.i18n.Messages;
import io.github.rwml.api.ui.UiFramework;
import io.github.rwml.api.ui.UiHooks;

import java.io.File;
import java.io.IOException;
import java.util.Collections;

/** 平台入口。界面由选中的 ui-framework 扩展负责。 */
public final class MainActivity extends ComponentActivity {
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
        android.net.Uri incoming = ArchiveImports.incomingUri(getIntent());
        if (incoming == null) checkStoragePermission();
        else stageIncoming(incoming, true);
    }

    private boolean launched;
    private boolean requesting;
    private volatile boolean externalGameFileIntent;
    private volatile Throwable incomingFailure;
    private void checkStoragePermission() {
        if (requesting) return;
        if (launched && hasStoragePermission()) return;
        launched = false;
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

    private boolean hasStoragePermission() {
        return android.os.Build.VERSION.SDK_INT >= 30
            ? android.os.Environment.isExternalStorageManager()
            : checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED
              && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** Copy the provider stream before leaving for Android's storage-permission screen. */
    private void stageIncoming(android.net.Uri uri, boolean initial) {
        Thread worker = new Thread(() -> {
            boolean staged = false;
            Throwable operationFailure = null;
            try {
                ArchiveImports.Request request = ArchiveImports.stage(this, uri);
                externalGameFileIntent = request.isGameFile();
                staged = true;
            }
            catch (Throwable failure) {
                HostLog.error("Cannot stage incoming game or extension file", failure);
                if (initial) incomingFailure = failure;
                operationFailure = failure;
            }
            if (!initial && staged && hasStoragePermission()) {
                try { consumeIncomingGameFile(); }
                catch (Throwable failure) {
                    HostLog.error("Cannot import the incoming game file", failure);
                    operationFailure = failure;
                }
            }
            boolean operationSucceeded = staged && operationFailure == null;
            Throwable reportedFailure = operationFailure;
            runOnUiThread(() -> {
                if (initial) { checkStoragePermission(); return; }
                if (!hasStoragePermission()) { checkStoragePermission(); return; }
                if (reportedFailure != null) {
                    new android.app.AlertDialog.Builder(this)
                        .setMessage(reportedFailure.getMessage())
                        .setPositiveButton(android.R.string.ok, null).show();
                }
                if (operationSucceeded) routePendingExtensionIfReady();
            });
        }, "rwml-content-snapshot");
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
            if (incomingFailure != null) throw new IOException("The incoming file could not be read", incomingFailure);
            consumeIncomingGameFile();
            AndroidBootstrap runtime = AndroidBootstrap.get(this);
            if (externalGameFileIntent && runtime.gameBoot() != null && runtime.gameBoot().isStarted()) {
                runOnUiThread(this::finish);
                return;
            }
            if (!runtime.prepareLauncher()) { return; }
            ArchiveImports.Request pending = ArchiveImports.pending(this);
            if (pending != null) {
                openExtensionManager();
                return;
            }
            if (runtime.ui().extensions().versionSelectionRequired()) {
                openExtensionManager();
                return;
            }
            UiFramework framework = runtime.uiFramework();
            if (!runtime.gameBoot().isStarted()) {
                runOnUiThread(() -> {
                    startActivity(new Intent(this, LauncherActivity.class));
                    finish();
                });
                return;
            }
            if (framework != null) {
                try { framework.start(new AndroidUiHost(this, Collections.emptyList())); }
                catch (Throwable failure) {
                    runtime.manager().quarantine(runtime.uiFrameworkExtensionId(), failure);
                    runtime.ui().hooks().publish(UiHooks.EXTENSIONS_CHANGED, null);
                    throw failure;
                }
            } else {
                // 即使禁用了所有界面扩展，管理功能仍然可用。
                runOnUiThread(() -> {
                    startActivity(new Intent(this, runtime.gameBoot().isStarted()
                        ? io.github.rwml.android.builtin.MainMenuActivity.class : LauncherActivity.class));
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

    private void openExtensionManager() {
        runOnUiThread(() -> {
            startActivity(new Intent(this, io.github.rwml.android.builtin.ExtensionManagerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
        });
    }

    private void consumeIncomingGameFile() {
        try {
            ArchiveImports.Request request = ArchiveImports.pending(this);
            if (request == null || !request.isGameFile()) return;
            File destination = GameFileImports.importFile(this, request);
            if (destination == null) return;
            HostLog.add("Imported " + request.name + " to " + destination.getAbsolutePath());
            runOnUiThread(() -> android.widget.Toast.makeText(this,
                messages.text("files.imported", destination.getName()), android.widget.Toast.LENGTH_LONG).show());
        } catch (Throwable failure) {
            HostLog.error("Cannot import the incoming game file", failure);
            throw new IllegalStateException("The game file remains staged and can be retried", failure);
        }
    }

    private void routePendingExtensionIfReady() {
        try {
            if (AndroidBootstrap.get(this).launcherReady()) {
                ArchiveImports.Request request = ArchiveImports.pending(this);
                if (request != null && !request.isGameFile()) openExtensionManager();
            }
        } catch (IOException | IllegalStateException preparing) {
            // The regular startup worker routes staged extension archives after the host scan completes.
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        android.net.Uri incoming = ArchiveImports.incomingUri(intent);
        if (incoming != null) stageIncoming(incoming, false);
    }
}
