/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.lang.reflect.Field;

import com.corrodinggames.rts.appFramework.GameActivity;
import com.corrodinggames.rts.appFramework.GameViewInterface;
import com.corrodinggames.rts.appFramework.TouchEvent;
import com.corrodinggames.rts.gameFramework.GameEngine;
import com.corrodinggames.rts.gameFramework.render.Basic2DGLRenderer;
import com.corrodinggames.rts.gameFramework.render.CanvasDirect;
import com.corrodinggames.rts.gameFramework.render.CanvasLike;

import io.github.rwml.android.AndroidBootstrap;

/**
 * 引擎看到的游戏绘制表面：引擎不拥有窗口，而是向 {@link GameViewInterface} 的实现请求画布。
 *
 * <p>引擎在自己的线程中运行帧循环；使用缓冲渲染路径时，会通过 {@link #getCanvas} 请求画布，在其上绘制，然后通过
 * {@link #drawCanvas} 显示画面。这两个调用分别对应一帧的开始和结束：{@link #getCanvas} 锁定 SurfaceView，并将锁定的
 * Android 画布包装为游戏自身的 {@link CanvasDirect}；{@link #drawCanvas} 则提交画布。平台无关归档已经包含
 * {@link CanvasDirect}，因此此视图只需提供游戏缺少的绘制表面。</p>
 */
public final class AndroidGameView extends SurfaceView implements GameViewInterface {

    private static final String TAG = "RWML";

    /** 引擎使用的绘图画布，包装了已锁定的 SurfaceView 画布。 */
    private CanvasDirect surfaceCanvas;

    /** 当前帧锁定的 SurfaceView 画布，由 {@link #drawCanvas} 提交。 */
    private android.graphics.Canvas lockedCanvas;

    /** 缓冲渲染路径用于同步帧的锁。 */
    private final Object renderLock = new Object();

    /**
     * 是否向引擎提供画布进行绘制。
     *
     * <p>引擎每帧都会请求画布，却不会检查拥有该表面的界面是否仍位于前台。若在此界面上方打开另一个界面，帧循环仍会运行，
     * 并继续绘制到无人查看的表面。游戏原生版本会在当前界面进入后台时将视图状态通知引擎，以避免这种情况；当前应用运行的归档
     * 没有提供此调用，因此由此处补充处理：只有缓冲渲染路径会显示画面，将其报告为关闭会让引擎只更新、不绘制任何帧。</p>
     */
    private volatile boolean drawing = true;

    /** 引擎触摸状态，根据此视图收到的事件填充。 */
    private final TouchEvent touchEvent = new TouchEvent();

    private int viewWidth;
    private int viewHeight;

    /** 宿主 Activity，供引擎通过 {@link #getActivity()} 查询。 */
    private GameActivity hostActivity;

    /** 缓存的引擎 {@link TouchEvent} 触点数量字段句柄；该字段没有公开的设置方法。 */
    private static Field touchPointerCountField;
    private static Field touchPointerDownField;
    private static boolean touchFieldsReady;

    public AndroidGameView(Context context) {
        super(context);
        captureHost(context);
        init();
    }

    public AndroidGameView(Context context, AttributeSet attributes) {
        super(context, attributes);
        captureHost(context);
        init();
    }

    /**
     * 保存承载游戏的 Activity，以供引擎通过 {@link #getActivity()} 查询。引擎会通过 Activity 的
     * {@code onPrepareOptionsMenu} 填充游戏内暂停菜单，并将菜单点击事件回传给 Activity；若宿主为空，菜单就只剩下硬编码的
     *“继续”按钮。宿主 Activity 是引擎所需的 GameActivity 类型，也是有效的前台 Activity，因此引擎显示的对话框可以使用其窗口令牌。
     */
    private void captureHost(Context context) {
        if (context instanceof GameActivity) {
            hostActivity = (GameActivity) context;
        }
    }

    private void init() {
        setFocusable(true);
        setFocusableInTouchMode(true);
        getHolder().addCallback(new SurfaceEvents());
        Log.i(TAG, "The game surface is up.");
    }

    /**
     * 向引擎提供画布，或撤销画布。
     *
     * @param drawing 帧循环是否可以在此表面上绘制
     */
    public void setDrawingEnabled(boolean drawing) {
        this.drawing = drawing;
    }

    /** @return 当前是否向引擎提供了画布进行绘制 */
    public boolean isDrawingEnabled() {
        return drawing;
    }

    /**
     * 停止引擎向当前持有的表面绘制，或允许它恢复绘制。
     *
     * <p>引擎一次只持有一个游戏视图，并会在下一帧使用最近收到的视图，因此需要同时切换当前正在绘制的视图和此前请求绘制的视图。
     * 其他类型的视图保持不变。</p>
     *
     * @param engine 要切换其视图状态的引擎
     * @param drawing 帧循环是否可以在引擎当前持有的表面上绘制
     */
    public static void setEngineDrawingEnabled(GameEngine engine, boolean drawing) {
        if (engine == null) {
            return;
        }
        setDrawingEnabled(engine.gameView, drawing);
        setDrawingEnabled(engine.pendingGameView, drawing);
    }

    private static void setDrawingEnabled(GameViewInterface view, boolean drawing) {
        if (view instanceof AndroidGameView) {
            ((AndroidGameView) view).setDrawingEnabled(drawing);
        }
    }

    /** @return 表面最近一次设置的宽度 */
    public int getViewWidth() {
        return viewWidth;
    }

    /** @return 表面最近一次设置的高度 */
    public int getViewHeight() {
        return viewHeight;
    }

    // ------------------------------------------------------------------
    // 引擎查询
    // ------------------------------------------------------------------

    @Override
    public CanvasLike getCanvas(boolean primary) {
        if (lockedCanvas != null) {
            // 当前帧已经锁定画布；直接返回同一个画布，避免重复锁定。
            return surfaceCanvas;
        }
        android.graphics.Canvas canvas = getHolder().lockCanvas(null);
        if (canvas == null) {
            return null;
        }
        lockedCanvas = canvas;
        if (surfaceCanvas == null) {
            surfaceCanvas = new CanvasDirect(canvas);
        } else {
            // lockCanvas 每帧都会返回新的画布；将包装器指向该画布。
            surfaceCanvas.canvas = canvas;
        }
        return surfaceCanvas;
    }

    @Override
    public void drawCanvas(CanvasLike target, boolean secondary) {
        if (lockedCanvas == null) {
            return;
        }
        try {
            getHolder().unlockCanvasAndPost(lockedCanvas);
        } catch (Throwable t) {
            Log.w(TAG, "The game surface could not be posted.", t);
        } finally {
            lockedCanvas = null;
        }
    }

    @Override
    public TouchEvent getTouchEvent() {
        return touchEvent;
    }

    @Override
    public Basic2DGLRenderer getRenderer() {
        // 渲染器属于 GL 路径，目前没有归档提供可用的实现。
        return null;
    }

    @Override
    public GameActivity getActivity() {
        // 宿主 Activity 是 GameActivity 类型，也是有效的前台 Activity，因此它的窗口令牌可用于引擎显示的对话框；
        // 其 onPrepareOptionsMenu 还会填充引擎绘制的游戏内暂停菜单。此处若返回 null，菜单就只剩下硬编码的“继续”按钮。
        return hostActivity;
    }

    @Override
    public boolean isUpdateEnabled() {
        return true;
    }

    @Override
    public boolean isBufferedRendering() {
        // 只有缓冲渲染路径会显示画面：引擎通过 getCanvas 获取画布，再通过 drawCanvas 提交。若报告该路径已关闭，帧循环仍会更新，
        // 但会完全跳过绘制；界面被其他界面覆盖时正需要这种行为。
        return drawing;
    }

    @Override
    public boolean usesExternalRendering() {
        // 画布由视图自身提供；引擎不会将渲染交给外部表面。
        return false;
    }

    @Override
    public boolean isFullscreen() {
        // 窗口装饰由 Activity 管理；引擎不应强制切换自己的全屏模式。
        return false;
    }

    @Override
    public boolean isDirectCanvasMode() {
        // 直接渲染路径不会调用 drawCanvas，因此 SurfaceView 永远不会提交画面。只有缓冲路径
        //（isBufferedRendering）会显示画面，所以此处保持 false。
        return false;
    }

    @Override
    public Object getGameThreadSync() {
        // 缓冲渲染路径等待的帧同步锁。
        return renderLock;
    }

    @Override
    public void onGameViewAttaching() {
    }

    @Override
    public void onCanvasDrawError() {
    }

    @Override
    public void onGameViewDetached() {
    }

    @Override
    public void onMapUnloaded() {
    }

    @Override
    public void onGameViewAttached() {
    }

    /**
     * 引擎使用时间戳报告帧开始时间；视图无需处理该值，因此会记录并忽略。
     */
    @Override
    public void notifyDrawStart(float value, int code) {
    }

    @Override
    public void notifyDrawEnd(float value, int code) {
    }

    // ------------------------------------------------------------------
    // 输入：将平台事件转换为引擎可读取的格式
    // ------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean down;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_MOVE:
                down = true;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                down = false;
                break;
            default:
                return true;
        }

        // 引擎会读取所有报告的触点：遍历 getPointerX()[i] / getPointerY()[i]，其中 i 的范围为
        // 0..getPointerCount()-1。主触点对应桌面端鼠标输入，因此单指触摸只填充索引 0；多点手势会填满整个数组，
        // 让引擎识别每个触点。TouchEvent 没有公开的数量设置方法，因此通过反射写入引擎自身的字段。
        int count = event.getPointerCount();
        float[] pointerX = touchEvent.getPointerX();
        float[] pointerY = touchEvent.getPointerY();
        if (count > pointerX.length) {
            count = pointerX.length;
        }
        for (int i = 0; i < count; i++) {
            pointerX[i] = event.getX(i);
            pointerY[i] = event.getY(i);
        }
        clearPointerStates();
        setTouchPointerCount(count, down);
        touchEvent.updateTouchState();
        return true;
    }

    /**
     * 清除每个触点对应的鼠标按钮状态数组。{@link TouchEvent#getPointerIds()} 并非平台触点 ID 列表：引擎会通过
     * {@code GameEngine.getPointerState} 和 {@code GameEngine.findPointerIndexWithState} 将其解释为鼠标按钮状态
     *（桌面端从鼠标按钮填充该数组：1 = 主键，2 = 次键，3 = 中键）。触摸输入不报告鼠标按钮，因此所有元素均为零。这样可以区分两种
     * 引擎输入路径。主键状态为零时，{@code InterfaceEngine.update} 会在单指拖动时执行摄像机平移逻辑；如果没有触点携带状态 1 或 2，
     * {@code BattleInterface.handleDragSelection} 就会跳过单点锚定分支，并进入双指分支，根据两个触点构建选区矩形。
     */
    private void clearPointerStates() {
        int[] states = touchEvent.getPointerIds();
        if (states == null) {
            return;
        }
        for (int i = 0; i < states.length; i++) {
            states[i] = 0;
        }
    }

    /**
     * 将活动触点数量写入引擎的 {@link TouchEvent}。该类没有公开的设置方法；引擎会在
     * {@code updateTouchState()} 后从此字段推导 {@code getPointerCount()}。因此，平台报告的触点数量必须与此处一致，
     * 引擎才能识别双指缩放或三指手势。字段句柄会被缓存；若查找失败，单指触摸仍可正常使用，且只记录日志，不抛出异常。
     */
    private void setTouchPointerCount(int count, boolean down) {
        Field countField = touchPointerCountField();
        Field downField = touchPointerDownField();
        if (countField == null && downField == null) {
            return;
        }
        try {
            if (countField != null) {
                countField.setInt(touchEvent, count);
            }
            if (downField != null) {
                downField.setBoolean(touchEvent, down);
            }
        } catch (Throwable t) {
            Log.w(TAG, "The touch pointer count could not be set.", t);
        }
    }

    private static Field touchPointerCountField() {
        ensureTouchFields();
        return touchPointerCountField;
    }

    private static Field touchPointerDownField() {
        ensureTouchFields();
        return touchPointerDownField;
    }

    private static synchronized void ensureTouchFields() {
        if (touchFieldsReady) {
            return;
        }
        try {
            touchPointerCountField = TouchEvent.class.getDeclaredField("pendingPointerCount");
            touchPointerCountField.setAccessible(true);
            touchPointerDownField = TouchEvent.class.getDeclaredField("pendingDown");
            touchPointerDownField.setAccessible(true);
        } catch (Throwable t) {
            Log.w(TAG, "The engine touch fields could not be reached.", t);
        }
        touchFieldsReady = true;
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
    }

    /** 将表面尺寸写入视图，供引擎读取。 */
    private final class SurfaceEvents implements SurfaceHolder.Callback {

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            Log.i(TAG, "The game surface was created.");
        }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        viewWidth = width;
        viewHeight = height;
        Log.i(TAG, "The game surface is " + width + "x" + height + ".");
        pushScreenSize();
    }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            Log.i(TAG, "The game surface was destroyed.");
        }
    }

    /**
     * dex 挂载后，向引擎报告表面的尺寸。
     *
     * <p>原始渲染器每帧都会报告画布大小，并据此设置屏幕尺寸和小地图使用的侧栏宽度。当前循环中没有渲染器，因此该步骤不会发生；
     * 屏幕尺寸为零时侧栏宽度也会是零，小地图随后会通过 {@code Bitmap.createBitmap} 请求创建零尺寸缓冲区，导致帧循环崩溃。
     * 应报告表面自身的几何尺寸。</p>
     */
    public void pushScreenSize() {
        if (viewWidth <= 0 || viewHeight <= 0) {
            return;
        }
        ClassLoader loader = AndroidBootstrap.gameLoader();
        if (loader == null) {
            return;
        }
        try {
            Class<?> engine = Class.forName(
                "com.corrodinggames.rts.gameFramework.GameEngine", true, loader);
            Object instance = engine.getMethod("getInstance").invoke(null);
            if (instance == null) {
                return;
            }
            instance.getClass().getMethod("resizeScreen", int.class, int.class)
                .invoke(instance, viewWidth, viewHeight);
        } catch (Throwable t) {
            Log.w(TAG, "The engine screen size could not be set.", t);
        }
    }
}
