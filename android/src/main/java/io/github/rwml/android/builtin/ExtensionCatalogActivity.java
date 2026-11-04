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
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import io.github.rwml.android.AndroidBootstrap;
import io.github.rwml.android.HostLog;
import io.github.rwml.android.MainActivity;
import io.github.rwml.api.extensions.ExtensionEntry;
import io.github.rwml.api.i18n.Messages;
import io.github.rwml.api.repository.AvailableUpdate;
import io.github.rwml.api.repository.Catalog;
import io.github.rwml.api.repository.CatalogEntry;
import io.github.rwml.api.repository.ExtensionArtifact;
import io.github.rwml.api.repository.ExtensionRepository;
import io.github.rwml.api.repository.InstallReport;
import io.github.rwml.api.ui.UiHooks;
import io.github.rwml.api.ui.UiServices;
import io.github.rwml.core.ExtensionVersion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 扩展仓库浏览器：搜索目录、查看详情、安装与更新。
 *
 * <p>目录读取与归档下载都在工作线程执行，界面更新切回主线程。安装完成后发布 {@link UiHooks#EXTENSIONS_CHANGED}，
 * 已安装列表据此刷新。此界面只依赖 core，游戏引擎未启动时同样可用。</p>
 */
public final class ExtensionCatalogActivity extends Activity {

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private UiServices services;
    private Messages messages;
    private LinearLayout rows;
    private TextView status;
    private EditText search;
    private ProgressBar progress;
    private AutoCloseable changes;
    private AutoCloseable language;

    private List<CatalogEntry> entries = new ArrayList<>();
    private Map<String, ExtensionEntry> installed = new LinkedHashMap<>();
    private String generatedAt = "";
    private String offline = "";
    private String working = "";
    private boolean busy;
    private boolean loaded;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            services = AndroidBootstrap.get(this).ui();
        } catch (IllegalStateException unavailable) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }
        messages = services.messages();
        layout();
        load(false);
    }

    private void layout() {
        setTitle(messages.text("extensions.browse"));
        int padding = (int) (12 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);

        status = new TextView(this);
        root.addView(status);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress);

        LinearLayout actions = new LinearLayout(this);
        button(actions, messages.text("extensions.browseRefresh"), () -> load(true));
        button(actions, messages.text("extensions.updateAll"), this::updateAll);
        button(actions, messages.text("language.title"),
            () -> LanguageChoice.show(this, messages, this::layout));
        root.addView(actions);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint(messages.text("extensions.browseSearch"));
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
        Button close = new Button(this);
        close.setText(messages.text("common.close"));
        close.setOnClickListener(view -> finish());
        root.addView(close, new LinearLayout.LayoutParams(-2, -2));
        setContentView(root);
        render();
    }

    /** 界面状态与列表都由同一组数据重建，避免下载进度与列表状态分离。 */
    private void render() {
        if (rows == null) { return; }
        rows.removeAllViews();
        boolean supported = services.hasRepository();
        status.setText(statusText());
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        progress.setIndeterminate(busy);

        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        int count = 0;
        for (CatalogEntry entry : entries) {
            if (!matches(entry, query)) { continue; }
            count++;
            rows.addView(row(entry));
        }
        if (count == 0) {
            TextView empty = new TextView(this);
            empty.setText(!supported ? messages.text("extensions.browseNoRepository")
                : loaded ? messages.text("extensions.browseEmpty")
                : messages.text("extensions.browseLoading"));
            empty.setPadding(0, 12, 0, 12);
            rows.addView(empty);
        }
    }

    private View row(CatalogEntry entry) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 10, 0, 6);

        TextView name = new TextView(this);
        name.setText(entry.name() + "  " + entry.version());
        name.setTextSize(18);
        row.addView(name);

        TextView detail = new TextView(this);
        detail.setText(badge(entry));
        row.addView(detail);
        if (!entry.summary().isEmpty()) {
            TextView summary = new TextView(this);
            summary.setText(entry.summary());
            row.addView(summary);
        }

        LinearLayout actions = new LinearLayout(this);
        boolean actionable = entry.supports(platform()) && !entry.artifactsFor(platform()).isEmpty();
        Button install = button(actions, installLabel(entry), () -> install(entry));
        install.setEnabled(actionable && !busy);
        button(actions, messages.text("extensions.details"), () -> details(entry));
        row.addView(actions);
        if (!actionable) {
            TextView unsupported = new TextView(this);
            unsupported.setText(messages.text("extensions.platforms") + ": "
                + String.join(", ", entry.platforms()));
            row.addView(unsupported);
        }
        return row;
    }

    private String badge(CatalogEntry entry) {
        ExtensionEntry current = installed.get(entry.id());
        if (current == null) {
            return messages.text("extensions.notInstalled");
        }
        int order = ExtensionVersion.compare(entry.version(), current.version());
        return order > 0
            ? messages.text("extensions.update") + " · " + current.version() + " → " + entry.version()
            : messages.text("extensions.upToDate") + " · " + current.version();
    }

    private String installLabel(CatalogEntry entry) {
        ExtensionEntry current = installed.get(entry.id());
        if (current == null) { return messages.text("extensions.install"); }
        return ExtensionVersion.compare(entry.version(), current.version()) > 0
            ? messages.text("extensions.update") : messages.text("extensions.reinstall");
    }

    private String statusText() {
        if (!services.hasRepository()) {
            return messages.text("extensions.browseNoRepository");
        }
        if (!working.isEmpty()) {
            return messages.text("extensions.downloading", working);
        }
        if (busy) {
            return messages.text("extensions.browseLoading");
        }
        if (!offline.isEmpty()) {
            return messages.text("extensions.browseOffline", offline);
        }
        int updates = availableUpdates().size();
        String text = updates > 0
            ? messages.text("extensions.updatesAvailable", updates)
            : messages.text("extensions.noUpdates");
        if (!generatedAt.isEmpty()) {
            text = text + " · " + messages.text("extensions.repositoryCache", generatedAt);
        }
        return text;
    }

    private boolean matches(CatalogEntry entry, String query) {
        if (query.isEmpty()) { return true; }
        for (String token : query.split("\\s+")) {
            if (token.isEmpty()) { continue; }
            boolean hit = entry.id().toLowerCase(Locale.ROOT).contains(token)
                || entry.name().toLowerCase(Locale.ROOT).contains(token)
                || entry.summary().toLowerCase(Locale.ROOT).contains(token)
                || entry.keywords().contains(token);
            if (!hit) { return false; }
        }
        return true;
    }

    /** 详情沿用目录中的元数据，安装前无需读取归档。 */
    private void details(CatalogEntry entry) {
        List<String> lines = new ArrayList<>();
        lines.add(entry.name() + "  " + entry.version());
        if (!entry.id().equals(entry.name())) { lines.add(entry.id()); }
        if (!entry.summary().isEmpty()) { lines.add(""); lines.add(entry.summary()); }
        ExtensionEntry current = installed.get(entry.id());
        lines.add("");
        lines.add(messages.text("extensions.latestVersion") + ": " + entry.version());
        lines.add(messages.text("extensions.installedVersion") + ": "
            + (current == null ? messages.text("extensions.notInstalled") : current.version()));
        if (!entry.authors().isEmpty()) {
            lines.add(messages.text("extensions.authors") + ": " + String.join(", ", entry.authors()));
        }
        if (!entry.tags().isEmpty()) {
            lines.add(messages.text("extensions.tags") + ": " + String.join(", ", entry.tags()));
        }
        if (!entry.platforms().isEmpty()) {
            lines.add(messages.text("extensions.platforms") + ": " + String.join(", ", entry.platforms()));
        }
        if (!entry.license().isEmpty()) {
            lines.add(messages.text("extensions.license") + ": " + entry.license());
        }
        if (entry.downloads() > 0) {
            lines.add(messages.text("extensions.downloads") + ": " + entry.downloads());
        }
        if (!entry.homepage().isEmpty()) {
            lines.add(messages.text("extensions.homepage") + ": " + entry.homepage());
        }
        long size = 0;
        for (ExtensionArtifact artifact : entry.artifactsFor(platform())) { size += artifact.size(); }
        if (size > 0) {
            lines.add(messages.text("extensions.archive") + ": " + (size / 1024) + " KiB");
        }
        if (!entry.description().isEmpty()) { lines.add(""); lines.add(entry.description()); }
        if (!entry.notes().isEmpty()) {
            lines.add("");
            lines.add(messages.text("extensions.notes") + ":");
            lines.add(entry.notes());
        }
        new AlertDialog.Builder(this).setTitle(entry.name())
            .setMessage(String.join("\n", lines))
            .setPositiveButton(messages.text("common.close"), null).show();
    }

    private void load(boolean force) {
        if (busy || !services.hasRepository()) { render(); return; }
        busy = true;
        offline = "";
        working = "";
        render();
        ExtensionRepository repository = services.repository();
        worker.execute(() -> {
            try {
                Catalog catalog = force ? repository.refresh() : repository.catalog();
                List<CatalogEntry> sorted = new ArrayList<>(catalog.entries());
                sorted.sort(Comparator.comparing(CatalogEntry::name, String.CASE_INSENSITIVE_ORDER));
                Map<String, ExtensionEntry> versions = installedVersions(services.extensions().entries());
                runOnUiThread(() -> {
                    entries = sorted;
                    installed = versions;
                    generatedAt = catalog.generatedAt();
                    loaded = true;
                    busy = false;
                    render();
                });
            } catch (Exception failure) {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                HostLog.error("The extension repository is unavailable", failure);
                runOnUiThread(() -> {
                    offline = messageOf(cause);
                    loaded = true;
                    busy = false;
                    render();
                });
            }
        });
    }

    private void install(CatalogEntry entry) {
        if (busy || !services.hasRepository()) { return; }
        busy = true;
        working = entry.name();
        render();
        String name = entry.name();
        ExtensionRepository repository = services.repository();
        worker.execute(() -> {
            try {
                InstallReport report = repository.install(entry.id(), (downloaded, total, message) ->
                    runOnUiThread(() -> progress(downloaded, total, name)));
                runOnUiThread(() -> {
                    busy = false;
                    working = "";
                    afterInstall(entry, report);
                });
            } catch (Exception failure) {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                HostLog.error("Cannot install " + entry.id(), failure);
                runOnUiThread(() -> {
                    busy = false;
                    working = "";
                    error(messages.text("extensions.installFailed", name, messageOf(cause)));
                    render();
                });
            }
        });
    }

    private void progress(long downloaded, long total, String name) {
        if (isDestroyed()) { return; }
        int percent = total > 0 ? (int) Math.min(100, downloaded * 100 / total) : 0;
        progress.setIndeterminate(total <= 0);
        progress.setProgress(percent);
        status.setText(messages.text("extensions.downloading", name)
            + (total > 0 ? " " + percent + "%" : ""));
    }

    private void afterInstall(CatalogEntry entry, InstallReport report) {
        if (!report.failed().isEmpty()) {
            String reason = String.join("; ", report.failed().values());
            error(messages.text("extensions.installFailed", entry.name(), reason));
        } else if (report.requiresRestart()) {
            Toast.makeText(this,
                messages.text("extensions.installDone", entry.name(), report.version()),
                Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, messages.text("extensions.upToDate"), Toast.LENGTH_SHORT).show();
        }
        services.hooks().publish(UiHooks.EXTENSIONS_CHANGED, null);
        load(false);
    }

    /** 逐个安装落后于目录的扩展，单个失败不中断其余更新。 */
    private void updateAll() {
        List<AvailableUpdate> updates = availableUpdates();
        if (updates.isEmpty() || busy || !services.hasRepository()) { return; }
        busy = true;
        working = messages.text("extensions.updateAll");
        render();
        ExtensionRepository repository = services.repository();
        worker.execute(() -> {
            int count = 0;
            for (AvailableUpdate update : updates) {
                try {
                    InstallReport report = repository.install(update.id(), repository.platform(),
                        (downloaded, total, message) -> runOnUiThread(() ->
                            progress(downloaded, total, update.entry().name())));
                    if (report.requiresRestart()) { count++; }
                } catch (Exception failure) {
                    HostLog.error("Cannot update " + update.id(), failure);
                }
            }
            int updated = count;
            runOnUiThread(() -> {
                busy = false;
                working = "";
                status.setText(messages.text("extensions.updateDone", updated));
                services.hooks().publish(UiHooks.EXTENSIONS_CHANGED, null);
                load(false);
            });
        });
    }

    private List<AvailableUpdate> availableUpdates() {
        List<AvailableUpdate> updates = new ArrayList<>();
        for (CatalogEntry entry : entries) {
            ExtensionEntry current = installed.get(entry.id());
            if (current == null || !entry.supports(platform())) { continue; }
            if (ExtensionVersion.compare(entry.version(), current.version()) > 0) {
                updates.add(new AvailableUpdate(entry.id(), current.version(), entry));
            }
        }
        return updates;
    }

    private String platform() {
        return services.hasRepository() ? services.repository().platform() : "android";
    }

    private Button button(LinearLayout parent, String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(view -> { if (!busy) { action.run(); } });
        parent.addView(button, new LinearLayout.LayoutParams(-2, -2));
        return button;
    }

    private void error(String message) {
        if (isDestroyed() || isFinishing()) { return; }
        new AlertDialog.Builder(this).setTitle(messages.text("common.error"))
            .setMessage(message).setPositiveButton(messages.text("common.close"), null).show();
    }

    private static Map<String, ExtensionEntry> installedVersions(List<ExtensionEntry> entries) {
        Map<String, ExtensionEntry> result = new LinkedHashMap<>();
        for (ExtensionEntry entry : entries) {
            if (entry == null || entry.id() == null || entry.builtIn() || entry.version() == null) { continue; }
            ExtensionEntry current = result.get(entry.id());
            if (current == null || ExtensionVersion.compare(entry.version(), current.version()) > 0) {
                result.put(entry.id(), entry);
            }
        }
        return result;
    }

    private static String messageOf(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (services == null) { return; }
        changes = services.hooks().subscribe(UiHooks.EXTENSIONS_CHANGED,
            this::runOnUiThread, ignored -> { if (!busy) { load(false); } });
        language = services.hooks().subscribe(UiHooks.LANGUAGE_CHANGED,
            this::runOnUiThread, ignored -> layout());
    }

    @Override
    protected void onPause() {
        try {
            if (changes != null) { changes.close(); changes = null; }
            if (language != null) { language.close(); language = null; }
        } catch (Exception failure) { HostLog.error("Cannot close UI subscription", failure); }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
