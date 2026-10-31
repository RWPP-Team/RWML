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
 * 将小地图待绘制点画成游戏 Android 版本所用的小方块。
 *
 * <p>此应用运行的是引擎跨平台构建，其待绘制点会整批交给 {@code drawLines}，该方法会在每两个相邻单位之间绘制线段，
 * 因而小地图上的单位标记看起来像横穿地图的长条。游戏 Android 版本也会将相同批次排队，但会将其绘制为点：每个单位对应
 * 一个方块，大小由画笔线宽决定，这也符合“线宽”设置项的含义。</p>
 *
 * <p>两种绘制模式都会经过批次调用，并在重定向后为每个点绘制一个方块。游戏逐点处理的分支每次只移动一个 float，
 * 会越过完整数组，因此此处绕过该分支。每个绘图线程会重复使用同一个矩形对象处理多个批次。</p>
 *
 * <p>处理器签名使用 Object 表示游戏类型：游戏 dex 尚未生成时，框架就会通过反射检查这些方法。类型转换放在处理器方法体内，
 * 因为只有挂载 dex 后才会执行该方法。与覆盖式 mixin 不同，重定向式 mixin 无需在 APK 中提供 JVM 类文件资源。</p>
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

    /** 将两种模式都转交给修正后的批次渲染器。 */
    @Redirect(
        method = "execute(Lcom/corrodinggames/rts/gameFramework/render/GraphicsEngine;)V",
        at = @At(value = InjectionPoint.FIELD, target = "drawAsPoints:Z"),
        require = 1, allow = 1
    )
    public static boolean useBatchRenderer(Object command) {
        return false;
    }

    /** 待绘制批次：每个排队点绘制一个方块，而不是每对点绘制一条线。 */
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
