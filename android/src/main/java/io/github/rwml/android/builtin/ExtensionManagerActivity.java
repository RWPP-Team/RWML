/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import io.github.rwml.android.AndroidBootstrap;
import io.github.rwml.android.ArchiveImports;
import io.github.rwml.android.HostLog;
import io.github.rwml.android.MainActivity;
import io.github.rwml.android.RestartActivity;
import io.github.rwml.api.extensions.ExtensionEntry;
import io.github.rwml.api.extensions.ExtensionPackReport;
import io.github.rwml.api.i18n.Messages;
import io.github.rwml.api.ui.UiHooks;
import io.github.rwml.api.ui.UiServices;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 管理功能独立于游戏 dex，即使游戏启动失败仍可使用。 */
public final class ExtensionManagerActivity extends Activity {

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private UiServices services;
    private Messages messages;
    private LinearLayout rows;
    private TextView status;
    private EditText search;
    private AutoCloseable changes;
    private boolean busy;
    private int pending;
    private boolean importOffered;
    private AlertDialog importDialog;
    private static final int PICK_ARCHIVE = 402;
    private List<ExtensionEntry> entries = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try { services = AndroidBootstrap.get(this).ui(); }
        catch (IllegalStateException unavailable) {
            startActivity(new Intent(getIntent()).setClass(this, MainActivity.class)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            finish();
            return;
        }
        messages = services.messages();
        importOffered = false;
        layout();
    }

    private void layout() {
        setTitle(messages.text("extensions.title"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (12 * getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);
        status = new TextView(this);
        root.addView(status);
        LinearLayout actions = new LinearLayout(this);
        button(actions, messages.text("extensions.import"), this::chooseArchive);
        button(actions, messages.text("extensions.browseTab"),
            () -> startActivity(new Intent(this, ExtensionCatalogActivity.class)));
        button(actions, messages.text("extensions.refresh"), this::refresh);
        button(actions, messages.text("language.title"), () -> LanguageChoice.show(this, messages, () -> {
            layout();
            refresh();
        }));
        button(actions, messages.text("launcher.restart"), () -> startActivity(
            new Intent(this, RestartActivity.class).putExtra("rwml.oldPid", android.os.Process.myPid())));
        root.addView(actions);
        TextView directory = new TextView(this);
        directory.setText(messages.text("extensions.directory") + ": " + services.extensions().directory());
        directory.setTextIsSelectable(true);
        root.addView(directory);
        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint(messages.text("extensions.search"));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { render(); }
            @Override public void afterTextChanged(Editable text) { }
        });
        root.addView(search);
        ScrollView scroll = new ScrollView(this);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        button(root, messages.text("common.close"), this::finish);
        setContentView(root);
        render();
    }

    private void render() {
        if (rows == null) { return; }
        rows.removeAllViews();
        status.setText(messages.text(busy ? "extensions.working" : services.extensions().restartRequired()
            ? "extensions.changed" : services.extensions().versionSelectionRequired()
            ? "extensions.chooseVersionRequired" : "extensions.nextLaunch"));
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        int count = 0;
        for (ExtensionEntry entry : entries) {
            if (!(entry.displayName(messages) + " " + entry.name() + " " + entry.id())
                .toLowerCase(Locale.ROOT).contains(query)) { continue; }
            count++;
            TextView name = new TextView(this);
            name.setText(entry.displayName(messages) + "  " + entry.version());
            name.setTextSize(18);
            name.setPadding(0, 12, 0, 0);
            rows.addView(name);
            TextView detail = new TextView(this);
            List<String> details = new ArrayList<>();
            if (entry.valid()) { details.add(entry.id()); }
            List<String> states = new ArrayList<>();
            states.add(status(entry));
            if (entry.loaded()) { states.add(messages.text("extensions.loaded")); }
            if (entry.builtIn()) { states.add(messages.text("extensions.builtin")); }
            details.add(String.join(" · ", states));
            if (!entry.problem().isEmpty()) { details.add(entry.problem()); }
            detail.setText(String.join("\n", details));
            rows.addView(detail);
            LinearLayout actions = new LinearLayout(this);
            Button toggle = button(actions, messages.text(entry.enabled() ? "extensions.disable" : "extensions.enable"),
                () -> run(() -> services.extensions().setEnabled(entry.id(), !entry.enabled())));
            toggle.setEnabled(entry.valid() && !busy);
            if (entry.valid() && entry.source() != null && !entry.versionSelected()) {
                button(actions, messages.text("extensions.useVersion"), () ->
                    new AlertDialog.Builder(this).setTitle(messages.text("extensions.chooseVersion"))
                        .setMessage(messages.text("extensions.useVersionConfirm", entry.displayName(messages), entry.version()))
                        .setNegativeButton(messages.text("common.cancel"), null)
                        .setPositiveButton(messages.text("common.confirm"), (dialog, which) ->
                            run(() -> services.extensions().selectVersion(entry.source()))).show());
            }
            button(actions, messages.text("extensions.details"), () -> details(entry));
            Button remove = button(actions, messages.text("extensions.remove"), () ->
                new AlertDialog.Builder(this).setMessage(messages.text("extensions.removeConfirm", entry.name()))
                    .setNegativeButton(messages.text("common.cancel"), null)
                    .setPositiveButton(messages.text("common.confirm"), (dialog, which) ->
                        run(() -> services.extensions().removeArchive(entry.source()))).show());
            remove.setEnabled(!entry.builtIn() && !busy);
            rows.addView(actions);
        }
        if (count == 0) {
            TextView empty = new TextView(this);
            empty.setText(messages.text(entries.isEmpty() ? "extensions.empty" : "extensions.none"));
            rows.addView(empty);
        }
    }

    private void details(ExtensionEntry entry) {
        List<String> lines = new ArrayList<>();
        String description = entry.displayDescription(messages);
        if (!description.isEmpty()) { lines.add(description); }
        if (!entry.authors().isEmpty()) {
            lines.add(messages.text("extensions.authors") + ": " + String.join(", ", entry.authors()));
        }
        if (!entry.dependencies().isEmpty()) {
            lines.add(messages.text("extensions.dependencies") + ": " + String.join(", ", entry.dependencies()));
        }
        lines.add(messages.text("extensions.archive") + ": "
            + (entry.builtIn() ? messages.text("extensions.builtin") : entry.source()));
        new AlertDialog.Builder(this).setTitle(entry.displayName(messages))
            .setMessage(String.join("\n", lines))
            .setPositiveButton(messages.text("common.close"), null).show();
    }

    private String status(ExtensionEntry entry) {
        if (!entry.valid()) { return messages.text("extensions.invalid"); }
        if (entry.versionChoiceRequired()) {
            return messages.text(entry.enabled() ? "extensions.versionPending" : "extensions.versionPendingDisabled");
        }
        if (entry.versionSelected()) {
            return messages.text(entry.enabled() ? "extensions.versionSelected" : "extensions.versionSelectedDisabled");
        }
        return messages.text(entry.enabled() ? "extensions.versionInactive" : "extensions.versionInactiveDisabled");
    }

    private Button button(LinearLayout parent, String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(view -> { if (!busy) { action.run(); } });
        parent.addView(button, new LinearLayout.LayoutParams(-2, -2));
        return button;
    }

    private void chooseArchive() {
        try { startActivityForResult(ArchiveImports.pickerIntent(), PICK_ARCHIVE); }
        catch (android.content.ActivityNotFoundException missing) { error(missing); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_ARCHIVE && result == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null && data.getClipData() != null && data.getClipData().getItemCount() > 0)
                uri = data.getClipData().getItemAt(0).getUri();
            if (uri != null) {
                Uri selected = uri;
                importOffered = true;
                run(() -> {
                    ArchiveImports.Request staged = ArchiveImports.stage(this, selected);
                    runOnUiThread(() -> offerImport(staged));
                });
            }
        }
    }

    private void offerPendingImport() {
        if (importOffered) return;
        importOffered = true;
        run(() -> {
            ArchiveImports.Request request = ArchiveImports.pending(this);
            runOnUiThread(() -> { if (request == null) importOffered = false; else offerImport(request); });
        });
    }

    private void discardImport(ArchiveImports.Request request) {
        run(() -> ArchiveImports.discard(this, request));
        importOffered = false;
    }

    private void offerImport(ArchiveImports.Request request) {
        if (isDestroyed() || isFinishing()) return;
        if (request.isGameFile()) {
            run(() -> {
                java.io.File destination = io.github.rwml.android.GameFileImports.importFile(this, request);
                if (destination != null) runOnUiThread(() -> Toast.makeText(this,
                    messages.text("files.imported", destination.getName()), Toast.LENGTH_LONG).show());
            });
            return;
        }
        if (importDialog != null) importDialog.dismiss();
        importDialog = new AlertDialog.Builder(this).setTitle(messages.text("extensions.import"))
            .setMessage(request.name).setNegativeButton(messages.text("common.cancel"), (dialog, which) -> discardImport(request))
            .setOnCancelListener(dialog -> discardImport(request))
            .setPositiveButton(messages.text("common.confirm"), (dialog, which) -> importArchive(request, false)).show();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        importOffered = false;
        offerPendingImport();
    }

    private void importArchive(ArchiveImports.Request request, boolean replace) {
        String name = request.name;
        ExtensionEntry[] imported = new ExtensionEntry[1];
        ExtensionPackReport[] pack = new ExtensionPackReport[1];
        run(() -> {
            try {
            try (InputStream input = request.open()) {
                if (name != null && name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    pack[0] = services.extensions().importPack(input, replace);
                } else {
                    imported[0] = services.extensions().importArchive(input, replace);
                }
            }
            ArchiveImports.discard(this, request);
            } catch (FileAlreadyExistsException exists) {
                runOnUiThread(() -> {
                    if (isDestroyed()) { return; }
                    new AlertDialog.Builder(this).setTitle(messages.text("extensions.replace"))
                        .setMessage(messages.text("extensions.replaceConfirm"))
                        .setNegativeButton(messages.text("common.cancel"), (dialog, which) -> discardImport(request))
                        .setPositiveButton(messages.text("common.confirm"),
                            (dialog, which) -> importArchive(request, true))
                        .setOnCancelListener(dialog -> discardImport(request)).show();
                });
            }
        }, () -> {
            if (pack[0] != null) { showReport(name, pack[0]); }
            else if (imported[0] != null) { chooseVersion(imported[0].id()); }
        });
    }

    /** 整合包的成员逐个安装，因此结果以汇总形式呈现。 */
    private void showReport(String file, ExtensionPackReport report) {
        if (isDestroyed() || isFinishing()) { return; }
        StringBuilder text = new StringBuilder();
        text.append(file);
        if (!report.name().isEmpty()) { text.append(" · ").append(report.name()); }
        if (!report.version().isEmpty()) { text.append(' ').append(report.version()); }
        text.append("\n\n").append(messages.text("extensions.packSummary", report.installed(),
            report.skipped().size(), report.failed().size()));
        if (!report.skipped().isEmpty()) {
            text.append("\n\n").append(messages.text("extensions.packSkipped")).append(':');
            for (String skipped : report.skipped()) { text.append("\n    ").append(skipped); }
        }
        if (!report.failed().isEmpty()) {
            text.append("\n\n").append(messages.text("extensions.packFailed")).append(':');
            for (java.util.Map.Entry<String, String> failure : report.failed().entrySet()) {
                text.append("\n    ").append(failure.getKey()).append(": ").append(failure.getValue());
            }
        }
        new AlertDialog.Builder(this).setTitle(messages.text("extensions.packTitle"))
            .setMessage(text.toString()).setPositiveButton(messages.text("common.close"), null).show();
    }

    private void chooseVersion(String id) {
        List<ExtensionEntry> versions = new ArrayList<>();
        for (ExtensionEntry entry : entries) {
            if (id.equals(entry.id()) && entry.source() != null) { versions.add(entry); }
        }
        if (versions.size() < 2) { return; }
        String[] choices = new String[versions.size()];
        int selected = 0;
        for (int index = 0; index < versions.size(); index++) {
            ExtensionEntry entry = versions.get(index);
            choices[index] = entry.version() + " · " + entry.source().getFileName();
            if (entry.versionSelected()) { selected = index; }
        }
        new AlertDialog.Builder(this).setTitle(messages.text("extensions.chooseVersion") + ": " + id)
            .setSingleChoiceItems(choices, selected, (dialog, which) -> {
                dialog.dismiss();
                run(() -> services.extensions().selectVersion(versions.get(which).source()));
            }).setNegativeButton(messages.text("common.cancel"), null).show();
    }

    private interface Work { void run() throws Exception; }

    private void run(Work work) { run(work, () -> { }); }

    private void run(Work work, Runnable completed) {
        if (isDestroyed()) { return; }
        pending++;
        busy = true;
        render();
        worker.execute(() -> {
            try {
                work.run();
                List<ExtensionEntry> updated = services.extensions().entries();
                runOnUiThread(() -> {
                    if (isDestroyed()) { return; }
                    entries = updated;
                    completed.run();
                });
            } catch (Exception failure) { runOnUiThread(() -> error(failure)); }
            finally {
                runOnUiThread(() -> {
                    pending--;
                    busy = pending > 0;
                    if (!isDestroyed()) { render(); }
                });
            }
        });
    }

    private void refresh() { if (!busy) { run(() -> { }); } }

    private void error(Exception failure) {
        HostLog.error("Extension management failed", failure);
        if (isDestroyed() || isFinishing()) { return; }
        new AlertDialog.Builder(this).setTitle(messages.text("common.error"))
            .setMessage(failure.getMessage()).setPositiveButton(messages.text("common.close"), null).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (services == null) { return; }
        changes = services.hooks().subscribe(UiHooks.EXTENSIONS_CHANGED, this::runOnUiThread, ignored -> refresh());
        refresh();
        offerPendingImport();
    }

    @Override
    protected void onPause() {
        try { if (changes != null) { changes.close(); changes = null; } }
        catch (Exception failure) { HostLog.error("Cannot close UI subscription", failure); }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (importDialog != null) importDialog.dismiss();
        worker.shutdown();
        super.onDestroy();
    }
}
