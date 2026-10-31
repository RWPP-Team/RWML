/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.event.hooks

import com.corrodinggames.rts.game.RWGameEngine
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.render.CanvasLike
import io.github.rwml.api.event.DrawContext
import io.github.rwml.api.event.DrawEvents
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.InjectionPoint

@AsmMixin("com/corrodinggames/rts/game/RWGameEngine")
object DrawEventMixin {
    @AsmInject(method = "drawAll(Lcom/corrodinggames/rts/gameFramework/render/CanvasLike;F)V", target = InjectionPoint.TAIL, require = 1)
    fun publishFrameDraw(target: Any, canvas: CanvasLike?, deltaTime: Float) {
        if (GameEngine.noResources) return
        val engine = target as RWGameEngine
        val graphics = engine.graphics ?: return
        DrawEvents.FRAME.invoker.onEvent(
            DrawContext(
                engine = engine,
                graphics = graphics,
                canvas = canvas,
                deltaTime = deltaTime,
                frameCount = engine.frameDrawCount,
            ),
        )
    }
}
