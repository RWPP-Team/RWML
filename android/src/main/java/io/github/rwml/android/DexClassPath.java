/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexClassLoader;

/**
 * Puts compiled dex on the class path of the running app.
 *
 * <p>Which loader ends up owning the patched classes matters, because a mixin is compiled into a
 * static call inside the class it patches and that call resolves through the loader that defines the
 * patched class. Adding the dex to the app's own loader is therefore the preferred outcome: the
 * patched game classes then sit on the same path as everything else, and a mixin can reach the
 * extension it came from and the host that loaded it.</p>
 *
 * <p>There is no public API for that. {@code DexPathList.addDexPath} is what the platform itself
 * uses, and reaching it takes reflection; on a device where the hidden API is refused, a
 * {@link DexClassLoader} parented by the app loader is the fallback. It defines the very same
 * classes, with the one difference that they are no longer visible under the app's own loader.</p>
 *
 * <p>Both routes open the file the same way, and both are refused the same way: the platform will
 * not load a dex it can still write. Every file is therefore sealed read-only here, which is the
 * last point where it can be, and which also repairs a cache an earlier build left writable.</p>
 */
final class DexClassPath {

    private DexClassPath() {
    }

    /**
     * Adds every dex file to the class path and returns the loader that now owns them.
     *
     * @param optimizedDirectory where the platform may keep its own optimized artifacts
     */
    static ClassLoader mount(List<Path> dexFiles, Path optimizedDirectory, ClassLoader appLoader)
        throws IOException {
        if (dexFiles.isEmpty()) {
            throw new IOException("no dex to mount");
        }
        for (Path dex : dexFiles) {
            Dexer.makeReadOnly(dex);
        }
        // A DexClassLoader refuses to start unless its optimized directory exists, and the fallback
        // path below is exactly the case where it matters.
        Files.createDirectories(optimizedDirectory);
        Throwable refused = null;
        if (appLoader instanceof BaseDexClassLoader) {
            int added = 0;
            try {
                for (; added < dexFiles.size(); added++) {
                    addToAppLoader(appLoader, dexFiles.get(added));
                }
                return appLoader;
            } catch (Throwable t) {
                // Partial mounting is harmless: the same classes are also in the fallback loader.
                refused = t;
            }
            // Android 9 and later refuse reflection into anything outside the SDK, and adding dex to
            // a running app's path is exactly that. The platform's own escape hatch is hidden too,
            // so it is asked for only after the first refusal, and the adding resumes where it
            // stopped rather than repeating a dex that is already on the path.
            exemptHiddenApi();
            try {
                for (; added < dexFiles.size(); added++) {
                    addToAppLoader(appLoader, dexFiles.get(added));
                }
                return appLoader;
            } catch (Throwable t) {
                refused = t;
            }
        }

        String path = join(dexFiles);
        ClassLoader loader = new DexClassLoader(path, optimizedDirectory.toAbsolutePath().toString(),
            null, appLoader);
        if (refused != null) {
            // Worth saying plainly: on this loader the app's own classes - the screens - cannot
            // resolve the game, so every screen falls back to whatever it can do without it.
            HostLog.warn("The app class loader refused the patched dex (" + refused
                + "); the game is loaded on a loader of its own, which the app's own classes cannot "
                + "see.", refused);
        }
        return loader;
    }

    /** @return whether the loader has the dex on its own path, meaning app classes should not shadow it. */
    static boolean isAppLoader(ClassLoader loader) {
        return loader != null && loader == DexClassPath.class.getClassLoader();
    }

    /**
     * Asks the platform to stop refusing the members this class needs.
     *
     * <p>{@code VMRuntime.setHiddenApiExemptions} is the platform's own escape hatch from its
     * hidden-API policy, and a prefix of {@code "L"} exempts every member at once. It is hidden
     * itself, so it is reached the same way as the call it is meant to unblock, and a platform that
     * refuses it simply keeps the fallback.</p>
     */
    private static void exemptHiddenApi() {
        try {
            Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntime.getDeclaredMethod("getRuntime");
            getRuntime.setAccessible(true);
            Object runtime = getRuntime.invoke(null);
            Method exemptions = vmRuntime.getDeclaredMethod("setHiddenApiExemptions", String[].class);
            exemptions.setAccessible(true);
            exemptions.invoke(runtime, (Object) new String[] {"L"});
        } catch (Throwable ignored) {
            // A platform that refuses this refuses the dex path as well; the fallback loader stands.
        }
    }

    private static void addToAppLoader(ClassLoader appLoader, Path dex) throws Exception {
        Object pathList = readField(appLoader, "pathList");
        if (pathList == null) {
            throw new NoSuchFieldException("BaseDexClassLoader.pathList");
        }
        Method add = findAddDexPath(pathList.getClass());
        if (add == null) {
            throw new NoSuchMethodException("DexPathList.addDexPath");
        }
        add.setAccessible(true);
        String path = dex.toAbsolutePath().toString();
        Class<?>[] parameters = add.getParameterTypes();
        if (parameters.length == 1) {
            add.invoke(pathList, path);
        } else if (parameters[1] == Boolean.TYPE) {
            // Later releases take whether the dex is trusted rather than an optimized directory.
            add.invoke(pathList, path, Boolean.TRUE);
        } else {
            // API 26 and later take the optimized directory for compatibility and ignore it.
            add.invoke(pathList, path, (File) null);
        }
    }

    private static Method findAddDexPath(Class<?> pathListClass) {
        for (Class<?> type = pathListClass; type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals("addDexPath")) {
                    continue;
                }
                int count = method.getParameterCount();
                if (count == 2 && method.getParameterTypes()[0] == String.class) {
                    return method;
                }
                if (count == 1 && method.getParameterTypes()[0] == String.class) {
                    return method;
                }
            }
        }
        return null;
    }

    private static Object readField(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // Keep walking up.
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static String join(List<Path> dexFiles) {
        StringBuilder path = new StringBuilder();
        for (Path dex : dexFiles) {
            if (path.length() > 0) {
                path.append(File.pathSeparatorChar);
            }
            path.append(dex.toAbsolutePath());
        }
        return path.toString();
    }
}
