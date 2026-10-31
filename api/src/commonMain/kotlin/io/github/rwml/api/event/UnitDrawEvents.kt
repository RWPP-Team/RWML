/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.event

import android.graphics.Paint
import android.graphics.RectF
import com.corrodinggames.rts.game.units.Unit
import com.corrodinggames.rts.gameFramework.render.GraphicsEngine

/** A per-unit callback at the native draw pass tail. */
data class UnitDraw(
    val unit: Unit,
    val graphics: GraphicsEngine,
    val deltaTime: Float,
)

/** Result of drawing one rectangle used by Unit's native overlay bars. */
enum class UnitOverlayBarDecision {
    /** Keep the native rectangle draw after all listeners have observed it. */
    PASS,

    /** A listener supplied the replacement draw, so skip this native rectangle. */
    HANDLED,
}

/** Synchronous customization point for each native health, transport, energy, or build bar rectangle. */
fun interface UnitOverlayBarListener {
    fun onOverlayBar(
        unit: Unit,
        graphics: GraphicsEngine,
        rect: RectF,
        paint: Paint,
    ): UnitOverlayBarDecision
}

/** Unit drawing callbacks at the engine's native unit render boundaries. */
object UnitDrawEvents {
    /** Runs when the render loop reaches Unit.runOnDestroyHooks, after drawOverlay. */
    val DRAW = nativeNotification<UnitDraw>("unit.draw")

    /** Runs before each native drawRect in Unit.drawOverlay; defaults to PASS with no listeners. */
    val OVERLAY_BAR = SyncEvent.decision<UnitOverlayBarListener>("unit.overlay_bar") { listeners, invokeListener ->
        UnitOverlayBarListener { unit, graphics, rect, paint ->
            var decision = UnitOverlayBarDecision.PASS
            for (listener in listeners) {
                invokeListener(listener) {
                    if (listener.onOverlayBar(unit, graphics, rect, paint) == UnitOverlayBarDecision.HANDLED) {
                        decision = UnitOverlayBarDecision.HANDLED
                    }
                }
                if (decision == UnitOverlayBarDecision.HANDLED) break
            }
            decision
        }
    }
}
