/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.event

import com.corrodinggames.rts.game.RWGameEngine

/** Synchronous notifications around one completed native simulation update. */
object SimulationEvents {
    fun interface BeforeTickListener {
        /** Runs before native command processing and state updates for [tick]. */
        fun onTick(engine: RWGameEngine, tick: Int)
    }

    fun interface AfterTickListener {
        /** Runs after native state updates completed successfully for [tick]. */
        fun onTick(engine: RWGameEngine, tick: Int)
    }

    /** Actual native tick at entry, used by queued commands and replay before the native increment. */
    val BEFORE_TICK = SyncEvent.notification<BeforeTickListener>("simulation.before_tick") { listeners, invoke ->
        BeforeTickListener { engine, tick ->
            for (listener in listeners) invoke(listener) { listener.onTick(engine, tick) }
        }
    }

    /** `tick` is the native engine's completed `simulationTick`. */
    val AFTER_TICK = SyncEvent.notification<AfterTickListener>("simulation.after_tick") { listeners, invoke ->
        AfterTickListener { engine, tick ->
            for (listener in listeners) invoke(listener) { listener.onTick(engine, tick) }
        }
    }
}
