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
 * 引擎在进程剩余生命周期中持有的上下文，并确保可安全显示对话框。
 *
 * <p>引擎启动时会收到一个上下文，并在打开窗口（包括对话框）时继续使用它。应用上下文本身没有窗口，
 * 因此基于它创建的对话框会因窗口令牌为空而抛出 {@code BadTokenException}。此包装器通过当前显示的
 * Activity 响应窗口、主题和资源相关请求，其余请求则交给创建包装器时使用的应用上下文。这样，引擎
 * 无需知道当前 Activity，也能在真实窗口上显示对话框。</p>
 */
public final class EngineContext extends ContextWrapper {

    /** 引擎应视为窗口所有者的 Activity。首个界面打开前为 null。 */
    private static volatile Activity foreground;

    /** 提供给引擎的唯一包装器；它会自行跟踪前台 Activity。 */
    private static volatile EngineContext instance;

    /** 是否已安装生命周期跟踪器，以便持续更新 {@link #foreground}。 */
    private static volatile boolean installed;

    private final Context application;

    private EngineContext(Context application) {
        super(application);
        this.application = application;
    }

    /**
     * @param application 应用上下文；除窗口相关请求外，引擎都会回退到该上下文
     * @return 提供给引擎的唯一包装器，并已安装前台 Activity 跟踪器
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

    /** 只安装一次生命周期跟踪器，使 {@link #foreground} 始终指向当前前台 Activity。 */
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

    /** 记录刚进入前台的 Activity，并在其离开时清除记录。 */
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

    public static Activity foregroundActivity() { return foreground; }

    /**
     * 对话框唯一需要窗口的调用：窗口管理器携带 Activity 的令牌。其他服务均由应用上下文提供。
     */
    @Override
    public Object getSystemService(String name) {
        Activity activity = activity();
        if (activity != null && Context.WINDOW_SERVICE.equals(name)) {
            return activity.getSystemService(name);
        }
        return super.getSystemService(name);
    }

    /** 对话框使用前台 Activity 的资源和主题进行填充与样式设置。 */
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

    /** 引擎发出的 Intent 会经由前台 Activity 启动，必要时会为其添加任务标志。 */
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
