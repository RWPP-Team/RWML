/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Both hosts carry the version of the core and mapped game they were built with. */
final class RwmlVersion {
    private static final String CURRENT = readVersion();

    private RwmlVersion() { }

    static String current() {
        return CURRENT;
    }

    static String incompatibility(ExtensionDescriptor descriptor) {
        String minimum = descriptor.minRwmlVersion();
        if (minimum.isEmpty() || !CURRENT.equals("unknown") && ExtensionVersion.compare(CURRENT, minimum) >= 0) {
            return null;
        }
        return "Incompatible with RWML " + CURRENT + ". Upgrade to RWML " + minimum
            + " or later for this extension's required game mappings.";
    }

    private static String readVersion() {
        try (InputStream stream = RwmlVersion.class.getResourceAsStream("/META-INF/rwml/version.properties")) {
            if (stream != null) {
                Properties properties = new Properties();
                properties.load(stream);
                String version = properties.getProperty("version", "").trim();
                if (!version.isEmpty()) { return version; }
            }
        } catch (IOException ignored) {
            // Archive-only tools may not have the packaged host version. Fail closed for a requirement.
        }
        return "unknown";
    }
}
