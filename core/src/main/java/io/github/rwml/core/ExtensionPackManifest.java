/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 整合包根目录的可选清单。
 *
 * <p>清单不是必需的：包里每个扩展的说明文件已经描述了 ID、版本和依赖。清单只表达包级别意图，即包名、作者以及作者希望
 * 下一次启动启用的扩展和固定使用的版本。没有清单时，版本仍按 {@link ExtensionVersions} 的规则自动选择。</p>
 *
 * <p>示例：</p>
 *
 * <pre>
 * name=Frontier Pack
 * version=2.1.0
 * authors=RWPP-Team
 * disabled=com.acme.optional
 * pin.com.acme.core=1.4.2
 * </pre>
 */
public record ExtensionPackManifest(String name, String version, List<String> authors,
                                    List<String> enabled, List<String> disabled,
                                    Map<String, String> pins) {

    /** 包根目录中的清单文件名。 */
    public static final String ENTRY = "rwml-pack.properties";
    /** 允许把清单放在 {@code META-INF} 中，与扩展说明文件的位置一致。 */
    public static final String ALTERNATE_ENTRY = "META-INF/" + ENTRY;

    public ExtensionPackManifest {
        name = name == null ? "" : name;
        version = version == null ? "" : version;
        authors = Collections.unmodifiableList(new ArrayList<>(authors));
        enabled = Collections.unmodifiableList(new ArrayList<>(enabled));
        disabled = Collections.unmodifiableList(new ArrayList<>(disabled));
        pins = Collections.unmodifiableMap(new LinkedHashMap<>(pins));
    }

    /** @return 不含任何意图的空清单，用于没有清单文件的整合包 */
    public static ExtensionPackManifest empty() {
        return new ExtensionPackManifest("", "", Collections.<String>emptyList(),
            Collections.<String>emptyList(), Collections.<String>emptyList(),
            Collections.<String, String>emptyMap());
    }

    /** @return 压缩包中声明的清单；没有清单文件时返回 {@link #empty()} */
    static ExtensionPackManifest read(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry(ENTRY);
        if (entry == null) { entry = zip.getEntry(ALTERNATE_ENTRY); }
        if (entry == null) { return empty(); }
        Properties properties = new Properties();
        try (InputStream stream = zip.getInputStream(entry)) {
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        Map<String, String> pins = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith("pin.")) { continue; }
            String id = key.substring("pin.".length()).trim();
            String version = properties.getProperty(key, "").trim();
            if (!id.isEmpty() && !version.isEmpty()) { pins.put(id, version); }
        }
        List<String> authors = new ArrayList<>(values(properties.getProperty("authors")));
        for (String single : values(properties.getProperty("author"))) {
            if (!authors.contains(single)) { authors.add(single); }
        }
        return new ExtensionPackManifest(value(properties.getProperty("name")),
            value(properties.getProperty("version")), authors,
            values(properties.getProperty("enabled")), values(properties.getProperty("disabled")), pins);
    }

    private static String value(String raw) { return raw == null ? "" : raw.trim(); }

    private static List<String> values(String raw) {
        Set<String> result = new LinkedHashSet<>();
        if (raw != null) {
            for (String part : raw.split("[,;\\s]+")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) { result.add(trimmed); }
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(result));
    }
}
