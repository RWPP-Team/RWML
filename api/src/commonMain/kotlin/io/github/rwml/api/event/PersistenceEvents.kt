/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.event

import com.corrodinggames.rts.gameFramework.GameSaver
import com.corrodinggames.rts.gameFramework.network.InputNetStream
import com.corrodinggames.rts.gameFramework.network.OutputNetStream
import java.io.File

/** The native reason visible at the GameSaver boundary. */
enum class PersistenceReason {
    /** A file operation entered through GameSaver.autoSaveGame or GameSaver.loadSaveFile. */
    FILE_REQUEST,

    /** GameSaver.autoSaveGame was called with its native autoSave flag set. */
    AUTO_SAVE,

    /** A replay game-state stream: the recording's initial save or a load during replay playback. */
    REPLAY_SNAPSHOT,

    /** GameSaver serialized or loaded a stream while the network engine reported a network session. */
    NETWORK_SNAPSHOT,

    /** A stream operation with no more specific native runtime state. */
    OTHER_STREAM,
}

/** A GameSaver file-save request. The file is the resolved native save path when it can be resolved. */
data class GameSaveRequest(
    val gameSaver: GameSaver,
    val reason: PersistenceReason,
    val name: String?,
    val file: File?,
    /** The native autoSave flag; null for stream serialization. */
    val autoSave: Boolean?,
    /** The native output stream for replay, network, or other stream serialization. */
    val outputStream: OutputNetStream?,
)

/** A GameSaver load request. Stream flags are preserved as received, without assigning guessed names. */
data class GameLoadRequest(
    val gameSaver: GameSaver,
    val reason: PersistenceReason,
    val name: String?,
    val file: File?,
    val inputStream: InputNetStream?,
    /** The first native flag, used by the game to preserve existing teams. */
    val keepTeams: Boolean?,
    /** Native load flags whose purpose is not named by the mapped GameSaver source. */
    val streamFlag2: Boolean?,
    val streamFlag3: Boolean?,
)

/** Shared payload for BEFORE. Save/load details remain separately typed in [GameSaveRequest] and [GameLoadRequest]. */
sealed interface PersistenceOperation {
    data class Saving(val request: GameSaveRequest) : PersistenceOperation
    data class Loading(val request: GameLoadRequest) : PersistenceOperation
}

enum class PersistenceFailureReason {
    /** A boolean native load entry point returned false. */
    RETURNED_FALSE,

    /** autoSaveGame returned without reaching its native successful-file-commit point. */
    FILE_NOT_COMMITTED,

    /** The native operation threw before completion. */
    THREW,
}

data class PersistenceFailure(
    val operation: PersistenceOperation,
    val reason: PersistenceFailureReason,
    /** Null when the native method reported failure without throwing or swallowed its internal exception. */
    val error: Throwable?,
)

/** Synchronous notifications at the native GameSaver boundary. BEFORE observes and cannot cancel. */
object SaveEvents {
    val BEFORE = nativeNotification<PersistenceOperation>("persistence.before")
    val SAVED = nativeNotification<GameSaveRequest>("persistence.saved")
    val LOADED = nativeNotification<GameLoadRequest>("persistence.loaded")
    val FAILED = nativeNotification<PersistenceFailure>("persistence.failed")
}
