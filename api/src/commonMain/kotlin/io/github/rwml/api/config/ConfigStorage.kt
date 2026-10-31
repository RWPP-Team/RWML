/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.config

/** Platform persistence used by the single API-owned ConfigIO implementation. */
internal expect object ConfigStorage {
    fun saveConfig(name: String, source: String)
    fun readConfig(name: String): String?
    fun deleteConfig(name: String)
    fun saveSingleConfig(group: String, key: String, value: String)
    fun readSingleConfig(group: String, key: String): String?
}
