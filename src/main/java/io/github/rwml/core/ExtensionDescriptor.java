package io.github.rwml.core;

import java.nio.file.Path;
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
    List<String> dependsOn,
    List<String> loadBefore,
    List<String> loadAfter,
    long loadTimeoutMillis,
    Path source,
    long sourceSize,
    String sourceSha256) {

    /** Returns a copy with the effective Java requirement replaced. */
    public ExtensionDescriptor withRequiredJavaVersion(int javaVersion) {
        return new ExtensionDescriptor(id, name, version, description, authors, license, licenseUrl,
            homepage, icon, tags, apiVersion, javaVersion, mainClass, dependsOn,
            loadBefore, loadAfter, loadTimeoutMillis,
            source, sourceSize, sourceSha256);
    }

    @Override
    public String toString() {
        return "ExtensionDescriptor{id='" + id + "', name='" + name + "', version='" + version
            + "', java=" + requiredJavaVersion + ", dependsOn=" + dependsOn
            + ", mainClass='" + mainClass + "'}";
    }
}