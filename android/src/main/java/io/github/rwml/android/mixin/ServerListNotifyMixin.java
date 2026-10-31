/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.mixin;

import io.github.rwml.android.HostLog;
import io.github.rwml.api.Rwml;
import io.github.rwml.api.ui.UiHooks;

import kim.der.asm.api.annotation.AsmInject;
import kim.der.asm.api.annotation.AsmMixin;

/**
 * 将引擎发出的“主服务器上的游戏列表已更改”通知转发到游戏大厅。
 *
 * <p>引擎会保存已发现的游戏列表，并通过调用原游戏服务器列表界面类中的静态方法通知列表发生变化。当前应用运行的归档中该类只是空壳：
 * 实际界面属于桌面版本，而本宿主未携带该界面。因此通知会被丢弃，若大厅在引擎填充列表时已经打开，也不会重新绘制。</p>
 *
 * <p>引擎在添加刚收到通知的游戏，或移除一段时间未发现的游戏时都会发送该通知。如果丢弃通知，大厅就可能继续列出已关闭的游戏，
 * 同时漏掉新开放的游戏。此调用会重定向到大厅，因为该通知原本就是发给大厅的。</p>
 */
@AsmMixin("com/corrodinggames/rts/appFramework/ServerListActivity")
public final class ServerListNotifyMixin {

    /** 标记通知是否已发送；该事件会频繁触发，因此只发送一次。 */
    private static volatile boolean announced;

    private ServerListNotifyMixin() {
    }

    /**
     * 在引擎通知逻辑开头运行，并将通知交给大厅。
     *
     * <p>不会跳过原方法：它在当前平台上不执行任何操作，因此保留原方法，方便游戏后续版本在此加入行为。</p>
     */
    @AsmInject(method = "l")
    public static void whenTheGameListChanges() {
        announce();
        if (Rwml.get() != null) {
            Rwml.get().ui().hooks().publish(UiHooks.SERVERS_CHANGED, null);
        }
    }

    private static void announce() {
        if (announced) {
            return;
        }
        announced = true;
        HostLog.add("mixin      ServerListNotifyMixin is in: the lobby hears about changed games");
    }
}
