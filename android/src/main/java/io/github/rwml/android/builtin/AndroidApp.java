/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.corrodinggames.rts.appFramework.AppContext;
import com.corrodinggames.rts.gameFramework.GameEngine;

import io.github.rwml.android.AndroidBootstrap;

import java.lang.reflect.Field;

/**
 * 本包与平台交互的入口。
 *
 * <p>所有 Activity 都需要完成相同的三项工作：告知引擎当前运行所用的上下文、将全屏界面设置为全屏，以及
 * 支持从非 Activity 上下文启动界面。这些工作都不应由各个 Activity 重复实现。</p>
 *
 * <p>不能调用游戏自身的对应方法：这些方法通过归档中携带的 Android API 副本访问平台，因此在设备上调用会
 * 引发 {@code NoSuchMethodError}。此处的方法使用平台真正的 API 完成相同工作。唯一例外是应用上下文：引擎
 * 会从游戏辅助类的静态字段读取该值，因此此处按名称写入。</p>
 */
public final class AndroidApp {

    private static final String TAG = "RWML";

    /** 游戏辅助类；引擎从中读取应用上下文。 */
    private static final String APP_CONTEXT_CLASS = "com.corrodinggames.rts.appFramework.AppContext";

    /** 根据映射名称确定的辅助类静态字段，用于保存应用上下文。 */
    private static final String APP_CONTEXT_FIELD = "applicationContext";

    private AndroidApp() {
    }

    /**
     * 初始化一个 Activity。
     *
     * <p>如果引擎尚未创建，除启动画面以外的界面都会转交给启动画面处理。Android 可能从上次打开的 Activity
     * 重启进程，而该入口不会执行启动画面的工作，因此 dex 虽然已挂载，引擎却可能尚未创建。此时依赖引擎的
     * 界面会无内容可显示，看起来像是列表为空，而不是缺少引擎。启动画面是唯一能创建引擎的界面，因此其他界面
     * 都会先进入启动画面。</p>
     *
     * @param activity   正在启动的 Activity
     * @param fullScreen 是否隐藏系统栏
     */
    public static void prepare(Activity activity, boolean fullScreen) {
        // 任何代码按名称查找游戏类之前，运行时都必须准备就绪：Android 可能从上次打开的 Activity 重启进程，
        // 而该 Activity 从未执行启动画面的引导流程。
        AndroidBootstrap.prepare(activity.getApplicationContext());
        GameEngine engine = GameEngine.getInstance();
        if (!(activity instanceof IntroScreen) && engine == null) {
            Log.i(TAG, "No engine is running; handing over to the splash from "
                + activity.getClass().getSimpleName() + ".");
            startScreen(activity, new Intent(activity, IntroScreen.class));
            activity.finish();
            return;
        }
        // 其余工作沿用游戏自身的界面初始化方法，而不在此重复实现：保存引擎读取的应用上下文；根据玩家的设置
        // 隐藏系统栏；显示先前因没有界面而暂存的引擎消息（例如地图加载失败消息）；清除窗口背景，因为游戏每帧
        // 都会自行绘制背景。游戏界面也会使用相同参数调用此方法，只是中间的全屏参数在游戏界面中为 false，
        // 而此处为 true，因为应用携带的这些界面都会占满整个窗口。
        try {
            AppContext.setupActivity(activity, true, fullScreen);
        } catch (Throwable t) {
            // 即使平台界面设置失败，也必须显示该界面：玩家正在等待这个界面，否则只会看到空白窗口。
            Log.w(TAG, "The game's screen setup did not complete.", t);
        }
    }

    /**
     * 关闭界面切换动画。
     *
     * <p>游戏也会关闭该动画：界面本身会淡入，再叠加平台动画会显得卡顿。</p>
     */
    public static void disableTransitionAnimation(Activity activity) {
        try {
            activity.overridePendingTransition(0, 0);
        } catch (Throwable t) {
            Log.w(TAG, "Could not turn the transition animation off.", t);
        }
    }

    /** 在主线程执行操作；上下文不是 Activity 时，则在当前线程执行。 */
    public static void runOnUiThread(Context context, Runnable action) {
        if (context instanceof Activity) {
            ((Activity) context).runOnUiThread(action);
        } else {
            action.run();
        }
    }

    /**
     * 在调用方所在的线程中，从任意上下文启动界面；该上下文不一定是 Activity。
     *
     * <p>若上下文不是 Activity，就会添加让新界面创建任务的标志；如果调用来自非主线程，则会将启动操作切换到主线程。</p>
     */
    public static void startScreen(Context context, Intent intent) {
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        Runnable launch = () -> {
            try {
                context.startActivity(intent);
            } catch (Throwable t) {
                Log.e(TAG, "Could not start " + intent.getComponent(), t);
            }
        };
        runOnUiThread(context, launch);
    }

    /**
     * 将应用上下文写入游戏自身的静态字段；如果上下文为 null，则会抛出异常。
     *
     * <p>辅助类会从拥有游戏 dex 的加载器中查找，而非从应用自身的加载器中查找：由平台决定 dex 是否加入应用类路径；
     * 若平台拒绝添加，应用加载器就无法提供游戏类。</p>
     */
    public static void setApplicationContext(Context context) {
        ClassLoader loader = AndroidBootstrap.gameLoader();
        try {
            Class<?> helper = Class.forName(APP_CONTEXT_CLASS, true,
                loader != null ? loader : context.getClassLoader());
            Field field = helper.getDeclaredField(APP_CONTEXT_FIELD);
            field.setAccessible(true);
            field.set(null, context);
            Log.i(TAG, "The engine's application context is set.");
        } catch (Throwable t) {
            Log.w(TAG, "The engine's application context could not be set. The engine will report "
                + "this itself when it is asked to start.", t);
        }
    }
}
