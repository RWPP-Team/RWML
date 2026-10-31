/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import android.content.Context;
import android.content.res.Resources;
import android.util.TypedValue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把游戏传入的资源 ID 解析为资源包中的文件。
 *
 * <p>Android 上图片与音效按资源 ID 加载，而不是按路径。ID 属于游戏归档构建时所用的资源表，当前应用中的同名资源可能是
 * 另一个 ID，因此先按名称映射到本应用的 ID，再从该 ID 取得文件名，最后在资源包的 {@code res} 目录中查找同名文件。
 * 任一环节缺失都表示资源包没有替换这个资源，游戏继续使用原版资源。</p>
 */
public final class ResourcePackResources {

    /** 资源包目录下的 {@code res}，即资源 ID 对应的文件所在处。 */
    private static volatile Path root;

    /**
     * 资源 ID 到替换文件的解析结果，按资源类型分开缓存。
     *
     * <p>解析一次要走平台资源表的三次查询，而图片与音效在启动阶段被逐个请求。资源包在进程内不变，因此结果可以一直
     * 保留到资源包切换；未提供替换的 ID 也记入缓存，避免重复查询。</p>
     */
    private static volatile Map<String, Map<Integer, Optional<Source>>> cache = new ConcurrentHashMap<>();

    private ResourcePackResources() {
    }

    /**
     * 切换生效的资源包。
     *
     * @param packDirectory 物化目录；null 表示回到原版资源
     */
    public static void use(Path packDirectory) {
        root = packDirectory == null ? null : packDirectory.resolve("res");
        cache = new ConcurrentHashMap<>();
    }

    /**
     * @param gameResId 游戏归档中的资源 ID
     * @param type 资源类型，{@code drawable} 或 {@code raw}
     * @return 资源包中的文件及资源条目名；资源包未提供时返回 null
     */
    public static Source resolve(int gameResId, String type) {
        Path directory = root;
        if (directory == null || gameResId == 0) {
            return null;
        }
        Map<Integer, Optional<Source>> entries = entries(type);
        Optional<Source> cached = entries.get(Integer.valueOf(gameResId));
        if (cached == null) {
            cached = Optional.ofNullable(lookup(gameResId, type, directory));
            entries.put(Integer.valueOf(gameResId), cached);
        }
        return cached.orElse(null);
    }

    /** @return 该资源类型的缓存，首次使用时创建 */
    private static Map<Integer, Optional<Source>> entries(String type) {
        Map<Integer, Optional<Source>> entries = cache.get(type);
        if (entries != null) {
            return entries;
        }
        Map<Integer, Optional<Source>> created = new ConcurrentHashMap<>();
        Map<Integer, Optional<Source>> existing = cache.putIfAbsent(type, created);
        return existing == null ? created : existing;
    }

    /** @return 资源包中对应的文件；任一环节缺失时返回 null */
    private static Source lookup(int gameResId, String type, Path directory) {
        Context context = GameResources.context();
        if (context == null) {
            return null;
        }
        int own = GameResources.ownResId(context, type, gameResId);
        if (own == 0) {
            return null;
        }
        Resources resources = context.getResources();
        TypedValue value = new TypedValue();
        try {
            resources.getValue(own, value, true);
        } catch (RuntimeException unreadable) {
            return null;
        }
        CharSequence raw = value.string;
        if (raw == null) {
            return null;
        }
        String relative = raw.toString().replace('\\', '/');
        if (relative.startsWith("res/")) {
            relative = relative.substring("res/".length());
        }
        if (relative.isEmpty()) {
            return null;
        }
        File file;
        try {
            Path candidate = directory.resolve(relative).normalize();
            if (!candidate.startsWith(directory) || !Files.isRegularFile(candidate)) {
                return null;
            }
            file = candidate.toFile();
        } catch (RuntimeException invalidPath) {
            return null;
        }
        String name;
        try {
            name = resources.getResourceEntryName(own);
        } catch (RuntimeException missingName) {
            name = relative.substring(relative.lastIndexOf('/') + 1);
        }
        return new Source(file, name);
    }

    /** 资源包中的一个替换文件及它对应的资源条目名。 */
    public static final class Source {

        private final File file;
        private final String name;

        private Source(File file, String name) {
            this.file = file;
            this.name = name;
        }

        public File file() {
            return file;
        }

        public String name() {
            return name;
        }
    }
}
