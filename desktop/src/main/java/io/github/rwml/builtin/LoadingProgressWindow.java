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
 * 桌面端内置界面中的延迟加载窗口。
 *
 * <p>游戏初始化发生在游戏窗口显示之前；如果没有此窗口，玩家在初始化期间将看不到任何内容，看起来就像卡住了，尤其是在扫描大型扩展时。
 * 此窗口会显示加载进度，并在控制权移交给游戏后立即关闭。</p>
 *
 * <p>此窗口按尽力而为方式显示。无界面 JVM、故障显示设备或无法启动的窗口工具包都会导致返回 {@code null}，加载器会仅通过控制台继续运行。
 * 此外，只有等待 {@link #SHOW_DELAY_MILLIS} 后才会显示窗口：正常加载通常只需几百毫秒，每次启动都闪现一次窗口比完全不显示更糟。</p>
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
     * 有可用显示设备时创建窗口。
     *
     * @return 加载窗口；调用方应回退到控制台显示进度时返回 {@code null}
     */
    public static LoadingProgressWindow showIfPossible(String title) {
        try {
            if (GraphicsEnvironment.isHeadless()) {
                return null;
            }
            // 配置不完整的显示设备通常会在访问工具包时失败，因此在此处的 try 块中主动初始化，而不是留到 Swing 线程中延迟初始化。
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
            // 无法显示窗口不应成为停止加载扩展的原因。
        }
    }

    /** @return 将加载进度同步到窗口的监听器 */
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
                    // 没有需要清理的内容。
                }
            });
        } catch (Throwable ignored) {
            // 已经关闭。
        }
    }
}
