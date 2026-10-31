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
 * 确保引擎排队等待绘制的小地图点位不超出其所在数组范围。
 *
 * <p>绘制小地图单位标记的命令从对象池中获取点位数组；如果该数组恰好适配请求数量，容量就等于点位数。随后逐点绘制方块的部分会
 * 越界读取最后一个点之后的一个 float。引擎自身的线条绘制不会发生这种读取：它会一次性传入整个数组，并明确指定结束位置。
 * 方块绘制则每次读取一个 float，而且总会向前多读一个；数组被完全填满时，就会在游戏线程中抛出
 * {@code ArrayIndexOutOfBoundsException}，随后游戏报告错误并退出。</p>
 *
 * <p>请求数量比批次实际持有的点位多一个，就能使对象池返回的任何容量都留出一个点位的余量，且不会影响其他行为：绘制操作读取的是
 * 命令实际持有的点位数，而不是数组可容纳的数量。</p>
 */
@AsmMixin("com/corrodinggames/rts/gameFramework/gui/MinimapHandler")
public final class MinimapHandlerMixin {

    /** 标记该 Mixin 是否已执行；每帧的每个批次都会触发它。 */
    private static volatile boolean announced;

    private MinimapHandlerMixin() {
    }

    /** 引擎向对象池请求的点数，并额外增加一个点。 */
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
