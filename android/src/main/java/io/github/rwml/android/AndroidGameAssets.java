/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import android.content.res.AssetManager;

import io.github.rwml.api.GameAssets;

import java.io.IOException;
import java.io.InputStream;

/**
 * The game's assets as the APK carries them.
 *
 * <p>There is no path to hand out: the entries live in the package, the platform reads them through
 * an {@link AssetManager}, and a name is all a caller has. That is why the extension API asks for
 * bytes rather than a directory.</p>
 */
final class AndroidGameAssets implements GameAssets {

    private final AssetManager assets;

    AndroidGameAssets(AssetManager assets) {
        this.assets = assets;
    }

    @Override
    public boolean exists(String path) {
        try (InputStream ignored = assets.open(path)) {
            return true;
        } catch (IOException missing) {
            return false;
        }
    }

    @Override
    public InputStream open(String path) throws IOException {
        return assets.open(path);
    }
}
