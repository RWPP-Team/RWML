/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.game

import com.corrodinggames.rts.game.units.UnitTypeInterface
import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import io.github.rwml.api.game.map.GameMap
import io.github.rwml.api.game.map.Mission
import io.github.rwml.api.game.map.MissionType
import io.github.rwml.api.game.map.Replay

/** The game service exposed by the host extension. */
interface Game {
    val gameRoom: GameRoom
    var state: GameState
    /** The latest answer supplied to the game's question callback, if one is pending. */
    val questionAnswer: String?

    fun startNewMissionGame(difficulty: Int, mission: Mission)
    suspend fun load(context: LoadingContext)
    fun hostStartWithPasswordAndMods(isPublic: Boolean, password: String?, useMods: Boolean)
    fun hostNewSinglePlayer(sandbox: Boolean)
    fun setUserName(name: String)
    suspend fun directJoinServer(address: String, uuid: String?, context: LoadingContext): Result<String>
    fun cancelJoinServer()
    fun onQuestionCallback(option: String)
    /** Returns and clears the pending callback answer so a native prompt consumes it only once. */
    fun takeQuestionAnswer(): String?
    fun setTeamUnitCapHostGame(cap: Int)
    fun getAllMissionTypes(): List<MissionType>
    fun getAllMissions(): List<Mission>
    fun getMissionsByType(type: MissionType): List<Mission>
    fun getAllMaps(flush: Boolean = false): List<GameMap>
    fun getAllMapsByMapType(mapType: GameSetupMapType): List<GameMap>
    fun getStartingUnitOptions(): List<Pair<Int, String>>
    fun getAllUnitTypes(): List<UnitTypeInterface>
    fun onBanUnits(units: List<UnitTypeInterface>)
    fun getAllReplays(): List<Replay>
    fun watchReplay(replay: Replay)
    fun setEffectLimitForAllEffects(limit: Int)
    fun isGameCouldContinue(): Boolean
    fun continueGame()
}
