/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** 提供 UTF-8 翻译、英文回退、扩展翻译源及持久化的语言偏好。 */
public final class Messages {

    private static final Properties ENGLISH = bundle("en");
    private static final Properties CHINESE = bundle("zh");
    private static final Map<String, String> LABEL_KEYS = labelKeys();

    @FunctionalInterface
    public interface Source {
        String find(Locale locale, String key);

        /** Keys available from this source for the selected locale. */
        default Set<String> keys(Locale locale) { return Collections.emptySet(); }
    }

    @FunctionalInterface
    public interface NativeReader {
        String text(String key, Object... arguments);
    }

    private final Path preference;
    private final UiHooks hooks;
    private final CopyOnWriteArrayList<Source> sources = new CopyOnWriteArrayList<>();
    private volatile String language;
    private volatile NativeReader nativeReader;

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

    /** 先保存语言偏好，再通知监听器；诊断信息保持原语言。 */
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

    /** 扩展使用独立命名空间保存键值，并在使用完毕后关闭临时注册。 */
    public AutoCloseable register(Source source) {
        sources.add(Objects.requireNonNull(source));
        return () -> sources.remove(source);
    }

    /** Connects lookups to the game locale engine after its native bundle is wrapped. */
    public void bindNativeReader(NativeReader reader) {
        nativeReader = Objects.requireNonNull(reader);
    }

    /** Keeps early boot on the properties path while the native bundle is being replaced. */
    public void clearNativeReader() { nativeReader = null; }

    /** True only while the native locale bundle is ready to serve unified lookups. */
    public boolean nativeReady() { return nativeReader != null; }

    public String text(String key, Object... arguments) {
        Locale locale = locale();
        NativeReader reader = nativeReader;
        if (reader != null) {
            try {
                String nativeValue = reader.text(key, arguments);
                if (nativeValue != null) { return nativeValue; }
            } catch (java.util.MissingResourceException missing) {
                // Keep the properties bootstrap available for keys absent from every bundle.
            }
        }
        String value = find(locale, key);
        if (value == null) { value = key; }
        return arguments.length == 0 ? value : new MessageFormat(value, locale).format(arguments);
    }

    /** Raw host and extension lookup for the native ResourceBundle wrapper; never calls text(). */
    public String find(Locale locale, String key) {
        Locale selectedLocale = locale == null ? Locale.getDefault() : locale;
        for (Source source : sources) {
            String value = source.find(selectedLocale, key);
            if (value != null) { return value; }
        }
        if (!"en".equals(selectedLocale.getLanguage())) {
            for (Source source : sources) {
                String value = source.find(Locale.ENGLISH, key);
                if (value != null) { return value; }
            }
        }
        if ("zh".equals(selectedLocale.getLanguage())) {
            String value = CHINESE.getProperty(key);
            if (value != null) { return value; }
        }
        return ENGLISH.getProperty(key);
    }

    /** All known keys for native ResourceBundle enumeration and containsKey checks. */
    public Set<String> keys(Locale locale) {
        Locale selectedLocale = locale == null ? Locale.getDefault() : locale;
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (Source source : sources) {
            Set<String> sourceKeys = source.keys(selectedLocale);
            if (sourceKeys != null) { keys.addAll(sourceKeys); }
        }
        if (!"en".equals(selectedLocale.getLanguage())) {
            for (Source source : sources) {
                Set<String> sourceKeys = source.keys(Locale.ENGLISH);
                if (sourceKeys != null) { keys.addAll(sourceKeys); }
            }
        }
        if ("zh".equals(selectedLocale.getLanguage())) { keys.addAll(CHINESE.stringPropertyNames()); }
        keys.addAll(ENGLISH.stringPropertyNames());
        return Collections.unmodifiableSet(keys);
    }

    /** 转换布局中的静态英文标签，不修改已恢复的布局及其数据。 */
    public String label(String english) {
        String key = LABEL_KEYS.get(english);
        return key == null ? english : text(key);
    }

    /** 为运行时安装前恢复的 Activity 提供安全标签。 */
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
