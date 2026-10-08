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
 * The surface the game is drawn into, as the engine sees it: the engine does not own a window, it asks
 * whoever implements {@link GameViewInterface} for a canvas.
 *
 * <p>The engine runs the frame loop on its own thread and, on the buffered render path, asks for a
 * canvas with {@link #getCanvas}, draws into it and presents it with {@link #drawCanvas}. The two calls
 * bracket one frame: {@link #getCanvas} locks the SurfaceView and wraps the locked Android canvas in the
 * game's own {@link CanvasDirect}, and {@link #drawCanvas} posts it. The platform-independent archive
 * already carries {@link CanvasDirect}, so this view only has to supply the surface the game lacks.</p>
 */
public final class AndroidGameView extends SurfaceView implements GameViewInterface {

    private static final String TAG = "RWML";

    /** The canvas the engine draws into, wrapping the SurfaceView's locked canvas. */
    private CanvasDirect surfaceCanvas;

    /** The SurfaceView canvas locked for the current frame, posted in {@link #drawCanvas}. */
    private android.graphics.Canvas lockedCanvas;

    /** The lock the buffered render path synchronises the frame on. */
    private final Object renderLock = new Object();

    /**
     * Whether the engine is given a canvas to draw into.
     *
     * <p>The engine asks for a canvas on every frame and never asks whether the screen that owns the
     * surface is still the one in front. A screen opened over this one leaves the frame loop running
     * and drawing into a surface nobody is looking at, which is what the game's own build avoids by
     * handing its view back to the engine as the screen behind goes into the background. The archive
     * this app runs carries no call that does that, so the same thing is done from this side: the
     * buffered render path is the only path that presents, and reporting it as off leaves the engine
     * updating without drawing a single frame.</p>
     */
    private volatile boolean drawing = true;

    /** The engine's touch state, filled from the events this view receives. */
    private final TouchEvent touchEvent = new TouchEvent();

    private int viewWidth;
    private int viewHeight;

    /** The host activity, which the engine asks for through {@link #getActivity()}. */
    private GameActivity hostActivity;

    /** Cached handles into the engine's {@link TouchEvent} for the pointer count, which has no public setter. */
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
     * Remembers the activity that carries the game, which the engine asks for through
     * {@link #getActivity()}. The engine fills its in-game pause menu from the activity's
     * {@code onPrepareOptionsMenu} and routes menu clicks back to it, so a null host leaves that menu
     * with only the hard-coded Resume button. The host activity is a GameActivity: the type the engine
     * expects, and a real foreground activity whose window tokens are valid for the dialogs the engine
     * shows.
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
     * Gives the engine a canvas, or takes it away.
     *
     * @param drawing whether the frame loop may draw into this surface
     */
    public void setDrawingEnabled(boolean drawing) {
        this.drawing = drawing;
    }

    /** @return whether the engine is currently given a canvas to draw into */
    public boolean isDrawingEnabled() {
        return drawing;
    }

    /**
     * Stops the engine drawing into the surface it is holding, or lets it start again.
     *
     * <p>The engine holds one game view at a time and takes the one it was last handed on the next
     * frame, so both are switched: the one it is drawing into now, and the one it has been asked to
     * draw into. A view of another kind is left alone.</p>
     *
     * @param engine the engine whose view is switched off or on
     * @param drawing whether the frame loop may draw into the surface the engine is holding
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

    /** @return the width the surface was last sized to */
    public int getViewWidth() {
        return viewWidth;
    }

    /** @return the height the surface was last sized to */
    public int getViewHeight() {
        return viewHeight;
    }

    // ------------------------------------------------------------------
    // The engine's questions
    // ------------------------------------------------------------------

    @Override
    public CanvasLike getCanvas(boolean primary) {
        if (lockedCanvas != null) {
            // The frame is already locked; hand back the same canvas rather than lock twice.
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
            // lockCanvas returns a fresh canvas each frame; point the wrapper at it.
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
        // The renderer belongs to the GL path, which no archive carries a usable version of yet.
        return null;
    }

    @Override
    public GameActivity getActivity() {
        // The host activity is a GameActivity and a real foreground activity, so its window tokens are
        // valid for the dialogs the engine shows, and its onPrepareOptionsMenu fills the in-game pause
        // menu the engine draws. A null here would leave that menu with only the hard-coded Resume.
        return hostActivity;
    }

    @Override
    public boolean isUpdateEnabled() {
        return true;
    }

    @Override
    public boolean isBufferedRendering() {
        // The buffered render path is the only one that presents: the engine fetches a canvas with
        // getCanvas and posts it with drawCanvas. Reporting it as off leaves the frame loop running
        // its update while the whole draw block is skipped, which is what a screen that has been
        // covered over wants.
        return drawing;
    }

    @Override
    public boolean usesExternalRendering() {
        // The view supplies its own canvas; the engine does not hand rendering to an external surface.
        return false;
    }

    @Override
    public boolean isFullscreen() {
        // The activity owns the window chrome; the engine should not force its own fullscreen mode.
        return false;
    }

    @Override
    public boolean isDirectCanvasMode() {
        // The direct render path does not call drawCanvas, so a SurfaceView never gets posted. The
        // buffered path (isBufferedRendering) is the one that presents, so this stays false.
        return false;
    }

    @Override
    public Object getGameThreadSync() {
        // The frame synchronisation lock the buffered render path waits on.
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
     * The engine reports the start of a frame with a timestamp; the view has nothing to do with it,
     * so it is recorded and otherwise ignored.
     */
    @Override
    public void notifyDrawStart(float value, int code) {
    }

    @Override
    public void notifyDrawEnd(float value, int code) {
    }

    // ------------------------------------------------------------------
    // Input: the platform's events, in the shape the engine reads
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

        // The engine reads every reported finger: it loops over getPointerX()[i] / getPointerY()[i]
        // for i in 0..getPointerCount()-1. The primary pointer is what the desktop feeds from the
        // mouse, so a single touch fills only index 0; a multi-touch gesture fills the whole array so
        // the engine sees every finger. TouchEvent has no public setter for the count, so the pending
        // count is written through reflection into the engine's own field.
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
     * Clears the per-pointer mouse-button state array. {@link TouchEvent#getPointerIds()} is not a
     * list of platform pointer ids: the engine reads it as mouse-button states through
     * {@code GameEngine.getPointerState} and {@code GameEngine.findPointerIndexWithState} (the desktop
     * fills it from the mouse buttons, 1 = primary, 2 = secondary, 3 = middle). A touch reports no
     * button, so every entry is zero. That keeps the two engine paths apart. With the primary state at
     * zero, {@code InterfaceEngine.update} takes the camera-pan branch for a one-finger drag; and with
     * no pointer carrying state 1 or 2, {@code BattleInterface.handleDragSelection} skips its
     * single-anchor branch and takes its two-finger branch, which builds the selection rectangle from
     * the two contact points.
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
     * Writes the number of active pointers into the engine's {@link TouchEvent}, which has no public
     * setter for it. The engine derives {@code getPointerCount()} from this field after
     * {@code updateTouchState()}, so a pinch (two fingers) or a three-finger gesture is only seen when
     * the count here matches the fingers the platform reported. The field handles are cached; a miss
     * leaves single-touch working and is logged rather than thrown.
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

    /** Reports the surface geometry into the view, which is where the engine reads it. */
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
     * Tells the engine how large the surface is, when the dex that carries it is mounted.
     *
     * <p>The original renderer reports the canvas size every frame, which is what sets the screen
     * size and the sidebar width the minimap reads. With no renderer in the loop that never happens,
     * and a zero screen size leaves the sidebar width at zero, so the minimap asks
     * {@code Bitmap.createBitmap} for a zero-sized buffer and the frame dies. The surface's own
     * geometry is the size to report.</p>
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
