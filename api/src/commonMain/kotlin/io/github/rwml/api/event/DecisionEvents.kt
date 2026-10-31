package io.github.rwml.api.event

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.gameFramework.CommandControllerCommand
import com.corrodinggames.rts.gameFramework.network.Connection

/** First non-Pass result wins. Exceptions propagate; decisions never launch or await coroutines. */
sealed interface Decision {
    data object Pass : Decision
    data object Handled : Decision
    data class Reject(val reason: String) : Decision
}

data class JoiningPlayer(val connection: Connection, val name: String, val protocolVersion: Int,
                         val gameVersion: Int, val packageName: String?, val team: Team?)
fun interface PlayerJoinCallback { fun check(player: JoiningPlayer): Decision }
fun interface ModCheckCallback { fun check(requiredMods: List<String>): Decision }
fun interface CommandQueueCallback { fun check(command: CommandControllerCommand): Decision }
enum class ChatOrigin { LOCAL_SEND, REMOTE_RELAY, RECEIVE }
data class NativeChat(val origin: ChatOrigin, val connection: Connection?, val team: Team?,
                      val sender: String?, val message: String, val color: Int = -1)
fun interface ChatFilter { fun check(chat: NativeChat): Decision }

object DecisionEvents {
    /** Server receive thread. Reject supplies the native login rejection reason. Handled is treated as Pass. */
    val PLAYER_JOIN = SyncEvent.decision<PlayerJoinCallback>("player.before_join") { listeners, invoke ->
        PlayerJoinCallback { player ->
            var result: Decision = Decision.Pass
            for (listener in listeners) {
                invoke(listener) { result = listener.check(player) }
                if (result is Decision.Reject) break
            }
            result
        }
    }
    /** Native unit-mismatch report thread. Handled/Reject suppress native rejection; listener owns recovery. */
    val MOD_CHECK = SyncEvent.decision<ModCheckCallback>("mods.check") { listeners, invoke ->
        ModCheckCallback { mods ->
            var result: Decision = Decision.Pass
            for (listener in listeners) {
                invoke(listener) { result = listener.check(mods) }
                if (result != Decision.Pass) break
            }
            result
        }
    }
    /** Native queue caller, before insertion. This callback adds no multiplayer synchronization. */
    val COMMAND_QUEUE = SyncEvent.decision<CommandQueueCallback>("command.before_queue") { listeners, invoke ->
        CommandQueueCallback { command ->
            var result: Decision = Decision.Pass
            for (listener in listeners) {
                invoke(listener) { result = listener.check(command) }
                if (result != Decision.Pass) break
            }
            result
        }
    }
    /** Original caller/receive thread. Handled and Reject both stop native send/relay/display. */
    val CHAT = SyncEvent.decision<ChatFilter>("chat.before") { listeners, invoke ->
        ChatFilter { chat ->
            var result: Decision = Decision.Pass
            for (listener in listeners) {
                invoke(listener) { result = listener.check(chat) }
                if (result != Decision.Pass) break
            }
            result
        }
    }
}

object ChatEvents {
    /** Native receiveChatMessage completed; notification cannot cancel earlier delivery. */
    val RECEIVED = nativeNotification<NativeChat>("chat.received")
}
