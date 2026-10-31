package io.github.rwml.api.event

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.GameEngineMapType
import com.corrodinggames.rts.gameFramework.ReplayEngine
import com.corrodinggames.rts.gameFramework.mod.ModManager
import com.corrodinggames.rts.gameFramework.network.Connection
import com.corrodinggames.rts.gameFramework.network.GameSetup
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import com.corrodinggames.rts.gameFramework.trigger.MapTrigger

/** Synchronous native-thread notification. Native references must not escape into async work. */
fun interface NativeCallback<T> { fun onEvent(value: T) }

internal fun <T> nativeNotification(name: String): SyncEvent<NativeCallback<T>> =
    SyncEvent.notification(name) { listeners, invokeListener ->
        NativeCallback { value ->
            listeners.forEach { listener -> invokeListener(listener) { listener.onEvent(value) } }
        }
    }

object EngineEvents {
    /** init returned successfully; API components are ready. Runs on the initialization thread. */
    val READY = nativeNotification<GameEngine>("engine.ready")
    /** The native engine resumed from a menu or battleroom transition. */
    val RESUMED = nativeNotification<GameEngine>("engine.resumed")
}

data class MapLoad(val engine: GameEngine, val path: String?, val type: GameEngineMapType,
                   val loadAi: Boolean, val keepTeams: Boolean)
data class MapLoadFailure(val load: MapLoad, val reason: String)

/** Synchronous on the native map-loading caller; BEFORE does not permit cancellation. */
object MapEvents {
    val BEFORE = nativeNotification<MapLoad>("map.before")
    val LOADED = nativeNotification<MapLoad>("map.loaded")
    /** Normal unsuccessful return, or an explicit native throw. Does not change the native outcome. */
    val FAILED = nativeNotification<MapLoadFailure>("map.failed")
}

data class TransportConnected(val network: NetworkEngine, val address: String?)
data class RoomJoined(val network: NetworkEngine, val connection: Connection?, val team: Team)
data class NetworkDisconnected(val network: NetworkEngine, val reason: String)
data class PlayerDisconnected(val connection: Connection, val team: Team?, val reason: String?)

/** Receive-worker callbacks retain its thread; they do not silently schedule simulation work. */
object ConnectionEvents {
    val TRANSPORT_CONNECTED = nativeNotification<TransportConnected>("connection.transport_connected")
    /** Native login accepted: server onPlayerJoined, or client team/setup packet fully applied. */
    val HANDSHAKE_COMPLETE = nativeNotification<RoomJoined>("connection.handshake_complete")
    val DISCONNECTED = nativeNotification<NetworkDisconnected>("connection.disconnected")
    val PLAYER_DISCONNECTED = nativeNotification<PlayerDisconnected>("connection.player_disconnected")
}

data class RoomSetupApplied(val network: NetworkEngine, val setup: GameSetup)
object RoomEvents {
    val JOINED = nativeNotification<RoomJoined>("room.joined")
    /** Native local or remote player registration completed. */
    val PLAYER_ADDED = nativeNotification<Team>("room.player_added")
    val SETUP_APPLIED = nativeNotification<RoomSetupApplied>("room.setup_applied")
    val RETURNED = nativeNotification<NetworkEngine>("room.returned")
}

data class ReplayLoad(val engine: ReplayEngine, val name: String)
object ReplayEvents {
    val BEFORE = nativeNotification<ReplayLoad>("replay.before")
    val LOADED = nativeNotification<ReplayLoad>("replay.loaded")
    val FAILED = nativeNotification<ReplayLoad>("replay.failed")
}

data class ModReload(val manager: ModManager, val preserveMissing: Boolean, val metadataOnly: Boolean)
data class ModReloadFailure(val reload: ModReload, val error: Throwable)
object ModEvents {
    /** The internal reload boundary also covers the editor's direct calls. */
    val BEFORE = nativeNotification<ModReload>("mods.before")
    /** Completed unit metadata/cache refresh; native method returned normally. */
    val AFTER = nativeNotification<ModReload>("mods.after")
    val FAILED = nativeNotification<ModReloadFailure>("mods.failed")
}

object TriggerEvents {
    val ACTIVATED = nativeNotification<MapTrigger>("trigger.activated")
}
