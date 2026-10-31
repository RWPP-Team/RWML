/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api

import io.github.rwml.api.game.team.TeamModes
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

/** The built-in RWML API extension's Koin entry point. */
@Module
@ComponentScan("io.github.rwml.api")
class RwmlApiModule {
    @Single
    fun teamModes(): TeamModes = TeamModes
}
