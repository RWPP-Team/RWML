/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.lifecycle.ViewModelStore;
import androidx.lifecycle.ViewModelStoreOwner;
import androidx.lifecycle.ViewTreeLifecycleOwner;
import androidx.lifecycle.ViewTreeViewModelStoreOwner;
import androidx.savedstate.SavedStateRegistry;
import androidx.savedstate.SavedStateRegistryController;
import androidx.savedstate.SavedStateRegistryOwner;
import androidx.savedstate.ViewTreeSavedStateRegistryOwner;
import androidx.activity.OnBackPressedDispatcher;
import androidx.activity.OnBackPressedDispatcherOwner;
import androidx.activity.ViewTreeOnBackPressedDispatcherOwner;

import io.github.rwml.api.ui.UiContentFactory;
import io.github.rwml.api.ui.UiSurface;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deferred overlay surface attached to the host's existing InGameActivity. */
public final class AndroidGameSurface implements UiSurface {

    private static final String SAVED_STATE_KEY = "rwml.extensionUi";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, AndroidGameSurface> SURFACES = new ConcurrentHashMap<>();

    private final Object lock = new Object();
    private final String id = UUID.randomUUID().toString();
    private final List<Runnable> closeListeners = new ArrayList<>();
    private final Executor uiExecutor = command -> {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            command.run();
        } else {
            MAIN.post(command);
        }
    };

    private UiContentFactory contentFactory;
    private volatile boolean closed;
    private boolean attached;
    private Activity activity;
    private FrameLayout gameRoot;
    private View content;
    private SurfaceOwners owners;
    private final ViewModelStore viewModelStore = new ViewModelStore();

    AndroidGameSurface(Activity launcher) {
        Objects.requireNonNull(launcher, "launcher");
        SURFACES.put(id, this);
    }

    String id() {
        return id;
    }

    static AndroidGameSurface attachTo(
        String id,
        Activity activity,
        FrameLayout gameRoot,
        Bundle savedInstanceState
    ) {
        AndroidGameSurface surface = id == null ? null : SURFACES.get(id);
        if (surface == null) {
            return null;
        }
        surface.attachTo(activity, gameRoot, savedInstanceState);
        return surface;
    }

    private void attachTo(Activity activity, FrameLayout gameRoot, Bundle savedInstanceState) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Attach game UI on the Android main thread");
        }
        UiContentFactory factory;
        synchronized (lock) {
            if (closed) {
                return;
            }
            if (attached) {
                throw new IllegalStateException("The Android game UI surface already has content");
            }
            factory = contentFactory;
            if (factory == null) {
                throw new IllegalStateException("No deferred content factory was attached to the game surface");
            }
            attached = true;
            this.activity = activity;
            this.gameRoot = gameRoot;
        }

        SurfaceOwners surfaceOwners = new SurfaceOwners(viewModelStore, activity);
        Bundle restored = savedInstanceState == null ? null : savedInstanceState.getBundle(SAVED_STATE_KEY);
        surfaceOwners.create(restored);
        View created = null;
        try {
            Object result = factory.create(activity);
            if (!(result instanceof View)) {
                throw new IllegalArgumentException("Android game UI factories must return an android.view.View");
            }
            created = (View) result;
            ViewTreeLifecycleOwner.set(created, surfaceOwners);
            ViewTreeViewModelStoreOwner.set(created, surfaceOwners);
            ViewTreeSavedStateRegistryOwner.set(created, surfaceOwners);
            ViewTreeOnBackPressedDispatcherOwner.set(created, surfaceOwners);
            gameRoot.addView(created, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            synchronized (lock) {
                this.owners = surfaceOwners;
                this.content = created;
            }
        } catch (Throwable failure) {
            surfaceOwners.destroy(true);
            synchronized (lock) {
                attached = false;
                this.activity = null;
                this.gameRoot = null;
            }
            finishClose();
            throw failure;
        }
    }

    @Override
    public Executor uiExecutor() {
        return uiExecutor;
    }

    @Override
    public void attach(Object nativeContent) {
        if (!(nativeContent instanceof View)) {
            throw new IllegalArgumentException("Android game UI surfaces require an android.view.View");
        }
        attachFactory(platform -> nativeContent);
    }

    @Override
    public void attachFactory(UiContentFactory factory) {
        Objects.requireNonNull(factory, "factory");
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Attach content factories on UiSurface.uiExecutor()");
        }
        synchronized (lock) {
            if (closed || attached || contentFactory != null) {
                throw new IllegalStateException("The Android game UI surface is already attached or closed");
            }
            contentFactory = factory;
        }
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
        AtomicBoolean active = new AtomicBoolean(true);
        return () -> {
            if (active.compareAndSet(true, false)) {
                synchronized (lock) {
                    closeListeners.remove(listener);
                }
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
            notifyListeners();
            Activity current;
            synchronized (lock) {
                current = activity;
            }
            if (current != null && !current.isFinishing() && !current.isDestroyed()) {
                current.finish();
            }
            SURFACES.remove(id, this);
        });
    }

    public void onHostResume() {
        SurfaceOwners current;
        synchronized (lock) {
            current = owners;
        }
        if (current != null) {
            current.resume();
        }
    }

    public void onHostPause() {
        SurfaceOwners current;
        synchronized (lock) {
            current = owners;
        }
        if (current != null) {
            current.pause();
        }
    }

    public void onHostStop() {
        SurfaceOwners current;
        synchronized (lock) {
            current = owners;
        }
        if (current != null) {
            current.stop();
        }
    }

    public void onHostSaveInstanceState(Bundle outState) {
        SurfaceOwners current;
        synchronized (lock) {
            current = owners;
        }
        if (current != null) {
            outState.putBundle(SAVED_STATE_KEY, current.save());
        }
    }

    public void onHostBackPressed() {
        SurfaceOwners current;
        Activity currentActivity;
        synchronized (lock) {
            current = owners;
            currentActivity = activity;
        }
        if (current != null) {
            current.onBackPressedDispatcher.onBackPressed();
        } else if (currentActivity != null) {
            currentActivity.finish();
        }
    }

    public void onHostDestroy(boolean changingConfigurations) {
        SurfaceOwners currentOwners;
        Activity currentActivity;
        FrameLayout currentRoot;
        View currentContent;
        synchronized (lock) {
            currentOwners = owners;
            currentActivity = activity;
            currentRoot = gameRoot;
            currentContent = content;
            owners = null;
            activity = null;
            gameRoot = null;
            content = null;
            attached = false;
        }
        if (currentRoot != null && currentContent != null && currentContent.getParent() == currentRoot) {
            currentRoot.removeView(currentContent);
        }
        if (currentOwners != null) {
            currentOwners.destroy(!changingConfigurations);
        }
        if (!changingConfigurations) {
            finishClose();
            SURFACES.remove(id, this);
        }
    }

    boolean isActivityActive() {
        synchronized (lock) {
            return activity != null && !activity.isFinishing() && !activity.isDestroyed();
        }
    }

    private void finishClose() {
        if (markClosed()) {
            notifyListeners();
            SURFACES.remove(id, this);
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
            HostLog.error("Extension game UI close callback failed", failure);
        }
    }

    private static final class SurfaceOwners implements LifecycleOwner, ViewModelStoreOwner,
        SavedStateRegistryOwner, OnBackPressedDispatcherOwner {

        private final LifecycleRegistry lifecycle = new LifecycleRegistry(this);
        private final SavedStateRegistryController savedState = SavedStateRegistryController.create(this);
        private final ViewModelStore viewModelStore;
        private final OnBackPressedDispatcher onBackPressedDispatcher;
        private boolean created;

        SurfaceOwners(ViewModelStore viewModelStore, Activity activity) {
            this.viewModelStore = viewModelStore;
            this.onBackPressedDispatcher = new OnBackPressedDispatcher(activity::finish);
        }

        @Override
        public Lifecycle getLifecycle() {
            return lifecycle;
        }

        @Override
        public ViewModelStore getViewModelStore() {
            return viewModelStore;
        }

        @Override
        public SavedStateRegistry getSavedStateRegistry() {
            return savedState.getSavedStateRegistry();
        }

        @Override
        public OnBackPressedDispatcher getOnBackPressedDispatcher() {
            return onBackPressedDispatcher;
        }

        void create(Bundle restored) {
            savedState.performAttach();
            savedState.performRestore(restored);
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
            created = true;
        }

        void resume() {
            Lifecycle.State state = lifecycle.getCurrentState();
            if (state == Lifecycle.State.CREATED) {
                lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START);
                state = lifecycle.getCurrentState();
            }
            if (state == Lifecycle.State.STARTED) {
                lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME);
            }
        }

        void pause() {
            if (lifecycle.getCurrentState() == Lifecycle.State.RESUMED) {
                lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE);
            }
        }

        void stop() {
            if (lifecycle.getCurrentState() == Lifecycle.State.RESUMED) {
                lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE);
            }
            if (lifecycle.getCurrentState() == Lifecycle.State.STARTED) {
                lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP);
            }
        }

        Bundle save() {
            Bundle state = new Bundle();
            savedState.performSave(state);
            return state;
        }

        void destroy(boolean clearViewModels) {
            if (lifecycle.getCurrentState() == Lifecycle.State.RESUMED) {
                lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE);
            }
            if (lifecycle.getCurrentState() == Lifecycle.State.STARTED) {
                lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP);
            }
            if (created && lifecycle.getCurrentState() != Lifecycle.State.DESTROYED) {
                lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
            }
            if (clearViewModels) {
                viewModelStore.clear();
            }
        }
    }
}
