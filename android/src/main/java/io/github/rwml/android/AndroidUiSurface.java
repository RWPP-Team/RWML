/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import android.os.Looper;
import android.view.View;

import androidx.activity.ComponentActivity;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;

import io.github.rwml.api.ui.UiSurface;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/** Android implementation that attaches native content to the current launcher activity. */
final class AndroidUiSurface implements UiSurface {

    private final Object lock = new Object();
    private final ComponentActivity activity;
    private final List<Runnable> closeListeners = new ArrayList<>();
    private final Executor uiExecutor;
    private final LifecycleEventObserver lifecycleObserver = (LifecycleOwner owner, Lifecycle.Event event) -> {
        if (event == Lifecycle.Event.ON_DESTROY) {
            finishClose();
        }
    };

    private volatile boolean closed;
    private boolean attached;

    AndroidUiSurface(ComponentActivity activity) {
        this.activity = Objects.requireNonNull(activity, "activity");
        this.uiExecutor = command -> {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                command.run();
            } else {
                this.activity.runOnUiThread(command);
            }
        };
        // MainActivity starts extension code on a worker thread, so lifecycle registration belongs on main.
        uiExecutor.execute(() -> {
            if (closed || activity.getLifecycle().getCurrentState() == Lifecycle.State.DESTROYED) {
                finishClose();
            } else {
                activity.getLifecycle().addObserver(lifecycleObserver);
            }
        });
    }

    @Override
    public Executor uiExecutor() {
        return uiExecutor;
    }

    @Override
    public void attach(Object nativeContent) {
        if (!(nativeContent instanceof View)) {
            throw new IllegalArgumentException("Android UI surfaces require an android.view.View");
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Attach Android UI content on UiSurface.uiExecutor()");
        }
        synchronized (lock) {
            if (closed || activity.isFinishing() || activity.isDestroyed()) {
                throw new IllegalStateException("The Android UI surface is already closed");
            }
            if (attached) {
                throw new IllegalStateException("The Android UI surface already has content");
            }
            attached = true;
        }
        activity.setContentView((View) nativeContent);
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
        uiExecutor.execute(() -> {
            if (!markClosed()) {
                return;
            }
            activity.getLifecycle().removeObserver(lifecycleObserver);
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                activity.finish();
            }
            notifyListeners();
        });
    }

    private void finishClose() {
        if (markClosed()) {
            activity.getLifecycle().removeObserver(lifecycleObserver);
            notifyListeners();
        }
    }

    private boolean markClosed() {
        synchronized (lock) {
            if (closed) {
                return false;
            }
            closed = true;
            return true;
        }
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
            HostLog.error("Extension UI close callback failed", failure);
        }
    }
}
