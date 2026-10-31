/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.game.impl

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.units.UnitType
import com.corrodinggames.rts.game.units.UnitTypeInterface
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import io.github.rwml.api.State
import io.github.rwml.api.coroutines.GameScope
import io.github.rwml.api.event.GameEventPublisher
import io.github.rwml.api.game.Game
import io.github.rwml.api.game.GameRoom
import io.github.rwml.api.game.GameState
import io.github.rwml.api.game.LoadingContext
import io.github.rwml.api.game.bannedUnitList
import io.github.rwml.api.game.map.GameMap
import io.github.rwml.api.game.map.Mission
import io.github.rwml.api.game.map.MissionType
import io.github.rwml.api.game.map.Replay
import io.github.rwml.api.game.roomMods
import io.github.rwml.api.logging.logger
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single

/** The shared game service. Native file and screen operations are implemented by the API source sets. */
@Single(binds = [Game::class])
class GameImpl(
    final override val gameRoom: GameRoom,
) : Game {
    final override var state: GameState = GameState()

    private var missionsCache: List<Mission>? = null
    private var mapsCache: List<GameMap>? = null
    private var mapsByType: Map<GameSetupMapType, List<GameMap>> = emptyMap()
    private var replaysCache: List<Replay>? = null

    /** The latest answer provided by the game's native question dialog. */
    final override var questionAnswer: String? = null
        private set

    override suspend fun load(context: LoadingContext) {
        context.message("Preparing game interface…")
        GamePlatform.initializeMap()
    }

    override fun startNewMissionGame(difficulty: Int, mission: Mission) {
        state.multiplayer = false
        state.replay = false
        state.sandbox = false
        GameEngine.getInstance().settings.aiDifficulty = difficulty
        val path = "maps/${mission.type.pathName()}/${mission.mapName}${mission.getMapSuffix()}"
        GameScope.launch {
            runCatching {
                GameEngine.getInstance().networkEngine.disconnect("starting singleplayer")
                GamePlatform.loadMission(path, sandbox = false)
                state.state = State.Playing
                GamePlatform.openGameScreen()
            }.onFailure { logger().error("Could not start mission ${mission.name}", it) }
        }
    }

    override fun hostStartWithPasswordAndMods(isPublic: Boolean, password: String?, useMods: Boolean) {
        val engine = GameEngine.getInstance()
        val network = engine.networkEngine
        network.disconnect("starting new")
        roomMods = emptyArray()
        GamePlatform.initializeMap(force = true)
        network.serverPassword = password
        network.modsEnabled = useMods
        network.serverVisible = isPublic
        gameRoom.isRWPPRoom = true

        GameScope.launch {
            if (!network.startServer(false)) return@launch
            state.state = State.Menu
            GameEventPublisher.refreshUi()
        }
    }

    override fun hostNewSinglePlayer(sandbox: Boolean) {
        val engine = GameEngine.getInstance()
        engine.settings.aiDifficulty = 1
        engine.networkEngine.disconnect("starting singleplayer")
        Team.clearAllTeams()
        engine.networkEngine.playerName = "You"
        engine.networkEngine.modsEnabled = true
        GamePlatform.initializeMap(force = true)

        GameScope.launch {
            runCatching {
                GamePlatform.startSinglePlayer(DEFAULT_SKIRMISH_MAP, sandbox)
                state.multiplayer = false
                state.sandbox = sandbox
                state.replay = false
                state.gameOver = false
                state.defeatedPlayerSet.clear()
                state.state = State.Playing
                GamePlatform.openGameScreen()
                GameEventPublisher.refreshUi()
            }.onFailure { logger().error("Could not start a new single-player game", it) }
        }
    }

    override fun setUserName(name: String) {
        GameEngine.getInstance().networkEngine.sanitiseAndStorePlayerName(name)
    }

    override suspend fun directJoinServer(
        address: String,
        uuid: String?,
        context: LoadingContext,
    ): Result<String> {
        GamePlatform.initializeMap()
        val result = GamePlatform.directJoinServer(address, uuid, context)
        if (result.isSuccess) {
            gameRoom.isRWPPRoom = false
            GameEventPublisher.playerJoin(gameRoom.localPlayer)
        }
        return result
    }

    override fun cancelJoinServer() = GamePlatform.cancelJoinServer()

    override fun onQuestionCallback(option: String) {
        questionAnswer = option
    }

    override fun takeQuestionAnswer(): String? = questionAnswer.also { questionAnswer = null }

    override fun setTeamUnitCapHostGame(cap: Int) {
        val engine = GameEngine.getInstance()
        engine.networkEngine.teamUnitCap = cap
        engine.networkEngine.teamUnitCapMax = cap
        engine.unitsMaxCap = cap
    }

    override fun getAllMissionTypes(): List<MissionType> =
        listOf(MissionType.Normal, MissionType.Challenge, MissionType.Survival)

    override fun getAllMissions(): List<Mission> = missionsCache ?: GamePlatform.getAllMissions(getAllMissionTypes())
        .also { missionsCache = it }

    override fun getMissionsByType(type: MissionType): List<Mission> = getAllMissions().filter { it.type == type }

    override fun getAllMaps(flush: Boolean): List<GameMap> {
        if (flush) {
            mapsCache = null
            mapsByType = emptyMap()
        }
        mapsCache?.let { return it }
        val maps = GamePlatform.getAllMaps()
        mapsByType = maps
        return maps.values.flatten().also { mapsCache = it }
    }

    override fun getAllMapsByMapType(mapType: GameSetupMapType): List<GameMap> {
        if (mapsCache == null) getAllMaps()
        return mapsByType[mapType].orEmpty()
    }

    override fun getStartingUnitOptions(): List<Pair<Int, String>> {
        val network = GameEngine.getInstance().networkEngine
        return network.getStartingUnitOptions().mapNotNull { option ->
            (option as? Int)?.let { it to network.getStartingUnitsName(it) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun getAllUnitTypes(): List<UnitTypeInterface> = UnitType.allTypes as List<UnitTypeInterface>

    override fun onBanUnits(units: List<UnitTypeInterface>) {
        bannedUnitList = units.map(UnitTypeInterface::getInternalName)
        if (units.isNotEmpty()) {
            gameRoom.sendSystemMessage(
                "Host has banned these units (房间已经ban以下单位): ${units.joinToString(", ") { it.getDisplayName() }}",
            )
        }
    }

    override fun getAllReplays(): List<Replay> = replaysCache ?: GamePlatform.getAllReplays().also { replaysCache = it }

    override fun watchReplay(replay: Replay) {
        GameScope.launch {
            val loaded = runCatching {
                GameEngine.getInstance().replayEngine.loadReplay(replay.name)
            }.getOrDefault(false)
            if (!loaded) {
                logger().warn("Could not load replay ${replay.name}")
                return@launch
            }
            GamePlatform.openGameScreen()
        }
    }

    override fun setEffectLimitForAllEffects(limit: Int) {
        GameEngine.getInstance().effectEngine.apply {
            limitVerylow = limit
            limitLow = limit
            limitHigh = limit
            limitVeryhigh = limit
        }
    }

    override fun isGameCouldContinue(): Boolean {
        val engine = GameEngine.getInstance()
        val path = engine.currentMapPath
        return engine.gameLoopActive && !engine.gameEnded && (path == null || !path.startsWith("maps/menu_background/"))
    }

    override fun continueGame() {
        if (!isGameCouldContinue()) return
        NativeGameLifecycle.onResume()
        state.state = State.Playing
        GamePlatform.continueGame()
    }

    private companion object {
        const val DEFAULT_SKIRMISH_MAP = "maps/skirmish/[z;p10]Crossing Large (10p).tmx"
    }
}
