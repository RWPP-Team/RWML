/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.game;

import com.corrodinggames.rts.gameFramework.GameEngine;
import com.corrodinggames.rts.gameFramework.localization.LocaleEngine;

import io.github.rwml.api.Rwml;
import io.github.rwml.api.RwmlRuntime;
import io.github.rwml.api.ui.GameSettings;
import io.github.rwml.api.ui.GameUi;
import io.github.rwml.api.ui.UiHooks;
import io.github.rwml.api.ui.UiServices;
import io.github.rwml.core.PropertySettings;

import java.util.Locale;

/** The game adapter belongs to the host; builtin screens only receive portable data. */
public final class GameUiBridge implements GameUi {

    public static void install(UiServices ui) {
        if (ui.find(GameUi.class) != null) { return; }
        GameUiBridge bridge = new GameUiBridge();
        ui.register(GameUi.class, bridge);
        ui.register(GameSettings.class, new PropertySettings(() -> {
            GameEngine engine = GameEngine.getInstance();
            return engine == null ? null : engine.settings;
        }));
        ui.hooks().subscribe(UiHooks.LANGUAGE_CHANGED, Runnable::run, ignored ->
            bridge.language(ui.messages().language()));
        bridge.language(ui.messages().language());
        ui.hooks().publish(UiHooks.GAME_CHANGED, null);
    }

    @Override
    public Snapshot snapshot() {
        GameEngine engine = GameEngine.getInstance();
        if (engine == null) { return Snapshot.UNAVAILABLE; }
        String path = engine.currentMapPath;
        boolean resume = engine.gameLoopActive && !engine.gameEnded
            && (path == null || !path.startsWith("maps/menu_background/"));
        return new Snapshot(true, engine.demoVersion, resume, engine.getVersion());
    }

    @Override
    public String text(String key) {
        try { return LocaleEngine.getString(key); }
        catch (java.util.MissingResourceException missing) { return null; }
    }

    @Override
    public String mapName(String displayName) {
        return LocaleEngine.b(displayName);
    }

    @Override
    public void language(String tag) {
        applyLanguage(tag);
        if (GameEngine.getInstance() != null) { LocaleEngine.reloadBundle(); }
    }

    /** Called at the engine's own reload boundary, after its settings have been initialized. */
    public static void beforeLocaleReload() {
        RwmlRuntime runtime = Rwml.get();
        if (runtime != null) { applyLanguage(runtime.ui().messages().language()); }
    }

    private static void applyLanguage(String tag) {
        String code = "system".equals(tag) ? null : Locale.forLanguageTag(tag).getLanguage();
        LocaleEngine.languageOverride = code;
        GameEngine engine = GameEngine.getInstance();
        if (engine != null && engine.settings != null) {
            engine.settings.forceEnglish = "en".equals(code);
            engine.settings.overrideLanguageCode = code;
        }
    }
}
