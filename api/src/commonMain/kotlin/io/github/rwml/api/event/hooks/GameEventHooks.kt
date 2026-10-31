/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.event.hooks

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.gameFramework.CommonUtils
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.Connection
import io.github.rwml.api.ApiRuntime
import io.github.rwml.api.appKoin
import io.github.rwml.api.coroutines.GameMainDispatcher
import io.github.rwml.api.event.GameEventPublisher
import io.github.rwml.api.event.*
import io.github.rwml.api.game.Game
import io.github.rwml.api.logging.logger
import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.Shift
import java.lang.reflect.Field
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

private data class TeamDefeatSnapshot(val team: Team, val defeated: Boolean, val wipedOut: Boolean)

@AsmMixin("com/corrodinggames/rts/game/Team")
object PlayerEventMixin {
    private val defeatFrames = ThreadLocal.withInitial { ArrayDeque<TeamDefeatSnapshot>() }

    @AsmInject(method = "updateDefeatCheck(F)V", target = InjectionPoint.HEAD, require = 1)
    fun captureDefeatState(target: Any, @Suppress("UNUSED_PARAMETER") deltaTime: Float) {
        val team = target as Team
        defeatFrames.get().addLast(TeamDefeatSnapshot(team, team.defeated, team.wipedOut))
    }

    @AsmInject(method = "updateDefeatCheck(F)V", target = InjectionPoint.TAIL, require = 1)
    fun publishDefeatAndGameOver(target: Any, @Suppress("UNUSED_PARAMETER") deltaTime: Float) {
        val game = appKoin.get<Game>()
        val player = target as Team
        val frames = defeatFrames.get()
        val before = frames.pollLast() ?: return
        if (frames.isEmpty()) defeatFrames.remove()
        if (before.team !== player || !game.state.isPlaying()) return

        val newlyDefeated = !before.defeated && player.defeated
        val newlyWipedOut = !before.wipedOut && player.wipedOut
        if (!newlyDefeated && !newlyWipedOut) return

        if (newlyWipedOut) TeamEvents.WIPED_OUT.invoker.onEvent(player)
        if (newlyDefeated) TeamEvents.DEFEATED.invoker.onEvent(player)
        if (!game.state.defeatedPlayerSet.add(player)) return
        GameEventPublisher.playerDefeated(player)
        if (game.state.gameOver) return

        var lastTeam: Int? = null
        for (slot in 0 until game.gameRoom.maxPlayerCount) {
            val candidate = Team.getTeam(slot) ?: continue
            if (candidate.allyTeam == -3 || candidate.wipedOut || candidate.defeated || candidate.eliminated) continue
            if (lastTeam != null && lastTeam != candidate.allyTeam) return
            lastTeam = candidate.allyTeam
        }

        if (lastTeam != null) {
            logger().info("Game Over! Team $lastTeam wins!")
            GameEventPublisher.gameOver(GameEngine.getInstance().gameTimeMillis, lastTeam)
            game.state.gameOver = true
        }
    }
}

@AsmMixin("com/corrodinggames/rts/gameFramework/network/Connection")
object ClientEventMixin {
    private val closedConnections = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Connection, Boolean>())
    @AsmInject(
        method = "handleRemoteDisconnect(ZZLjava/lang/String;)V",
        target = InjectionPoint.TAIL,
        require = 1,
    )
    fun publishPlayerLeave(
        connection: Any,
        @Suppress("UNUSED_PARAMETER") graceful: Boolean,
        @Suppress("UNUSED_PARAMETER") timeout: Boolean,
        reason: String?,
    ) {
        val native = connection as Connection
        if (closedConnections.put(native, true) != null) return
        val player = native.player as? Team
        ConnectionEvents.PLAYER_DISCONNECTED.invoker.onEvent(PlayerDisconnected(native, player, reason))
        if (player != null) GameEventPublisher.playerLeave(player, CommonUtils.escapeXml(reason.orEmpty()))
    }
}

@AsmMixin("com/corrodinggames/rts/game/units/custom/CustomUnitMetadata")
object UnitMetadataEventMixin {
    private val modNameFields = ConcurrentHashMap<Class<*>, Field>()

    @AsmInject(
        method = "reportUnitMismatch(Lcom/corrodinggames/rts/game/units/custom/UnitMismatchInfo;Ljava/util/HashMap;)V",
        target = InjectionPoint.HEAD,
        cancellable = true,
        require = 1,
    )
    fun publishModCheck(
        callbackInfo: CallbackInfo,
        @Suppress("UNUSED_PARAMETER") mismatchInfo: Any,
        mismatches: HashMap<*, *>,
    ) {
        val requiredMods = mismatches.values.mapNotNull { mismatch ->
            mismatch?.let {
                modNameFields.computeIfAbsent(it.javaClass) { type ->
                    type.getDeclaredField("modName").apply { isAccessible = true }
                }.get(it) as? String
            }
        }
        if (DecisionEvents.MOD_CHECK.invoker.check(requiredMods) != Decision.Pass) callbackInfo.cancel()
    }
}

@AsmMixin("com/corrodinggames/rts/gameFramework/trigger/TriggerActivator")
object MapTriggerEventMixin {
    @AsmInject(
        method = "activate(Lcom/corrodinggames/rts/gameFramework/trigger/TriggerEngine;Lcom/corrodinggames/rts/gameFramework/trigger/MapTrigger;)V",
        target = InjectionPoint.TAIL,
        require = 1,
    )
    fun publishTriggerActivation(
        @Suppress("UNUSED_PARAMETER") triggerEngine: Any,
        trigger: Any,
    ) {
        val native = trigger as com.corrodinggames.rts.gameFramework.trigger.MapTrigger
        TriggerEvents.ACTIVATED.invoker.onEvent(native)
        GameEventPublisher.triggerActivated(native.id)
    }
}

@AsmMixin("com/corrodinggames/rts/gameFramework/network/NetworkEngine")
object NetworkEventMixin {
    @AsmInject(
        method = "processSystemPacket(Lcom/corrodinggames/rts/gameFramework/network/Packet;)V",
        target = InjectionPoint.INVOKE,
        at = At(
            value = InjectionPoint.INVOKE,
            target = "com.corrodinggames.rts.gameFramework.network.NetworkCallbacks.onPlayerAdded"
                + "(Lcom/corrodinggames/rts/game/Team;)V",
            shift = Shift.AFTER,
        ),
        require = 1,
    )
    fun publishRemotePlayerJoin(
        team: Any,
    ) {
        GameEventPublisher.playerJoin(team as Team)
    }

    @AsmInject(
        method = "addAIToGame()V",
        target = InjectionPoint.INVOKE,
        at = At(
            value = InjectionPoint.INVOKE,
            target = "com.corrodinggames.rts.gameFramework.network.NetworkCallbacks.onPlayerAdded"
                + "(Lcom/corrodinggames/rts/game/Team;)V",
            shift = Shift.AFTER,
        ),
        require = 1,
    )
    fun publishAIPlayerJoin(
        team: Any,
    ) {
        GameEventPublisher.playerJoin(team as Team)
    }

    @AsmInject(
        method = "receiveChatMessage(Lcom/corrodinggames/rts/gameFramework/network/Connection;ILjava/lang/String;Ljava/lang/String;)V",
        target = InjectionPoint.TAIL,
        require = 1,
    )
    fun publishChatMessage(
        @Suppress("UNUSED_PARAMETER") target: Any,
        nativeConnection: Any?,
        color: Int,
        sender: String?,
        message: String?,
    ) {
        val room = appKoin.get<Game>().gameRoom
        val player = if (room.isHost) {
            if (nativeConnection != null) {
                room.getPlayerByClient(nativeConnection as Connection)
            } else if (sender != null) {
                room.getPlayers().firstOrNull { it.name == room.localPlayer.name }
            } else {
                null
            }
        } else {
            room.getPlayers().firstOrNull { it.name == sender }
        }
        val text = message.orEmpty()
        val network = GameEngine.getInstance().networkEngine
        if (!network.networked && (text.startsWith("-i ") || text.startsWith("-qc "))) return
        ChatEvents.RECEIVED.invoker.onEvent(NativeChat(ChatOrigin.RECEIVE, nativeConnection as? Connection, player, sender, text, color))
        if (player == null) GameEventPublisher.systemMessage(text)
        else GameEventPublisher.chatMessage(sender.orEmpty(), text, player, color)
    }

}

@AsmMixin("com/corrodinggames/rts/appFramework/MultiplayerBattleroomActivity")
object RefreshUiEventMixin {
    @AsmInject(method = "updateUI()V", target = InjectionPoint.TAIL, require = 1)
    fun publishRefreshUi() {
        GameEventPublisher.refreshUi()
    }
}

/** Publishes the API lifecycle after the native engine has completed its initialization. */
@AsmMixin("com/corrodinggames/rts/game/RWGameEngine")
object EngineLoadedMixin {
    @AsmInject(
        method = "init(Landroid/content/Context;)V",
        target = InjectionPoint.TAIL,
        require = 1,
    )
    fun publishGameLoaded(
        @Suppress("UNUSED_PARAMETER") target: Any,
        @Suppress("UNUSED_PARAMETER") context: Any,
    ) {
        ApiRuntime.onGameLoaded()
    }
}
