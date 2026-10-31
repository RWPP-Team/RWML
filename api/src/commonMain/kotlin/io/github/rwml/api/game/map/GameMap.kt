/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.game.map

import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import java.io.InputStream

/** A concrete map entry shared by the API; hosts provide only the platform-specific stream readers. */
open class GameMap(
    val id: Int,
    val mapName: String,
    val mapType: GameSetupMapType,
    private val inputStreamProvider: () -> InputStream,
    private val displayNameProvider: (() -> String)? = null,
    val enginePath: String = defaultMapPath(mapName, mapType),
) {
    open fun openInputStream(): InputStream = inputStreamProvider()

    open fun displayName(): String = displayNameProvider?.invoke()
        ?: mapName.replace(mapPrefixRegex, "")

    fun getMapSuffix() = if (mapType == GameSetupMapType.savedGame) ".rwsave" else ".tmx"

    companion object {
        private val mapPrefixRegex = Regex("""^\[.*?\]""")
    }
}

internal fun defaultMapPath(name: String, type: GameSetupMapType): String {
    val suffix = if (type == GameSetupMapType.savedGame) ".rwsave" else ".tmx"
    val normalizedName = name.replace('\\', '/')
    val fileName = if (normalizedName.endsWith(suffix, ignoreCase = true)) normalizedName else normalizedName + suffix
    if (fileName.startsWith('/') || Regex("^[A-Za-z]:/").containsMatchIn(fileName)) return fileName
    if (listOf(
            "maps/",
            "mods/maps/",
            "saves/",
            "/SD/rusted_warfare_maps/",
            "/SD/rustedWarfare/saves/",
            "/SD/rustedWarfare/maps/",
        ).any { fileName.startsWith(it, ignoreCase = true) }
    ) {
        return fileName
    }
    val directory = when (type) {
        GameSetupMapType.skirmishMap -> "maps/skirmish"
        GameSetupMapType.customMap -> "/SD/rusted_warfare_maps"
        GameSetupMapType.savedGame -> "saves"
    }
    return "$directory/$fileName"
}

/** Normalize separators without discarding folder names or native virtual-path tags. */
internal fun normalizeGameMapPath(path: String): String = path
    .replace('\\', '/')
    .replace(Regex("/{2,}"), "/")
    .removePrefix("./")
    .trimEnd('/')

/** Compare full engine paths; callers must not reduce a path to its basename. */
internal fun sameGameMapPath(first: String, second: String): Boolean =
    normalizeGameMapPath(first).equals(normalizeGameMapPath(second), ignoreCase = true)

internal fun sameGameMapName(first: String, second: String): Boolean =
    withoutGameMapSuffix(normalizeGameMapPath(first)).equals(
        withoutGameMapSuffix(normalizeGameMapPath(second)),
        ignoreCase = true,
    )

private fun withoutGameMapSuffix(value: String): String = when {
    value.endsWith(".tmx", ignoreCase = true) -> value.dropLast(4)
    value.endsWith(".rwsave", ignoreCase = true) -> value.dropLast(7)
    else -> value
}
