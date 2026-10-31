/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.game.impl

import com.corrodinggames.librocket.scripts.ScriptEngine
import java.io.File
import java.io.InputStream

internal actual object GamePlatformHost {
    actual val generatedMapDirectory: String = "maps"
    actual val savedGameDirectory: String = "saves"

    actual fun listGameAssetDirectory(path: String): List<String> =
        File("assets/$path").list()?.toList().orEmpty()

    actual fun openGameAsset(path: String): InputStream = File("assets/$path").inputStream()

    actual fun openGameScreen() = continueGame()

    actual fun closeGameScreen() {
        ScriptEngine.getInstance().root.showMainMenu()
    }

    actual fun continueGame() {
        ScriptEngine.getInstance().root.resumeNonMenu()
    }
}
