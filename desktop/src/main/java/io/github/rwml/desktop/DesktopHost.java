/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.desktop;

import io.github.rwml.api.ExtensionException;
import io.github.rwml.api.MixinBridge;
import io.github.rwml.api.ProgressListener;
import io.github.rwml.asm.MixinSupport;
import io.github.rwml.builtin.BuiltinExtension;
import io.github.rwml.core.ExtensionDescriptor;
import io.github.rwml.host.HostPlatform;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * The host platform of a JVM: a live transformer, a class loader per extension, and a Java version
 * that has to be checked before an extension is instantiated.
 */
public final class DesktopHost implements HostPlatform {

    @Override
    public MixinBridge mixins() {
        return MixinSupport.instance();
    }

    /** Nothing is bundled: a desktop install reads the extension directory the user fills. */
    @Override
    public List<String> stageArchives(Path extensionDirectory) {
        return Collections.emptyList();
    }

    /**
     * The interface RWML ships for the desktop.
     *
     * <p>A desktop install is a player's, so it wants the game on screen; the built-in extension is
     * what answers with it. Nothing else is bundled.</p>
     */
    @Override
    public List<ExtensionDescriptor> builtInExtensions() {
        return Collections.singletonList(BuiltinExtension.descriptor());
    }

    /** Every extension gets its own loader, so two of them may use different library versions. */
    @Override
    public ClassLoader createExtensionLoader(ExtensionDescriptor descriptor) throws IOException {
        try {
            URL url = descriptor.source().toUri().toURL();
            return new URLClassLoader(new URL[]{url}, DesktopHost.class.getClassLoader());
        } catch (Exception e) {
            throw new IOException("Unable to open " + descriptor.source().getFileName(), e);
        }
    }

    /**
     * Reconciles the Java requirement declared in the descriptor with the actual bytecode level of
     * the archive and with the running JVM.
     */
    @Override
    public ExtensionDescriptor reconcileJavaRequirement(ExtensionDescriptor descriptor,
                                                        ProgressListener progress) throws ExtensionException {
        int bytecodeLevel;
        try {
            bytecodeLevel = JavaVersionUtil.requiredJavaVersion(descriptor.source());
        } catch (IOException e) {
            throw new ExtensionException("Unable to inspect bytecode of "
                + descriptor.source().getFileName(), e);
        }

        int declared = descriptor.requiredJavaVersion();
        int required = Math.max(declared, bytecodeLevel);
        int runtime = JavaVersionUtil.currentJavaVersion();

        if (required > runtime) {
            Path alternative = JavaVersionUtil.findJvmAtLeast(required);
            String hint = alternative != null
                ? " A compatible JVM was found at " + alternative
                + "; restart the host with that JVM to load this extension."
                : " No compatible JVM was found on this machine.";
            throw new ExtensionException(String.format(
                "Extension '%s' requires Java %d (declared %d, bytecode %d) but the running JVM is Java %d.%s",
                descriptor.id(), required, declared, bytecodeLevel, runtime, hint));
        }
        if (bytecodeLevel > declared) {
            progress.onProgress(45, "Raised Java requirement of " + descriptor.id()
                + " from " + declared + " to " + bytecodeLevel);
        }
        return descriptor.withRequiredJavaVersion(required);
    }
}
