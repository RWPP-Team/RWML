/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.ui;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 进程内的类型化通知；界面退出时应关闭订阅。 */
public final class UiHooks {

    public static final Topic<Void> EXTENSIONS_CHANGED = new Topic<>();
    public static final Topic<Void> LANGUAGE_CHANGED = new Topic<>();
    public static final Topic<Void> GAME_CHANGED = new Topic<>();
    public static final Topic<Void> SERVERS_CHANGED = new Topic<>();

    /** 扩展可以声明自己的主题；主题标识用于隔离各自的数据。 */
    public static final class Topic<T> {
    }

    private final ConcurrentHashMap<Topic<?>, CopyOnWriteArrayList<Consumer<?>>> listeners =
        new ConcurrentHashMap<>();
    private final Consumer<RuntimeException> failures;

    public UiHooks(Consumer<RuntimeException> failures) {
        this.failures = failures;
    }

    /** 通知通过提供的 UI 执行器派发，不隐式使用平台线程。 */
    public <T> AutoCloseable subscribe(Topic<T> topic, Executor executor, Consumer<T> listener) {
        AtomicBoolean active = new AtomicBoolean(true);
        // 在执行器中再次检查：排队中的回调不能重新激活已关闭的界面。
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
