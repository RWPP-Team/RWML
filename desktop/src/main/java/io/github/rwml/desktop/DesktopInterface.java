/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.desktop;

import com.corrodinggames.librocket.CommonLibRocket;
import com.corrodinggames.rts.gameFramework.GameEngine;
import com.corrodinggames.rts.gameFramework.network.MasterServerConnector;
import com.corrodinggames.rts.java.DesktopHttpClientFactory;
import com.corrodinggames.rts.java.JavaCanvas;

import io.github.rwml.api.game.GameBoot;
import io.github.rwml.api.game.GameContext;
import io.github.rwml.bootstrap.GameEntry;
import io.github.rwml.game.GameUiBridge;
import io.github.rwml.api.RwmlRuntime;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The desktop's half of bringing the game up.
 *
 * <p>It lives in the desktop module because the desktop build is Slick, LWJGL and libRocket, none of
 * which is portable. The core knows the shape of a boot; what the desktop puts in its steps is this
 * class.</p>
 *
 * <p>Most of the launcher's steps can only be taken from inside the launcher: a process holds one GUI
 * engine, its helpers are package private, and the engine is created with a {@code Context} only the
 * launcher can build. What is left is what this class does - the platform choices the engine reads
 * from public statics, and the hand-over. The choices are idempotent, because the launcher assigns
 * the same values on its first frame.</p>
 */
public final class DesktopInterface {

    /** The document fonts the desktop interface needs; the list is the launcher's. */
    static final List<String> DOCUMENT_FONTS = Collections.unmodifiableList(Arrays.asList(
        "font/Delicious-Roman.otf",
        "font/Delicious-Italic.otf",
        "font/Delicious-Bold.otf",
        "font/Delicious-BoldItalic.otf",
        "font/Roboto-Regular.ttf",
        "font/Roboto-Bold.ttf",
        "font/NotoSansCJKsc-Regular.otf",
        "font/DroidSansFallback.ttf"));

    /** Fonts that also carry a family name, which is how the interface asks for them by name. */
    private static final String FALLBACK_FONT = "font/DroidSansFallback.ttf";
    private static final String FALLBACK_FAMILY = "fallback";

    private DesktopInterface() {
    }

    /** @param boot the boot to contribute to */
    static void contribute(GameBoot boot) {
        boot.add("desktop platform", DesktopInterface::choosePlatform);
        boot.add(GameBoot.ENGINE, DesktopInterface::handOverToTheGame);
    }

    /** States the desktop's choices through the statics the engine reads; the launcher sets them too. */
    static void choosePlatform(GameContext context) {
        GameEngine.isDesktopPlatform = true;
        GameEngine.isDesktopCanvas = true;
        GameEngine.graphicsCanvasClass = JavaCanvas.class;
        MasterServerConnector.httpClientFactory = new DesktopHttpClientFactory();
        // Registration follows extension loading and platform selection. Resolving the game earlier
        // would define its classes before user mixins were registered.
        GameUiBridge.install(((RwmlRuntime) context.platform()).ui());
        context.log("The desktop draws through " + JavaCanvas.class.getName() + " (Slick and LWJGL).");
    }

    /**
     * Hands the process over to the game's own launcher, which creates the window, the GUI engine, the
     * audio stack and the {@code Context} and then loads everything. Reached by reflection, through
     * the constant the mappings produce.
     */
    static void handOverToTheGame(GameContext context) throws Exception {
        context.log("Handing over to " + GameEntry.GAME_MAIN + ".");
        Class<?> launcher = Class.forName(GameEntry.GAME_MAIN);
        launcher.getMethod("main", String[].class)
            .invoke(null, (Object) context.arguments().toArray(new String[0]));
    }

    /** @param libRocket the interface engine the fonts are loaded into */
    static void loadFonts(CommonLibRocket libRocket) {
        if (libRocket == null) {
            return;
        }
        for (String font : DOCUMENT_FONTS) {
            if (FALLBACK_FONT.equals(font)) {
                libRocket.loadFont(font, FALLBACK_FAMILY);
            } else {
                libRocket.loadFont(font);
            }
        }
    }
}
