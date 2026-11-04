/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.game;

import com.corrodinggames.rts.gameFramework.GameEngine;
import com.corrodinggames.rts.gameFramework.localization.LocaleEngine;

import io.github.rwml.api.Rwml;
import io.github.rwml.api.RwmlRuntime;
import io.github.rwml.api.i18n.Messages;
import io.github.rwml.api.ui.GameSettings;
import io.github.rwml.api.ui.GameUi;
import io.github.rwml.api.ui.UiHooks;
import io.github.rwml.api.ui.UiServices;
import io.github.rwml.core.PropertySettings;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;

/** 游戏适配器属于宿主；内置界面只接收可移植的数据。 */
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
        return LocaleEngine.translateMapName(displayName);
    }

    @Override
    public void language(String tag) {
        applyLanguage(tag);
        if (GameEngine.getInstance() != null) { LocaleEngine.reloadBundle(); }
    }

    /** 在引擎重载前保留用户语言偏好，并暂停 native reader。 */
    public static void beforeLocaleReload() {
        RwmlRuntime runtime = Rwml.get();
        if (runtime != null) {
            Messages messages = runtime.ui().messages();
            messages.clearNativeReader();
            applyLanguage(messages.language());
        }
    }

    /** Wraps the freshly loaded game bundle so host and extension keys resolve natively. */
    public static void afterLocaleReload() {
        RwmlRuntime runtime = Rwml.get();
        if (runtime == null) { return; }
        Messages messages = runtime.ui().messages();
        try {
            Field bundleField = LocaleEngine.class.getDeclaredField("bundle");
            bundleField.setAccessible(true);
            ResourceBundle nativeBundle = (ResourceBundle) bundleField.get(null);
            if (nativeBundle == null) {
                messages.clearNativeReader();
                GameEngine.log("RWML: LocaleEngine bundle is not initialized; using properties translations");
                return;
            }
            ResourceBundle delegate = unwrap(nativeBundle);
            bundleField.set(null, new HostResourceBundle(messages, delegate, selectedLocale()));
            messages.bindNativeReader((key, arguments) -> LocaleEngine.getString(key, arguments));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            messages.clearNativeReader();
            GameEngine.log("RWML: could not install unified locale bundle: " + failure);
        }
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

    private static Locale selectedLocale() {
        GameEngine engine = GameEngine.getInstance();
        if (engine != null && engine.settings != null && engine.settings.forceEnglish) {
            return Locale.ROOT;
        }
        if (LocaleEngine.languageOverride != null) { return new Locale(LocaleEngine.languageOverride); }
        if (engine != null && engine.settings != null) {
            String code = engine.settings.overrideLanguageCode;
            if (code != null && !code.isEmpty()) { return new Locale(code); }
        }
        return LocaleEngine.getCurrentLocale();
    }

    private static ResourceBundle unwrap(ResourceBundle bundle) {
        ResourceBundle current = bundle;
        while (current instanceof HostResourceBundle) {
            current = ((HostResourceBundle) current).delegate;
        }
        return current;
    }

    private static final class HostResourceBundle extends ResourceBundle {
        private final Messages messages;
        private final ResourceBundle delegate;
        private final Locale locale;

        private HostResourceBundle(Messages messages, ResourceBundle delegate, Locale locale) {
            this.messages = messages;
            this.delegate = delegate;
            this.locale = locale;
        }

        @Override
        protected Object handleGetObject(String key) {
            String hostValue = messages.find(locale, key);
            if (hostValue != null) { return hostValue; }
            try { return delegate.getObject(key); }
            catch (MissingResourceException missing) { return null; }
        }

        @Override
        public Enumeration<String> getKeys() {
            return Collections.enumeration(allKeys());
        }

        @Override
        protected Set<String> handleKeySet() {
            return allKeys();
        }

        @Override
        public Locale getLocale() { return locale; }

        private Set<String> allKeys() {
            LinkedHashSet<String> keys = new LinkedHashSet<>(messages.keys(locale));
            if (delegate != null) { keys.addAll(delegate.keySet()); }
            return keys;
        }
    }
}
