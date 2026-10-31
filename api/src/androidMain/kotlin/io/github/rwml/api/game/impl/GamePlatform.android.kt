/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.game.impl

import android.app.Activity
import android.content.Intent
import com.corrodinggames.rts.gameFramework.GameEngine
import java.io.File
import java.io.InputStream

internal actual object GamePlatformHost {
    private const val GAME_ACTIVITY = "io.github.rwml.android.builtin.InGameActivity"

    actual val generatedMapDirectory: String
        get() = File(requireNotNull(GameEngine.getInstance().context).filesDir, "rwml/maps").absolutePath
    actual val savedGameDirectory: String = "/SD/rustedWarfare/saves"

    actual fun listGameAssetDirectory(path: String): List<String> =
        GameEngine.getInstance().context.assets.list(path).orEmpty().toList()

    actual fun openGameAsset(path: String): InputStream = GameEngine.getInstance().context.assets.open(path)

    actual fun openGameScreen() {
        val engine = GameEngine.getInstance()
        val context = engine.currentActivity ?: engine.context ?: return
        if (context.javaClass.name == GAME_ACTIVITY) return
        val flags = if (context is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(Intent().setClassName(context, GAME_ACTIVITY).addFlags(flags))
    }

    actual fun closeGameScreen() {
        val activity = GameEngine.getInstance().currentActivity as? Activity ?: return
        if (activity.javaClass.name != GAME_ACTIVITY) return
        activity.runOnUiThread { activity.finish() }
    }

    actual fun continueGame() = openGameScreen()
}
