/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api

import io.github.rwml.api.coroutines.GameMainDispatcher
import io.github.rwml.api.coroutines.GameScope
import io.github.rwml.api.event.GameEventPublisher
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.plugin.module.dsl.modules

/** Runtime owned by the built-in API extension and shared with host-side extension modules. */
object ApiRuntime {
    private val lock = Any()
    private var application: KoinApplication? = null
    private var context: ExtensionContext? = null
    private var componentsInitialized = false
    private var gameLoaded = false

    val extensionContext: ExtensionContext
        get() = checkNotNull(context) {
            "The RWML API extension has not been loaded"
        }

    /** Starts the one Koin container used by the API and the application extension. */
    internal fun initialize(extensionContext: ExtensionContext) {
        synchronized(lock) {
            check(context == null) { "The RWML API extension was loaded more than once" }
            context = extensionContext
            val started = startKoin {
                modules(RwmlApiModule::class)
            }
            application = started
            appKoin = started.koin
            gameScope = GameScope
            GameMainDispatcher.startPump()
        }
    }

    /** Adds the platform and application modules to the same container the API already created. */
    fun configureKoin(configuration: KoinApplication.() -> Unit) {
        synchronized(lock) {
            val started = checkNotNull(application) {
                "The RWML API extension must load before application Koin modules are installed"
            }
            configuration(started)
        }
    }

    /** Runs all API and application initialization hooks once the Koin modules are ready. */
    fun initializeComponents() {
        synchronized(lock) {
            if (componentsInitialized) return
            val koin = checkNotNull(application) {
                "The RWML API extension must load before components are initialized"
            }.koin
            koin.getAll<Initialization>().forEach(Initialization::init)
            componentsInitialized = true
        }
    }

    /** Finishes the shared game-loaded lifecycle, then publishes the API event. */
    fun onGameLoaded() {
        GameMainDispatcher.noteEngineInitialized()
        initializeComponents()
        val publish = synchronized(lock) {
            if (gameLoaded) return
            val koin = checkNotNull(application) {
                "The RWML API extension must load before the game is loaded"
            }.koin
            koin.getAll<Initialization>().forEach(Initialization::onGameLoaded)
            gameLoaded = true
            true
        }
        if (publish) {
            io.github.rwml.api.event.EngineEvents.READY.invoker.onEvent(com.corrodinggames.rts.gameFramework.GameEngine.getInstance())
            GameEventPublisher.gameLoaded()
        }
    }
}
