/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.event

import com.corrodinggames.rts.gameFramework.CommandControllerCommand

/** Synchronous notifications from native command processing. */
object CommandEvents {
    /** Fires each time the native command reports that all cached paths are ready. */
    val PATHS_READY_NOTIFICATION =
        nativeNotification<CommandControllerCommand>("command.paths_ready")
}
