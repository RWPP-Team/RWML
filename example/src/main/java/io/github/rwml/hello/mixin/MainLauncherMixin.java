/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.hello.mixin;

import com.corrodinggames.rts.java.MainLauncher;
import io.github.rwml.api.Rwml;
import kim.der.asm.api.annotation.AsmInject;
import kim.der.asm.api.annotation.AsmMixin;

@AsmMixin("com/corrodinggames/rts/java/MainLauncher")
public class MainLauncherMixin {
    @AsmInject(method = "parseArguments")
    public static void parseArguments() {
        int handles = Rwml.get().manager().handles().size();
        MainLauncher.gameTitle = "Rusted Warfare [RWML - " + handles + " loaded]";
    }
}
