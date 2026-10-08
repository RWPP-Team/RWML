/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;

/**
 * The context the engine keeps for the rest of the process, made safe for dialogs.
 *
 * <p>The engine is given one context at start and reaches for it whenever it opens a window, a
 * dialog among them. An application context has no window of its own, so a dialog built from it
 * fails with {@code BadTokenException}: the window token is null. This wrapper answers window,
 * theme and resource questions with the activity that is currently on screen, and everything else
 * with the application context it was built on. The engine therefore shows its dialogs against a
 * real window without being told which activity that is.</p>
 */
public final class EngineContext extends ContextWrapper {

    /** The activity the engine should treat as its window owner. Null until the first screen opens. */
    private static volatile Activity foreground;

    /** The one wrapper handed to the engine; it tracks the foreground activity on its own. */
    private static volatile EngineContext instance;

    /** Whether the lifecycle tracker that keeps {@link #foreground} current has been installed. */
    private static volatile boolean installed;

    private final Context application;

    private EngineContext(Context application) {
        super(application);
        this.application = application;
    }

    /**
     * @param application the application context the engine falls back to for everything but windows
     * @return the one wrapper the engine is handed, with the foreground tracker installed
     */
    public static EngineContext obtain(Context application) {
        EngineContext current = instance;
        if (current == null) {
            synchronized (EngineContext.class) {
                current = instance;
                if (current == null) {
                    current = instance = new EngineContext(application);
                }
            }
        }
        current.install(application);
        return current;
    }

    /** Installs the lifecycle tracker once, so {@link #foreground} follows the activity in front. */
    private void install(Context application) {
        if (installed) {
            return;
        }
        synchronized (EngineContext.class) {
            if (installed) {
                return;
            }
            Context app = application.getApplicationContext();
            if (app instanceof Application) {
                ((Application) app).registerActivityLifecycleCallbacks(new ForegroundTracker());
            }
            installed = true;
        }
    }

    /** Records the activity that just came to the front and forgets it when it leaves. */
    private static final class ForegroundTracker implements Application.ActivityLifecycleCallbacks {

        @Override
        public void onActivityResumed(Activity activity) {
            foreground = activity;
        }

        @Override
        public void onActivityPaused(Activity activity) {
            if (foreground == activity) {
                foreground = null;
            }
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
            if (foreground == activity) {
                foreground = null;
            }
        }

        @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) { }
        @Override public void onActivityStarted(Activity activity) { }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }
    }

    private Activity activity() {
        return foreground;
    }

    /**
     * The only call a dialog makes that needs a window: the window manager carries the activity's
     * token. Every other service is answered by the application context.
     */
    @Override
    public Object getSystemService(String name) {
        Activity activity = activity();
        if (activity != null && Context.WINDOW_SERVICE.equals(name)) {
            return activity.getSystemService(name);
        }
        return super.getSystemService(name);
    }

    /** Dialogs inflate and theme against the foreground activity's resources and theme. */
    @Override
    public android.content.res.Resources getResources() {
        Activity activity = activity();
        return activity != null ? activity.getResources() : super.getResources();
    }

    @Override
    public android.content.res.Resources.Theme getTheme() {
        Activity activity = activity();
        return activity != null ? activity.getTheme() : super.getTheme();
    }

    @Override
    public void setTheme(int resid) {
        Activity activity = activity();
        if (activity != null) {
            activity.setTheme(resid);
            return;
        }
        super.setTheme(resid);
    }

    /** Intents from the engine leave through the foreground activity, so they gain a task when needed. */
    @Override
    public void startActivity(android.content.Intent intent) {
        Activity activity = activity();
        if (activity != null) {
            activity.startActivity(intent);
            return;
        }
        super.startActivity(intent);
    }

    @Override
    public void startActivity(android.content.Intent intent, android.os.Bundle options) {
        Activity activity = activity();
        if (activity != null) {
            activity.startActivity(intent, options);
            return;
        }
        super.startActivity(intent, options);
    }
}
