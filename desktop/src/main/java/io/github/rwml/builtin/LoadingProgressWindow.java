/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.builtin;

import io.github.rwml.api.ProgressListener;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.Timer;

/**
 * The desktop builtin UI's delayed game-loading window.
 *
 * <p>Game initialization runs before the game's own window appears, so without this the player sees
 * nothing at all for the duration - which looks like a hang, especially when a large extension is
 * being scanned. The window mirrors the load pass progress and disappears as soon as the game is
 * handed over to.</p>
 *
 * <p>It is strictly best-effort. A headless JVM, a broken display or a windowing toolkit that
 * refuses to start all degrade to {@code null}, and the loader carries on with console output only.
 * The window is also only revealed after {@link #SHOW_DELAY_MILLIS}: a typical load takes a few
 * hundred milliseconds, and flashing a window on every launch would be worse than showing
 * nothing.</p>
 */
public final class LoadingProgressWindow implements AutoCloseable {

    private static final int SHOW_DELAY_MILLIS = 400;
    private static final int WIDTH = 360;
    private static final int HEIGHT = 92;

    private final JWindow window;
    private final JLabel headline;
    private final JLabel status;
    private final JProgressBar bar;
    private final AtomicBoolean closed = new AtomicBoolean();
    private Timer revealTimer;

    private LoadingProgressWindow(String title) {
        this.headline = new JLabel(title, JLabel.CENTER);
        this.headline.setFont(this.headline.getFont().deriveFont(Font.BOLD, 13f));

        this.status = new JLabel("", JLabel.CENTER);
        this.status.setFont(this.status.getFont().deriveFont(Font.PLAIN, 11f));
        this.status.setForeground(new Color(0x60, 0x60, 0x60));

        this.bar = new JProgressBar(0, 100);
        this.bar.setStringPainted(false);
        this.bar.setPreferredSize(new Dimension(WIDTH - 48, 14));

        JPanel content = new JPanel(new BorderLayout(0, 6));
        content.setBackground(Color.WHITE);
        content.setBorder(new EmptyBorder(12, 16, 12, 16));
        content.add(headline, BorderLayout.NORTH);
        content.add(status, BorderLayout.CENTER);
        content.add(bar, BorderLayout.SOUTH);

        this.window = new JWindow();
        this.window.setContentPane(content);
        this.window.setAlwaysOnTop(true);
        this.window.pack();
        this.window.setSize(WIDTH, HEIGHT);
        this.window.setLocationRelativeTo(null);
    }

    /**
     * Creates the window when a display is available.
     *
     * @return the window, or {@code null} when the caller should fall back to console progress
     */
    public static LoadingProgressWindow showIfPossible(String title) {
        try {
            if (GraphicsEnvironment.isHeadless()) {
                return null;
            }
            // Touching the toolkit is what actually fails on a half-configured display, so it is
            // done here, inside the try, rather than lazily on the swing thread.
            Toolkit.getDefaultToolkit().getScreenSize();
            AtomicReference<LoadingProgressWindow> result = new AtomicReference<>();
            Runnable create = () -> {
                LoadingProgressWindow instance = new LoadingProgressWindow(title);
                instance.scheduleReveal();
                result.set(instance);
            };
            if (SwingUtilities.isEventDispatchThread()) {
                create.run();
            } else {
                SwingUtilities.invokeAndWait(create);
            }
            return result.get();
        } catch (Throwable t) {
            return null;
        }
    }

    private void scheduleReveal() {
        revealTimer = new Timer(SHOW_DELAY_MILLIS, event -> reveal());
        revealTimer.setRepeats(false);
        revealTimer.start();
    }

    private void reveal() {
        if (closed.get()) {
            return;
        }
        try {
            window.setVisible(true);
            window.toFront();
        } catch (Throwable ignored) {
            // A window we cannot show is not a reason to stop loading extensions.
        }
    }

    /** @return a listener that mirrors load progress into the window. */
    public ProgressListener listener() {
        return (percent, message) -> {
            if (closed.get()) {
                return;
            }
            SwingUtilities.invokeLater(() -> {
                if (closed.get()) {
                    return;
                }
                bar.setValue(Math.max(0, Math.min(100, percent)));
                status.setText(message);
            });
        };
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            SwingUtilities.invokeLater(() -> {
                try {
                    if (revealTimer != null) {
                        revealTimer.stop();
                    }
                    window.setVisible(false);
                    window.dispose();
                } catch (Throwable ignored) {
                    // Nothing left to clean up.
                }
            });
        } catch (Throwable ignored) {
            // Already shut down.
        }
    }
}
