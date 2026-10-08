/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.mixin;

import io.github.rwml.android.HostLog;

import kim.der.asm.api.annotation.AsmMixin;
import kim.der.asm.api.annotation.At;
import kim.der.asm.api.annotation.InjectionPoint;
import kim.der.asm.api.annotation.ModifyArg;

/**
 * Keeps the engine's queued minimap points inside the array they were queued in.
 *
 * <p>The command that draws the minimap's unit marks takes its points from a pooled array handed
 * out with a capacity of exactly the asked-for count when that is the best fit in the pool, and its
 * square-per-point half then reads one float past the last point of an array the points fill
 * exactly. With the engine's own line rendering that read never happens: the whole array is handed
 * to one draw call that is told where it ends. The square half steps through the array one float at
 * a time and always looks one float ahead, so a full array ends in an
 * {@code ArrayIndexOutOfBoundsException} on the game thread and the game reports it and dies.</p>
 *
 * <p>Asking for one point more than the batch holds leaves one point of spare room at every
 * capacity the pool can hand out, and changes nothing else: the draw is told how many points the
 * command holds, never how many it has room for.</p>
 */
@AsmMixin("com/corrodinggames/rts/gameFramework/gui/MinimapHandler")
public final class MinimapHandlerMixin {

    /** Whether this mixin has said that it is in: it runs for every batch of every frame. */
    private static volatile boolean announced;

    private MinimapHandlerMixin() {
    }

    /** The point count the engine asks its pool for, one point richer than it asked for. */
    @ModifyArg(
        method = "drawUnitDots(Lcom/corrodinggames/rts/gameFramework/render/GraphicsEngine;IIFF)V",
        index = 0,
        at = @At(
            value = InjectionPoint.INVOKE,
            target = "com.corrodinggames.rts.gameFramework.gui.MinimapHandler.obtainQueuedPointsDraw"
                + "(ILandroid/graphics/Paint;)"
                + "Lcom/corrodinggames/rts/gameFramework/gui/MinimapHandlerQueuedPointsDraw;"
        )
    )
    public static int withOneSparePoint(int count) {
        if (!announced) {
            announced = true;
            HostLog.add("mixin      MinimapHandlerMixin is in: minimap point batches keep one spare point");
        }
        return count + 1;
    }
}
