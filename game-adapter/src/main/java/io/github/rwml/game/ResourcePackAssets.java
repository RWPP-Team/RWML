/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.game;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 当前生效的资源包目录，以及游戏资源路径到该目录的映射。
 *
 * <p>只有一个目录生效，因此状态是一个静态引用，由宿主在物化完成后设置。资源读取发生在游戏线程上，读取时不加锁，
 * 只读取一次引用；目录切换发生在游戏启动前，此时没有任何读取者。</p>
 *
 * <p>游戏并非所有资源都可以被替换：内置模组、地图文件与翻译由游戏另行管理，覆盖它们会破坏模组与语言选择。过滤规则
 * 集中在 {@link #redirects}，两个宿主共用同一份判断。</p>
 */
public final class ResourcePackAssets {

    private static final String ASSET_INPUT_STREAM =
        "com.corrodinggames.rts.gameFramework.utility.AssetInputStream";

    /** 资源 ID 到文件名的索引，按资源类别缓存；切换资源包时清空。 */
    private static final Map<String, Map<Integer, String>> RESOURCE_NAMES = new ConcurrentHashMap<>();

    /** 生效的资源目录；没有资源包时为 null。 */
    private static volatile Path directory;

    /** 按类别索引的文件名到文件映射；首次查找时构建，切换资源包时清空。 */
    private static volatile Map<String, Map<String, File>> index;

    /** {@code AssetInputStream} 的构造器缓存；它属于游戏类加载器，每次打开都在同一进程内复用。 */
    private static volatile Constructor<?> assetInputStreamConstructor;

    private ResourcePackAssets() {
    }

    /**
     * 切换生效的资源目录。
     *
     * @param root 已物化的资源目录；null 表示回到原版资源
     */
    public static void use(Path root) {
        directory = root;
        index = null;
        assetInputStreamConstructor = null;
    }

    /** @return 生效的资源目录；没有资源包时为 null */
    public static Path directory() {
        return directory;
    }

    /**
     * @param path 游戏传入的资源路径，可带 {@code assets/} 前缀
     * @return 资源包是否可以接管此路径
     */
    public static boolean redirects(String path) {
        if (directory == null || path == null || path.isEmpty()) {
            return false;
        }
        if (path.contains("builtin_mods")) {
            return false;
        }
        // 地图文件与图块集共用 maps 前缀；只有图块集位图参与替换。
        if (path.contains("maps") && !path.contains("bitmaps")) {
            return false;
        }
        return !path.contains("translations");
    }

    /**
     * @param path 游戏传入的资源路径
     * @return 资源包中对应文件的相对路径；路径不在包内时返回 null
     */
    public static String relativePath(String path) {
        String relative = path.replace('\\', '/');
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        if (relative.startsWith("assets/")) {
            relative = relative.substring("assets/".length());
        }
        return relative.isEmpty() ? null : relative;
    }

    /**
     * @param path 游戏传入的资源路径
     * @return 资源包中的文件；未提供该文件时返回 null
     */
    public static File file(String path) {
        Path root = directory;
        if (root == null || !redirects(path)) {
            return null;
        }
        String relative = relativePath(path);
        if (relative == null) {
            return null;
        }
        try {
            Path candidate = root.resolve(relative).normalize();
            if (!candidate.startsWith(root)) {
                return null;
            }
            return Files.isRegularFile(candidate) ? candidate.toFile() : null;
        } catch (RuntimeException invalidPath) {
            return null;
        }
    }

    /**
     * 解析按资源 ID 加载的资源。
     *
     * <p>桌面版按 {@code R.drawable}/{@code R.raw} 的字段名查找资源文件，扩展名可能与字段名不同，因此按名称匹配
     * 前缀并忽略扩展名，与游戏自身的查找方式一致。</p>
     *
     * @param type {@code drawable} 或 {@code raw}
     * @param resourceId 游戏资源表使用的 ID
     * @return 资源包中的文件；未提供时返回 null
     */
    public static File resourceFile(String type, int resourceId) {
        if (directory == null || resourceId == 0) {
            return null;
        }
        String name = resourceNames(type).get(resourceId);
        return name == null ? null : indexed("res/" + type, name);
    }

    /**
     * 解析界面纹理路径。
     *
     * <p>游戏界面按 {@code drawable:}、{@code assets:} 或裸文件名引用纹理。资源包只接管它确实提供的文件，
     * 其余路径保持游戏原来的解析结果。</p>
     *
     * @param texturePath 游戏传入的纹理表达式
     * @return 资源包中的文件；未提供时返回 null
     */
    public static File textureFile(String texturePath) {
        if (directory == null || texturePath == null) {
            return null;
        }
        String path = texturePath.replace('\\', '/');
        if (path.startsWith("drawable:")) {
            return indexed("res/drawable", path.substring("drawable:".length()));
        }
        if (path.startsWith("assets:")) {
            return exact(path.substring("assets:".length()));
        }
        if (path.startsWith("base:")) {
            String relative = path.substring("base:".length());
            return relative.startsWith("gui/") ? indexed("gui", relative) : exact(relative);
        }
        return indexed("gui", path);
    }

    /**
     * 修正结果中已经指向资源包目录的路径。
     *
     * <p>界面层会把基础路径再拼到转换结果前面。若转换结果本身已经是资源包中的绝对路径，拼接后的路径无效，因此取包目录
     * 之后的部分重建路径，重复的前缀被丢弃。</p>
     *
     * @param path 已转换的资源路径
     * @return 修正后的路径；路径不在资源包目录内时返回 null
     */
    public static String inside(String path) {
        Path root = directory;
        if (root == null || path == null) {
            return null;
        }
        String normalized = path.replace('\\', '/');
        String prefix = root.toAbsolutePath().toString().replace('\\', '/');
        int at = normalized.indexOf(prefix);
        if (at < 0) {
            return null;
        }
        return at == 0 ? path : normalized.substring(at);
    }

    /**
     * 用资源包中的文件打开一个游戏能识别的资源流。
     *
     * <p>返回类型声明为 {@code Object}：Android 上处理方法的类由应用加载器定义，而该加载器看不到游戏类。构造器通过
     * 游戏类自己的加载器解析，因此两种宿主都能得到正确的类型。</p>
     *
     * @param owner 目标类实例，用于取得游戏类加载器
     * @param path 游戏传入的资源路径
     * @return 资源流；未提供该文件或无法构造时返回 null
     */
    public static Object open(Object owner, String path) {
        File file = file(path);
        if (file == null || owner == null) {
            return null;
        }
        String relative = relativePath(path);
        try {
            Constructor<?> constructor = assetInputStreamConstructor;
            if (constructor == null) {
                constructor = Class.forName(ASSET_INPUT_STREAM, true, owner.getClass().getClassLoader())
                    .getConstructor(InputStream.class, String.class, String.class);
                assetInputStreamConstructor = constructor;
            }
            return constructor.newInstance(new FileInputStream(file), file.getAbsolutePath(), relative);
        } catch (ReflectiveOperationException | IOException unavailable) {
            announce("could not open " + relative + " from the resource pack: " + unavailable);
            return null;
        }
    }

    /** 记录一条资源包诊断信息。 */
    static void announce(String message) {
        System.out.println("[rwml] resource pack: " + message);
    }

    // ------------------------------------------------------------------
    // 查找
    // ------------------------------------------------------------------

    /** @return 物化目录中该相对路径对应的文件 */
    private static File exact(String relative) {
        Path root = directory;
        if (root == null) {
            return null;
        }
        try {
            Path candidate = root.resolve(relative).normalize();
            if (!candidate.startsWith(root)) {
                return null;
            }
            return Files.isRegularFile(candidate) ? candidate.toFile() : null;
        } catch (RuntimeException invalidPath) {
            return null;
        }
    }

    /** 在资源包的一个目录中按名称查找文件，忽略扩展名差异。 */
    private static File indexed(String category, String name) {
        Map<String, File> files = index().get(category);
        return files == null ? null : files.get(baseName(name));
    }

    private static String baseName(String name) {
        String plain = name.replace('\\', '/');
        int slash = plain.lastIndexOf('/');
        if (slash >= 0) {
            plain = plain.substring(slash + 1);
        }
        int dot = plain.lastIndexOf('.');
        return dot > 0 ? plain.substring(0, dot) : plain;
    }

    /** @return 类别到文件名的索引；首次查找时构建一次，切换资源包后重建 */
    private static Map<String, Map<String, File>> index() {
        Map<String, Map<String, File>> current = index;
        if (current != null) {
            return current;
        }
        synchronized (ResourcePackAssets.class) {
            if (index != null) {
                return index;
            }
            Map<String, Map<String, File>> built = new HashMap<>();
            for (String category : new String[]{"res/drawable", "res/raw", "gui"}) {
                built.put(category, listFiles(category));
            }
            index = built;
            return built;
        }
    }

    private static Map<String, File> listFiles(String category) {
        Path root = directory;
        Path target = root == null ? null : root.resolve(category);
        if (target == null || !Files.isDirectory(target)) {
            return Collections.emptyMap();
        }
        Map<String, File> files = new LinkedHashMap<>();
        try (Stream<Path> list = Files.list(target)) {
            for (Path candidate : (Iterable<Path>) list::iterator) {
                if (!Files.isRegularFile(candidate)) {
                    continue;
                }
                files.putIfAbsent(baseName(candidate.getFileName().toString()), candidate.toFile());
            }
        } catch (IOException unreadable) {
            announce("could not list " + target + ": " + unreadable);
        }
        return Collections.unmodifiableMap(files);
    }

    /** @return 资源类别中 ID 到字段名的映射；资源表不可读时为空 */
    private static Map<Integer, String> resourceNames(String type) {
        Map<Integer, String> cached = RESOURCE_NAMES.get(type);
        if (cached != null) {
            return cached;
        }
        Map<Integer, String> names = new HashMap<>();
        try {
            ClassLoader loader = ResourcePackAssets.class.getClassLoader();
            Class<?> table = Class.forName("com.corrodinggames.rts.R$" + type, false, loader);
            for (Field field : table.getDeclaredFields()) {
                if (field.getType() == Integer.TYPE) {
                    names.put(Integer.valueOf(field.getInt(null)), field.getName());
                }
            }
        } catch (Throwable unavailable) {
            // 资源表不可用不影响其他资源路径；该类别按空映射处理。
            names.clear();
        }
        Map<Integer, String> result = Collections.unmodifiableMap(names);
        RESOURCE_NAMES.put(type, result);
        return result;
    }
}
