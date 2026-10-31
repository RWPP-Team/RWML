/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.ui;

import io.github.rwml.api.i18n.Messages;
import io.github.rwml.api.extensions.ExtensionLibrary;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 供界面共享的数据和功能，在加载任何游戏类之前即可使用。 */
public final class UiServices {

    private final Messages messages;
    private final ExtensionLibrary extensions;
    private final UiHooks hooks;
    private final ConcurrentHashMap<Class<?>, Object> capabilities = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Consumer<UiHost>> screens = new ConcurrentHashMap<>();

    public UiServices(Messages messages, ExtensionLibrary extensions, UiHooks hooks) {
        this.messages = messages;
        this.extensions = extensions;
        this.hooks = hooks;
    }

    public Messages messages() { return messages; }
    public ExtensionLibrary extensions() { return extensions; }
    public UiHooks hooks() { return hooks; }

    /** 宿主和扩展可注册类型化服务，无需向 core 引入平台类型。 */
    public <T> AutoCloseable register(Class<T> type, T service) {
        type.cast(service);
        if (capabilities.putIfAbsent(type, service) != null) {
            throw new IllegalStateException("UI service already registered: " + type.getName());
        }
        return () -> capabilities.remove(type, service);
    }

    public <T> T find(Class<T> type) {
        return type.cast(capabilities.get(type));
    }

    public GameUi.Snapshot game() {
        GameUi game = find(GameUi.class);
        return game == null ? GameUi.Snapshot.UNAVAILABLE : game.snapshot();
    }

    /** 添加带命名空间的界面，无需让平台宿主依赖扩展类。 */
    public AutoCloseable registerScreen(String name, Executor executor, Consumer<UiHost> screen) {
        if (name == null || name.trim().isEmpty()
            || java.util.Arrays.asList("game", "main-menu", "extensions", "launcher").contains(name)) {
            throw new IllegalArgumentException("Reserved or empty screen name: " + name);
        }
        AtomicBoolean active = new AtomicBoolean(true);
        Consumer<UiHost> route = host -> executor.execute(() -> {
            if (active.get()) { screen.accept(host); }
        });
        if (screens.putIfAbsent(name, route) != null) {
            throw new IllegalStateException("Screen already registered: " + name);
        }
        return () -> { active.set(false); screens.remove(name, route); };
    }

    /** 平台宿主映射标准界面名称前，先检查扩展路由。 */
    public boolean openRegisteredScreen(String name, UiHost host) {
        Consumer<UiHost> screen = name == null ? null : screens.get(name);
        if (screen == null) { return false; }
        screen.accept(host);
        return true;
    }
}
