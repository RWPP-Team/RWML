/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.util.Log;

import io.github.rwml.api.Rwml;
import io.github.rwml.api.ui.GameUi;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 地图文件在界面上的显示名称。
 *
 * <p>地图文件名包含游戏自身的标记信息：任务地图会在分号前带有关卡标记
 * ({@code l040;mission_4__-__Sneak_Attack.tmx})，仅限演示版的地图会带有方括号标记
 * ({@code [demo]mission_3_...})，所有名称都以 {@code .tmx} 结尾，标题中的空格则用下划线代替。此处沿用游戏
 * 对这些名称的解析方式，也就是地图网格图块上的显示规则。</p>
 *
 * <p>随后通过引擎自身的地图名称查询逻辑查找语言文件：查询键由 {@code maps.name.} 加小写名称构成，名称中的空格替换为
 * 下划线；语言文件没有对应条目时，则返回传入的原名称。查询前会先检查条目是否存在，因此未翻译的地图名称属于正常结果，
 * 而不是资源缺失。</p>
 */
public final class MapNames {

    private static final String TAG = "RWML";

    /** 带关卡和演示版标记的任务文件名示例：{@code l040;[demo]mission_1__-__Dividing_River.tmx} */
    private static final Pattern TAGGED_AND_BRACKETED = Pattern.compile("^l\\d*;\\[.*\\](.+)\\.tmx");

    /** 带关卡标记的任务文件名示例：{@code l040;mission_4__-__Sneak_Attack.tmx} */
    private static final Pattern TAGGED = Pattern.compile("^l\\d*;(.+)\\.tmx");

    /** 带演示版标记的任务文件名示例：{@code [demo]mission_3__-__Crossfire.tmx} */
    private static final Pattern BRACKETED = Pattern.compile("^ *\\[.*\\](.+)\\.tmx");

    /** 普通地图文件名示例：{@code beach_landing.tmx}；存档列表中的文件则以 {@code .rwsave} 结尾。 */
    private static final Pattern PLAIN = Pattern.compile("(.*)\\.tmx");

    private MapNames() {
    }

    /**
     * 按地图网格的规则格式化单个地图名称：移除关卡和演示版标记、目录及扩展名，将首字母改为大写，并把下划线替换为空格。
     * 不符合游戏名称格式的地图会按原样显示。
     */
    public static String forDisplay(String path) {
        if (path == null) {
            return null;
        }
        String name = lastSegment(path);
        String display = match(TAGGED_AND_BRACKETED, name);
        if (display == null) {
            display = match(TAGGED, name);
        }
        if (display == null) {
            display = match(BRACKETED, name);
        }
        if (display == null) {
            display = match(PLAIN, name);
        }
        if (display != null) {
            name = display;
        }
        name = name.replace('_', ' ');
        if (name.endsWith(".rwsave")) {
            name = name.replace(".rwsave", "");
        }
        return name;
    }

    /**
     * 获取显示名称在语言文件中的翻译；没有翻译时返回显示名称本身。
     *
     * @return 要显示的名称；传入值不为 {@code null} 时结果也不会为 {@code null}
     */
    public static String localized(String display) {
        if (display == null) {
            return null;
        }
        try {
            GameUi game = Rwml.get() == null ? null : Rwml.get().ui().find(GameUi.class);
            String translated = game == null ? null : game.mapName(display);
            return translated == null || translated.isEmpty() ? display : translated;
        } catch (Throwable t) {
            // 语言文件没有对应内容时，保留游戏自身解析出的名称。
            Log.d(TAG, "Map name '" + display + "' left as the file named it.");
            return display;
        }
    }

    /** 单张地图的显示名称：按当前语言使用游戏自身的规则解析文件名。 */
    public static String of(String path) {
        String display = forDisplay(path);
        return display == null ? null : localized(display);
    }

    /** 路径中最后一个分隔符之后、{@code MOD|} 或 {@code NEW_PATH|} 标记之后的部分。 */
    private static String lastSegment(String path) {
        String name = path;
        int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (separator >= 0) {
            name = name.substring(separator + 1);
        }
        int tag = name.lastIndexOf('|');
        if (tag >= 0) {
            name = name.substring(tag + 1);
        }
        return name;
    }

    /** 第一个匹配模式中的命名组，首字母大写；没有匹配项时返回 {@code null}。 */
    private static String match(Pattern pattern, String name) {
        Matcher matcher = pattern.matcher(name);
        if (!matcher.matches()) {
            return null;
        }
        String group = matcher.group(1);
        if (group == null || group.isEmpty()) {
            return null;
        }
        // 文件中的名称全部为小写；地图网格会将标题首字母大写。
        return group.substring(0, 1).toUpperCase(Locale.ENGLISH) + group.substring(1);
    }
}
