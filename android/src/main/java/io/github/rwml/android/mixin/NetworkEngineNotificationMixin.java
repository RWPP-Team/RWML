/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.mixin;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import io.github.rwml.android.HostLog;

import kim.der.asm.api.annotation.AsmMixin;
import kim.der.asm.api.annotation.Redirect;

/**
 * 确保此应用运行所用的 Android 系统可以创建多人游戏通知。
 *
 * <p>游戏只使用更新标志请求 pending intent；在构建时这样做是足够的。从 Android 12 起，target level 为 31 或更高的应用会因
 * 该调用收到 {@code IllegalArgumentException}：pending intent 必须声明接收方是否可以修改它，而用于声明不可修改的标志在编写
 * 该调用时尚不存在。游戏不会将这些 intent 交给会修改它们的接收方，因此在游戏请求的标志上附加不可变标志后，仍可按原样发布通知。</p>
 *
 * <p>另一种做法是降低应用的 target level，但这会导致其他平台行为一起回退到三个版本之前。这里只需修改一个调用点，因此直接在该处
 * 添加标志。</p>
 */
@AsmMixin("com/corrodinggames/rts/gameFramework/network/NetworkEngine")
public final class NetworkEngineNotificationMixin {

    /** 游戏中的方法调用形式，与应用重写的归档文件保持一致。 */
    private static final String GET_ACTIVITY =
        "android.app.PendingIntent.getActivity(Landroid/content/Context;ILandroid/content/Intent;I)"
            + "Landroid/app/PendingIntent;";

    /** 标记这些处理器是否已执行；每条通知都会触发处理器。 */
    private static volatile boolean announced;

    private NetworkEngineNotificationMixin() {
    }

    /** 表示游戏正在进行的通知所使用的 PendingIntent。 */
    @Redirect(method = "showGameInProgressNotification", target = GET_ACTIVITY)
    public static PendingIntent immutableForGameInProgress(Context context, int requestCode,
                                                           Intent intent, int flags) {
        announce();
        return PendingIntent.getActivity(context, requestCode, intent, immutable(flags));
    }

    /** 聊天通知所使用的 PendingIntent。 */
    @Redirect(method = "showChatNotification", target = GET_ACTIVITY)
    public static PendingIntent immutableForChat(Context context, int requestCode,
                                                 Intent intent, int flags) {
        announce();
        return PendingIntent.getActivity(context, requestCode, intent, immutable(flags));
    }

    /** 添加标记，声明接收方不得补充 Intent 内容。 */
    private static int immutable(int flags) {
        return flags | PendingIntent.FLAG_IMMUTABLE;
    }

    private static void announce() {
        if (announced) {
            return;
        }
        announced = true;
        HostLog.add("mixin      NetworkEngineNotificationMixin is in: pending intents are immutable");
    }
}
