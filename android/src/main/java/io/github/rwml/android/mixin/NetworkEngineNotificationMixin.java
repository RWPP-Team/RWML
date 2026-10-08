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
 * Makes the multiplayer notifications creatable on the Android this app runs on.
 *
 * <p>The game asks for a pending intent with the update flag alone, which was enough when it was
 * built. From Android 12 on, an app whose target level is 31 or above is refused that call with
 * {@code IllegalArgumentException}: a pending intent has to say whether the receiver may fill it in,
 * and the flag that says it may not did not exist when the call was written. Nothing in the game
 * hands these intents to a receiver that rewrites them, so the immutable flag is added to whatever
 * the game asked for and the notification is posted as before.</p>
 *
 * <p>The alternative is to lower the app's target level, which would make every other platform
 * behaviour of the last three releases revert with it. One call site needs the flag, so the flag is
 * added there.</p>
 */
@AsmMixin("com/corrodinggames/rts/gameFramework/network/NetworkEngine")
public final class NetworkEngineNotificationMixin {

    /** The game's call, as it appears in the archive the app rewrites. */
    private static final String GET_ACTIVITY =
        "android.app.PendingIntent.getActivity(Landroid/content/Context;ILandroid/content/Intent;I)"
            + "Landroid/app/PendingIntent;";

    /** Whether one of these handlers has said that it is in; they run per notification. */
    private static volatile boolean announced;

    private NetworkEngineNotificationMixin() {
    }

    /** The pending intent behind the notification that says a game is in progress. */
    @Redirect(method = "showGameInProgressNotification", target = GET_ACTIVITY)
    public static PendingIntent immutableForGameInProgress(Context context, int requestCode,
                                                           Intent intent, int flags) {
        announce();
        return PendingIntent.getActivity(context, requestCode, intent, immutable(flags));
    }

    /** The pending intent behind the chat notification. */
    @Redirect(method = "showChatNotification", target = GET_ACTIVITY)
    public static PendingIntent immutableForChat(Context context, int requestCode,
                                                 Intent intent, int flags) {
        announce();
        return PendingIntent.getActivity(context, requestCode, intent, immutable(flags));
    }

    /** Adds the flag that says the receiver may not fill the intent in. */
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
