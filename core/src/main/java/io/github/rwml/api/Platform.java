/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api;

/**
 * 运行时所在的平台。
 *
 * <p>扩展有时只需在某个平台执行操作，因此需要此枚举。桌面界面依赖 libRocket、文档字体以及
 * 游戏启动器创建的窗口；Android 上没有这些组件，同一扩展就必须避免使用它们。检查类名也能回答
 * 平台问题，但那只是偶然奏效，无法可靠表达平台差异。</p>
 */
public enum Platform {

    /** JVM：由游戏自身启动器启动的桌面版。 */
    DESKTOP,

    /** Android 应用：RWML 在应用进程内自行启动游戏引擎。 */
    ANDROID
}
