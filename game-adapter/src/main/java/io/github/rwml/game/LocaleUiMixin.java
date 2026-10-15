/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.game;

import kim.der.asm.api.annotation.AsmInject;
import kim.der.asm.api.annotation.AsmMixin;

/** Keeps the selected UI language when the engine reloads its own translation bundle. */
@AsmMixin("com/corrodinggames/rts/gameFramework/localization/LocaleEngine")
public final class LocaleUiMixin {
    private LocaleUiMixin() { }

    @AsmInject(method = "reloadBundle")
    public static void beforeReload() {
        GameUiBridge.beforeLocaleReload();
    }
}
