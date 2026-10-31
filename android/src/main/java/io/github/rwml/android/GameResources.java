/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import android.content.Context;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 将游戏归档编译时使用的资源 ID 转换为当前应用中的资源 ID。
 *
 * <p>归档携带的是构建它时 APK 中的资源 ID，并会直接传给平台；这些 ID 在当前应用中可能指向其他资源，
 * 也可能没有对应内容。必须从游戏自身的 {@code R} 中读取每个 ID，再查找当前应用中同名资源的 ID 并返回。</p>
 *
 * <p>每个无法转换的 ID 只报告一次：如果这里保持静默，可能会因资源表读取失败而对所有 ID 返回零，导致游戏
 * 加载后没有音效或纹理，却没有任何原因说明。</p>
 */
public final class GameResources {

    /** 已挂载游戏 dex 所看到的游戏原始资源表。 */
    private static final String GAME_RAW_CLASS = "com.corrodinggames.rts.R$raw";

    /** 已挂载游戏 dex 所看到的游戏 drawable 资源表。 */
    private static final String GAME_DRAWABLE_CLASS = "com.corrodinggames.rts.R$drawable";

    /** 拥有已挂载游戏 dex 的加载器；由启动器设置，设置前为 null。 */
    private static volatile ClassLoader gameLoader;

    /** 当前应用上下文；被修补调用未传入上下文时，mixin 会从此处获取。 */
    private static volatile Context appContext;

    /** 按字段名称索引的游戏 {@code R} 资源 ID，仅构建一次。首次转换前为 null。 */
    private static volatile Map<Integer, String> namesById;

    /** 已报告过无法转换的资源 ID，避免一次加载中重复报告。 */
    private static final Set<Integer> reported = new HashSet<>();

    private GameResources() {
    }

    /**
     * 记录拥有已挂载游戏 dex 的类加载器。
     *
     * @param loader 启动器挂载游戏 dex 所使用的加载器
     */
    public static void mounted(ClassLoader loader) {
        gameLoader = loader;
    }

    /**
     * 记录当前应用的上下文。
     *
     * <p>处理方法未接收上下文的 mixin 需要从此处获取上下文：框架会创建 mixin 实例，因此目标类中的隐藏
     * 上下文字段会保持为 null。</p>
     *
     * @param context 当前应用的上下文
     */
    public static void attach(Context context) {
        appContext = context;
    }

    /** @return {@link #attach} 记录的上下文；调用前返回 null */
    public static Context context() {
        return appContext;
    }

    /**
     * @param context 加载流程收到的上下文
     * @param gameResId 游戏归档构建时 APK 的 {@code R} 资源 ID
     * @return 当前应用中同一音效的 ID；任一侧缺少对应资源时返回零
     */
    public static int ownResId(Context context, String type, int gameResId) {
        if (gameResId == 0 || context == null) {
            return 0;
        }
        String name = gameNames().get(Integer.valueOf(gameResId));
        if (name == null) {
            miss(gameResId, "the game archive names no resource with that id");
            return 0;
        }
        int own = context.getResources().getIdentifier(name, type, context.getPackageName());
        if (own == 0) {
            miss(gameResId, "this app carries no res resource called '" + name + "'");
        }
        return own;
    }

    /**
     * 对无法转换的资源 ID 只报告一次。
     *
     * <p>加载过程会请求整组资源，因此每个绘制该资源的界面都会查询缺失的 ID；每个 ID 记录一行即可，若每次
     * 调用都记录一行，日志将无法阅读。</p>
     */
    private static void miss(int gameResId, String reason) {
        synchronized (reported) {
            if (!reported.add(Integer.valueOf(gameResId))) {
                return;
            }
        }
        HostLog.warn("no resource in this app for the game's id " + gameResId + ": " + reason);
    }

    /** @return 游戏 {@code R.raw} 字段构成的 ID 到名称映射；无法读取时返回空映射。 */
    private static Map<Integer, String> gameNames() {
        Map<Integer, String> names = namesById;
        if (names == null) {
            synchronized (GameResources.class) {
                names = namesById;
                if (names == null) {
                    names = readGameResTable();
                    namesById = names;
                }
            }
        }
        return names;
    }

    /** 读取游戏 {@code R$raw} 中所有 {@code int} 字段；无法读取时返回空映射。 */
    private static Map<Integer, String> readGameResTable() {
        Map<Integer, String> names = new HashMap<Integer, String>();
        ClassLoader loader = gameLoader;
        if (loader == null) {
            HostLog.warn("the game's resource table cannot be read: no game dex is mounted");
            return names;
        }
        try {
            putResNames(names, loader, GAME_RAW_CLASS);
            putResNames(names, loader, GAME_DRAWABLE_CLASS);
        } catch (Throwable t) {
            // 没有游戏类，或资源表无法读取：所有转换结果均为零。
            HostLog.warn("the game's resource table cannot be read", t);
            return names;
        }
        HostLog.add("resources  " + names.size() + " id(s) read from the game archive's own R");
        return names;
    }

    private static void putResNames(Map<Integer, String> names, ClassLoader loader, String gameResClass) throws ClassNotFoundException, IllegalAccessException {
        Class<?> drawable = Class.forName(gameResClass, false, loader);
        for (Field field : drawable.getDeclaredFields()) {
            if (field.getType() != Integer.TYPE) {
                continue;
            }
            HostLog.add("field: " + field.getName() + "  value: " + field.getInt(null));
            names.put(Integer.valueOf(field.getInt(null)), field.getName());
        }
    }
}
