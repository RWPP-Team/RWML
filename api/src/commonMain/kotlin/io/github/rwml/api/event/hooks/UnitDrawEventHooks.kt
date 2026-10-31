/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.event.hooks

import android.graphics.Paint
import android.graphics.RectF
import com.corrodinggames.rts.game.units.Unit as GameUnit
import com.corrodinggames.rts.gameFramework.CommandControllerCommand
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.render.GraphicsEngine
import io.github.rwml.api.event.CommandEvents
import io.github.rwml.api.event.UnitDraw
import io.github.rwml.api.event.UnitDrawEvents
import io.github.rwml.api.event.UnitOverlayBarDecision
import java.util.ArrayDeque
import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.Operation
import kim.der.asm.api.annotation.WrapMethod
import kim.der.asm.api.annotation.WrapOperation

/**
 * Runs first on Unit so the later method wrapper encloses this entry callback and native body.
 * The explicit priority ordering is needed: Unit.drawOverlay must push before any bar redirect,
 * and the scope wrapper must be applied after both.
 */
@AsmMixin("com/corrodinggames/rts/game/units/Unit", priority = 2000)
object UnitDrawEntryMixin {
    @AsmInject(method = "runOnDestroyHooks(F)V", target = InjectionPoint.HEAD, require = 1)
    fun publishUnitDraw(target: Any, deltaTime: Float) {
        UnitDrawEvents.DRAW.invoker.onEvent(
            UnitDraw(
                unit = target as GameUnit,
                graphics = GameEngine.getInstance().graphics,
                deltaTime = deltaTime,
            ),
        )
    }

    @AsmInject(method = "drawOverlay(FZ)V", target = InjectionPoint.HEAD, require = 1)
    fun pushOverlayUnit(target: Any) {
        UnitOverlayDrawContext.push(target as GameUnit)
    }
}

/** Intercepts each rectangle in the native Unit.drawOverlay body, preserving native drawing on PASS. */
@AsmMixin("com/corrodinggames/rts/game/units/Unit", priority = 1500)
object UnitOverlayBarMixin {
    @WrapOperation(
        method = "drawOverlay(FZ)V",
        at = At(
            value = InjectionPoint.INVOKE,
            target = "com.corrodinggames.rts.gameFramework.render.GraphicsEngine.drawRect(Landroid/graphics/RectF;Landroid/graphics/Paint;)V",
        ),
        require = 1,
    )
    fun drawNativeOverlayBar(
        graphics: Any,
        rect: RectF,
        paint: Paint,
        operation: Operation<Unit>,
    ) {
        val unit = UnitOverlayDrawContext.currentOrNull()
        val nativeGraphics = graphics as GraphicsEngine
        if (unit == null || !UnitDrawEvents.OVERLAY_BAR.hasListeners) {
            callNativeDraw(operation, graphics, rect, paint)
            return
        }

        val decision = UnitDrawEvents.OVERLAY_BAR.invoker.onOverlayBar(unit, nativeGraphics, rect, paint)
        if (decision == UnitOverlayBarDecision.PASS) {
            callNativeDraw(operation, graphics, rect, paint)
        }
    }
}

/** Applied last so its finally scope encloses the entry injection and every native early return. */
@AsmMixin("com/corrodinggames/rts/game/units/Unit", priority = 1000)
object UnitOverlayScopeMixin {
    @WrapMethod(method = "drawOverlay(FZ)V", require = 1)
    fun restoreOverlayContext(
        deltaTime: Float,
        drawSelection: Boolean,
        operation: Operation<Unit>,
    ) {
        val entryDepth = UnitOverlayDrawContext.depth
        try {
            operation.call(deltaTime, drawSelection)
        } catch (failure: Throwable) {
            throw originalDrawFailure(failure)
        } finally {
            UnitOverlayDrawContext.restoreDepth(entryDepth)
        }
    }
}

private fun callNativeDraw(operation: Operation<Unit>, graphics: Any, rect: RectF, paint: Paint) {
    try {
        operation.call(graphics, rect, paint)
    } catch (failure: Throwable) {
        throw originalDrawFailure(failure)
    }
}

private fun originalDrawFailure(failure: Throwable): Throwable {
    var original = failure
    while (original is java.lang.reflect.InvocationTargetException && original.targetException != null) {
        original = original.targetException
    }
    return original
}

private object UnitOverlayDrawContext {
    private val frames = ThreadLocal.withInitial { ArrayDeque<GameUnit>() }

    val depth: Int
        get() = frames.get().size

    fun push(unit: GameUnit) {
        frames.get().addLast(unit)
    }

    fun currentOrNull(): GameUnit? = frames.get().peekLast()

    /** Unwinds nested frames without throwing over a native draw exception. */
    fun restoreDepth(entryDepth: Int) {
        val stack = frames.get()
        while (stack.size > entryDepth) stack.removeLast()
        if (stack.isEmpty()) frames.remove()
    }
}

@AsmMixin("com/corrodinggames/rts/gameFramework/CommandControllerCommand")
object CommandEventMixin {
    @AsmInject(method = "allPathsReady()Z", target = InjectionPoint.TAIL, require = 1)
    fun publishPathsReady(callback: CallbackInfo, target: Any) {
        if (callback.getReturnValue<Boolean>() == true) {
            CommandEvents.PATHS_READY_NOTIFICATION.invoker.onEvent(target as CommandControllerCommand)
        }
    }
}
