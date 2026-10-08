/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api;

/**
 * The platform a runtime is running on.
 *
 * <p>It exists because an extension sometimes has to do something on one platform only. The
 * desktop's interface needs libRocket, its document fonts and a window the game's own launcher
 * creates; none of that exists on Android, where the same extension has to keep its hands off. A
 * class-name check would answer the same question, but it would answer it by accident.</p>
 */
public enum Platform {

    /** A JVM: the game's desktop build, started from its own launcher. */
    DESKTOP,

    /** An Android application, where RWML boots the engine itself inside the app's process. */
    ANDROID
}
