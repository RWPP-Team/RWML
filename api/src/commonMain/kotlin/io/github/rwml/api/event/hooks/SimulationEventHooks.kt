/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.event.hooks

import com.corrodinggames.rts.game.RWGameEngine
import io.github.rwml.api.event.SimulationEvents
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.InjectionPoint

@AsmMixin("com/corrodinggames/rts/game/RWGameEngine")
object SimulationEventHooks {
    @AsmInject(method = "updateAllGame(F)V", target = InjectionPoint.HEAD, require = 1)
    fun publishBeforeTick(target: Any, @Suppress("UNUSED_PARAMETER") deltaTime: Float) {
        val engine = target as RWGameEngine
        SimulationEvents.BEFORE_TICK.invoker.onTick(engine, engine.simulationTick)
    }

    @AsmInject(method = "updateAllGame(F)V", target = InjectionPoint.TAIL, require = 1)
    fun publishAfterTick(target: Any, @Suppress("UNUSED_PARAMETER") deltaTime: Float) {
        val engine = target as RWGameEngine
        SimulationEvents.AFTER_TICK.invoker.onTick(engine, engine.simulationTick)
    }
}
