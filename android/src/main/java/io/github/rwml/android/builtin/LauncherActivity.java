/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import io.github.rwml.android.AndroidBootstrap;
import io.github.rwml.android.HostLog;
import io.github.rwml.android.R;
import io.github.rwml.core.LoadReport;

/** The builtin launcher; custom ui-frameworks never need to create this activity. */
public final class LauncherActivity extends Activity {
    public static final String EXTRA_ERROR = "rwml.launchError";

    private boolean ready;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        AndroidBootstrap runtime = AndroidBootstrap.get(this);
        TextView log = findViewById(R.id.log);
        String error = getIntent().getStringExtra(EXTRA_ERROR);
        StringBuilder text = new StringBuilder();
        for (String line : runtime.log()) {
            text.append(line).append('\n');
        }
        LoadReport report = runtime.report();
        if (report != null) {
            text.append("Loaded: ").append(report.loaded().size())
                .append("  Disabled: ").append(report.disabled().size())
                .append("  Failed: ").append(report.failed().size()).append('\n');
            report.failed().forEach((id, reason) -> text.append(id).append(": ").append(reason).append('\n'));
        }
        if (error != null) {
            text.append(error);
        }
        log.setText(text);
        findViewById(R.id.loadButton).setEnabled(false);
        findViewById(R.id.loadButton).setOnClickListener(view -> {
            view.setEnabled(false);
            startActivity(new Intent().setClassName(this,
                "io.github.rwml.android.builtin.MainMenuActivity"));
        });
        findViewById(R.id.clearButton).setEnabled(error != null);
        findViewById(R.id.clearButton).setOnClickListener(view -> {
            Thread worker = new Thread(() -> {
                try {
                    runtime.clear();
                    runOnUiThread(() -> log.append("\nDex cache cleared. Restart the app to rebuild it.\n"));
                } catch (Throwable failure) {
                    HostLog.error("Cache cleanup failed", failure);
                    runOnUiThread(() -> log.append("\nCache cleanup failed: " + failure));
                }
            }, "rwml-cache-cleanup");
            worker.setDaemon(true);
            worker.start();
        });
        if (error == null) {
            // Begin only after the loading page has participated in its first draw.
            getWindow().getDecorView().getViewTreeObserver().addOnDrawListener(new android.view.ViewTreeObserver.OnDrawListener() {
                private boolean started;
                @Override public void onDraw() {
                    if (started) { return; }
                    started = true;
                    getWindow().getDecorView().post(() -> {
                        getWindow().getDecorView().getViewTreeObserver().removeOnDrawListener(this);
                        Thread worker = new Thread(() -> {
                            try {
                                io.github.rwml.api.game.StageReport boot = runtime.gameBoot().start(
                                    (percent, message) -> runOnUiThread(() -> log.append("\n" + percent + "% " + message)));
                                runOnUiThread(() -> {
                                    ready = boot.isClean();
                                    if (!ready) { log.append("\n" + boot.failed()); }
                                    findViewById(R.id.loadButton).setEnabled(ready);
                                    findViewById(R.id.clearButton).setEnabled(true);
                                });
                            } catch (Throwable failure) {
                                HostLog.error("Builtin game boot failed", failure);
                                runOnUiThread(() -> { log.append("\n" + failure); findViewById(R.id.clearButton).setEnabled(true); });
                            }
                        }, "rwml-game-boot");
                        worker.setDaemon(true); worker.start();
                    });
                }
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        findViewById(R.id.loadButton).setEnabled(ready);
    }
}
