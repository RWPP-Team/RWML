/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */
package io.github.rwml.desktop;

import io.github.rwml.api.MixinBridge;
import io.github.rwml.api.ProgressListener;
import io.github.rwml.core.ExtensionDescriptor;
import io.github.rwml.core.ExtensionManager;
import io.github.rwml.host.HostPlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DesktopBootstrapTest {
    @TempDir Path root;

    @Test void metadataAndUiServicesAreAvailableBeforeAnyMixinOrExtensionLoads() throws Exception {
        ExtensionManager manager = new ExtensionManager(root, new HostPlatform() {
            public MixinBridge mixins() { throw new AssertionError("Mixin installation preceded bootstrap"); }
            public List<ExtensionDescriptor> builtInExtensions() { return Collections.emptyList(); }
            public List<String> stageArchives(Path directory) { throw new AssertionError("Extension staging preceded bootstrap"); }
            public ClassLoader createExtensionLoader(ExtensionDescriptor descriptor) { throw new AssertionError("Extension code preceded bootstrap"); }
            public ExtensionDescriptor reconcileJavaRequirement(ExtensionDescriptor descriptor, ProgressListener progress) { throw new AssertionError(); }
        });
        java.lang.reflect.Constructor<DesktopRuntime> constructor = DesktopRuntime.class.getDeclaredConstructor(
            ExtensionManager.class, String[].class);
        constructor.setAccessible(true);
        DesktopRuntime runtime = constructor.newInstance(manager, new String[0]);
        assertTrue(runtime.ui().extensions().entries().isEmpty());
        assertTrue(manager.handles().isEmpty());
        assertNull(runtime.report());
        assertFalse(runtime.gameBoot().isStarted());
        assertEquals(List.of("extensions", "desktop platform", "engine"), runtime.gameBoot().stageNames());
    }
}
