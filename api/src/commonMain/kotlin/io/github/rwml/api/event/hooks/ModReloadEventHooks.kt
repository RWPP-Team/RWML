/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.event.hooks

import io.github.rwml.api.game.impl.NativeModLifecycle
import io.github.rwml.api.event.ModEvents
import io.github.rwml.api.event.ModReload
import io.github.rwml.api.event.ModReloadFailure
import com.corrodinggames.rts.gameFramework.GameEngine
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.WrapMethod
import kim.der.asm.api.annotation.Operation

@AsmMixin("com/corrodinggames/rts/gameFramework/mod/ModManager")
object ModReloadEventHooks {
    @WrapMethod(method = "reloadModDataInternal(ZZ)V", require = 1)
    fun reload(preserveMissing: Boolean, metadataOnly: Boolean, original: Operation<Any?>) {
        val reload = ModReload(GameEngine.getInstance().modManager, preserveMissing, metadataOnly)
        ModEvents.BEFORE.invoker.onEvent(reload)
        NativeModLifecycle.onReloadStarted()
        try {
            original.call(preserveMissing, metadataOnly)
        } catch (failure: Throwable) {
            val error = if (failure is java.lang.reflect.InvocationTargetException) failure.targetException else failure
            try {
                ModEvents.FAILED.invoker.onEvent(ModReloadFailure(reload, error))
            } catch (notificationFailure: Throwable) {
                if (notificationFailure !== error) error.addSuppressed(notificationFailure)
            }
            throw error
        }
        ModEvents.AFTER.invoker.onEvent(reload)
        NativeModLifecycle.onReloadFinished()
    }
}
