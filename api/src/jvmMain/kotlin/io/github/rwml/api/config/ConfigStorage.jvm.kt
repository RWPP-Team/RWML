/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.config

import java.io.File
import java.util.Properties

internal actual object ConfigStorage {
    private val propertiesCache = mutableMapOf<String, Properties>()

    private fun configFile(name: String) = File(System.getProperty("user.dir"), "$name.toml")
    private fun propertiesFile(group: String) = File(System.getProperty("user.dir"), "$group.properties")

    actual fun saveConfig(name: String, source: String) {
        configFile(name).apply {
            parentFile?.mkdirs()
            writeText(source)
        }
    }

    actual fun readConfig(name: String): String? = configFile(name).takeIf(File::exists)?.readText()

    actual fun deleteConfig(name: String) {
        configFile(name).delete()
    }

    actual fun saveSingleConfig(group: String, key: String, value: String) {
        val properties = properties(group)
        synchronized(properties) {
            properties.setProperty(key, value)
            propertiesFile(group).outputStream().use { properties.store(it, null) }
        }
    }

    actual fun readSingleConfig(group: String, key: String): String? = properties(group).getProperty(key)

    private fun properties(group: String): Properties = synchronized(propertiesCache) {
        propertiesCache.getOrPut(group) {
            Properties().apply { propertiesFile(group).takeIf(File::exists)?.inputStream()?.use(::load) }
        }
    }
}
