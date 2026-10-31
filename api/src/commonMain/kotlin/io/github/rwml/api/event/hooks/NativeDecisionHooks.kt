package io.github.rwml.api.event.hooks

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.gameFramework.CommandControllerCommand
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.Connection
import io.github.rwml.api.event.*
import kim.der.asm.api.annotation.*

@AsmMixin("com/corrodinggames/rts/gameFramework/network/NetworkCallbacks")
object NativeJoinDecisionMixin {
    @AsmInject(method = "onPlayerJoining(Lcom/corrodinggames/rts/gameFramework/network/Connection;Ljava/lang/String;IILjava/lang/String;Lcom/corrodinggames/rts/game/Player;)Ljava/lang/String;", target = InjectionPoint.TAIL, require = 1)
    fun check(callback: CallbackInfo, target: Any, connection: Connection, name: String,
              protocolVersion: Int, gameVersion: Int, packageName: String?, player: Any?) {
        if (callback.getReturnValue<String>() != null) return
        val result = DecisionEvents.PLAYER_JOIN.invoker.check(
            JoiningPlayer(connection, name, protocolVersion, gameVersion, packageName, player as? Team),
        )
        if (result is Decision.Reject) callback.setReturnValue(result.reason)
    }
}

/** Shared transport entry points. Product commands register callbacks instead of platform mixins. */
@AsmMixin("com/corrodinggames/rts/gameFramework/network/NetworkEngine")
object NativeDecisionMixin {
    @AsmInject(method = "queueCommand(Lcom/corrodinggames/rts/gameFramework/CommandControllerCommand;)V", target = InjectionPoint.HEAD, cancellable = true, require = 1)
    fun beforeQueue(callback: CallbackInfo, target: Any, command: CommandControllerCommand) {
        if (DecisionEvents.COMMAND_QUEUE.invoker.check(command) != Decision.Pass) callback.cancel()
    }

    @AsmInject(method = "sendChatMessage(Ljava/lang/String;)V", target = InjectionPoint.HEAD, cancellable = true, require = 1)
    fun beforeLocalChat(callback: CallbackInfo, target: Any, message: String) {
        val team = GameEngine.getInstance().networkEngine.localTeam
        if (DecisionEvents.CHAT.invoker.check(NativeChat(ChatOrigin.LOCAL_SEND, null, team, team?.name, message)) != Decision.Pass) callback.cancel()
    }

    @AsmInject(method = "routeChatMessageViaRelay(Lcom/corrodinggames/rts/gameFramework/network/Connection;Lcom/corrodinggames/rts/game/Team;Ljava/lang/String;Ljava/lang/String;Lcom/corrodinggames/rts/gameFramework/network/Connection;)V", target = InjectionPoint.HEAD, cancellable = true, require = 1)
    fun beforeRelayChat(callback: CallbackInfo, target: Any, connection: Connection?, team: Team?, sender: String?, message: String?) {
        if (DecisionEvents.CHAT.invoker.check(NativeChat(ChatOrigin.REMOTE_RELAY, connection, team, sender, message.orEmpty())) != Decision.Pass) callback.cancel()
    }

    @AsmInject(method = "receiveChatMessage(Lcom/corrodinggames/rts/gameFramework/network/Connection;ILjava/lang/String;Ljava/lang/String;)V", target = InjectionPoint.HEAD, cancellable = true, require = 1)
    fun beforeReceive(callback: CallbackInfo, target: Any, connection: Connection?, color: Int, sender: String?, message: String?) {
        val team = connection?.player as? Team
        if (DecisionEvents.CHAT.invoker.check(NativeChat(ChatOrigin.RECEIVE, connection, team, sender, message.orEmpty(), color)) != Decision.Pass) callback.cancel()
    }

    @AsmInject(method = "processSystemPacket(Lcom/corrodinggames/rts/gameFramework/network/Packet;)V", target = InjectionPoint.INVOKE,
        at = At(value = InjectionPoint.INVOKE, target = "com.corrodinggames.rts.gameFramework.network.NetworkCallbacks.onPlayerJoined(Lcom/corrodinggames/rts/gameFramework/network/Connection;Ljava/lang/String;Ljava/lang/String;)V", shift = Shift.AFTER), require = 1)
    fun serverAccepted(connection: Connection, name: String?, packageName: String?) {
        val network = GameEngine.getInstance().networkEngine
        val team = connection.player as? Team ?: return
        val joined = RoomJoined(network, connection, team)
        ConnectionEvents.HANDSHAKE_COMPLETE.invoker.onEvent(joined)
        RoomEvents.JOINED.invoker.onEvent(joined)
    }
}
