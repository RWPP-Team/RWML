/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.coroutines

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/** Pumps game-thread coroutines while Android has no native game update loop to drain them. */
internal actual object PlatformGameMainDispatcherPump {
    private val handler = Handler(Looper.getMainLooper())
    private val schedulerRunning = AtomicBoolean()
    private val drainEnabled = AtomicBoolean()
    @Volatile private var nativeLoopAccessor: NativeLoopAccessor? = null
    private val drainTask = object : Runnable {
        override fun run() {
            if (!drainEnabled.get()) return
            try {
                GameMainDispatcher.drainPlatformPump()
            } catch (failure: Throwable) {
                Log.e("RWML", "Error draining the Android game dispatcher", failure)
            } finally {
                if (drainEnabled.get()) handler.postDelayed(this, FRAME_INTERVAL_MS)
            }
        }
    }
    private val loopMonitorTask = object : Runnable {
        override fun run() {
            if (!schedulerRunning.get()) return
            try {
                GameMainDispatcher.startPump()
            } catch (failure: Throwable) {
                Log.e("RWML", "Error checking the Android game dispatcher owner", failure)
            } finally {
                if (schedulerRunning.get()) handler.postDelayed(this, NATIVE_LOOP_POLL_INTERVAL_MS)
            }
        }
    }

    actual fun start() {
        if (schedulerRunning.compareAndSet(false, true)) {
            drainEnabled.set(true)
            handler.post(drainTask)
            handler.postDelayed(loopMonitorTask, NATIVE_LOOP_POLL_INTERVAL_MS)
        } else if (drainEnabled.compareAndSet(false, true)) {
            handler.post(drainTask)
        }
    }

    actual fun stop() {
        if (drainEnabled.getAndSet(false)) handler.removeCallbacks(drainTask)
    }

    actual fun isRunning(): Boolean = drainEnabled.get()

    actual fun isPumpThread(): Boolean = Looper.myLooper() === Looper.getMainLooper()

    actual fun isNativeLoopActive(): Boolean? = try {
        val accessor = nativeLoopAccessor ?: resolveNativeLoopAccessor()?.also { nativeLoopAccessor = it }
        accessor?.isActive()
    } catch (_: Throwable) {
        null
    }

    private fun resolveNativeLoopAccessor(): NativeLoopAccessor? {
        val className = "com.corrodinggames.rts.gameFramework.GameEngine"
        val loaders = listOfNotNull(
            Thread.currentThread().contextClassLoader,
            PlatformGameMainDispatcherPump::class.java.classLoader,
            ClassLoader.getSystemClassLoader(),
        ).distinct()
        val engineClass = loaders.firstNotNullOfOrNull { loader ->
            runCatching { Class.forName(className, false, loader) }.getOrNull()
        } ?: return null
        val field = engineClass.getDeclaredField("gameLoopActive").apply { isAccessible = true }
        val getInstance = engineClass.getDeclaredMethod("getInstance").apply { isAccessible = true }
        return NativeLoopAccessor(field, getInstance)
    }

    private class NativeLoopAccessor(
        private val activeField: Field,
        private val getInstance: Method,
    ) {
        fun isActive(): Boolean = activeField.getBoolean(getInstance.invoke(null))
    }

    private const val FRAME_INTERVAL_MS = 16L
    private const val NATIVE_LOOP_POLL_INTERVAL_MS = 250L
}
