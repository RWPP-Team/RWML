/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.event.events

import io.github.rwml.api.event.AbstractEvent
import com.corrodinggames.rts.game.Team
import io.github.rwml.api.State

sealed class GameEvent : AbstractEvent()

class RefreshUIEvent : GameEvent()

/**
 * Event that is fired when the game is loaded.
 */
class GameLoadedEvent : GameEvent()

/** Event emitted when the application is leaving the game. */
class QuitGameEvent : GameEvent()

/** Event emitted when gameplay returns to the main menu. */
class ReturnMainMenuEvent : GameEvent()

/**
 * Event that is fired when the game is started.
 */
class StartGameEvent : GameEvent()

/**
 * Event that is fired when the game is resumed.
 */
class ResumeGameEvent : GameEvent()

/**
 * Event that is fired when hosting a multiplayer game.
 */
class HostGameEvent : GameEvent()

/**
 * Event that is fired when the local player starts hosting a singleplayer game.
 */
class HostSinglePlayerGameEvent : GameEvent()

/**
 * Event emitted after the local player successfully connects to a game.
 */
class JoinGameEvent(val address: String) : GameEvent()

/**
 * Event that is fired when the game is over.
 */
class GameOverEvent(val gameTime: Int, val winTeam: Int) : GameEvent()

/**
 * Event that is fired when the map changes.
 */
class MapChangedEvent(val mapName: String) : GameEvent()

/**
 * Event that is fired when the local player disconnects from the game.
 */
class DisconnectEvent(val reason: String) : GameEvent()

/**
 * Event that is fired when a player sends a chat message.
 */
@Deprecated("Compatibility notification; use DecisionEvents.CHAT to filter native messages")
class ChatMessageEvent(var sender: String, var message: String, val player: Team, var color: Int) : GameEvent()

/**
 * Event that is fired when the room sends a system message.
 */
class SystemMessageEvent(val message: String) : GameEvent()

/**
 * Event that is fired when the game compares the local player's mod list with the required unit data from the server.
 *
 * Native interception is provided by DecisionEvents.MOD_CHECK. This legacy type is only emitted
 * by callers explicitly broadcasting it and does not change the native mismatch outcome.
 */
@Deprecated("Use DecisionEvents.MOD_CHECK; the native hook does not emit this legacy event")
class ModCheckEvent(val requiredMods: List<String>) : GameEvent()

/**
 * Event that is fired when the game reloads the mod list.
 */
class ReloadModEvent : GameEvent()

/**
 * Event that is fired when the game finishes reloading the mod list.
 */
class ReloadModFinishedEvent : GameEvent()

class StateChangedEvent(val state: State) : GameEvent()

