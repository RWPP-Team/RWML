/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.builtin;

import io.github.rwml.api.extensions.ExtensionEntry;
import io.github.rwml.api.i18n.Messages;
import io.github.rwml.api.repository.AvailableUpdate;
import io.github.rwml.api.repository.Catalog;
import io.github.rwml.api.repository.CatalogEntry;
import io.github.rwml.api.repository.ExtensionArtifact;
import io.github.rwml.api.repository.ExtensionRepository;
import io.github.rwml.api.repository.InstallReport;
import io.github.rwml.api.ui.UiHooks;
import io.github.rwml.api.ui.UiHost;
import io.github.rwml.api.ui.UiServices;
import io.github.rwml.core.ExtensionVersion;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 扩展仓库浏览器：搜索目录、查看详情、安装与更新。
 *
 * <p>目录读取与归档下载都在工作线程执行，界面线程只负责渲染；进度回调来自工作线程，因此每次都切回 EDT。安装完成后发布
 * {@link UiHooks#EXTENSIONS_CHANGED}，已安装列表与启动器状态据此刷新。</p>
 */
final class ExtensionCatalogPanel extends JPanel {

    private final UiServices services;
    private final Messages messages;
    private final Runnable changed;

    private final DefaultListModel<CatalogEntry> model = new DefaultListModel<>();
    private final JList<CatalogEntry> list = new JList<>(model);
    private final JTextField search = new JTextField();
    private final JTextArea details = new JTextArea();
    private final JLabel searchLabel = new JLabel();
    private final JLabel status = new JLabel();
    private final JButton refresh = new JButton();
    private final JButton install = new JButton();
    private final JButton updateAll = new JButton();
    private final JProgressBar progress = new JProgressBar(0, 100);

    private List<CatalogEntry> entries = new ArrayList<>();
    private Map<String, ExtensionEntry> installed = Map.of();
    private String generatedAt = "";
    private boolean busy;
    private boolean loaded;

    private AutoCloseable changes;
    private AutoCloseable language;

    ExtensionCatalogPanel(UiHost host, Runnable changed) {
        super(new BorderLayout(8, 8));
        this.services = host.services();
        this.messages = services.messages();
        this.changed = changed;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel heading = new JPanel();
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        JPanel searchRow = new JPanel(new BorderLayout(6, 0));
        searchRow.add(searchLabel, BorderLayout.WEST);
        searchRow.add(search, BorderLayout.CENTER);
        heading.add(searchRow);
        JPanel toolbar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 4));
        toolbar.add(refresh);
        toolbar.add(updateAll);
        heading.add(toolbar);
        heading.add(status);
        add(heading, BorderLayout.NORTH);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(-1);
        list.setCellRenderer((items, entry, index, selected, focused) -> {
            JLabel label = new JLabel(cell(entry));
            label.setOpaque(true);
            label.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
            label.setBackground(selected ? items.getSelectionBackground() : items.getBackground());
            label.setForeground(selected ? items.getSelectionForeground() : items.getForeground());
            return label;
        });
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setColumns(38);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
            new JScrollPane(list), new JScrollPane(details));
        split.setResizeWeight(0.5);
        add(split, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(8, 4));
        progress.setStringPainted(true);
        progress.setVisible(false);
        progress.setPreferredSize(new Dimension(240, 18));
        footer.add(progress, BorderLayout.CENTER);
        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
        actions.add(install);
        footer.add(actions, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);

        refresh.addActionListener(event -> load(true));
        install.addActionListener(event -> installSelected());
        updateAll.addActionListener(event -> updateAll());
        list.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                selection();
            }
        });
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { filter(); }
            @Override public void removeUpdate(DocumentEvent event) { filter(); }
            @Override public void changedUpdate(DocumentEvent event) { filter(); }
        });
        changes = services.hooks().subscribe(UiHooks.EXTENSIONS_CHANGED,
            SwingUtilities::invokeLater, ignored -> reloadInstalled());
        language = services.hooks().subscribe(UiHooks.LANGUAGE_CHANGED,
            SwingUtilities::invokeLater, ignored -> { labels(); filter(); });

        labels();
        load(false);
    }

    /** 关闭订阅；窗口销毁时调用。 */
    void close() {
        try {
            if (changes != null) { changes.close(); }
            if (language != null) { language.close(); }
        } catch (Exception failure) {
            System.err.println("[rwml] Cannot close the repository subscriptions: " + failure);
        }
    }

    /** 让已安装列表重新与目录比较。 */
    private void reloadInstalled() {
        if (busy) {
            return;
        }
        load(false);
    }

    private void labels() {
        searchLabel.setText(messages.text("extensions.search"));
        search.setToolTipText(messages.text("extensions.browseSearch"));
        refresh.setText(messages.text("extensions.browseRefresh"));
        updateAll.setText(messages.text("extensions.updateAll"));
        updateAll.setEnabled(!busy && availableUpdates().size() > 0);
        boolean supported = services.hasRepository();
        search.setEnabled(supported);
        refresh.setEnabled(supported && !busy);
        if (!supported) {
            status.setText(messages.text("extensions.browseNoRepository"));
        }
        progress.setStringPainted(true);
        selection();
    }

    private void filter() {
        CatalogEntry selected = list.getSelectedValue();
        String query = search.getText().trim().toLowerCase(Locale.ROOT);
        model.clear();
        for (CatalogEntry entry : entries) {
            if (matches(entry, query)) {
                model.addElement(entry);
            }
        }
        if (selected != null) {
            for (int index = 0; index < model.size(); index++) {
                if (model.get(index).id().equals(selected.id())) {
                    list.setSelectedIndex(index);
                    break;
                }
            }
        }
        if (list.getSelectedIndex() < 0 && !model.isEmpty()) {
            list.setSelectedIndex(0);
        }
        if (model.isEmpty() && loaded) {
            details.setText(messages.text("extensions.browseEmpty"));
        }
        if (!busy && loaded) {
            status.setText(statusText());
        }
        labels();
    }

    private boolean matches(CatalogEntry entry, String query) {
        if (query.isEmpty()) {
            return true;
        }
        for (String token : query.split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            boolean hit = entry.id().toLowerCase(Locale.ROOT).contains(token)
                || entry.name().toLowerCase(Locale.ROOT).contains(token)
                || entry.summary().toLowerCase(Locale.ROOT).contains(token)
                || entry.keywords().contains(token);
            if (!hit) {
                return false;
            }
        }
        return true;
    }

    private String statusText() {
        int updates = availableUpdates().size();
        String text = updates > 0
            ? messages.text("extensions.updatesAvailable", updates)
            : messages.text("extensions.noUpdates");
        if (!generatedAt.isEmpty()) {
            text = text + " · " + messages.text("extensions.repositoryCache", generatedAt);
        }
        return text;
    }

    private void selection() {
        CatalogEntry entry = list.getSelectedValue();
        boolean supported = services.hasRepository() && !busy && entry != null;
        String label = messages.text("extensions.install");
        boolean actionable = false;
        if (supported) {
            ExtensionEntry current = installed.get(entry.id());
            int order = current == null ? 1 : ExtensionVersion.compare(entry.version(), current.version());
            actionable = entry.supports(repositoryPlatform()) && !entry.artifactsFor(repositoryPlatform()).isEmpty();
            if (order > 0) {
                label = messages.text(current == null ? "extensions.install" : "extensions.update");
            } else {
                label = messages.text("extensions.reinstall");
            }
        }
        install.setText(label);
        install.setEnabled(supported && actionable);
        if (entry != null) {
            details.setText(describe(entry));
            details.setCaretPosition(0);
        }
    }

    private String cell(CatalogEntry entry) {
        ExtensionEntry current = installed.get(entry.id());
        String badge;
        if (current == null) {
            badge = messages.text("extensions.notInstalled");
        } else {
            int order = ExtensionVersion.compare(entry.version(), current.version());
            badge = order > 0
                ? messages.text("extensions.update") + " · " + current.version() + " → " + entry.version()
                : messages.text("extensions.upToDate") + " · " + current.version();
        }
        String summary = entry.summary().isEmpty() ? "" : "<br><small>"
            + escape(entry.summary()) + "</small>";
        return "<html><b>" + escape(entry.name()) + "</b> <small>"
            + escape(entry.version()) + " · " + escape(badge) + "</small>" + summary + "</html>";
    }

    private String describe(CatalogEntry entry) {
        List<String> lines = new ArrayList<>();
        lines.add(entry.name() + "  " + entry.version());
        if (!entry.id().equals(entry.name())) {
            lines.add(entry.id());
        }
        if (!entry.summary().isEmpty()) {
            lines.add("");
            lines.add(entry.summary());
        }
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
        for (ExtensionArtifact artifact : entry.artifactsFor(repositoryPlatform())) {
            size += artifact.size();
        }
        if (size > 0) {
            lines.add(messages.text("extensions.archive") + ": " + (size / 1024) + " KiB");
        }
        if (!entry.description().isEmpty()) {
            lines.add("");
            lines.add(entry.description());
        }
        if (!entry.notes().isEmpty()) {
            lines.add("");
            lines.add(messages.text("extensions.notes") + ":");
            lines.add(entry.notes());
        }
        if (!services.hasRepository()) {
            lines.add("");
            lines.add(messages.text("extensions.browseNoRepository"));
        }
        return String.join("\n", lines);
    }

    private String repositoryPlatform() {
        return services.hasRepository() ? services.repository().platform() : "jvm";
    }

    private List<AvailableUpdate> availableUpdates() {
        if (!services.hasRepository()) {
            return List.of();
        }
        Map<String, ExtensionEntry> current = new LinkedHashMap<>(installed);
        List<AvailableUpdate> updates = new ArrayList<>();
        for (CatalogEntry entry : entries) {
            ExtensionEntry installedEntry = current.get(entry.id());
            if (installedEntry == null || !entry.supports(repositoryPlatform())) {
                continue;
            }
            if (ExtensionVersion.compare(entry.version(), installedEntry.version()) > 0) {
                updates.add(new AvailableUpdate(entry.id(), installedEntry.version(), entry));
            }
        }
        return updates;
    }

    private void load(boolean force) {
        if (busy || !services.hasRepository()) {
            return;
        }
        busy = true;
        labels();
        status.setText(messages.text("extensions.browseLoading"));
        progress.setVisible(false);
        ExtensionRepository repository = services.repository();
        new SwingWorker<Catalog, Void>() {
            @Override protected Catalog doInBackground() throws Exception {
                Catalog catalog = force ? repository.refresh() : repository.catalog();
                installed = installedVersions(services.extensions().entries());
                return catalog;
            }

            @Override protected void done() {
                busy = false;
                try {
                    Catalog catalog = get();
                    entries = new ArrayList<>(catalog.entries());
                    entries.sort(Comparator.comparing(CatalogEntry::name, String.CASE_INSENSITIVE_ORDER));
                    generatedAt = catalog.generatedAt();
                    loaded = true;
                    filter();
                } catch (Exception failure) {
                    Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                    loaded = true;
                    status.setText(messages.text("extensions.browseOffline",
                        cause.getMessage() == null ? cause.toString() : cause.getMessage()));
                    System.err.println("[rwml] Cannot read the extension repository: " + cause);
                }
                labels();
            }
        }.execute();
    }

    private void installSelected() {
        CatalogEntry entry = list.getSelectedValue();
        if (entry == null || busy || !services.hasRepository()) {
            return;
        }
        ExtensionRepository repository = services.repository();
        busy = true;
        labels();
        progress.setVisible(true);
        progress.setValue(0);
        progress.setString("0%");
        String name = entry.name();
        status.setText(messages.text("extensions.downloading", name));
        new SwingWorker<InstallReport, Void>() {
            @Override protected InstallReport doInBackground() throws Exception {
                return repository.install(entry.id(), (downloaded, total, message) -> {
                    int percent = total > 0 ? (int) Math.min(100, downloaded * 100 / total) : 0;
                    SwingUtilities.invokeLater(() -> {
                        if (!isDisplayable()) {
                            return;
                        }
                        progress.setIndeterminate(total <= 0);
                        progress.setValue(percent);
                        progress.setString(total > 0 ? percent + "%" : "");
                        status.setText(messages.text("extensions.downloading", name));
                    });
                });
            }

            @Override protected void done() {
                busy = false;
                progress.setVisible(false);
                try {
                    InstallReport report = get();
                    afterInstall(entry, report);
                } catch (Exception failure) {
                    Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                    JOptionPane.showMessageDialog(ExtensionCatalogPanel.this,
                        messages.text("extensions.installFailed", name, messageOf(cause)),
                        messages.text("common.error"), JOptionPane.ERROR_MESSAGE);
                    status.setText(messages.text("extensions.installFailed", name, messageOf(cause)));
                }
                labels();
            }
        }.execute();
    }

    private void updateAll() {
        List<AvailableUpdate> updates = availableUpdates();
        if (updates.isEmpty() || busy || !services.hasRepository()) {
            return;
        }
        ExtensionRepository repository = services.repository();
        busy = true;
        labels();
        progress.setVisible(true);
        progress.setValue(0);
        new SwingWorker<Integer, Void>() {
            @Override protected Integer doInBackground() {
                int installedCount = 0;
                for (AvailableUpdate update : updates) {
                    String name = update.entry().name();
                    SwingUtilities.invokeLater(() -> {
                        progress.setIndeterminate(true);
                        progress.setValue(0);
                        status.setText(messages.text("extensions.downloading", name));
                    });
                    try {
                        InstallReport report = repository.install(update.id(), repository.platform(),
                            (downloaded, total, message) -> {
                                int percent = total > 0 ? (int) Math.min(100, downloaded * 100 / total) : 0;
                                SwingUtilities.invokeLater(() -> {
                                    if (!isDisplayable()) {
                                        return;
                                    }
                                    progress.setIndeterminate(total <= 0);
                                    progress.setValue(percent);
                                    progress.setString(total > 0 ? percent + "%" : "");
                                });
                            });
                        if (report.requiresRestart()) {
                            installedCount++;
                        }
                    } catch (IOException failure) {
                        System.err.println("[rwml] Cannot update " + update.id() + ": " + failure);
                    }
                }
                return installedCount;
            }

            @Override protected void done() {
                busy = false;
                progress.setVisible(false);
                int count = 0;
                try {
                    count = get();
                } catch (Exception ignored) {
                    // 单个扩展的失败已经记录在诊断输出中。
                }
                status.setText(messages.text("extensions.updateDone", count));
                services.hooks().publish(UiHooks.EXTENSIONS_CHANGED, null);
                if (changed != null) {
                    changed.run();
                }
                load(false);
            }
        }.execute();
    }

    private void afterInstall(CatalogEntry entry, InstallReport report) {
        if (!report.failed().isEmpty()) {
            String reason = String.join("; ", report.failed().values());
            JOptionPane.showMessageDialog(this,
                messages.text("extensions.installFailed", entry.name(), reason),
                messages.text("common.error"), JOptionPane.ERROR_MESSAGE);
            status.setText(messages.text("extensions.installPartial", report.failed().size(), reason));
        } else if (report.requiresRestart()) {
            status.setText(messages.text("extensions.installDone", entry.name(), report.version()));
        } else {
            status.setText(messages.text("extensions.upToDate"));
        }
        services.hooks().publish(UiHooks.EXTENSIONS_CHANGED, null);
        if (changed != null) {
            changed.run();
        }
        load(false);
    }

    private static Map<String, ExtensionEntry> installedVersions(List<ExtensionEntry> entries) {
        Map<String, ExtensionEntry> result = new LinkedHashMap<>();
        for (ExtensionEntry entry : entries) {
            if (entry == null || entry.id() == null || entry.builtIn() || entry.version() == null) {
                continue;
            }
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

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
