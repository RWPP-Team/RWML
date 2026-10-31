/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.mixin;

import io.github.rwml.android.builtin.LobbyServerList;
import kim.der.asm.api.annotation.AsmMixin;
import kim.der.asm.api.annotation.AsmInject;
import kim.der.asm.api.annotation.CallbackInfo;

/** 替换不受管理的线程和计时器，无需从 APK 读取 JVM Mixin 字节码。 */
@AsmMixin("com/corrodinggames/rts/gameFramework/network/MasterServerConnector")
public final class MasterServerListLifecycleMixin {
    @AsmInject(method = "startLoadRequest(Ljava/lang/Runnable;)V", cancellable = true)
    public static void loadOnlyInLobby(CallbackInfo callback, Runnable completion) {
        callback.cancel();
        LobbyServerList.request(completion);
    }
}
