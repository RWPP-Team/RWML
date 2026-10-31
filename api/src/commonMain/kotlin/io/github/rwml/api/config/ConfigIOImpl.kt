/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.config

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.SettingsEngine
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import net.peanuuutz.tomlkt.Toml
import org.koin.core.annotation.Single
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/** Shared configuration behavior. Android and desktop only supply their persistent storage backend. */
@Single(binds = [ConfigIO::class])
class ConfigIOImpl : ConfigIO {
    private val gameSettingsFields = ConcurrentHashMap<String, Field>()

    @OptIn(InternalSerializationApi::class)
    @Suppress("UNCHECKED_CAST")
    override fun saveConfig(config: Config) {
        ConfigStorage.saveConfig(config::class.qualifiedName.orEmpty(), Toml.encodeToString(
            config::class.serializer() as KSerializer<Any>,
            config,
        ))
    }

    @OptIn(InternalSerializationApi::class)
    override fun <T : Config> readConfig(clazz: KClass<T>): T? {
        val source = ConfigStorage.readConfig(clazz.qualifiedName.orEmpty()) ?: return null
        if (source.isBlank()) return null
        return Toml.decodeFromString(clazz.serializer(), source)
    }

    override fun <T : Config> deleteConfig(clazz: KClass<T>) {
        ConfigStorage.deleteConfig(clazz.qualifiedName.orEmpty())
    }

    override fun saveSingleConfig(group: String, key: String, value: Any?) =
        ConfigStorage.saveSingleConfig(group, key, value?.toString().orEmpty())

    override fun readSingleConfig(group: String, key: String): String? =
        ConfigStorage.readSingleConfig(group, key)

    @Suppress("UNCHECKED_CAST")
    override fun <T> getGameConfig(name: String): T = gameSettingsField(name).get(GameEngine.getInstance().settings) as T

    override fun setGameConfig(name: String, value: Any?) {
        gameSettingsField(name).set(GameEngine.getInstance().settings, value)
    }

    override fun saveAllConfig() {
        super.saveAllConfig()
        GameEngine.getInstance().settings.save()
    }

    private fun gameSettingsField(name: String): Field =
        gameSettingsFields.getOrPut(name) { SettingsEngine::class.java.getDeclaredField(name).apply { isAccessible = true } }
}
