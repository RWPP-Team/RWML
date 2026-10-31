/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.game

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwml.api.State
import io.github.rwml.api.event.GameEventPublisher
import java.util.concurrent.CopyOnWriteArraySet

class GameState {
    private var sandboxFallback: Boolean = false
    var sandbox: Boolean
        get() = nativeModeOrNull { engine -> engine.networkEngine.sandboxMode || engine.debugEditorMode }
            ?: sandboxFallback
        set(value) {
            sandboxFallback = value
        }

    private var multiplayerFallback: Boolean = false
    var multiplayer: Boolean
        get() = nativeModeOrNull { engine ->
            engine.networkEngine.networked && !engine.networkEngine.singlePlayerServer
        } ?: multiplayerFallback
        set(value) {
            multiplayerFallback = value
        }

    /**
     * ReplayEngine.isPlayingReplay() can remain true after a replay load fails, so this mode is
     * driven by the successful native lifecycle transition rather than the engine's raw flag.
     */
    var replay: Boolean = false
    var gameOver: Boolean = false
    var mods: List<String> = listOf()
    val defeatedPlayerSet = CopyOnWriteArraySet<Team>()
    var state: State = State.Menu
        set(value) {
            if (field == value) return
            field = value
            GameEventPublisher.stateChanged(value)
        }

    fun isPlaying(): Boolean = state == State.Playing
    fun isMenu(): Boolean = state == State.Menu
    fun isPaused(): Boolean = state == State.Paused

    private inline fun nativeModeOrNull(mode: (GameEngine) -> Boolean): Boolean? = runCatching {
        mode(GameEngine.getInstance())
    }.getOrNull()
}
