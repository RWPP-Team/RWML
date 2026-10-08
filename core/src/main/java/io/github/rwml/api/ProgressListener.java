/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api;

/** Receives coarse grained progress notifications during a load operation. */
@FunctionalInterface
public interface ProgressListener {

    /** Shared no-op instance used when the caller is not interested. */
    ProgressListener NOOP = (percent, message) -> {
    };

    /**
     * @param percent completion ratio in the range {@code [0, 100]}
     * @param message short human readable status line
     */
    void onProgress(int percent, String message);
}