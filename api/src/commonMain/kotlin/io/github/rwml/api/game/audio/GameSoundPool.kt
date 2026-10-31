/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.game.audio

import java.io.InputStream

/** Loads and caches decoded sounds by their stable asset name. */
interface GameSoundPool {
    fun newSound(input: InputStream, name: String): GameSound
}
