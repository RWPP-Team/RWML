/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.config

import android.content.Context
import io.github.rwml.api.appKoin

internal actual object ConfigStorage {
    private fun preferences(name: String) = appKoin.get<Context>().getSharedPreferences(name, Context.MODE_PRIVATE)

    actual fun saveConfig(name: String, source: String) {
        check(preferences(name).edit().putString("src", source).commit()) { "Could not save configuration $name" }
    }

    actual fun readConfig(name: String): String? = preferences(name).getString("src", null)

    actual fun deleteConfig(name: String) {
        check(preferences(name).edit().remove("src").commit()) { "Could not delete configuration $name" }
    }

    actual fun saveSingleConfig(group: String, key: String, value: String) {
        check(preferences(group).edit().putString(key, value).commit()) { "Could not save configuration $group/$key" }
    }

    actual fun readSingleConfig(group: String, key: String): String? = preferences(group).getString(key, null)
}
