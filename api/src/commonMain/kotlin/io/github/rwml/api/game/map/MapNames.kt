/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.game.map

/** Pure map-file name formatting shared by Android and desktop game adapters. */
object MapNames {
    private val patterns = listOf(
        Regex("^l\\d*;\\[.*\\](.+)\\.tmx$"),
        Regex("^l\\d*;(.+)\\.tmx$"),
        Regex("^ *\\[.*\\](.+)\\.tmx$"),
        Regex("^(.*)\\.tmx$"),
    )

    fun getPrettyMapName(path: String?): String? {
        if (path == null) return null

        val fileName = path.substringAfterLast('/').substringAfterLast('\\')
        val prettyName = patterns.firstNotNullOfOrNull { pattern ->
            pattern.matchEntire(fileName)?.groupValues?.get(1)?.capitalizeFirst()
        } ?: fileName

        return prettyName.replace('_', ' ').removeSuffix(".rwsave")
    }

    private fun String.capitalizeFirst(): String =
        replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
