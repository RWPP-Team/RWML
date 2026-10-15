/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.builtin;

import io.github.rwml.api.extensions.ExtensionEntry;
import io.github.rwml.api.i18n.Messages;
import io.github.rwml.api.ui.UiHooks;
import io.github.rwml.api.ui.UiHost;
import io.github.rwml.api.ui.UiServices;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/** The desktop renderer of the shared extension catalog. All filesystem work stays off the EDT. */
public final class ExtensionManagementWindow extends JDialog {

    private final UiServices services;
    private final Messages messages;
    private final DefaultListModel<ExtensionEntry> model = new DefaultListModel<>();
    private final JList<ExtensionEntry> list = new JList<>(model);
    private final JTextField search = new JTextField();
    private final JTextArea details = new JTextArea();
    private final JLabel status = new JLabel();
    private final JLabel directory = new JLabel();
    private final JLabel searchLabel = new JLabel();
    private final JButton enable = new JButton();
    private final JButton selectVersion = new JButton();
    private final JButton remove = new JButton();
    private final JButton importButton = new JButton();
    private final JButton refresh = new JButton();
    private final JButton restart = new JButton();
    private List<ExtensionEntry> entries = new ArrayList<>();
    private boolean busy;

    public ExtensionManagementWindow(Frame owner, UiHost host) {
        super(owner, false);
        services = host.services();
        messages = services.messages();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(800, 480);
        setMinimumSize(new Dimension(600, 360));
        setLocationRelativeTo(owner);
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(new javax.swing.border.EmptyBorder(12, 12, 12, 12));
        JPanel heading = new JPanel(new BorderLayout(6, 6));
        directory.setToolTipText(services.extensions().directory().toString());
        heading.add(directory, BorderLayout.NORTH);
        JPanel searchBar = new JPanel(new BorderLayout(6, 0));
        searchBar.add(searchLabel, BorderLayout.WEST);
        searchBar.add(search, BorderLayout.CENTER);
        heading.add(searchBar, BorderLayout.CENTER);
        heading.add(status, BorderLayout.SOUTH);
        content.add(heading, BorderLayout.NORTH);
        list.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer((items, entry, index, selected, focused) -> {
            JLabel label = new JLabel(entry.displayName(messages) + " " + entry.version() + " · "
                + status(entry));
            label.setOpaque(true);
            label.setBorder(new javax.swing.border.EmptyBorder(7, 5, 7, 5));
            label.setBackground(selected ? items.getSelectionBackground() : items.getBackground());
            label.setForeground(selected ? items.getSelectionForeground() : items.getForeground());
            return label;
        });
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setColumns(40);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(list), new JScrollPane(details));
        split.setResizeWeight(0.45);
        content.add(split, BorderLayout.CENTER);
        JPanel actions = new JPanel();
        for (JButton button : new JButton[]{importButton, refresh, enable, selectVersion, remove, restart}) { actions.add(button); }
        content.add(actions, BorderLayout.SOUTH);
        setContentPane(content);
        importButton.addActionListener(event -> chooseArchive());
        refresh.addActionListener(event -> load(() -> { }));
        enable.addActionListener(event -> {
            ExtensionEntry entry = list.getSelectedValue();
            if (entry != null) { load(() -> services.extensions().setEnabled(entry.id(), !entry.enabled())); }
        });
        selectVersion.addActionListener(event -> {
            ExtensionEntry entry = list.getSelectedValue();
            if (entry != null && confirm(messages.text("extensions.useVersionConfirm", entry.displayName(messages), entry.version()))) {
                load(() -> services.extensions().selectVersion(entry.source()));
            }
        });
        remove.addActionListener(event -> {
            ExtensionEntry entry = list.getSelectedValue();
            if (entry != null && confirm(messages.text("extensions.removeConfirm", entry.name()))) {
                load(() -> services.extensions().removeArchive(entry.source()));
            }
        });
        restart.addActionListener(event -> {
            try { host.restart(); } catch (RuntimeException failure) { error(failure); }
        });
        list.addListSelectionListener(event -> selection());
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { filter(); }
            @Override public void removeUpdate(DocumentEvent event) { filter(); }
            @Override public void changedUpdate(DocumentEvent event) { filter(); }
        });
        AutoCloseable changes = services.hooks().subscribe(UiHooks.EXTENSIONS_CHANGED,
            SwingUtilities::invokeLater, ignored -> load(() -> { }));
        AutoCloseable language = services.hooks().subscribe(UiHooks.LANGUAGE_CHANGED,
            SwingUtilities::invokeLater, ignored -> { labels(); filter(); });
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) {
                try { changes.close(); language.close(); }
                catch (Exception failure) { System.err.println("[rwml] Cannot close UI subscription: " + failure); }
            }
        });
        labels();
        load(() -> { });
    }

    private void labels() {
        setTitle(messages.text("extensions.title"));
        directory.setText(messages.text("extensions.directory") + ": " + services.extensions().directory());
        searchLabel.setText(messages.text("extensions.search"));
        search.setToolTipText(messages.text("extensions.search"));
        importButton.setText(messages.text("extensions.import"));
        refresh.setText(messages.text("extensions.refresh"));
        remove.setText(messages.text("extensions.remove"));
        selectVersion.setText(messages.text("extensions.useVersion"));
        restart.setText(messages.text("launcher.restart"));
        status.setText(messages.text(busy ? "extensions.working" : services.extensions().restartRequired()
            ? "extensions.changed" : services.extensions().versionSelectionRequired()
            ? "extensions.chooseVersionRequired" : "extensions.nextLaunch"));
        importButton.setEnabled(!busy);
        refresh.setEnabled(!busy);
        restart.setEnabled(!busy);
        selection();
    }

    private void filter() {
        ExtensionEntry selected = list.getSelectedValue();
        String query = search.getText().trim().toLowerCase(Locale.ROOT);
        model.clear();
        for (ExtensionEntry entry : entries) {
            if ((entry.displayName(messages) + " " + entry.name() + " " + entry.id())
                .toLowerCase(Locale.ROOT).contains(query)) { model.addElement(entry); }
        }
        if (selected != null) {
            for (int index = 0; index < model.size(); index++) {
                ExtensionEntry candidate = model.get(index);
                if (Objects.equals(selected.id(), candidate.id())
                    && Objects.equals(selected.source(), candidate.source())) {
                    list.setSelectedIndex(index);
                    break;
                }
            }
        }
        if (list.getSelectedIndex() < 0 && !model.isEmpty()) { list.setSelectedIndex(0); }
        if (model.isEmpty()) { details.setText(messages.text(entries.isEmpty() ? "extensions.empty" : "extensions.none")); }
        labels();
    }

    private void selection() {
        ExtensionEntry entry = list.getSelectedValue();
        enable.setText(messages.text(entry != null && entry.enabled() ? "extensions.disable" : "extensions.enable"));
        enable.setEnabled(!busy && entry != null && entry.valid());
        remove.setEnabled(!busy && entry != null && !entry.builtIn());
        selectVersion.setEnabled(!busy && entry != null && entry.valid()
            && entry.source() != null && !entry.versionSelected());
        if (entry == null) { return; }
        List<String> lines = new ArrayList<>();
        lines.add(entry.id() == null ? messages.text("extensions.invalid") : entry.id());
        String description = entry.displayDescription(messages);
        if (!description.isEmpty()) { lines.add(""); lines.add(description); }
        if (!entry.authors().isEmpty()) {
            lines.add(messages.text("extensions.authors") + ": " + String.join(", ", entry.authors()));
        }
        if (!entry.dependencies().isEmpty()) {
            lines.add(messages.text("extensions.dependencies") + ": " + String.join(", ", entry.dependencies()));
        }
        lines.add(messages.text("extensions.archive") + ": "
            + (entry.builtIn() ? messages.text("extensions.builtin") : entry.source()));
        if (entry.loaded()) { lines.add(messages.text("extensions.loaded")); }
        if (!entry.problem().isEmpty()) { lines.add(""); lines.add(entry.problem()); }
        details.setText(String.join("\n", lines));
        details.setCaretPosition(0);
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

    private void chooseArchive() {
        JFileChooser picker = new JFileChooser();
        picker.setDialogTitle(messages.text("extensions.import"));
        picker.setFileFilter(new FileNameExtensionFilter(messages.text("extensions.jarFilter"), "jar"));
        if (picker.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            importArchive(picker.getSelectedFile().toPath(), false);
        }
    }

    private void importArchive(Path archive, boolean replace) {
        ExtensionEntry[] imported = new ExtensionEntry[1];
        load(() -> {
            try (InputStream input = Files.newInputStream(archive)) {
                imported[0] = services.extensions().importArchive(input, replace);
            }
        }, failure -> {
            if (failure instanceof FileAlreadyExistsException) {
                if (confirm(messages.text("extensions.replaceConfirm"))) { importArchive(archive, true); }
            } else { error(failure); }
        }, () -> chooseVersion(imported[0].id()));
    }

    private void chooseVersion(String id) {
        List<ExtensionEntry> versions = new ArrayList<>();
        for (ExtensionEntry entry : entries) {
            if (id.equals(entry.id()) && entry.source() != null) { versions.add(entry); }
        }
        if (versions.size() < 2) { return; }
        String[] choices = versions.stream().map(entry -> entry.version() + " · " + entry.source().getFileName())
            .toArray(String[]::new);
        int selected = 0;
        for (int index = 0; index < versions.size(); index++) {
            if (versions.get(index).versionSelected()) { selected = index; }
        }
        Object choice = JOptionPane.showInputDialog(this, messages.text("extensions.chooseVersionPrompt", id),
            messages.text("extensions.chooseVersion"), JOptionPane.QUESTION_MESSAGE, null, choices, choices[selected]);
        if (choice == null) { return; }
        for (int index = 0; index < choices.length; index++) {
            if (choices[index].equals(choice)) {
                Path source = versions.get(index).source();
                load(() -> services.extensions().selectVersion(source));
                break;
            }
        }
    }

    private boolean confirm(String text) {
        return JOptionPane.showOptionDialog(this, text, messages.text("extensions.title"),
            JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null,
            new String[]{messages.text("common.confirm"), messages.text("common.cancel")}, null) == 0;
    }

    private interface Work { void run() throws Exception; }

    private void load(Work work) {
        load(work, this::error);
    }

    private void load(Work work, Consumer<Throwable> failed) {
        load(work, failed, () -> { });
    }

    private void load(Work work, Consumer<Throwable> failed, Runnable completed) {
        if (busy) { return; }
        busy = true;
        labels();
        new SwingWorker<List<ExtensionEntry>, Void>() {
            @Override protected List<ExtensionEntry> doInBackground() throws Exception {
                work.run();
                return services.extensions().entries();
            }
            @Override protected void done() {
                busy = false;
                if (!isDisplayable()) { return; }
                try { entries = get(); filter(); completed.run(); }
                catch (Exception failure) { failed.accept(failure.getCause() == null ? failure : failure.getCause()); }
                labels();
            }
        }.execute();
    }

    private void error(Throwable failure) {
        System.err.println("[rwml] Extension management failed: " + failure);
        JOptionPane.showMessageDialog(this, failure.getMessage(), messages.text("common.error"), JOptionPane.ERROR_MESSAGE);
    }
}
