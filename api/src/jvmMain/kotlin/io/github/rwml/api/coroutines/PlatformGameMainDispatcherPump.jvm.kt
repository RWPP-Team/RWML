/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.coroutines

/** Desktop dispatch is drained by the native game update loop. */
internal actual object PlatformGameMainDispatcherPump {
    actual fun start() = Unit
    actual fun stop() = Unit
    actual fun isRunning() = false
    actual fun isPumpThread() = false
    actual fun isNativeLoopActive(): Boolean? = null
}
