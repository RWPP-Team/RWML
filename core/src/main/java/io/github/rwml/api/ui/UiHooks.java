/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.ui;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Typed, process-local notifications. A screen closes its subscription when it leaves. */
public final class UiHooks {

    public static final Topic<Void> EXTENSIONS_CHANGED = new Topic<>();
    public static final Topic<Void> LANGUAGE_CHANGED = new Topic<>();
    public static final Topic<Void> GAME_CHANGED = new Topic<>();
    public static final Topic<Void> SERVERS_CHANGED = new Topic<>();

    /** Extensions may declare their own topics; topic identity keeps their payloads separate. */
    public static final class Topic<T> {
    }

    private final ConcurrentHashMap<Topic<?>, CopyOnWriteArrayList<Consumer<?>>> listeners =
        new ConcurrentHashMap<>();
    private final Consumer<RuntimeException> failures;

    public UiHooks(Consumer<RuntimeException> failures) {
        this.failures = failures;
    }

    /** Delivery uses the supplied UI executor, never an implicit platform thread. */
    public <T> AutoCloseable subscribe(Topic<T> topic, Executor executor, Consumer<T> listener) {
        AtomicBoolean active = new AtomicBoolean(true);
        // Check again on the executor: a queued callback must not revive a closed screen.
        Consumer<T> queued = value -> executor.execute(() -> {
            if (active.get()) {
                try { listener.accept(value); }
                catch (RuntimeException failure) { failures.accept(failure); }
            }
        });
        listeners.compute(topic, (key, entries) -> {
            if (entries == null) { entries = new CopyOnWriteArrayList<>(); }
            entries.add(queued);
            return entries;
        });
        return () -> {
            active.set(false);
            listeners.computeIfPresent(topic, (key, entries) -> {
                entries.remove(queued);
                return entries.isEmpty() ? null : entries;
            });
        };
    }

    @SuppressWarnings("unchecked")
    public <T> void publish(Topic<T> topic, T value) {
        CopyOnWriteArrayList<Consumer<?>> entries = listeners.get(topic);
        if (entries == null) {
            return;
        }
        for (Consumer<?> listener : entries) {
            try {
                ((Consumer<T>) listener).accept(value);
            } catch (RuntimeException failure) {
                failures.accept(failure);
            }
        }
    }
}
