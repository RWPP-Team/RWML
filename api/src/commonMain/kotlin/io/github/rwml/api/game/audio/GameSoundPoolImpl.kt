/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.game.audio

import korlibs.audio.sound.readSound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.koin.core.annotation.Single
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/** One KorAU backend for both Android and desktop, independent of the game's platform sound engine. */
@Single(binds = [GameSoundPool::class])
class GameSoundPoolImpl : GameSoundPool {
    private val sounds = ConcurrentHashMap<String, GameSound>()

    override fun newSound(input: InputStream, name: String): GameSound {
        sounds[name]?.let {
            input.close()
            return it
        }

        synchronized(sounds) {
            sounds[name]?.let {
                input.close()
                return it
            }

            val sound = input.use { stream ->
                val bytes = stream.readBytes()
                val decoded = runBlocking(Dispatchers.IO) { bytes.readSound() }
                object : GameSound {
                    override fun play() {
                        decoded.play(Dispatchers.Default)
                    }
                }
            }
            sounds[name] = sound
            return sound
        }
    }
}
