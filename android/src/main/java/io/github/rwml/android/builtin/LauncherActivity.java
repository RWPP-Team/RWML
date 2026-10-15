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
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import io.github.rwml.android.AndroidBootstrap;
import io.github.rwml.android.AndroidUiHost;
import io.github.rwml.android.HostLog;
import io.github.rwml.android.RestartActivity;
import io.github.rwml.api.i18n.Messages;
import io.github.rwml.api.ui.UiHooks;
import io.github.rwml.api.ui.UiHost;
import io.github.rwml.api.ui.UiServices;

import java.io.File;
import java.io.IOException;
import java.util.Collections;

/** The builtin launcher remains usable while the engine is absent, loading or failed. */
public final class LauncherActivity extends Activity {
    public static final String EXTRA_ERROR = "rwml.launchError";

    private UiHost host;
    private UiServices services;
    private Messages messages;
    private TextView status;
    private TextView log;
    private ProgressBar progress;
    private Button start;
    private Button extensions;
    private Button clear;
    private Button language;
    private Button restart;
    private Button exit;
    private TextView hint;
    private boolean busy;
    private boolean ready;
    private boolean failed;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        host = new AndroidUiHost(this, Collections.emptyList());
        AndroidBootstrap runtime = AndroidBootstrap.get(this);
        try {
            services = host.services();
            messages = services.messages();
        } catch (RuntimeException unavailable) {
            try {
                messages = new Messages(new File(getFilesDir(), "rwml/data").toPath(),
                    new UiHooks(failure -> HostLog.error("UI callback failed", failure)));
            } catch (IOException failure) { throw new IllegalStateException(failure); }
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);
        status = new TextView(this);
        status.setTextSize(18);
        root.addView(status);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress);
        LinearLayout actions = new LinearLayout(this);
        start = button(actions, this::startGame);
        extensions = button(actions, () -> host.openScreen("extensions"));
        language = button(actions, () -> LanguageChoice.show(this, messages, this::labels));
        restart = button(actions, this::restart);
        root.addView(actions);
        hint = new TextView(this);
        root.addView(hint);
        ScrollView scroll = new ScrollView(this);
        log = new TextView(this);
        log.setTextIsSelectable(true);
        log.setTypeface(android.graphics.Typeface.MONOSPACE);
        log.setTextSize(11);
        scroll.addView(log);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout tools = new LinearLayout(this);
        clear = button(tools, () -> work(() -> {
            runtime.clear();
            runOnUiThread(() -> { ready = false; status.setText(messages.text("launcher.cleared")); });
        }));
        exit = button(tools, this::finish);
        exit.setText(messages.text("launcher.exit"));
        root.addView(tools);
        setContentView(root);
        for (String line : runtime.log()) { log.append(line + "\n"); }
        String error = getIntent().getStringExtra(EXTRA_ERROR);
        failed = error != null;
        if (error != null) { log.append(error); }
        labels();
    }

    private Button button(LinearLayout parent, Runnable action) {
        Button button = new Button(this);
        parent.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private void labels() {
        setTitle(messages.text("launcher.title"));
        status.setText(messages.text(busy ? "launcher.loading" : failed ? "launcher.failed"
            : ready ? "launcher.ready" : "launcher.subtitle"));
        start.setText(messages.text(ready ? "launcher.continue" : "launcher.start"));
        extensions.setText(messages.text("extensions.title"));
        clear.setText(messages.text("launcher.clear"));
        language.setText(messages.text("language.title"));
        restart.setText(messages.text("launcher.restart"));
        exit.setText(messages.text("launcher.exit"));
        hint.setText(messages.text("launcher.diagnostics"));
        start.setEnabled(!busy && !failed && services != null && !services.extensions().restartRequired()
            && !services.extensions().versionSelectionRequired());
        extensions.setEnabled(!busy && services != null);
        clear.setEnabled(!busy);
        restart.setEnabled(!busy);
        language.setEnabled(!busy);
        progress.setIndeterminate(busy);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (services != null && services.extensions().versionSelectionRequired()) {
            status.setText(messages.text("extensions.chooseVersionRequired"));
        }
        if (services != null && services.extensions().restartRequired()) {
            status.setText(messages.text(AndroidBootstrap.get(this).manager().recoveryRequired()
                ? "launcher.extensionFailure" : "extensions.changed"));
        }
    }

    private void startGame() {
        if (ready) { host.openScreen("main-menu"); return; }
        work(() -> {
            io.github.rwml.api.game.StageReport report = host.gameBoot().start((percent, message) ->
                runOnUiThread(() -> {
                    if (isDestroyed()) { return; }
                    progress.setIndeterminate(percent <= 0);
                    progress.setProgress(percent);
                    log.append(percent + "% " + message + "\n");
                }));
            ready = report.isClean();
            failed = !ready;
            if (!ready) { runOnUiThread(() -> log.append(report.failed().toString() + "\n")); }
            if (ready) { runOnUiThread(() -> host.openScreen("main-menu")); }
        });
    }

    private interface Work { void run() throws Exception; }

    private void work(Work work) {
        if (busy) { return; }
        busy = true;
        labels();
        new Thread(() -> {
            try { work.run(); }
            catch (Throwable failure) {
                failed = true;
                HostLog.error("Launcher operation failed", failure);
                runOnUiThread(() -> log.append(android.util.Log.getStackTraceString(failure)));
            } finally {
                runOnUiThread(() -> { busy = false; if (!isDestroyed()) { labels(); } });
            }
        }, "rwml-launcher").start();
    }

    private void restart() {
        startActivity(new Intent(this, RestartActivity.class)
            .putExtra("rwml.oldPid", android.os.Process.myPid()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (messages != null) { labels(); }
    }
}
