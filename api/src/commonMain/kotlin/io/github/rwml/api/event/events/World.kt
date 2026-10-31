/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.event.events

import io.github.rwml.api.event.AbstractEvent

sealed class WorldEvent : AbstractEvent()

class TriggerActivateEvent(val id: String) : WorldEvent()

class LocalPlayerUnitDamagedEvent : WorldEvent()

class LocalPlayerBaseDamagedEvent : WorldEvent()

class LocalPlayerUnitCreatedEvent : WorldEvent()

class LocalPlayerBuildingConstructedEvent : WorldEvent()

class LocalPlayerUpgradedCompletedEvent : WorldEvent()
