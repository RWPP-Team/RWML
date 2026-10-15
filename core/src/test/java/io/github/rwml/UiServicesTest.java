/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml;

import io.github.rwml.api.i18n.Messages;
import io.github.rwml.api.ui.UiHooks;
import io.github.rwml.api.ui.UiHost;
import io.github.rwml.api.ui.UiServices;
import io.github.rwml.core.PropertySettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class UiServicesTest {
    @TempDir Path directory;

    @Test
    void utf8LanguageSelectionSurvivesRestartAndExtensionsHaveEnglishFallback() throws Exception {
        UiHooks hooks = new UiHooks(failure -> fail(failure));
        Messages messages = new Messages(directory, hooks);
        AtomicInteger changes = new AtomicInteger();
        hooks.subscribe(UiHooks.LANGUAGE_CHANGED, Runnable::run, ignored -> changes.incrementAndGet());
        messages.language("zh");
        assertEquals("扩展管理", messages.text("extensions.title"));
        assertEquals("无迷雾", messages.label("No Fog"));
        assertEquals("Player supplied name", messages.label("Player supplied name"));
        assertEquals("已加载 2 个 · 已禁用 1 个 · 失败 0 个", messages.text("launcher.summary", 2, 1, 0));
        Messages next = new Messages(directory, hooks);
        assertEquals("zh", next.language());
        next.language("fr");
        assertEquals("Extensions", next.text("extensions.title"));
        try (AutoCloseable translation = next.register((locale, key) ->
            key.equals("example.greeting") ? "Bonjour" : null)) {
            assertEquals("Bonjour", next.text("example.greeting"));
        }
        assertEquals("example.greeting", next.text("example.greeting"));
        try (AutoCloseable translation = next.register((locale, key) ->
            locale.getLanguage().equals("en") && key.equals("example.fallback") ? "English fallback" : null)) {
            assertEquals("English fallback", next.text("example.fallback"));
        }
        assertEquals(2, changes.get());
        assertThrows(IllegalArgumentException.class, () -> next.language("!"));
    }

    @Test
    void everyBundledUiMessageHasChineseAndEnglishText() throws Exception {
        Properties english = bundle("en");
        Properties chinese = bundle("zh");
        assertEquals(english.keySet(), chinese.keySet());
        assertTrue(english.size() > 40);
        for (Object key : english.keySet()) {
            assertFalse(english.getProperty(key.toString()).trim().isEmpty(), key.toString());
            assertFalse(chinese.getProperty(key.toString()).trim().isEmpty(), key.toString());
        }
    }

    @Test
    void aClosedScreenReceivesNoQueuedCallbacksAndBrokenListenersDoNotStopOthers() throws Exception {
        AtomicInteger failures = new AtomicInteger();
        UiHooks hooks = new UiHooks(failure -> failures.incrementAndGet());
        UiHooks.Topic<String> topic = new UiHooks.Topic<>();
        List<Runnable> pending = new ArrayList<>();
        List<String> received = new ArrayList<>();
        AutoCloseable screen = hooks.subscribe(topic, pending::add, received::add);
        hooks.publish(topic, "late");
        screen.close();
        pending.forEach(Runnable::run);
        assertTrue(received.isEmpty());
        hooks.subscribe(topic, Runnable::run, value -> { throw new IllegalStateException(value); });
        hooks.subscribe(topic, Runnable::run, received::add);
        hooks.publish(topic, "visible");
        assertEquals(1, failures.get());
        assertEquals(List.of("visible"), received);
    }

    @Test
    void settingsAdapterPreservesTypesAndRejectsStaticOrObjectProperties() {
        Settings original = new Settings();
        PropertySettings settings = new PropertySettings(() -> original);
        assertEquals(7, settings.get("volume", Integer.class));
        settings.set("volume", 10);
        settings.save();
        assertEquals(10, original.volume);
        assertTrue(original.saved);
        assertThrows(ClassCastException.class, () -> settings.get("volume", String.class));
        assertThrows(IllegalArgumentException.class, () -> settings.set("global", 1));
        assertThrows(IllegalArgumentException.class, () -> settings.get("object", Object.class));
        assertThrows(IllegalStateException.class, () -> new PropertySettings(() -> null).save());
    }

    public static final class Settings {
        public int volume = 7;
        public static int global;
        public Object object = new Object();
        public boolean saved;
        public void save() { saved = true; }
    }

    @Test
    void screenRoutesAndTypedServicesCanBeReleasedWithoutTouchingPlatformObjects() throws Exception {
        UiHooks hooks = new UiHooks(failure -> fail(failure));
        UiServices ui = new UiServices(new Messages(directory, hooks), null, hooks);
        List<Runnable> queued = new ArrayList<>();
        AtomicInteger opened = new AtomicInteger();
        UiHost host = new UiHost() {
            public Object platform() { return this; }
            public List<String> arguments() { return List.of(); }
            public UiServices services() { return ui; }
            public void openScreen(String screen) { ui.openRegisteredScreen(screen, this); }
        };
        AutoCloseable registration = ui.registerScreen("example.settings", queued::add, ignored -> opened.incrementAndGet());
        assertTrue(ui.openRegisteredScreen("example.settings", host));
        assertEquals(1, queued.size());
        queued.remove(0).run();
        assertEquals(1, opened.get());
        host.openScreen("example.settings");
        registration.close();
        queued.forEach(Runnable::run);
        assertEquals(1, opened.get());
        assertFalse(ui.openRegisteredScreen("example.settings", host));
        assertThrows(IllegalArgumentException.class, () -> ui.registerScreen("extensions", Runnable::run, ignored -> { }));
        try (AutoCloseable service = ui.register(String.class, "capability")) {
            assertEquals("capability", ui.find(String.class));
            assertThrows(IllegalStateException.class, () -> ui.register(String.class, "duplicate"));
        }
        assertNull(ui.find(String.class));
    }

    private static Properties bundle(String language) throws Exception {
        Properties result = new Properties();
        try (InputStreamReader reader = new InputStreamReader(
            Messages.class.getResourceAsStream("/io/github/rwml/i18n/" + language + ".properties"),
            StandardCharsets.UTF_8)) { result.load(reader); }
        return result;
    }
}
