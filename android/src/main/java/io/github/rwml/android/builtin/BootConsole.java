/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import io.github.rwml.android.HostLog;

/**
 * 在启动画面上显示游戏自身的控制台。
 *
 * <p>引擎通过向 {@code System.out} 输出内容来报告进度；{@link HostLog} 会分流该输出，并将每条完整日志行发送给
 * 请求显示它们的界面。此类代表启动画面发起该请求，也会安装分流器，因为启动画面可能是进程中首个需要控制台输出的界面。</p>
 *
 * <p>此处不会显示宿主自身的内容，只会显示游戏输出的日志行。</p>
 */
final class BootConsole {

    private BootConsole() {
    }

    /** 在加载信息行中显示游戏控制台输出。 */
    static void install() {
        HostLog.install();
        HostLog.sink(LoadingStatus::line);
    }
}
