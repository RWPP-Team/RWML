/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml;

import io.github.rwml.core.ExtensionDescriptor;
import io.github.rwml.core.ExtensionSelection;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ExtensionSelectionTest {
    private final ExtensionDescriptor builtin = ExtensionDescriptor.builtIn("builtin", "Builtin", "1", "", "Main", "Ui");
    @Test void externalWinsEvenWhenBuiltinAppearsFirstAndOtherUisAreSuppressed() {
        ExtensionSelection plan = ExtensionSelection.resolve(List.of(builtin, ext("one", true),
            ext("two", true), ext("ordinary", false)), Set.of());
        assertEquals("one", plan.framework().id());
        assertEquals(List.of("one", "ordinary"), ids(plan.enabled()));
        assertEquals(Set.of("builtin", "two"), Set.copyOf(plan.disabled()));
    }
    @Test void earlyMountContainsOnlyUiAndTransitiveHardDependencies() {
        ExtensionSelection plan = ExtensionSelection.resolve(List.of(ext("ui", true, "dep"),
            ext("dep", false, "base"), ext("base", false), ext("later", false), builtin), Set.of());
        assertEquals(List.of("base", "dep", "ui"), ids(plan.interfaceArchives()));
    }
    @Test void disabledOrBrokenUiFallsBackWithoutEnablingOtherUiDependencies() {
        ExtensionSelection plan = ExtensionSelection.resolve(List.of(ext("broken", true, "missing"),
            ext("disabled", true), builtin), Set.of("disabled"));
        assertEquals("builtin", plan.framework().id());
        assertEquals(List.of("builtin"), ids(plan.enabled()));
        assertTrue(plan.interfaceArchives().isEmpty());
    }
    @Test void dependencyOnSuppressedUiCannotSilentlyEnableASecondFramework() {
        ExtensionSelection plan = ExtensionSelection.resolve(List.of(ext("chosen", true),
            ext("other", true), ext("needs-other", false, "other"), builtin), Set.of());
        assertEquals(List.of("chosen"), ids(plan.enabled()));
        assertTrue(plan.excluded().containsKey("needs-other"));
    }
    @Test void disablingBuiltinAndAllExternalUisLeavesOrdinaryExtensionsHeadless() {
        ExtensionSelection plan = ExtensionSelection.resolve(List.of(ext("ui", true), ext("ordinary", false), builtin),
            Set.of("ui", "builtin"));
        assertNull(plan.framework());
        assertEquals(List.of("ordinary"), ids(plan.enabled()));
    }
    private static List<String> ids(List<ExtensionDescriptor> descriptors) {
        return descriptors.stream().map(ExtensionDescriptor::id).toList();
    }
    private static ExtensionDescriptor ext(String id, boolean ui, String... deps) {
        return new ExtensionDescriptor(id, id, "1", "", List.of(), "", "", "", "", List.of(), "1", 8,
            "Main", ui ? "Ui" : "", List.of(deps), List.of(), List.of(), Path.of(id + ".jar"), 0, id);
    }
}
