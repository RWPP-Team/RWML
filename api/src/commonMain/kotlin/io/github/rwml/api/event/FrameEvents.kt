/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.event

import com.corrodinggames.rts.game.RWGameEngine

/** Synchronous notifications for one native rendered frame. */
object FrameEvents {
    fun interface BeginListener {
        fun onFrame(engine: RWGameEngine, deltaTime: Float, deltaMillis: Int)
    }

    fun interface EndListener {
        fun onFrame(engine: RWGameEngine, deltaTime: Float, deltaMillis: Int)
    }

    /** Runs on the native frame thread after the API dispatcher has been drained. */
    val BEGIN = SyncEvent.notification<BeginListener>("frame.begin") { listeners, invoke ->
        BeginListener { engine, deltaTime, deltaMillis ->
            for (listener in listeners) {
                invoke(listener) { listener.onFrame(engine, deltaTime, deltaMillis) }
            }
        }
    }

    /** Runs on normal return from the native frame update; it cannot cancel the completed frame. */
    val END = SyncEvent.notification<EndListener>("frame.end") { listeners, invoke ->
        EndListener { engine, deltaTime, deltaMillis ->
            for (listener in listeners) {
                invoke(listener) { listener.onFrame(engine, deltaTime, deltaMillis) }
            }
        }
    }
}
