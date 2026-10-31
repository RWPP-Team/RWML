/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.coroutines

import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext

/**
 * Game operations run on one drain owner at a time. Android uses the main looper while the
 * native loop is idle, then hands ownership to the native frame thread while it is active. A
 * lightweight main-looper monitor observes when the loop stops and reclaims the queue. The
 * handoff is serialized, but this is not fixed thread affinity: a coroutine may resume on the
 * other owner after a lifecycle transition. Frame-bound native state should be accessed while
 * the native loop owns the dispatcher.
 */
object GameMainDispatcher : CoroutineDispatcher() {
    private val taskQueue = ConcurrentLinkedQueue<Runnable>()
    private val updateTasks = CopyOnWriteArrayList<Runnable>()
    private val drainLock = Any()
    private var nativeLoopActive = false
    private var engineInitialized = false
    private var nativeThread: Thread? = null
    private var handoffThread: Thread? = null
    private var lastNativeFrameNanos = 0L

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        taskQueue.add(block)
    }

    /**
     * Drains work on Android's main looper while the native game loop is idle. The pump remains
     * scheduled while the game is active so it can detect a return to the launcher, but it does
     * not drain the queue while native frames own it.
     */
    internal fun drainPlatformPump(): Boolean = synchronized(drainLock) {
        if (refreshNativeLoopStateLocked()) {
            PlatformGameMainDispatcherPump.stop()
            return@synchronized true
        }
        drainTasksLocked()
        false
    }

    /** Starts Android's idle pump; it remains scheduled to detect later native-loop transitions. */
    internal fun startPump() {
        synchronized(drainLock) {
            if (!refreshNativeLoopStateLocked()) PlatformGameMainDispatcherPump.start()
        }
    }

    /** Reports whether the native loop owns game-thread work at this moment. */
    internal fun isNativeLoopActive(): Boolean = synchronized(drainLock) {
        refreshNativeLoopStateLocked()
    }

    /** Marks engine initialization without assuming that its native view is already running. */
    internal fun noteEngineInitialized() {
        synchronized(drainLock) {
            engineInitialized = true
            handoffThread = Thread.currentThread()
        }
    }

    /** Called by the native frame hook; the first frame becomes the dispatcher owner thread. */
    internal fun drainNativeFrame() {
        synchronized(drainLock) {
            engineInitialized = true
            if (!nativeLoopActive) {
                nativeLoopActive = true
                PlatformGameMainDispatcherPump.stop()
            }

            val currentThread = Thread.currentThread()
            val owner = nativeThread
            when {
                owner == null -> {
                    nativeThread = currentThread
                    handoffThread = null
                }
                owner === currentThread -> Unit
                !owner.isAlive -> {
                    nativeThread = currentThread
                    handoffThread = null
                }
                else -> check(false) {
                    "GameMainDispatcher must only be drained by the native engine thread"
                }
            }
            lastNativeFrameNanos = System.nanoTime()
            drainTasksLocked()
        }
    }

    /**
     * Synchronously awaits an event that may switch to this dispatcher. The event call begins
     * undispatched on the callback thread; GlobalEventChannel listeners run in the channel's
     * coroutine context and can resume on another thread. When called by the current dispatcher
     * owner, this method pumps queued continuations while the runBlocking event loop remains
     * available for the event's other suspensions.
     */
    internal fun <T> runBlockingWithGameDispatcher(block: suspend () -> T): T {
        if (!canDrainOnCurrentThread()) return runBlocking { block() }

        return runBlocking {
            val result = CompletableDeferred<Result<T>>()
            launch(start = CoroutineStart.UNDISPATCHED) {
                val outcome = try {
                    Result.success(block())
                } catch (failure: Throwable) {
                    Result.failure(failure)
                }
                result.complete(outcome)
            }

            while (!result.isCompleted) {
                synchronized(drainLock) {
                    drainTasksLocked(includeUpdateTasks = false)
                }
                if (!result.isCompleted) delay(COOPERATIVE_PUMP_DELAY_MILLIS)
            }
            result.await().getOrThrow()
        }
    }

    private fun canDrainOnCurrentThread(): Boolean = synchronized(drainLock) {
        refreshNativeLoopStateLocked()
        val currentThread = Thread.currentThread()
        val owner = nativeThread
        if (owner != null) {
            owner === currentThread
        } else if (nativeLoopActive) {
            handoffThread === currentThread
        } else {
            PlatformGameMainDispatcherPump.isPumpThread() || !PlatformGameMainDispatcherPump.isRunning()
        }
    }

    /** Refreshes loop ownership while holding the same lock used by both drain paths. */
    private fun refreshNativeLoopStateLocked(): Boolean {
        val recentNativeFrame = nativeThread != null &&
            System.nanoTime() - lastNativeFrameNanos < NATIVE_FRAME_GRACE_PERIOD_NANOS
        val reportedLoopActive = engineInitialized &&
            PlatformGameMainDispatcherPump.isNativeLoopActive() == true
        nativeLoopActive = reportedLoopActive || recentNativeFrame
        if (!nativeLoopActive) {
            nativeThread = null
            handoffThread = null
        }
        return nativeLoopActive
    }

    private fun drainTasksLocked(includeUpdateTasks: Boolean = true, stopAtHandoff: Boolean = false) {
        if (includeUpdateTasks) {
            for (task in updateTasks) {
                if (stopAtHandoff && nativeLoopActive) return
                task.run()
            }
        }
        while (true) {
            if (stopAtHandoff && nativeLoopActive) return
            val task = taskQueue.poll() ?: break
            task.run()
        }
    }

    fun addUpdateTask(task: Runnable) {
        updateTasks.add(task)
    }
    fun removeUpdateTask(task: Runnable) {
        updateTasks.remove(task)
    }

    private const val COOPERATIVE_PUMP_DELAY_MILLIS = 1L
    private const val NATIVE_FRAME_GRACE_PERIOD_NANOS = 1_000_000_000L
}

internal expect object PlatformGameMainDispatcherPump {
    fun start()
    fun stop()
    fun isRunning(): Boolean
    fun isPumpThread(): Boolean
    fun isNativeLoopActive(): Boolean?
}

val GameScope = CoroutineScope(GameMainDispatcher + SupervisorJob())
