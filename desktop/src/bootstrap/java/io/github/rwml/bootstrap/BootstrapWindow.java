/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.bootstrap;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

/** Java 8 loading UI for provisioning Java itself; it cannot reference core classes. */
final class BootstrapWindow implements AutoCloseable {

    private final JFrame window;
    private final JLabel detail;

    private BootstrapWindow() {
        Properties words = words();
        window = new JFrame("RWML");
        window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(new javax.swing.border.EmptyBorder(18, 18, 18, 18));
        content.add(new JLabel(words.getProperty("launcher.runtime", "Preparing Java runtime…")), BorderLayout.NORTH);
        JPanel body = new JPanel(new java.awt.GridLayout(0, 1, 0, 8));
        body.add(new JLabel(words.getProperty("launcher.runtimeHint", "The first launch may download Java.")));
        detail = new JLabel(" ");
        body.add(detail);
        content.add(body, BorderLayout.CENTER);
        JProgressBar progress = new JProgressBar();
        progress.setIndeterminate(true);
        content.add(progress, BorderLayout.SOUTH);
        window.setContentPane(content);
        window.setSize(600, 180);
        window.setLocationRelativeTo(null);
        window.setVisible(true);
    }

    static BootstrapWindow showIfPossible() {
        if (GraphicsEnvironment.isHeadless()) { return null; }
        try {
            AtomicReference<BootstrapWindow> result = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> result.set(new BootstrapWindow()));
            BootstrapWindow window = result.get();
            BootstrapLog.sink(message -> SwingUtilities.invokeLater(() -> window.detail.setText(message)));
            return window;
        } catch (Exception failure) {
            BootstrapLog.debug("Runtime preparation window unavailable: " + failure);
            return null;
        }
    }

    private static Properties words() {
        String language = Locale.getDefault().getLanguage();
        File ownJar = Relauncher.ownJar();
        File root = ownJar == null ? new File(System.getProperty("user.dir", ".")) : ownJar.getParentFile();
        File preference = new File(root, "data/language.txt");
        try {
            if (preference.isFile()) {
                String tag = new String(java.nio.file.Files.readAllBytes(preference.toPath()), "UTF-8").trim();
                if (!"system".equals(tag)) { language = Locale.forLanguageTag(tag).getLanguage(); }
            }
        } catch (Exception ignored) { }
        Properties result = new Properties();
        String resource = "/io/github/rwml/i18n/" + ("zh".equals(language) ? "zh" : "en") + ".properties";
        try (InputStream input = BootstrapWindow.class.getResourceAsStream(resource)) {
            if (input != null) { result.load(new InputStreamReader(input, "UTF-8")); }
        } catch (Exception ignored) { }
        return result;
    }

    @Override public void close() {
        BootstrapLog.sink(null);
        SwingUtilities.invokeLater(window::dispose);
    }
}
