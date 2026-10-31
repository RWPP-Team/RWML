/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.bootstrap;

/**
 * 读取启动器当前运行所用 JVM 的主版本。
 *
 * <p>用于提供该版本的两个属性在不同发行版中使用过不同格式（{@code 1.8}、{@code 9}、{@code 25}、{@code 25.0.4.1}），
 * 因此会同时检查两个属性，并将结果规范化为纯主版本号。这里只使用 {@link System#getProperty}：不使用 JMX 或
 * {@code Runtime.version()}，也不会因此要求 JVM 高于编译此类所用的 Java 8。</p>
 */
public final class JvmVersion {

    private JvmVersion() {
    }

    /**
     * @return 当前 JVM 的主版本（8、11、17、25 等）；无法确定时返回 {@code -1}
     */
    public static int current() {
        int fromSpecification = parseFeature(System.getProperty("java.specification.version"));
        if (fromSpecification > 0) {
            return fromSpecification;
        }
        return parseFeature(System.getProperty("java.version"));
    }

    /**
     * 将历史版本格式规范化为主版本号。
     *
     * @param raw 原始版本，例如 {@code "1.8"}、{@code "9"}、{@code "25.0.4.1"} 或 {@code null}
     * @return 主版本号；无法解析时返回 {@code -1}
     */
    static int parseFeature(String raw) {
        if (raw == null) {
            return -1;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return -1;
        }
        // 解析数字前移除诸如 "-ea"、"+1" 或 "-internal" 的后缀。
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
            // "1.8" / "1.7"：主版本号是第二部分。
            if (first == 1 && parts.length > 1) {
                return Integer.parseInt(parts[1]);
            }
            return first;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    /** @return 当前运行 JVM 的描述，用于日志记录 */
    public static String describe() {
        String version = System.getProperty("java.version", "unknown");
        String vendor = System.getProperty("java.vendor", "unknown");
        return version + " (" + vendor + ")";
    }
}
