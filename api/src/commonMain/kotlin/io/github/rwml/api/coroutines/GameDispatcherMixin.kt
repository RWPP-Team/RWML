/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.coroutines

import com.corrodinggames.rts.game.RWGameEngine
import io.github.rwml.api.event.FrameEvents
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.InjectionPoint

@AsmMixin("com/corrodinggames/rts/game/RWGameEngine")
object GameDispatcherMixin {
    @AsmInject(method = "updateFrameInternal(FI)V", target = InjectionPoint.HEAD, require = 1)
    fun beginNativeFrame(target: Any, deltaTime: Float, deltaMillis: Int) {
        GameMainDispatcher.drainNativeFrame()
        FrameEvents.BEGIN.invoker.onFrame(target as RWGameEngine, deltaTime, deltaMillis)
    }

    @AsmInject(method = "updateFrameInternal(FI)V", target = InjectionPoint.TAIL, require = 1)
    fun endNativeFrame(target: Any, deltaTime: Float, deltaMillis: Int) {
        FrameEvents.END.invoker.onFrame(target as RWGameEngine, deltaTime, deltaMillis)
    }
}
