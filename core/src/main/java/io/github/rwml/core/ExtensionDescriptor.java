/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * Fully resolved, immutable metadata of a single extension. It merges the
 * values found in {@code META-INF/extension.properties} with the
 * {@code @ExtensionInfo} annotation and with facts observed on the archive
 * itself (size, digest, required bytecode level).
 */
public record ExtensionDescriptor(
    String id,
    String name,
    String version,
    String description,
    List<String> authors,
    String license,
    String licenseUrl,
    String homepage,
    String icon,
    List<String> tags,
    String apiVersion,
    int requiredJavaVersion,
    String mainClass,
    String uiFramework,
    List<String> dependsOn,
    List<String> loadBefore,
    List<String> loadAfter,
    Path source,
    long sourceSize,
    String sourceSha256) {

    /** Returns a copy with the effective Java requirement replaced. */
    public ExtensionDescriptor withRequiredJavaVersion(int javaVersion) {
        return new ExtensionDescriptor(id, name, version, description, authors, license, licenseUrl,
            homepage, icon, tags, apiVersion, javaVersion, mainClass, uiFramework, dependsOn,
            loadBefore, loadAfter,
            source, sourceSize, sourceSha256);
    }

    /**
     * A descriptor for an extension the host carries inside itself.
     *
     * <p>Such an extension has no archive: its classes are part of the host's own class path, which
     * is what lets RWML ship one without a second jar to install and without the archive machinery
     * that a downloaded extension needs. The Java requirement is therefore irrelevant - nothing has
     * to be inspected or reconciled - and {@link #source()} is null.</p>
     *
     * @param id the extension id, which is what the disabled flag is keyed on
     * @param name the display name
     * @param mainClass the entry point, an {@link io.github.rwml.api.Extension} on the host's path
     * @param uiFramework the class implementing {@link io.github.rwml.api.ui.UiFramework}, or null
     * @return a descriptor whose {@link #isBuiltIn()} is true
     */
    public static ExtensionDescriptor builtIn(String id, String name, String version,
                                              String description, String mainClass,
                                              String uiFramework) {
        return new ExtensionDescriptor(id, name, version, description,
            Collections.<String>emptyList(), "Apache-2.0", "", "", "",
            Collections.<String>emptyList(), "1.0", 8, mainClass, uiFramework,
            Collections.<String>emptyList(), Collections.<String>emptyList(),
            Collections.<String>emptyList(), null, 0L, "");
    }

    /**
     * @return whether the host carries this extension inside itself rather than reading it from a
     *     jar in the extension directory
     */
    public boolean isBuiltIn() {
        return source == null;
    }

    @Override
    public String toString() {
        return "ExtensionDescriptor{id='" + id + "', name='" + name + "', version='" + version
            + "', java=" + requiredJavaVersion + ", dependsOn=" + dependsOn
            + ", mainClass='" + mainClass + "', uiFramework='" + uiFramework + "'}";
    }
}