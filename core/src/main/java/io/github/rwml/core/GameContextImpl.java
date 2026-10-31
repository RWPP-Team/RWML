/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core;

import io.github.rwml.api.game.GameContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * 宿主传递给启动流程的 {@link GameContext}。
 *
 * <p>它只是一个数据载体：平台句柄、参数和目录均由创建它的宿主提供，日志消息会交给宿主自己的日志系统。此处不依赖游戏，
 * 因而两个宿主可以共用此实现。</p>
 */
public final class GameContextImpl implements GameContext {

    private final Object platform;
    private final List<String> arguments;
    private final Path directory;
    private final Consumer<String> log;

    public GameContextImpl(Object platform, String[] arguments, Path directory, Consumer<String> log) {
        if (platform == null) {
            throw new IllegalArgumentException("A game context needs a platform handle");
        }
        if (directory == null) {
            throw new IllegalArgumentException("A game context needs a directory");
        }
        this.platform = platform;
        String[] copy = arguments == null ? new String[0] : arguments.clone();
        List<String> list = new ArrayList<>(copy.length);
        Collections.addAll(list, copy);
        this.arguments = Collections.unmodifiableList(list);
        this.directory = directory;
        this.log = log;
    }

    @Override
    public Object platform() {
        return platform;
    }

    @Override
    public List<String> arguments() {
        return arguments;
    }

    @Override
    public Path directory() {
        return directory;
    }

    @Override
    public void log(String message) {
        if (log != null) {
            log.accept(message);
        }
    }
}
