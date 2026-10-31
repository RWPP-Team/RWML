/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.game.impl

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.units.UnitTypeInterface
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.Connection
import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import com.corrodinggames.rts.gameFramework.network.TeamLayout
import io.github.rwml.api.State
import io.github.rwml.api.appKoin
import io.github.rwml.api.event.events.DisconnectEvent
import io.github.rwml.api.coroutines.GameScope
import io.github.rwml.api.event.GlobalEventChannel
import io.github.rwml.api.event.GameEventPublisher
import io.github.rwml.api.game.Game
import io.github.rwml.api.game.GameRoom
import io.github.rwml.api.game.GameState
import io.github.rwml.api.game.RoomOption
import io.github.rwml.api.game.bannedUnitList
import io.github.rwml.api.game.map.GameMap
import io.github.rwml.api.game.map.NetworkMap
import io.github.rwml.api.game.map.sameGameMapName
import io.github.rwml.api.game.map.sameGameMapPath
import io.github.rwml.api.game.roomGameSpeed
import io.github.rwml.api.game.roomMods
import io.github.rwml.api.game.team.TeamMode
import io.github.rwml.api.map.XMLMap
import io.github.rwml.api.net.packets.GamePacket
import io.github.rwml.api.net.sendPacketToClient
import io.github.rwml.api.util.Reflect
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single
import java.io.File
import java.util.Locale

/**
 * Shared room behavior over the engine's network API.
 *
 * Server/proxy setup, map selection, room messages, and network commands use the native engine API.
 */
@Single(binds = [GameRoom::class])
class GameRoomImpl : GameRoom {
    private val engine get() = GameEngine.getInstance()
    private val network get() = engine.networkEngine
    private val setup get() = network.gameSetup
    private val game get() = appKoin.get<Game>()

    private fun updateSetup(change: (com.corrodinggames.rts.gameFramework.network.GameSetup) -> Unit) {
        when {
            network.isServer -> {
                val current = network.gameSetup
                change(current)
                network.applyChangedSetup(current)
            }
            network.proxyController -> {
                val pending = network.getChangeableSetup()
                change(pending)
                network.applyChangedSetup(pending)
            }
        }
    }

    override var maxPlayerCount: Int
        get() = Team.maxTeamId
        set(value) {
            if (isHost && maxPlayerCount != value) Team.setMaxTeamId(value, true)
        }

    override val isHost: Boolean get() = network.isServer
    override val isHostServer: Boolean get() = network.proxyController
    override val localPlayer: Team get() = network.localTeam ?: engine.playerTeam ?: Team.blankPlayer

    override var sharedControl: Boolean
        get() = setup.sharedControl
        set(value) = updateSetup { it.sharedControl = value }

    override val randomSeed: Int get() = setup.randomSeed
    override val mapType: GameSetupMapType get() = setup.mapType

    override var selectedMap: GameMap
        get() {
            val maps = game.getAllMaps()
            val currentPath = network.currentGameMapPath
            val configuredName = setup.mapName
            return maps.firstOrNull {
                it.mapType == setup.mapType && currentPath != null &&
                    sameGameMapPath(it.enginePath, currentPath)
            } ?: maps.firstOrNull {
                it.mapType == setup.mapType && configuredName != null &&
                    (sameGameMapName(it.mapName, configuredName) ||
                        sameGameMapPath(it.enginePath, configuredName))
            } ?: NetworkMap(configuredName ?: currentPath.orEmpty(), setup.mapType)
        }
        set(value) {
            if (!isHost && !isHostServer) return
            updateSetup {
                it.mapType = value.mapType
                it.mapName = value.mapName + value.getMapSuffix()
            }
            network.currentGameMapPath = value.enginePath
            updateUI()
        }

    override var displayMapName: String
        get() = formatMapName(setup.mapName)
        set(value) = updateSetup { it.mapName = value }

    override var startingCredits: Int
        get() = setup.startingCredits
        set(value) = updateSetup { it.startingCredits = value }

    override var startingUnits: Int
        get() = setup.startingUnits
        set(value) = updateSetup { it.startingUnits = value }

    override var fogMode: Int
        get() = setup.fogMode
        set(value) = updateSetup { it.fogMode = value }

    override var revealedMap: Boolean
        get() = setup.revealedMap
        set(value) = updateSetup { it.revealedMap = value }

    override var aiDifficulty: Int
        get() = setup.aiDifficulty
        set(value) = updateSetup { it.aiDifficulty = value }

    override var incomeMultiplier: Float
        get() = setup.incomeMultiplier
        set(value) = updateSetup { it.incomeMultiplier = value }

    override var noNukes: Boolean
        get() = setup.noNukes
        set(value) = updateSetup { it.noNukes = value }

    override var allowSpectators: Boolean
        get() = setup.allowSpectators
        set(value) = updateSetup { it.allowSpectators = value }

    override var lockedRoom: Boolean
        get() = setup.lockedRoom
        set(value) {
            updateSetup { it.lockedRoom = value }
            if (isHost && value) sendSystemMessage("Room has been locked. Players can no longer join.")
        }

    override var teamLock: Boolean
        get() = setup.teamsLocked
        set(value) = updateSetup { it.teamsLocked = value }

    @Deprecated("Use game state.")
    override val mods: Array<String> get() = roomMods
    override var teamMode: TeamMode? = null
    override var gameSpeed: Float
        get() = roomGameSpeed
        set(value) {
            roomGameSpeed = value
            engine.gameSpeed = value
        }

    override var gameMapTransformer: ((XMLMap) -> Unit)? = null
    override var isRWPPRoom: Boolean = false
    override var option: RoomOption = RoomOption()
    override val isConnecting: Boolean
        get() = network.networked && !network.isServer && network.localTeam == null

    private var generatedMapIndex = 0
    private var previousMapPath: String? = null

    override fun getPlayers(): List<Team> =
        Team.getTeamList().filterNotNull().filter { it.isPlayerTeam }

    override fun getAllPlayers(): Array<Team?> =
        Team.getTeamList().map { it?.takeIf(Team::isPlayerTeam) }.toTypedArray()

    override suspend fun roomDetails(): String = network.getGameInfoText()
    override fun sendChatMessage(message: String) = network.sendChatMessage(message)
    override fun sendSystemMessage(message: String) = network.sendSystemMessage(message)
    override fun sendQuickGameCommand(command: String) = network.sendChatCommand(command)

    override fun pauseOrResumeGame(pause: Boolean) {
        if (game.state.multiplayer) {
            gameSpeed = if (pause) 0f else 1f
        } else {
            engine.gamePaused = pause
            engine.paused = pause
            network.gamePaused = pause
        }
    }

    override fun sendMessageToPlayer(player: Team?, title: String?, message: String, color: Int) {
        if (player != null && player != localPlayer) {
            network.getConnectionForTeam(player)?.sendPacketToClient(GamePacket.getChatPacket(title, message, color))
        } else {
            Reflect.call(
                network,
                "receiveChatMessage",
                listOf(Connection::class, Int::class, String::class, String::class),
                listOf(null, color, title, message),
            )
        }
    }

    override fun sendSurrender(player: Team) {
        if (!isHost) return
        Reflect.reifiedSet<Team>(player, "surrenderVoted", true)
        val command = engine.commandController.createCommand().apply {
            team = player
            hasSystemAction = true
            systemActionId = 100
        }
        network.queueCommand(command)
    }

    override fun spawnUnit(player: Team, unitType: UnitTypeInterface, x: Float, y: Float, size: Int) {
        if (!isHost) return
        val command = engine.commandController.createCommand().apply {
            team = player
            hasSystemAction = true
            systemActionId = 5
            setBuildWaypoint(x, y, unitType, size)
        }
        network.queueCommand(command)
    }

    override fun syncAllPlayer() {
        if (isHost) GameScope.launch { network.sendResyncSave(false, false, true) }
    }

    override fun addAI(count: Int) {
        repeat(count.coerceAtLeast(0)) {
            if (isHost) {
                if (Team.findFreeTeamId() != -1) network.addAIToGame()
            } else if (isHostServer) {
                sendQuickGameCommand("-addai")
            }
        }
        if (isHost) updateUI()
    }

    override fun applyRoomConfig(
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
    ) {
        if (isHost || isHostServer) {
            this.maxPlayerCount = maxPlayerCount
            updateSetup {
                it.sharedControl = sharedControl
                it.startingCredits = startingCredits
                it.startingUnits = startingUnits
                it.fogMode = fogMode
                it.aiDifficulty = aiDifficulty
                it.incomeMultiplier = incomeMultiplier
                it.noNukes = noNukes
                it.allowSpectators = allowSpectators
                it.teamsLocked = teamLock
            }
        }
        if (this.teamMode != teamMode) {
            this.teamMode = teamMode
            when (teamMode?.name) {
                "2t" -> network.overrideTeamLayout(TeamLayout.layout_2sides)
                "3t" -> network.overrideTeamLayout(TeamLayout.layout_3sides)
                "FFA" -> network.overrideTeamLayout(TeamLayout.layout_ffa)
                "spectators" -> network.overrideTeamLayout(TeamLayout.layout_spectators)
                null -> Unit
                else -> teamMode.onInit(this)
            }
        }
        updateUI()
    }

    override fun remainingPlayersCount(): Int = Team.countRemainingPlayers()

    override fun kickPlayer(player: Team) {
        if (isHost) network.kickTeamAndAttachedPlayer(player)
    }

    internal fun resetRuntimeState() {
        isRWPPRoom = false
        option = RoomOption()
        bannedUnitList = emptyList()
        roomMods = emptyArray()
        teamMode = null
        roomGameSpeed = 1f
        previousMapPath = null
    }

    override fun disconnect(reason: String) {
        val wasConnecting = isConnecting
        network.disconnect(reason)
        if (wasConnecting) {
            game.state = GameState()
            GameEventPublisher.stateChanged(State.Menu)
        }
        engine.interfaceEngine.pauseMenuOpen = false
        GamePlatform.closeGameScreen()
    }

    override fun updateUI() {
        if (!network.isServer || GameEngine.isDedicatedServerPlatform) return
        network.updateAIDifficulty()
        network.markPlayerListDirty()
        network.sendServerInfoToAll()
        network.updateTeamStatuses()
        network.networkCallbacks?.onBattleroomUIUpdate()
    }

    override fun startGame() {
        if (!isHost) {
            if (isHostServer) network.sendChatCommand("-start")
            return
        }
        var selectedPath = selectedMap.enginePath
        gameMapTransformer?.let { transform ->
            val map = XMLMap(selectedMap.openInputStream())
            transform(map)
            val path = "${GamePlatform.generatedMapDirectory}/generated_${generatedMapIndex++}.tmx"
            val file = map.saveToFile(path)
            previousMapPath = previousMapPath ?: network.currentGameMapPath
            network.currentGameMapPath = path
            selectedPath = path
            updateSetup {
                it.mapType = GameSetupMapType.customMap
                it.mapName = path
            }
            GlobalEventChannel.filter(DisconnectEvent::class).subscribeOnce { file.delete() }
            updateUI()
        }
        // Native Multiplayer.multiplayerStart prepares mapName before sending/loading the map.
        // currentGameMapPath is only a lobby cache; the engine consumes mapName for gameplay.
        network.currentGameMapPath = selectedPath
        network.mapName = if (setup.mapType == GameSetupMapType.savedGame) null else selectedPath
        network.startGame()
    }

    private fun formatMapName(raw: String?): String {
        val name = File(raw.orEmpty()).name.removeSuffix(".tmx").removeSuffix(".rwsave")
            .replace(Regex("^\\[.*?]"), "")
            .replace('_', ' ')
        return name.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }
    }

}
