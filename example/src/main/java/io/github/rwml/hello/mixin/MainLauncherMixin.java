package io.github.rwml.hello.mixin;

import com.corrodinggames.rts.java.MainLauncher;
import io.github.rwml.RwmlLauncher;
import kim.der.asm.api.annotation.AsmInject;
import kim.der.asm.api.annotation.AsmMixin;

@AsmMixin("com/corrodinggames/rts/java/MainLauncher")
public class MainLauncherMixin {
    @AsmInject(method = "parseArguments")
    public static void parseArguments() {
        int handles = RwmlLauncher.manager.handles().size();
        MainLauncher.gameTitle = "Rusted Warfare [RWML - " + handles + " loaded]";
    }
}
