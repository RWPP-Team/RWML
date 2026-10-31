/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.config

import org.koin.core.component.KoinComponent
import io.github.rwml.api.logging.logger
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty
import kotlin.reflect.full.declaredMemberProperties

/** Reads and writes built-in and game configuration through the RWML API extension. */
interface ConfigIO : KoinComponent {
    fun saveConfig(config: Config)
    fun <T : Config> readConfig(clazz: KClass<T>): T?
    fun <T : Config> deleteConfig(clazz: KClass<T>)
    fun saveSingleConfig(group: String, key: String, value: Any?)
    fun readSingleConfig(group: String, key: String): String?
    fun <T> getGameConfig(name: String): T
    fun setGameConfig(name: String, value: Any?)

    fun saveAllConfig() {
        getKoin().getAll<Config>().forEach(::saveConfig)
    }

    fun readAllConfig() {
        getKoin().getAll<Config>().forEach { config ->
            runCatching {
                readConfig(config::class)?.let(config::copyConfigFrom)
            }.onFailure {
                logger().error("Failed to read configuration ${config::class.qualifiedName}", it)
                deleteConfig(config::class)
            }
        }
    }
}

private fun <T : Config> T.copyConfigFrom(source: T) {
    source::class.declaredMemberProperties
        .filterIsInstance<KMutableProperty<*>>()
        .forEach { property ->
            property.setter.call(this, property.getter.call(source))
        }
}
