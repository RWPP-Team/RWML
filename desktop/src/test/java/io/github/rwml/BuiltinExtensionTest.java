/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml;

import io.github.rwml.api.Extension;
import io.github.rwml.api.Rwml;
import io.github.rwml.api.ui.UiFramework;
import io.github.rwml.api.ui.UiHost;
import io.github.rwml.builtin.BuiltinExtension;
import io.github.rwml.core.ExtensionDescriptor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bundled extension is what makes a fresh install able to start a game, and it is the one
 * extension that has no archive. Both facts are worth pinning down: the host decides headless or not
 * by reading the declaration, and a built-in with a source path would send the loader looking for an
 * archive that does not exist.
 */
class BuiltinExtensionTest {

    @Test
    void declaresTheInterfaceWithoutAnArchive() {
        ExtensionDescriptor descriptor = BuiltinExtension.descriptor();

        assertTrue(descriptor.isBuiltIn());
        assertNull(descriptor.source(), "a built-in extension has no archive to open");
        assertEquals(BuiltinExtension.ID, descriptor.id());
        assertNotNull(descriptor.uiFramework(), "with no declaration the host would stay headless");
        assertFalse(descriptor.mainClass().isEmpty());
    }

    @Test
    void theDeclaredEntryPointIsAnExtensionAndTheDeclaredInterfaceIsAUiFramework() throws Exception {
        ExtensionDescriptor descriptor = BuiltinExtension.descriptor();

        Class<?> entry = Class.forName(descriptor.mainClass());
        Class<?> ui = Class.forName(descriptor.uiFramework());

        assertTrue(Extension.class.isAssignableFrom(entry));
        assertTrue(UiFramework.class.isAssignableFrom(ui));
        assertTrue(entry.getDeclaredConstructor().newInstance() instanceof Extension);
        assertTrue(ui.getDeclaredConstructor().newInstance() instanceof UiFramework);
    }

    @Test
    void withoutARuntimeTheInterfaceAsksTheHostForTheGameScreen() throws Exception {
        assertNull(Rwml.get(), "this test states what happens when nothing installed a runtime");
        ExtensionDescriptor descriptor = BuiltinExtension.descriptor();
        UiFramework framework = (UiFramework) Class.forName(descriptor.uiFramework())
            .getDeclaredConstructor().newInstance();
        List<String> screens = new ArrayList<>();

        framework.start(host(screens));

        assertEquals(Collections.singletonList("game"), screens);
    }

    private static UiHost host(List<String> screens) {
        return new UiHost() {
            @Override
            public Object platform() {
                return this;
            }

            @Override
            public List<String> arguments() {
                return Collections.emptyList();
            }

            @Override
            public void openScreen(String screen) {
                screens.add(screen);
            }
        };
    }
}
