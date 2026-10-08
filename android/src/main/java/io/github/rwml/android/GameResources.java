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
 * Translates the resource ids the game archive was compiled with into the ids this app carries.
 *
 * <p>The archive carries the ids of the APK it was built in and hands them straight to the platform,
 * where they name something else or nothing at all. Every id has to be read out of the game's own
 * {@code R} and answered with the id this app gives the same name.</p>
 *
 * <p>What cannot be translated is said once per id: silence here means a table that could not be read
 * answers zero for everything, the game loads without a sound or a texture, and nothing says why.</p>
 */
public final class GameResources {

    /** The game's own raw-resource table, as the mounted game dex sees it. */
    private static final String GAME_RAW_CLASS = "com.corrodinggames.rts.R$raw";

    /** The game's own drawable-resource table, as the mounted game dex sees it. */
    private static final String GAME_DRAWABLE_CLASS = "com.corrodinggames.rts.R$drawable";

    /** The loader that owns the mounted game dex; set by the bootstrapper, null before that. */
    private static volatile ClassLoader gameLoader;

    /** This app's context, the one a mixin reaches for when the call it patches carries no context. */
    private static volatile Context appContext;

    /** The game's {@code R} ids by field name, built once. Null until the first translation. */
    private static volatile Map<Integer, String> namesById;

    /** The ids already reported as untranslatable, so one load does not say the same thing twice. */
    private static final Set<Integer> reported = new HashSet<>();

    private GameResources() {
    }

    /**
     * Records the loader that owns the mounted game dex.
     *
     * @param loader the loader the bootstrapper mounted the game on
     */
    public static void mounted(ClassLoader loader) {
        gameLoader = loader;
    }

    /**
     * Records this app's context.
     *
     * <p>A mixin whose handler receives no context of its own needs one: it runs on a mixin instance
     * the framework constructs, so a shadowed context field of the target stays null.</p>
     *
     * @param context this app's context
     */
    public static void attach(Context context) {
        appContext = context;
    }

    /** @return the context recorded by {@link #attach}, null before that */
    public static Context context() {
        return appContext;
    }

    /**
     * @param context  the context the load was handed
     * @param gameResId the {@code R} id of the APK the game archive was built in
     * @return this app's id for the same sound, or zero when either side lacks it
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
     * Reports one id that could not be translated, once.
     *
     * <p>The load asks for the whole set, so a missing id is asked for by every screen that draws it;
     * one line per id is enough, one per call is a log nobody reads.</p>
     */
    private static void miss(int gameResId, String reason) {
        synchronized (reported) {
            if (!reported.add(Integer.valueOf(gameResId))) {
                return;
            }
        }
        HostLog.warn("no resource in this app for the game's id " + gameResId + ": " + reason);
    }

    /** @return the game's {@code R.raw} fields as id-to-name, an empty map when they cannot be read. */
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

    /** Reads every {@code int} field of the game's {@code R$raw}; unreadable becomes empty. */
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
            // No game classes, or a table that cannot be read: every translation answers zero.
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
