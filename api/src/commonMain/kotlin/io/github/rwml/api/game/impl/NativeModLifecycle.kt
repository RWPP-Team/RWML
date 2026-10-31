/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.game.impl

import io.github.rwml.api.event.GameEventPublisher

/** Publishes the native mod reload lifecycle for every host and extension. */
internal object NativeModLifecycle {
    fun onReloadStarted() = GameEventPublisher.reloadMod()

    fun onReloadFinished() = GameEventPublisher.reloadModFinished()
}
