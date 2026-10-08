/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.bootstrap;

/**
 * Reads the feature version of the JVM the bootstrap is currently running on.
 *
 * <p>Both properties that carry it changed formats across releases ({@code 1.8}, {@code 9},
 * {@code 25}, {@code 25.0.4.1}), so both are probed and normalised to a bare feature number. Only
 * {@link System#getProperty} is used: no JMX, no {@code Runtime.version()}, nothing that would tie
 * this class to a JVM newer than the Java 8 it is compiled for.</p>
 */
public final class JvmVersion {

    private JvmVersion() {
    }

    /**
     * @return the feature version of the running JVM (8, 11, 17, 25, ...), or {@code -1} when it
     *         cannot be determined.
     */
    public static int current() {
        int fromSpecification = parseFeature(System.getProperty("java.specification.version"));
        if (fromSpecification > 0) {
            return fromSpecification;
        }
        return parseFeature(System.getProperty("java.version"));
    }

    /**
     * Normalises any of the historical version formats to a feature number.
     *
     * @param raw e.g. {@code "1.8"}, {@code "9"}, {@code "25.0.4.1"} or {@code null}
     * @return the feature number, or {@code -1} if it cannot be parsed
     */
    static int parseFeature(String raw) {
        if (raw == null) {
            return -1;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return -1;
        }
        // Drop any suffix such as "-ea", "+1" or "-internal" before looking at the numbers.
        int cut = text.length();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isDigit(c) && c != '.') {
                cut = i;
                break;
            }
        }
        text = text.substring(0, cut);
        if (text.isEmpty()) {
            return -1;
        }
        String[] parts = text.split("\\.");
        try {
            int first = Integer.parseInt(parts[0]);
            // "1.8" / "1.7": the feature version is the second component.
            if (first == 1 && parts.length > 1) {
                return Integer.parseInt(parts[1]);
            }
            return first;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    /** @return a description of the running JVM, for logging. */
    public static String describe() {
        String version = System.getProperty("java.version", "unknown");
        String vendor = System.getProperty("java.vendor", "unknown");
        return version + " (" + vendor + ")";
    }
}
