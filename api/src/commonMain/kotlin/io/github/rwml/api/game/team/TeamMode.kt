/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.game.team

import com.corrodinggames.rts.game.Team
import io.github.rwml.api.game.GameRoom

abstract class TeamMode(val name: String) {
    open val displayName: String get() = name
    abstract fun onPlayerJoin(gameRoom: GameRoom, player: Team)
    abstract fun onInit(gameRoom: GameRoom)
    open fun autoTeamAssign(gameRoom: GameRoom, targetSpawnPoint: Int, player: Team): Int = player.allyTeam
}
