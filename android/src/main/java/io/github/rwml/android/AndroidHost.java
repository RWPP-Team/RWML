/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import android.content.Context;

import io.github.rwml.android.builtin.AndroidExtension;
import io.github.rwml.api.MixinBridge;
import io.github.rwml.api.ProgressListener;
import io.github.rwml.core.ExtensionDescriptor;
import io.github.rwml.host.HostPlatform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * The host platform of an Android app.
 *
 * <p>Every answer follows from the one fact that separates Android from a JVM: classes are dex, they
 * are rewritten before they are loaded, and only the loader that owns a patched class can resolve the
 * mixin call inside it. The extension loader is therefore the one the bootstrapper mounted the patched
 * dex on, shared by every extension, so extensions cannot be isolated from each other here.</p>
 */
public final class AndroidHost implements HostPlatform {

    private final Context context;
    private final MixinBridge mixins;
    private volatile ClassLoader extensionLoader;

    AndroidHost(Context context, MixinBridge mixins, ClassLoader extensionLoader) {
        this.context = context;
        this.mixins = mixins;
        this.extensionLoader = extensionLoader;
    }

    void updateLoader(ClassLoader loader) { extensionLoader = loader; }

    @Override
    public MixinBridge mixins() {
        return mixins;
    }

    /** Unpacks the extensions the APK carries; see {@link AssetInstaller}. */
    @Override
    public List<String> stageArchives(Path extensionDirectory) throws IOException {
        return AssetInstaller.install(context, "extensions", extensionDirectory);
    }

    /**
     * The interface RWML ships inside the app, so that a fresh install - which has no extensions - can
     * start a game. A user extension that declares its own {@code ui-framework} wins over it, and
     * disabling it by id leaves the app headless. The platform-neutral built-in is not offered, because
     * it hands over to a launcher Android does not have.
     */
    @Override
    public List<ExtensionDescriptor> builtInExtensions() {
        return Collections.singletonList(AndroidExtension.descriptor());
    }

    @Override
    public ClassLoader createExtensionLoader(ExtensionDescriptor descriptor) {
        return extensionLoader;
    }

    /**
     * Android runs on the Java version the platform ships and offers no way to change it, so an
     * extension that declares a requirement cannot be acted on. The requirement is reported by the
     * descriptor and otherwise ignored.
     */
    @Override
    public ExtensionDescriptor reconcileJavaRequirement(ExtensionDescriptor descriptor,
                                                        ProgressListener progress) {
        return descriptor;
    }
}
