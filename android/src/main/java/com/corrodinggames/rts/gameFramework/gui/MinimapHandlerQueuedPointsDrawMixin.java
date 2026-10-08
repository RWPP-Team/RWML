/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package com.corrodinggames.rts.gameFramework.gui;

import android.graphics.Paint;
import android.graphics.RectF;

import com.corrodinggames.rts.gameFramework.render.GraphicsEngine;

import io.github.rwml.android.HostLog;

import kim.der.asm.api.annotation.AsmMixin;
import kim.der.asm.api.annotation.At;
import kim.der.asm.api.annotation.InjectionPoint;
import kim.der.asm.api.annotation.Redirect;

/**
 * Draws the minimap's queued points as the small squares the game's own Android build draws.
 *
 * <p>The archive this app runs is the engine's platform-independent build, and its queued points
 * draw hands the whole batch to {@code drawLines}, which paints one segment between every two
 * consecutive units — which is why the minimap's unit marks read as long bars crossing the map.
 * The game's own Android build queues the same batch but draws it as points: one square per unit,
 * sized by the paint's line width, which is what the setting named for line width means.</p>
 *
 * <p>Both drawing modes go through the batch call, redirected to a square per point. The game's
 * per-point branch steps one float at a time and reads past a full array, so it is bypassed. A
 * rectangle per drawing thread is reused across batches.</p>
 *
 * <p>Handler signatures use Object for game types: the framework reflects on these methods before
 * the game dex exists. The cast belongs in the handler body, which runs only after mounting. Unlike
 * an overwrite, a redirect needs no JVM class-file resource for the mixin in the APK.</p>
 */
@AsmMixin("com/corrodinggames/rts/gameFramework/gui/MinimapHandlerQueuedPointsDraw")
public final class MinimapHandlerQueuedPointsDrawMixin {

    private static volatile boolean announced;

    private static final ThreadLocal<RectF> scratchRect = new ThreadLocal<RectF>() {
        @Override
        protected RectF initialValue() {
            return new RectF();
        }
    };

    /** Routes both modes through the corrected batch renderer. */
    @Redirect(
        method = "execute(Lcom/corrodinggames/rts/gameFramework/render/GraphicsEngine;)V",
        at = @At(value = InjectionPoint.FIELD, target = "drawAsPoints:Z"),
        require = 1, allow = 1
    )
    public static boolean useBatchRenderer(Object command) {
        return false;
    }

    /** The batch, as one square per queued point instead of one line per point pair. */
    @Redirect(
        method = "execute(Lcom/corrodinggames/rts/gameFramework/render/GraphicsEngine;)V",
        target = "com.corrodinggames.rts.gameFramework.render.GraphicsEngine.drawLines"
            + "([FIILandroid/graphics/Paint;)V",
        require = 1, allow = 1
    )
    public static void drawSquares(Object renderer, float[] points, int offset, int count, Paint paint) {
        if (!announced) {
            announced = true;
            HostLog.add("mixin      MinimapHandlerQueuedPointsDrawMixin is in: "
                + "minimap points draw as squares");
        }
        GraphicsEngine graphics = (GraphicsEngine) renderer;
        RectF rect = scratchRect.get();
        float size = paint.getStrokeWidth();
        int end = offset + count;
        int i = offset;
        while (i + 1 < end) {
            float x = points[i];
            float y = points[i + 1];
            rect.left = x;
            rect.top = y;
            rect.right = x + size;
            rect.bottom = y + size;
            graphics.drawRect(rect, paint);
            i += 2;
        }
    }
}
