/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.builtin;

import io.github.rwml.api.ProgressListener;
import io.github.rwml.api.i18n.Messages;
import io.github.rwml.api.ui.UiHooks;
import io.github.rwml.api.ui.UiHost;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

/** The host's bootstrap stays available while extensions and the game are prepared on a worker. */
public final class LauncherWindow {
    @FunctionalInterface
    public interface Startup {
        void run(ProgressListener progress) throws Exception;
    }

    private final UiHost host;
    private final Startup startup;
    private final Messages messages;
    private final JFrame window = new JFrame();
    private final JLabel status = new JLabel();
    private final JTextArea log = new JTextArea(10, 60);
    private final JScrollPane diagnostics = new JScrollPane(log);
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JButton start = new JButton();
    private final JButton extensions = new JButton();
    private final JButton language = new JButton();
    private final JButton restart = new JButton();
    private final JButton exit = new JButton();
    private boolean busy;
    private boolean failed;

    private LauncherWindow(UiHost host, Startup startup, Throwable failure, CountDownLatch closed) {
        this.host = host;
        this.startup = startup;
        this.messages = host.services().messages();
        failed = failure != null;
        JPanel content = new JPanel(new BorderLayout(12, 12));
        content.setBorder(new javax.swing.border.EmptyBorder(20, 20, 20, 20));
        JPanel heading = new JPanel(new java.awt.GridLayout(0, 1, 0, 8));
        JLabel headline = new JLabel("RWML");
        headline.setFont(headline.getFont().deriveFont(java.awt.Font.BOLD, 24));
        heading.add(headline);
        heading.add(status);
        JTextField directory = new JTextField(host.services().extensions().directory().toString());
        directory.setEditable(false);
        heading.add(directory);
        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.add(heading, BorderLayout.CENTER);
        top.add(progress, BorderLayout.SOUTH);
        content.add(top, BorderLayout.NORTH);
        log.setEditable(false);
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        content.add(diagnostics, BorderLayout.CENTER);
        JPanel actions = new JPanel();
        for (JButton button : new JButton[]{start, extensions, language, restart, exit}) { actions.add(button); }
        content.add(actions, BorderLayout.SOUTH);
        start.addActionListener(event -> start());
        exit.addActionListener(event -> window.dispose());
        extensions.addActionListener(event -> new ExtensionManagementWindow(window, host).setVisible(true));
        language.addActionListener(event -> chooseLanguage());
        restart.addActionListener(event -> {
            try { host.restart(); }
            catch (RuntimeException error) { error(error); }
        });
        AutoCloseable changes = host.services().hooks().subscribe(UiHooks.EXTENSIONS_CHANGED,
            SwingUtilities::invokeLater, ignored -> labels());
        AutoCloseable languages = host.services().hooks().subscribe(UiHooks.LANGUAGE_CHANGED,
            SwingUtilities::invokeLater, ignored -> labels());
        window.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) {
                try { changes.close(); languages.close(); }
                catch (Exception error) { System.err.println("[rwml] Cannot close UI subscription: " + error); }
                closed.countDown();
            }
        });
        if (failure != null) { appendFailure(failure); }
        labels();
        window.setContentPane(content);
        window.pack();
        window.setMinimumSize(new java.awt.Dimension(700, 380));
        window.setLocationRelativeTo(null);
        window.setVisible(true);
        if (host.services().extensions().versionSelectionRequired()) {
            new ExtensionManagementWindow(window, host).setVisible(true);
        }
    }

    /** Creates the bootstrap before invoking any extension code. The calling thread remains off EDT. */
    public static void launch(UiHost host, Startup startup) {
        boolean unresolved = host.services().extensions().versionSelectionRequired();
        if (GraphicsEnvironment.isHeadless() || (host.arguments().contains("--rwml-no-launcher") && !unresolved)) {
            if (!unresolved) {
                try { startup.run((percent, message) -> System.out.println("[rwml] " + percent + "% " + message)); }
                catch (Exception failure) { failure.printStackTrace(System.err); }
            }
            return;
        }
        if (SwingUtilities.isEventDispatchThread()) { throw new IllegalStateException("Bootstrap must start off EDT"); }
        CountDownLatch closed = new CountDownLatch(1);
        try {
            SwingUtilities.invokeAndWait(() -> new LauncherWindow(host, startup, null, closed));
            closed.await();
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (Exception failure) { throw new IllegalStateException("Cannot show RWML bootstrap", failure); }
    }

    /** Also handles a transformation failure on a game thread after the initial hand-over. */
    public static void recover(UiHost host, Throwable failure) {
        if (GraphicsEnvironment.isHeadless()) { failure.printStackTrace(System.err); return; }
        SwingUtilities.invokeLater(() -> new LauncherWindow(host, null, failure, new CountDownLatch(1)));
    }

    private void start() {
        if (busy || failed || startup == null || host.services().extensions().restartRequired()
            || host.services().extensions().versionSelectionRequired()) { return; }
        busy = true;
        labels();
        new Thread(() -> {
            try {
                startup.run((percent, message) -> SwingUtilities.invokeLater(() -> {
                    if (!window.isDisplayable()) { return; }
                    progress.setIndeterminate(percent <= 0);
                    progress.setValue(Math.max(0, Math.min(100, percent)));
                    log.append(percent + "% " + message + "\n");
                    log.setCaretPosition(log.getDocument().getLength());
                }));
                SwingUtilities.invokeLater(window::dispose);
            } catch (Throwable failure) {
                SwingUtilities.invokeLater(() -> {
                    failed = true;
                    busy = false;
                    appendFailure(failure);
                    labels();
                    window.setVisible(true);
                    window.toFront();
                });
            }
        }, "rwml-bootstrap").start();
    }

    private void labels() {
        window.setTitle(messages.text("launcher.title"));
        diagnostics.setBorder(javax.swing.BorderFactory.createTitledBorder(messages.text("launcher.diagnostics")));
        boolean changed = host.services().extensions().restartRequired();
        boolean pending = host.services().extensions().versionSelectionRequired();
        status.setText(messages.text(busy ? "launcher.loading" : failed
            ? changed ? "launcher.extensionFailure" : "launcher.bootFailure"
            : changed ? "extensions.changed" : pending ? "extensions.chooseVersionRequired" : "launcher.subtitle"));
        start.setText(messages.text("launcher.start"));
        start.setEnabled(!busy && !failed && !changed && !pending && startup != null);
        extensions.setText(messages.text("extensions.title"));
        language.setText(messages.text("language.title"));
        restart.setText(messages.text("launcher.restart"));
        exit.setText(messages.text("launcher.exit"));
        extensions.setEnabled(!busy);
        language.setEnabled(!busy);
        restart.setEnabled(!busy);
        exit.setEnabled(!busy);
        progress.setVisible(busy);
        window.setDefaultCloseOperation(busy ? JFrame.DO_NOTHING_ON_CLOSE : JFrame.DISPOSE_ON_CLOSE);
    }

    private void appendFailure(Throwable failure) {
        StringWriter text = new StringWriter();
        failure.printStackTrace(new PrintWriter(text));
        log.append(text.toString());
        System.err.print(text);
    }

    private void chooseLanguage() {
        String[] tags = {"system", "en", "zh"};
        String[] names = {messages.text("language.system"), messages.text("language.en"), messages.text("language.zh")};
        Object selected = JOptionPane.showInputDialog(window, messages.text("language.title"),
            messages.text("language.title"), JOptionPane.PLAIN_MESSAGE, null, names,
            names[Math.max(0, Arrays.asList(tags).indexOf(messages.language()))]);
        if (selected == null) { return; }
        try { messages.language(tags[Arrays.asList(names).indexOf(selected)]); }
        catch (IOException failure) { error(failure); }
    }

    private void error(Throwable failure) {
        JOptionPane.showMessageDialog(window, failure.getMessage(), messages.text("common.error"), JOptionPane.ERROR_MESSAGE);
    }
}
