/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api;

import java.io.IOException;
import java.io.InputStream;

/**
 * Read access to the game's own asset tree.
 *
 * <p>Where the assets live is the one thing an extension cannot answer for itself. A desktop
 * installation keeps them in a directory below the game, so a path opens them. An Android app keeps
 * them inside the APK: they are readable through the platform's asset manager and have no path at
 * all. This interface is the single form both answer, so an extension that needs a map or a tile set
 * asks here instead of assuming a directory.</p>
 *
 * <p>The names are the ones the game itself uses, relative to its asset root, for example
 * {@code maps/skirmish/[p2]Example.tmx}. A host without a readable asset tree answers with
 * {@link #none()}, whose {@link #open} fails with an {@link IOException} rather than a missing
 * method, so an extension can fall back to what it carries.</p>
 */
public interface GameAssets {

    /** @return whether {@code path} exists below the asset root */
    boolean exists(String path);

    /**
     * @param path the asset's name, relative to the asset root
     * @return the asset's bytes
     * @throws IOException when the asset is absent or unreadable
     */
    default byte[] read(String path) throws IOException {
        try (InputStream in = open(path)) {
            return in.readAllBytes();
        }
    }

    /**
     * @param path the asset's name, relative to the asset root
     * @return a stream the caller closes
     * @throws IOException when the asset is absent or unreadable
     */
    InputStream open(String path) throws IOException;

    /** @return the answer of a host that exposes no asset tree */
    static GameAssets none() {
        return None.INSTANCE;
    }

    /** The empty tree. */
    final class None implements GameAssets {

        private static final None INSTANCE = new None();

        private None() {
        }

        @Override
        public boolean exists(String path) {
            return false;
        }

        @Override
        public InputStream open(String path) throws IOException {
            throw new IOException("This host does not expose the game's assets");
        }
    }
}
