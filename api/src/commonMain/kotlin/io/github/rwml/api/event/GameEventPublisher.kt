/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.event

import com.corrodinggames.rts.game.Team
import io.github.rwml.api.State
import io.github.rwml.api.event.events.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Central event creation and broadcast entry points used by the game adapters. */
object GameEventPublisher {
    fun gameLoaded() = GameLoadedEvent().broadcastIn()
    fun startGame() = StartGameEvent().broadcastIn()
    fun refreshUi(delay: Duration = 0.milliseconds) = RefreshUIEvent().broadcastIn(delay = delay)
    fun hostGame() = HostGameEvent().broadcastIn()
    fun hostSinglePlayerGame() = HostSinglePlayerGameEvent().broadcastIn()
    fun joinGame(address: String) = JoinGameEvent(address).broadcastIn()
    fun playerJoin(player: Team): PlayerJoinEvent {
        RoomEvents.PLAYER_ADDED.invoker.onEvent(player)
        return PlayerJoinEvent(player).broadcastIn()
    }
    fun playerLeave(player: Team, reason: String) = PlayerLeaveEvent(player, reason).broadcastIn()
    fun playerDefeated(player: Team) = PlayerDefeatedEvent(player).broadcastIn()
    fun quitGame() = QuitGameEvent().broadcastIn()
    fun returnMainMenu() = ReturnMainMenuEvent().broadcastIn()
    fun gameOver(gameTime: Int, winTeam: Int) = GameOverEvent(gameTime, winTeam).broadcastIn()
    fun mapChanged(mapName: String) = MapChangedEvent(mapName).broadcastIn()
    fun disconnect(reason: String) = DisconnectEvent(reason).broadcastIn()
    fun triggerActivated(id: String) = TriggerActivateEvent(id).broadcastIn()
    fun localPlayerUnitDamaged() = LocalPlayerUnitDamagedEvent().broadcastIn()
    fun localPlayerBaseDamaged() = LocalPlayerBaseDamagedEvent().broadcastIn()
    fun localPlayerUnitCreated() = LocalPlayerUnitCreatedEvent().broadcastIn()
    fun localPlayerBuildingConstructed() = LocalPlayerBuildingConstructedEvent().broadcastIn()
    fun localPlayerUpgradeCompleted() = LocalPlayerUpgradedCompletedEvent().broadcastIn()
    fun reloadMod() = ReloadModEvent().broadcastIn()
    fun reloadModFinished() = ReloadModFinishedEvent().broadcastIn()
    fun resumeGame(): ResumeGameEvent {
        EngineEvents.RESUMED.invoker.onEvent(com.corrodinggames.rts.gameFramework.GameEngine.getInstance())
        return ResumeGameEvent().broadcastIn()
    }
    fun stateChanged(state: State) = StateChangedEvent(state).broadcastIn()

    suspend fun modCheck(requiredMods: List<String>) = ModCheckEvent(requiredMods).broadcast()

    fun systemMessage(
        message: String,
        onFinished: (SystemMessageEvent) -> Unit = {},
    ) = SystemMessageEvent(message).broadcastIn(onFinished = onFinished)

    fun chatMessage(
        sender: String,
        message: String,
        player: Team,
        color: Int,
        onFinished: (ChatMessageEvent) -> Unit = {},
    ) = ChatMessageEvent(sender, message, player, color).broadcastIn(onFinished = onFinished)
}
