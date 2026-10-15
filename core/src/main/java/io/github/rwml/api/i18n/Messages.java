/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.i18n;

import io.github.rwml.api.ui.UiHooks;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

/** UTF-8 translations with English fallback, extension sources and a persisted language preference. */
public final class Messages {

    private static final Properties ENGLISH = bundle("en");
    private static final Properties CHINESE = bundle("zh");
    private static final Map<String, String> LABEL_KEYS = labelKeys();

    @FunctionalInterface
    public interface Source {
        String find(Locale locale, String key);
    }

    private final Path preference;
    private final UiHooks hooks;
    private final CopyOnWriteArrayList<Source> sources = new CopyOnWriteArrayList<>();
    private volatile String language;

    public Messages(Path dataDirectory, UiHooks hooks) throws IOException {
        Files.createDirectories(dataDirectory);
        preference = dataDirectory.resolve("language.txt");
        language = Files.isRegularFile(preference)
            ? new String(Files.readAllBytes(preference), StandardCharsets.UTF_8).trim() : "system";
        if (language.isEmpty()) { language = "system"; }
        this.hooks = hooks;
    }

    public String language() { return language; }

    public Locale locale() {
        return "system".equals(language) ? Locale.getDefault() : Locale.forLanguageTag(language);
    }

    /** The preference is committed before listeners see it; diagnostics stay in their original language. */
    public synchronized void language(String tag) throws IOException {
        if (tag == null || (!"system".equals(tag) && Locale.forLanguageTag(tag).getLanguage().isEmpty())) {
            throw new IllegalArgumentException("Invalid language tag: " + tag);
        }
        if (tag.equals(language)) { return; }
        Path temporary = Files.createTempFile(preference.getParent(), "language-", ".tmp");
        try {
            Files.write(temporary, tag.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary, preference, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, preference, StandardCopyOption.REPLACE_EXISTING);
            }
            language = tag;
        } finally { Files.deleteIfExists(temporary); }
        hooks.publish(UiHooks.LANGUAGE_CHANGED, null);
    }

    /** An extension keeps its keys in its own namespace and closes a temporary registration when done. */
    public AutoCloseable register(Source source) {
        sources.add(Objects.requireNonNull(source));
        return () -> sources.remove(source);
    }

    public String text(String key, Object... arguments) {
        Locale locale = locale();
        String value = null;
        for (Source source : sources) {
            value = source.find(locale, key);
            if (value != null) { break; }
        }
        if (value == null && !"en".equals(locale.getLanguage())) {
            for (Source source : sources) {
                value = source.find(Locale.ENGLISH, key);
                if (value != null) { break; }
            }
        }
        if (value == null && "zh".equals(locale.getLanguage())) { value = CHINESE.getProperty(key); }
        if (value == null) { value = ENGLISH.getProperty(key, key); }
        return arguments.length == 0 ? value : new MessageFormat(value, locale).format(arguments);
    }

    /** Adapts a static English layout label without changing the restored layout or its data. */
    public String label(String english) {
        String key = LABEL_KEYS.get(english);
        return key == null ? english : text(key);
    }

    /** Safe labels for an activity restored before a runtime is installed. */
    public static String fallback(String key, Object... arguments) {
        Locale locale = Locale.getDefault();
        String value = "zh".equals(locale.getLanguage()) ? CHINESE.getProperty(key) : null;
        if (value == null) { value = ENGLISH.getProperty(key, key); }
        return arguments.length == 0 ? value : new MessageFormat(value, locale).format(arguments);
    }

    private static Map<String, String> labelKeys() {
        Map<String, String> keys = new LinkedHashMap<>();
        List<String> names = new ArrayList<>(ENGLISH.stringPropertyNames());
        Collections.sort(names);
        for (String name : names) { keys.putIfAbsent(ENGLISH.getProperty(name), name); }
        return Collections.unmodifiableMap(keys);
    }

    private static Properties bundle(String language) {
        Properties values = new Properties();
        try (InputStream input = Messages.class.getResourceAsStream("/io/github/rwml/i18n/" + language + ".properties")) {
            if (input == null) { throw new IllegalStateException("Missing UI translations: " + language); }
            values.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read UI translations", failure);
        }
        return values;
    }
}
