/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.desktop;

import io.github.rwml.api.ui.UiSurface;

import java.awt.EventQueue;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/** Desktop implementation that owns the lifetime of an extension-created AWT window. */
final class DesktopUiSurface implements UiSurface {

    private final Object lock = new Object();
    private final List<Runnable> closeListeners = new ArrayList<>();
    private final Executor uiExecutor = command -> {
        if (EventQueue.isDispatchThread()) {
            command.run();
        } else {
            EventQueue.invokeLater(command);
        }
    };

    private volatile boolean closed;
    private Window window;
    private WindowAdapter windowListener;

    @Override
    public Executor uiExecutor() {
        return uiExecutor;
    }

    @Override
    public void attach(Object nativeContent) {
        if (!(nativeContent instanceof Window)) {
            throw new IllegalArgumentException("Desktop UI surfaces require a java.awt.Window");
        }
        if (!EventQueue.isDispatchThread()) {
            throw new IllegalStateException("Attach desktop UI content on UiSurface.uiExecutor()");
        }

        Window content = (Window) nativeContent;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("The desktop UI surface is already closed");
            }
            if (window != null) {
                throw new IllegalStateException("The desktop UI surface already has content");
            }
            window = content;
            windowListener = new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent event) {
                    close();
                }

                @Override
                public void windowClosed(WindowEvent event) {
                    finishClose();
                }
            };
            content.addWindowListener(windowListener);
        }
        content.setVisible(true);
    }

    @Override
    public AutoCloseable onClose(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        boolean notifyNow;
        synchronized (lock) {
            notifyNow = closed;
            if (!notifyNow) {
                closeListeners.add(listener);
            }
        }
        if (notifyNow) {
            uiExecutor.execute(() -> invokeListener(listener));
        }
        return () -> {
            synchronized (lock) {
                closeListeners.remove(listener);
            }
        };
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        uiExecutor.execute(this::closeOnUiThread);
    }

    private void closeOnUiThread() {
        Window content;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            content = window;
            window = null;
            if (content != null && windowListener != null) {
                content.removeWindowListener(windowListener);
            }
            windowListener = null;
        }
        if (content != null) {
            content.dispose();
        }
        notifyListeners();
    }

    private void finishClose() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            window = null;
            windowListener = null;
        }
        notifyListeners();
    }

    private void notifyListeners() {
        List<Runnable> pending;
        synchronized (lock) {
            pending = new ArrayList<>(closeListeners);
            closeListeners.clear();
        }
        for (Runnable listener : pending) {
            invokeListener(listener);
        }
    }

    private static void invokeListener(Runnable listener) {
        try {
            listener.run();
        } catch (Throwable failure) {
            System.err.println("[rwml] Extension UI close callback failed: " + failure);
            failure.printStackTrace(System.err);
        }
    }
}
