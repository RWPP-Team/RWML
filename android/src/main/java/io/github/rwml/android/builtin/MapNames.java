/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.util.Log;

import com.corrodinggames.rts.gameFramework.localization.LocaleEngine;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a map file is called on screen.
 *
 * <p>A map file's name carries the game's own bookkeeping: a mission carries a level tag before a
 * semicolon ({@code l040;mission_4__-__Sneak_Attack.tmx}), a map that is only in the demo carries a
 * bracketed tag ({@code [demo]mission_3_...}), and every name ends in {@code .tmx} with underscores
 * standing in for the spaces of the title. This is the game's own reading of those names, which is
 * what its map grid puts on a tile.</p>
 *
 * <p>The name is then looked up in the language file through the engine's own map-name path, which
 * asks for {@code maps.name.} plus the name in lower case with its spaces underscored and answers
 * with the name it was given when the language file has no entry for it. That path checks whether
 * the entry exists before it reads it, so an untranslated map is an ordinary answer rather than a
 * missing resource.</p>
 */
public final class MapNames {

    private static final String TAG = "RWML";

    /** {@code l040;[demo]mission_1__-__Dividing_River.tmx} */
    private static final Pattern TAGGED_AND_BRACKETED = Pattern.compile("^l\\d*;\\[.*\\](.+)\\.tmx");

    /** {@code l040;mission_4__-__Sneak_Attack.tmx} */
    private static final Pattern TAGGED = Pattern.compile("^l\\d*;(.+)\\.tmx");

    /** {@code [demo]mission_3__-__Crossfire.tmx} */
    private static final Pattern BRACKETED = Pattern.compile("^ *\\[.*\\](.+)\\.tmx");

    /** {@code beach_landing.tmx}, and the .rwsave files a saved game is listed as. */
    private static final Pattern PLAIN = Pattern.compile("(.*)\\.tmx");

    private MapNames() {
    }

    /**
     * The engine's path for one listed map, as the map grid shows it: the level and demo tags, the
     * folder and the extension are dropped, the first letter is capitalised and the underscores
     * become spaces. A map whose name fits none of the game's shapes is shown as it is.
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
     * The language file's word for a display name, or the display name itself when it has none.
     *
     * @return the name to show, never {@code null} for a name that is not {@code null}
     */
    public static String localized(String display) {
        if (display == null) {
            return null;
        }
        try {
            String translated = LocaleEngine.b(display);
            return translated == null || translated.isEmpty() ? display : translated;
        } catch (Throwable t) {
            // A language file that cannot answer leaves the name the game's own reading produced.
            Log.d(TAG, "Map name '" + display + "' left as the file named it.");
            return display;
        }
    }

    /** The name to show for one listed map: the game's reading of the file, in this language. */
    public static String of(String path) {
        String display = forDisplay(path);
        return display == null ? null : localized(display);
    }

    /** The part of a path after its last separator, and after a {@code MOD|} or {@code NEW_PATH|} tag. */
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

    /** The named group of the first pattern that fits, capitalised, or {@code null} when none does. */
    private static String match(Pattern pattern, String name) {
        Matcher matcher = pattern.matcher(name);
        if (!matcher.matches()) {
            return null;
        }
        String group = matcher.group(1);
        if (group == null || group.isEmpty()) {
            return null;
        }
        // The file writes its names in lower case; the title's first letter is what the grid shows.
        return group.substring(0, 1).toUpperCase(Locale.ENGLISH) + group.substring(1);
    }
}
