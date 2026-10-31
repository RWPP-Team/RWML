/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.game;

import java.nio.file.Path;
import java.util.List;

/**
 * {@link GameStage} 运行时接收的上下文。
 *
 * <p>此接口刻意保持精简，不包含任何游戏类型。RWML 核心在不依赖游戏类的情况下编译，且所有扩展都必须兼容两个宿主，
 * 因此阶段应通过 {@link #platform()} 获取平台对象，确认平台后再进行类型转换。一次启动流程中的所有阶段都会收到同一对象。</p>
 */
public interface GameContext {

    /**
     * 宿主的平台对象。
     *
     * <p>在 Android 上，这是应用的 {@code android.content.Context}，也是游戏入口点所需的对象。桌面端则为运行中的
     * {@link io.github.rwml.api.RwmlRuntime}。该对象不会为 null，其类型仅对提供它的平台已知。</p>
     *
     * @return 平台句柄，不为 null
     */
    Object platform();

    /**
     * 启动进程时传入的参数，顺序与传入时一致。
     *
     * @return 不可变的参数列表，不为 null
     */
    List<String> arguments();

    /**
     * 游戏读取数据和写入设置的目录。
     *
     * <p>桌面端为存放游戏 jar 的目录。Android 上则为运行时私有目录，因为打包资源只读，而游戏数据存放在应用之外。</p>
     *
     * @return 游戏目录，不为 null
     */
    Path directory();

    /** 写入一条描述阶段当前操作的信息。 */
    void log(String message);

    /**
     * 为正在运行的阶段报告一条子进度信息。
     *
     * <p>例如引擎自身的加载消息：如果某个平台在一个阶段内执行了多个启动操作，就可以转发引擎运行时输出的每条消息，
     * 让加载界面显示真实步骤，而非只显示阶段的一行概述。持有此上下文的启动流程会把消息转发给启动时提供的监听器，
     * 并在前面加上阶段名称。</p>
     *
     * <p>默认实现不执行任何操作。阶段没有更细的进度时无需特意说明；在启动流程之外创建的上下文也没有可接收消息的目标。</p>
     *
     * @param message 要报告的信息
     */
    default void subProgress(String message) {
    }
}
