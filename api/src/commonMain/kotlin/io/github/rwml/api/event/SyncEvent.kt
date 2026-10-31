/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.event

import io.github.rwml.api.ExtensionContext
import io.github.rwml.api.logging.logger
import java.util.concurrent.atomic.AtomicBoolean

@PublishedApi
internal val syncEventLog = logger()

/** How a synchronous event handles an exception from one listener. */
enum class SyncEventExceptionPolicy {
    /** Propagate the listener exception to the native caller and stop dispatching. */
    PROPAGATE,

    /** Log listener exceptions and continue with the remaining listeners. */
    LOG_AND_CONTINUE,
}

/** Listener priority. Registration order is stable within each priority. */
enum class SyncEventOrder {
    FIRST,
    NORMAL,
    LAST,
}

/** Invokes one listener using the exception policy belonging to its event. */
fun interface SyncEventListenerInvoker<T : Any> {
    operator fun invoke(listener: T, callback: () -> Unit)
}

/**
 * Owns event subscriptions and other closeable registrations for one extension or subsystem.
 * Resources are closed in reverse registration order. Closing is idempotent.
 *
 * When constructed with an extension context, the context owns this owner and closes it when the
 * extension unloads or fails to load.
 */
class EventOwner(context: ExtensionContext? = null) : AutoCloseable {
    private val lock = Any()
    private val resources = mutableListOf<AutoCloseable>()
    private var closed = false

    init {
        context?.own(this)
    }

    /** Adds a resource to this owner and returns it for convenient registration chaining. */
    fun <T : AutoCloseable> own(resource: T): T {
        val closeImmediately = synchronized(lock) {
            if (closed) {
                true
            } else {
                resources.add(resource)
                false
            }
        }
        if (closeImmediately) {
            val failure = try {
                resource.close()
                null
            } catch (closeFailure: Throwable) {
                closeFailure
            }
            throw IllegalStateException(
                "Cannot add a resource to an EventOwner after it has been closed.",
                failure,
            )
        }
        return resource
    }

    internal fun release(resource: AutoCloseable) {
        synchronized(lock) {
            val index = resources.indexOfFirst { it === resource }
            if (index >= 0) resources.removeAt(index)
        }
    }

    override fun close() {
        val toClose = synchronized(lock) {
            if (closed) return
            closed = true
            resources.asReversed().toList().also { resources.clear() }
        }

        var firstFailure: Throwable? = null
        for (resource in toClose) {
            try {
                resource.close()
            } catch (failure: Throwable) {
                val earlierFailure = firstFailure
                if (earlierFailure == null) firstFailure = failure
                else earlierFailure.addSuppressed(failure)
            }
        }
        firstFailure?.let { throw it }
    }
}

/** A closeable registration. A dispatch already using its snapshot may finish after close. */
class EventSubscription internal constructor(
    private val owner: EventOwner?,
    private val closeAction: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    internal val isClosed: Boolean
        get() = closed.get()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            closeAction()
        } finally {
            owner?.release(this)
        }
    }
}

/**
 * A synchronous, typed event with a cached array-snapshot invoker.
 *
 * The invoker is rebuilt only when listeners register or close. Dispatch uses the current
 * immutable listener snapshot; a registration change during a callback affects the next dispatch.
 * No coroutine, flow, reflection, or job is involved in dispatch.
 */
class SyncEvent<T : Any> @PublishedApi internal constructor(
    val name: String,
    val exceptionPolicy: SyncEventExceptionPolicy,
    private val onListenerFailure: (String, Throwable) -> Unit,
    private val createInvoker: (Array<T>, SyncEventListenerInvoker<T>) -> T,
    private val listenerType: Class<T>,
) {
    private val lock = Any()
    private var nextSequence = 0L
    private var entries: List<Entry<T>> = emptyList()

    private val listenerInvoker = SyncEventListenerInvoker<T> { listener, callback ->
        invokeListener(listener, callback)
    }

    @Volatile
    private var snapshot: Snapshot<T> = createSnapshot(toListenerArray(emptyList()))

    /** Cached invoker used by native hooks. Read this value immediately before dispatch. */
    val invoker: T
        get() = snapshot.invoker

    /** Lets expensive observational hooks avoid capturing native state when nobody is listening. */
    val hasListeners: Boolean
        get() = snapshot.listeners.isNotEmpty()

    /** Runs a single callback according to this event's exception policy. */
    fun invokeListener(listener: T, callback: () -> Unit) {
        when (exceptionPolicy) {
            SyncEventExceptionPolicy.PROPAGATE -> callback()
            SyncEventExceptionPolicy.LOG_AND_CONTINUE -> try {
                callback()
            } catch (failure: Exception) {
                try {
                    onListenerFailure(name, failure)
                } catch (reportFailure: Throwable) {
                    syncEventLog.error("Could not report listener failure for synchronous event '$name'.", reportFailure)
                }
            }
        }
    }

    /** Registers a listener; the returned handle must be closed to unregister it. */
    fun register(listener: T, order: SyncEventOrder = SyncEventOrder.NORMAL): EventSubscription =
        registerInternal(owner = null, listener = listener, order = order)

    /** Registers a listener and closes the registration when [owner] closes. */
    fun register(
        owner: EventOwner,
        listener: T,
        order: SyncEventOrder = SyncEventOrder.NORMAL,
    ): EventSubscription = registerInternal(owner = owner, listener = listener, order = order)

    private fun registerInternal(
        owner: EventOwner?,
        listener: T,
        order: SyncEventOrder,
    ): EventSubscription {
        val entry = synchronized(lock) {
            Entry(listener = listener, order = order, sequence = nextSequence++)
        }
        val subscription = EventSubscription(owner) { remove(entry) }
        owner?.own(subscription)

        synchronized(lock) {
            check(!subscription.isClosed) { "Cannot register a listener with a closed EventOwner." }
            val nextEntries = (entries + entry).sortedWith(
                compareBy<Entry<T>> { it.order.ordinal }.thenBy { it.sequence },
            )
            val nextSnapshot = createSnapshot(toListenerArray(nextEntries))
            entries = nextEntries
            snapshot = nextSnapshot
        }
        return subscription
    }

    private fun remove(entry: Entry<T>) {
        synchronized(lock) {
            val nextEntries = entries.filterNot { it === entry }
            if (nextEntries.size == entries.size) return
            val nextSnapshot = createSnapshot(toListenerArray(nextEntries))
            entries = nextEntries
            snapshot = nextSnapshot
        }
    }

    private fun createSnapshot(listeners: Array<T>): Snapshot<T> =
        Snapshot(listeners, createInvoker(listeners, listenerInvoker))

    @Suppress("UNCHECKED_CAST")
    private fun toListenerArray(source: List<Entry<T>>): Array<T> {
        // The factory bridge expects Callback[], not Object[]. Reflection is registration-only.
        val array = java.lang.reflect.Array.newInstance(listenerType, source.size) as Array<T>
        source.forEachIndexed { index, entry -> array[index] = entry.listener }
        return array
    }

    private data class Entry<T : Any>(
        val listener: T,
        val order: SyncEventOrder,
        val sequence: Long,
    )

    private data class Snapshot<T : Any>(
        val listeners: Array<T>,
        val invoker: T,
    )

    companion object {
        /**
         * Creates an observational event. Listener exceptions are logged and later listeners
         * still run; use [decision] when a native operation depends on listener output.
         */
        inline fun <reified T : Any> notification(
            name: String,
            noinline onListenerFailure: (String, Throwable) -> Unit = { eventName, failure ->
                syncEventLog.error("Listener failed for synchronous event '$eventName'.", failure)
            },
            noinline createInvoker: (Array<T>, SyncEventListenerInvoker<T>) -> T,
        ): SyncEvent<T> = SyncEvent(
            name = name,
            exceptionPolicy = SyncEventExceptionPolicy.LOG_AND_CONTINUE,
            onListenerFailure = onListenerFailure,
            createInvoker = createInvoker,
            listenerType = T::class.java,
        )

        /** Creates a decision event. Exceptions propagate to the native caller. */
        inline fun <reified T : Any> decision(
            name: String,
            noinline createInvoker: (Array<T>, SyncEventListenerInvoker<T>) -> T,
        ): SyncEvent<T> = SyncEvent(
            name = name,
            exceptionPolicy = SyncEventExceptionPolicy.PROPAGATE,
            onListenerFailure = { _, _ -> },
            createInvoker = createInvoker,
            listenerType = T::class.java,
        )
    }
}
