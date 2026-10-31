/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 一个已安装版本必须满足的版本范围。
 *
 * <p>说明文件的 {@code dependsOn} 中，依赖 ID 之后的部分即约束文本。支持的写法：</p>
 *
 * <ul>
 *   <li>{@code *} 或空：接受任何版本</li>
 *   <li>{@code >=1.2}、{@code >1.2}、{@code <=2.0}、{@code <2.0}：单侧边界</li>
 *   <li>{@code =1.2.3} 或 {@code 1.2.3}：精确版本，{@code 1.2} 与 {@code 1.2.0} 视为相同</li>
 *   <li>{@code ^1.2.3}：兼容范围，{@code [1.2.3, 2.0)}；首个非零段决定上界，因此 {@code ^0.2.3} 是 {@code [0.2.3, 0.3)}</li>
 *   <li>{@code ~1.2.3}：次级范围，{@code [1.2.3, 1.3)}</li>
 *   <li>{@code 1.2.x}、{@code 1.2.*}：通配段，{@code [1.2, 1.3)}</li>
 *   <li>{@code [1.2,2.0)}、{@code (1.2,2.0]}、{@code [1.2,)}、{@code [,2.0]}：显式区间</li>
 *   <li>多个边界可以用空格或逗号连接，例如 {@code >=1.2 <2.0}</li>
 * </ul>
 *
 * <p>无法解析的约束不会抛出异常。这样的约束永不满足，声明它的扩展会被排除并得到可读原因，因此一个笔误不会阻止其他扩展
 * 加载。</p>
 */
public final class VersionConstraint {

    /** 接受任何版本的约束。 */
    public static final VersionConstraint ANY = new VersionConstraint("*", Collections.<Bound>emptyList(),
        Collections.<Bound>emptyList(), false);

    private final String text;
    private final List<Bound> lower;
    private final List<Bound> upper;
    private final boolean invalid;

    private VersionConstraint(String text, List<Bound> lower, List<Bound> upper, boolean invalid) {
        this.text = text;
        this.lower = lower;
        this.upper = upper;
        this.invalid = invalid;
    }

    /** @return 永不满足的约束，用于报告无法解析的依赖写法 */
    public static VersionConstraint invalid(String text) {
        return new VersionConstraint(text == null ? "" : text.trim(), Collections.<Bound>emptyList(),
            Collections.<Bound>emptyList(), true);
    }

    /** @return 解析后的约束；空字符串与 {@code *} 得到 {@link #ANY} */
    public static VersionConstraint parse(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty() || "*".equals(text) || "x".equalsIgnoreCase(text)) { return ANY; }
        try {
            return text.startsWith("[") || text.startsWith("(") ? range(text) : bounds(text);
        } catch (RuntimeException failure) {
            return invalid(text);
        }
    }

    /** @return 约束原文，用于诊断信息 */
    public String text() { return text; }

    /** @return 约束是否因为写法无法解析而永不满足 */
    public boolean invalid() { return invalid; }

    /** @return 接受的版本中最低与最高的边界；没有边界时返回 {@code null} */
    public String lowest() {
        Bound bound = null;
        for (Bound candidate : lower) { if (bound == null || candidate.version.compareTo(bound.version) > 0) { bound = candidate; } }
        return bound == null ? null : bound.version.text();
    }

    /** @return {@code version} 是否满足本约束 */
    public boolean matches(String version) {
        if (invalid) { return false; }
        ExtensionVersion candidate = ExtensionVersion.parse(version);
        for (Bound bound : lower) {
            if (!bound.allowsAsLower(candidate)) { return false; }
        }
        for (Bound bound : upper) {
            if (!bound.allowsAsUpper(candidate)) { return false; }
        }
        return true;
    }

    @Override
    public String toString() { return text; }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    private static VersionConstraint bounds(String text) {
        List<Bound> lower = new ArrayList<>();
        List<Bound> upper = new ArrayList<>();
        int index = 0;
        boolean parsed = false;
        while (index < text.length()) {
            char character = text.charAt(index);
            if (isSeparator(character)) { index++; continue; }
            String operator = "";
            for (String candidate : new String[]{">=", "<=", "==", ">", "<", "=", "^", "~"}) {
                if (text.startsWith(candidate, index)) { operator = candidate; index += candidate.length(); break; }
            }
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) { index++; }
            int start = index;
            while (index < text.length() && !isSeparator(text.charAt(index))
                && !isOperatorStart(text.charAt(index))) {
                index++;
            }
            String version = text.substring(start, index).trim();
            if (version.isEmpty()) { throw new IllegalArgumentException(text); }
            add(lower, upper, operator, version);
            parsed = true;
        }
        if (!parsed) { throw new IllegalArgumentException(text); }
        return new VersionConstraint(text, Collections.unmodifiableList(lower),
            Collections.unmodifiableList(upper), false);
    }

    private static VersionConstraint range(String text) {
        List<Bound> lower = new ArrayList<>();
        List<Bound> upper = new ArrayList<>();
        char open = text.charAt(0);
        char close = text.charAt(text.length() - 1);
        if ((close != ')' && close != ']') || text.length() < 2) { throw new IllegalArgumentException(text); }
        String content = text.substring(1, text.length() - 1);
        String[] parts = content.split(",", -1);
        if (parts.length == 1) {
            String exact = parts[0].trim();
            if (exact.isEmpty()) { return ANY; }
            add(lower, upper, "=", exact);
            return new VersionConstraint(text, Collections.unmodifiableList(lower),
                Collections.unmodifiableList(upper), false);
        }
        if (parts.length != 2) { throw new IllegalArgumentException(text); }
        String minimum = parts[0].trim();
        String maximum = parts[1].trim();
        if (!minimum.isEmpty()) {
            lower.add(new Bound(ExtensionVersion.parse(minimum), open == '['));
        }
        if (!maximum.isEmpty()) {
            upper.add(new Bound(ExtensionVersion.parse(maximum), close == ']'));
        }
        return new VersionConstraint(text, Collections.unmodifiableList(lower),
            Collections.unmodifiableList(upper), false);
    }

    private static void add(List<Bound> lower, List<Bound> upper, String operator, String version) {
        switch (operator) {
            case ">=": lower.add(new Bound(ExtensionVersion.parse(version), true)); return;
            case ">": lower.add(new Bound(ExtensionVersion.parse(version), false)); return;
            case "<=": upper.add(new Bound(ExtensionVersion.parse(version), true)); return;
            case "<": upper.add(new Bound(ExtensionVersion.parse(version), false)); return;
            case "^": {
                lower.add(new Bound(ExtensionVersion.parse(version), true));
                upper.add(new Bound(ExtensionVersion.parse(compatibleCeiling(version)), false));
                return;
            }
            case "~": {
                lower.add(new Bound(ExtensionVersion.parse(version), true));
                upper.add(new Bound(ExtensionVersion.parse(nextMinor(version)), false));
                return;
            }
            default: {
                if (hasWildcard(version)) {
                    lower.add(new Bound(ExtensionVersion.parse(numericPrefix(version)), true));
                    upper.add(new Bound(ExtensionVersion.parse(incrementLast(numericPrefix(version))), false));
                } else {
                    ExtensionVersion exact = ExtensionVersion.parse(version);
                    lower.add(new Bound(exact, true));
                    upper.add(new Bound(exact, true));
                }
            }
        }
    }

    /** @return {@code ^} 的上界：首个非零段加一，其后归零 */
    private static String compatibleCeiling(String version) {
        List<Integer> numbers = ExtensionVersion.parse(version).numbers();
        int target = numbers.size() - 1;
        for (int index = 0; index < numbers.size(); index++) {
            if (numbers.get(index) != 0) { target = index; break; }
        }
        StringBuilder result = new StringBuilder();
        for (int index = 0; index <= target; index++) {
            if (index > 0) { result.append('.'); }
            result.append(index == target ? numbers.get(index) + 1 : numbers.get(index));
        }
        return result.toString();
    }

    /** @return {@code ~} 的上界：主版本保留，次版本加一，例如 {@code 1.2.3} 得到 {@code 1.3} */
    private static String nextMinor(String version) {
        List<Integer> numbers = ExtensionVersion.parse(version).numbers();
        if (numbers.size() == 1) { return Integer.toString(numbers.get(0) + 1); }
        return numbers.get(0) + "." + (numbers.get(1) + 1);
    }

    private static boolean hasWildcard(String version) {
        for (String part : version.split("\\.")) {
            String lower = part.trim().toLowerCase(Locale.ROOT);
            if (lower.equals("*") || lower.equals("x")) { return true; }
        }
        return false;
    }

    /** @return 通配段之前的数字部分；{@code 1.2.x} 得到 {@code 1.2} */
    private static String numericPrefix(String version) {
        StringBuilder result = new StringBuilder();
        for (String part : version.split("\\.")) {
            String lower = part.trim().toLowerCase(Locale.ROOT);
            if (lower.equals("*") || lower.equals("x")) { break; }
            if (result.length() > 0) { result.append('.'); }
            result.append(part.trim());
        }
        return result.length() == 0 ? "0" : result.toString();
    }

    /** @return 最后一段加一后的版本，{@code 1.2} 得到 {@code 1.3} */
    static String incrementLast(String version) {
        List<Integer> numbers = new ArrayList<>(ExtensionVersion.parse(version).numbers());
        numbers.set(numbers.size() - 1, numbers.get(numbers.size() - 1) + 1);
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < numbers.size(); index++) {
            if (index > 0) { result.append('.'); }
            result.append(numbers.get(index));
        }
        return result.toString();
    }

    private static boolean isSeparator(char character) {
        return Character.isWhitespace(character) || character == ',' || character == ';';
    }

    static boolean isOperatorStart(char character) {
        return character == '<' || character == '>' || character == '=' || character == '^'
            || character == '~' || character == '[' || character == '(';
    }

    /** 一条半开或闭合的边界。 */
    private static final class Bound {
        private final ExtensionVersion version;
        private final boolean inclusive;

        private Bound(ExtensionVersion version, boolean inclusive) {
            this.version = version;
            this.inclusive = inclusive;
        }

        private boolean allowsAsLower(ExtensionVersion candidate) {
            int comparison = candidate.compareTo(version);
            return comparison > 0 || (comparison == 0 && inclusive);
        }

        private boolean allowsAsUpper(ExtensionVersion candidate) {
            int comparison = candidate.compareTo(version);
            return comparison < 0 || (comparison == 0 && inclusive);
        }
    }
}
