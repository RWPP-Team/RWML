/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.game.impl

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.mod.Mod
import io.github.rwml.api.Initialization
import io.github.rwml.api.appKoin
import io.github.rwml.api.ApiRuntime
import io.github.rwml.api.coroutines.GameScope
import io.github.rwml.api.event.*
import io.github.rwml.api.game.Game
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single

/** API-owned listeners for shared game lifecycle events. */
@Single(binds = [Initialization::class])
class GameEventRegistration : Initialization {
    override fun init() {
        val owner = EventOwner(ApiRuntime.extensionContext)
        RoomEvents.PLAYER_ADDED.register(owner, NativeCallback { team ->
            GameScope.launch {
                val room = appKoin.get<Game>().gameRoom
                room.teamMode?.onPlayerJoin(room, team)
            }
        })

        ModEvents.AFTER.register(owner, NativeCallback {
            GameScope.launch { appKoin.get<Game>().getAllMaps(true) }
        })

        EngineEvents.RESUMED.register(owner, NativeCallback {
            GameScope.launch {
            val game = appKoin.get<Game>()
            NativeGameLifecycle.onResume()
            game.state.mods = GameEngine.getInstance().modManager.refreshModList()
                .filterIsInstance<Mod>()
                .filter { it.isEnabled }
                .mapNotNull { it.getTitle() ?: it.name }
            }
        })
    }
}
