/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.game

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.units.UnitTypeInterface
import com.corrodinggames.rts.gameFramework.network.Connection
import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import io.github.rwml.api.command.CommandSender
import io.github.rwml.api.command.CommandVisibility
import io.github.rwml.api.commands
import io.github.rwml.api.game.map.GameMap
import io.github.rwml.api.map.XMLMap
import io.github.rwml.api.game.team.TeamMode

interface GameRoom {
    var maxPlayerCount: Int
    val isHost: Boolean
    val isHostServer: Boolean
    val localPlayer: Team
    var sharedControl: Boolean
    val randomSeed: Int
    val mapType: GameSetupMapType
    var selectedMap: GameMap
    var displayMapName: String
    var startingCredits: Int
    var startingUnits: Int
    var fogMode: Int
    var revealedMap: Boolean
    var aiDifficulty: Int
    var incomeMultiplier: Float
    var noNukes: Boolean
    var allowSpectators: Boolean
    var lockedRoom: Boolean
    var teamLock: Boolean

    @Deprecated("Use game state.")
    val mods: Array<String>
    var teamMode: TeamMode?
    var gameSpeed: Float
    var gameMapTransformer: ((XMLMap) -> Unit)?
    var isRWPPRoom: Boolean
    var option: RoomOption
    val isConnecting: Boolean
    fun getPlayers(): List<Team>
    fun getAllPlayers(): Array<Team?>
    fun getPlayerByClient(client: Connection): Team? = getPlayers().firstOrNull {
        GameEngineAccess.connectionFor(it) === client
    }
    suspend fun roomDetails(): String
    fun sendChatMessage(message: String)
    fun sendSystemMessage(message: String)
    fun sendQuickGameCommand(command: String)
    fun pauseOrResumeGame(pause: Boolean)
    fun sendMessageToPlayer(player: Team?, title: String?, message: String, color: Int = -1)
    fun sendSurrender(player: Team)
    fun spawnUnit(player: Team, unitType: UnitTypeInterface, x: Float, y: Float, size: Int = 0)
    fun syncAllPlayer()
    fun addAI(count: Int = 1)
    fun applyRoomConfig(
        maxPlayerCount: Int,
        sharedControl: Boolean,
        startingCredits: Int,
        startingUnits: Int,
        fogMode: Int,
        aiDifficulty: Int,
        incomeMultiplier: Float,
        noNukes: Boolean,
        allowSpectators: Boolean,
        teamLock: Boolean,
        teamMode: TeamMode?,
    )
    fun remainingPlayersCount(): Int
    fun kickPlayer(player: Team)
    fun disconnect(reason: String = "excited")
    fun updateUI()
    fun startGame()
}

/** The network engine owns the team-to-connection lookup. */
private object GameEngineAccess {
    fun connectionFor(team: Team): Connection? =
        com.corrodinggames.rts.gameFramework.GameEngine.getInstance().networkEngine.getConnectionForTeam(team)
}

const val commandPrefix: String = "/"

fun GameRoom.sendChatMessageOrCommand(message: String) {
    if (isHost && message.startsWith(commandPrefix)) {
        val sender = object : CommandSender {
            override fun reply(message: String) = sendMessageToPlayer(localPlayer, "RWPP", message)
            override fun hasPermission(permission: String) = true
            override fun hasVisibility(visibility: String) = visibility == CommandVisibility.CLIENT
        }
        commands.execute(message, sender)
    } else {
        sendChatMessage(message)
    }
}
