/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import io.github.rwml.core.ExtensionDescriptor;
import io.github.rwml.core.ExtensionSelection;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AndroidInterfaceFallbackTest {

    @Test
    void disabledRwppKeepsApiAndUsesNativeMenuAfterEngineBoot() {
        ExtensionDescriptor rwpp = extension("io.github.rwpp", true);
        ExtensionDescriptor api = extension("io.github.rwpp.api", false);

        ExtensionSelection selection = ExtensionSelection.resolve(
            List.of(rwpp, api, AndroidExtension.descriptor()), Set.of("io.github.rwpp"));

        assertEquals(AndroidExtension.ID, selection.framework().id());
        assertTrue(selection.enabled().contains(api), "The API extension remains enabled");
        assertEquals(LauncherActivity.class, AndroidInterface.destination(false));
        assertEquals(MainMenuActivity.class, AndroidInterface.destination(true));
    }

    private static ExtensionDescriptor extension(String id, boolean hasUi) {
        return new ExtensionDescriptor(id, id, "1.0", "", List.of(), "", "", "", "", List.of(),
            "1.0", 8, id + ".Main", hasUi ? id + ".Ui" : "", false, List.of(), List.of(), List.of(),
            Path.of(id + ".jar"), 0L, id);
    }
}
