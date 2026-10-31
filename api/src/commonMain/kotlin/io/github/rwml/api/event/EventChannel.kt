/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.event

import io.github.rwml.api.logging.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

private val log = logger()

/**
 * 事件通道.
 *
 * 事件通道是监听事件的入口, 但不负责广播事件. 要广播事件, 使用 [Event.broadcast] 或 [GlobalEventChannel.broadcast].
 *
 * ### 创建事件监听
 * - [EventChannel.receive] 监听并等待直到一个事件返回.
 * - [EventChannel.subscribeAlways] 创建一个总是监听事件的事件监听器.
 * - [EventChannel.subscribeOnce] 创建一个只监听单次的事件监听器.
 */
@Suppress("PropertyName")
open class EventChannel<T : Event>(val coroutineScope: CoroutineScope) : CoroutineScope by coroutineScope {
    internal open val _events = MutableSharedFlow<T>().apply {
        val eventFlow = onEach { event ->
            launch {
                try {
                    runListener(event)
                } catch (failure: Throwable) {
                    reportFailure(failure)
                    if (failure is CancellationException) throw failure
                } finally {
                    event.job.complete()
                }
            }
        }.catch {
            reportFailure(it)
        }
        this@EventChannel.launch(start = CoroutineStart.UNDISPATCHED) {
            eventFlow.collect {}
        }
    }

    private val _listeners = buildMap<EventPriority, CopyOnWriteArraySet<Listener<T>>>
        { EventPriority.all.forEach { this[it] = CopyOnWriteArraySet() } }

    var errorHandler: (Throwable) -> Unit = { log.error(it.stackTraceToString()) }

    internal fun reportFailure(failure: Throwable) {
        if (failure is CancellationException) return
        try {
            errorHandler(failure)
        } catch (handlerFailure: Throwable) {
            log.error(handlerFailure.stackTraceToString())
        }
    }

    /**
     * 监听事件，将等待并直到有一个事件返回，类似[Channel.receive].
     *
     * @param timeout 设置该协程超时所需的时间，小于0则不设置，超时则返回null.
     * @param coroutineContext 在 [coroutineContext] 的基础上, 给事件监听协程的额外的 [CoroutineContext]
     */
    suspend fun receive(timeout: Long = -1, coroutineContext: CoroutineContext = EmptyCoroutineContext): T? =
        withContext(this.coroutineContext) {
            var result: T? = null
            suspend fun collect() {
                withContext(coroutineContext) {
                    result = _events.produceIn(this).receive()
                }
            }
            if (timeout > 0) withTimeoutOrNull(timeout) { collect() } else collect()
            result
        }

    /**
     * 创建一个事件监听器, 监听事件通道中所有 [T].
     * 每当 [事件广播][Event.broadcast] 时, [handler] 都会被执行.
     *
     * 可在任意时候通过 [Listener.complete] 来主动停止监听.
     *
     * @param coroutineContext 在 [coroutineContext] 的基础上, 给事件监听协程的额外的 [CoroutineContext]
     * @param priority 处理优先级, 优先级高的先执行
     *
     * @return 监听器实例. 此监听器已经注册到指定事件上, 在事件广播时将会调用 [handler]
     *
     * @see subscribe 获取更多说明
     */

    fun subscribeAlways(
        coroutineContext: CoroutineContext = EmptyCoroutineContext,
        priority: EventPriority = EventPriority.NORMAL,
        handler: suspend (T) -> Unit,
    ): Listener<T> = registerListener(coroutineContext, priority, ListeningStatus.LISTENING, handler)

    /**
     * 创建一个事件监听器, 监听事件通道中所有 [T], 只监听一次.
     * 当 [事件广播][Event.broadcast] 时, [handler] 会被执行.
     *
     * 可在任意时候通过 [Listener.complete] 来主动停止监听.
     *
     * @param coroutineContext 在 [coroutineContext] 的基础上, 给事件监听协程的额外的 [CoroutineContext]
     * @param priority 处理优先级, 优先级高的先执行
     *
     * @see subscribe 获取更多说明
     */

    fun subscribeOnce(
        coroutineContext: CoroutineContext = EmptyCoroutineContext,
        priority: EventPriority = EventPriority.NORMAL,
        handler: suspend (T) -> Unit,
    ): Listener<T> = registerListener(coroutineContext, priority, ListeningStatus.STOPPED, handler)

    fun registerListener(
        coroutineContext: CoroutineContext,
        priority: EventPriority,
        status: ListeningStatus,
        handler: suspend (T) -> Unit,
    ): Listener<T> {
        val listener = object : Listener<T>() {
            override val coroutineContext: CoroutineContext
                get() = this + coroutineContext
            override val priority: EventPriority
                get() = priority
            override suspend fun onEvent(event: T): ListeningStatus {
                handler(event)
                return status
            }
        }
        _listeners[priority]!!.add(listener)
        return listener
    }

    fun unregisterListener(listener: Listener<T>) {
        _listeners[listener.priority]!!.remove(listener)
    }

    protected suspend fun runListener(event: T) = withContext(coroutineContext) {
        var status: ListeningStatus
        loop@ for (priority in EventPriority.all) {
            if (event.isIntercepted) break@loop
            val list = _listeners[priority]!!
            for (item in list.iterator()) {
                if ((event as AbstractEvent)._cancelled) break@loop
                withContext(item.coroutineContext) {
                    status = item.onEvent(event)
                }
                if (!event.shouldBroadcast) {
                    (event as AbstractEvent)._cancelled = true
                }
                if (status == ListeningStatus.STOPPED) list.remove(item)
            }
        }
    }
}
