/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 */

@file:JvmName("i18nKt")

package io.github.rwml.api.i18n

import com.corrodinggames.rts.gameFramework.localization.LocaleEngine
import io.github.rwml.api.Rwml

/** Reads registered properties through the native locale engine once the game is ready. */
fun readI18n(path: String, vararg args: Any?): String {
    // Android's launcher can render before the game DEX has been mounted.
    val messages = Rwml.get()?.ui()?.messages()
    if (messages != null && !messages.nativeReady()) return messages.text(path, *args)
    return LocaleEngine.getString(path, *args)
}
