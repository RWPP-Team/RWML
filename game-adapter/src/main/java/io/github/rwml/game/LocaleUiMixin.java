/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.game;

import kim.der.asm.api.annotation.AsmInject;
import kim.der.asm.api.annotation.AsmMixin;
import kim.der.asm.api.annotation.InjectionPoint;

/** 引擎重新加载自身翻译包时保留已选的界面语言。 */
@AsmMixin("com/corrodinggames/rts/gameFramework/localization/LocaleEngine")
public final class LocaleUiMixin {
    private LocaleUiMixin() { }

    @AsmInject(method = "reloadBundle")
    public static void beforeReload() {
        GameUiBridge.beforeLocaleReload();
    }

    @AsmInject(method = "reloadBundle", target = InjectionPoint.TAIL, require = 1)
    public static void afterReload() {
        GameUiBridge.afterLocaleReload();
    }
}
