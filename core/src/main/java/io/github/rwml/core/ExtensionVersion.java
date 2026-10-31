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
 * 扩展版本号及其比较规则。
 *
 * <p>版本号按点分数字比较，缺失的段视为 0，因此 {@code 1.2} 与 {@code 1.2.0} 相等。连字符或下划线之后的部分是预发布
 * 或补丁标识，它让同一数字序列产生顺序差异：{@code 1.0.0-beta} 低于 {@code 1.0.0}，{@code 1.0.0-sp1} 高于
 * {@code 1.0.0}。加号之后的构建元数据被忽略。</p>
 *
 * <p>顺序解析不会抛出异常：说明文件中的版本字符串来自第三方作者，一个无法识别的版本号不应阻止宿主启动，因此无法解析的
 * 部分按 0 处理。</p>
 */
public final class ExtensionVersion implements Comparable<ExtensionVersion> {

    /** 质保标识的排序权重；浮点数不方便，因此用整数，数值越小越早。 */
    private static final List<String> QUALIFIERS =
        Collections.unmodifiableList(java.util.Arrays.asList("alpha", "beta", "milestone", "rc", "snapshot"));

    private final String text;
    private final List<Integer> numbers;
    private final String qualifier;

    private ExtensionVersion(String text, List<Integer> numbers, String qualifier) {
        this.text = text;
        this.numbers = numbers;
        this.qualifier = qualifier;
    }

    /** @return 输入中的版本号；{@code null} 或空字符串视为 {@code 0} */
    public static ExtensionVersion parse(String value) {
        String text = value == null ? "" : value.trim();
        String body = text;
        if (!body.isEmpty() && (body.charAt(0) == 'v' || body.charAt(0) == 'V')) { body = body.substring(1); }
        int metadata = body.indexOf('+');
        if (metadata >= 0) { body = body.substring(0, metadata); }

        String qualifier = "";
        int qualifierStart = -1;
        for (int index = 0; index < body.length(); index++) {
            char character = body.charAt(index);
            if (character == '-' || character == '_') { qualifierStart = index; break; }
        }
        if (qualifierStart >= 0) {
            qualifier = body.substring(qualifierStart + 1).trim().toLowerCase(Locale.ROOT);
            body = body.substring(0, qualifierStart);
        }

        List<Integer> numbers = new ArrayList<>();
        for (String part : body.split("\\.")) {
            String trimmed = part.trim();
            int end = 0;
            while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) { end++; }
            numbers.add(number(trimmed.substring(0, end)));
            if (end < trimmed.length() && qualifier.isEmpty()) {
                qualifier = trimmed.substring(end).toLowerCase(Locale.ROOT);
            }
        }
        if (numbers.isEmpty()) { numbers.add(0); }
        return new ExtensionVersion(text, Collections.unmodifiableList(numbers), qualifier);
    }

    /** @return 按本类规则比较两个版本字符串，无法解析的部分按 0 处理 */
    public static int compare(String left, String right) {
        return parse(left).compareTo(parse(right));
    }

    /** @return 原始版本字符串，用于诊断信息 */
    public String text() { return text; }

    /** @return 数字部分；不可变 */
    public List<Integer> numbers() { return numbers; }

    /** @return 小写预发布标识；正式版本为空字符串 */
    public String qualifier() { return qualifier; }

    @Override
    public int compareTo(ExtensionVersion other) {
        int size = Math.max(numbers.size(), other.numbers.size());
        for (int index = 0; index < size; index++) {
            int mine = index < numbers.size() ? numbers.get(index) : 0;
            int theirs = index < other.numbers.size() ? other.numbers.get(index) : 0;
            if (mine != theirs) { return mine < theirs ? -1 : 1; }
        }
        int rank = Integer.compare(rank(qualifier), rank(other.qualifier));
        if (rank != 0) { return rank; }
        int mine = trailingNumber(qualifier);
        int theirs = trailingNumber(other.qualifier);
        if (mine != theirs) { return mine < theirs ? -1 : 1; }
        return name(qualifier).compareTo(name(other.qualifier));
    }

    @Override
    public boolean equals(Object candidate) {
        return candidate instanceof ExtensionVersion && compareTo((ExtensionVersion) candidate) == 0;
    }

    @Override
    public int hashCode() {
        return numbers.hashCode() * 31 + qualifier.hashCode();
    }

    @Override
    public String toString() { return text; }

    /**
     * 预发布标识的排序权重。正式版本高于任何预发布版本；补丁版本高于正式版本，因为 {@code 1.0.0-sp1} 是
     * {@code 1.0.0} 的修订。
     */
    static int rank(String qualifier) {
        if (qualifier.isEmpty()) { return 0; }
        String name = name(qualifier);
        if (name.equals("sp") || name.equals("final") || name.equals("ga") || name.equals("release")
            || name.equals("r")) {
            return 1;
        }
        int index = QUALIFIERS.indexOf(name);
        if (index >= 0) { return index - QUALIFIERS.size(); }
        return -QUALIFIERS.size() - 1;
    }

    /** @return 标识开头的字母部分；{@code beta2} 得到 {@code beta} */
    static String name(String qualifier) {
        for (int index = 0; index < qualifier.length(); index++) {
            if (!Character.isLetter(qualifier.charAt(index))) { return qualifier.substring(0, index); }
        }
        return qualifier;
    }

    /** @return 标识末尾的数字；{@code beta2} 得到 2 */
    static int trailingNumber(String qualifier) {
        int start = qualifier.length();
        while (start > 0 && Character.isDigit(qualifier.charAt(start - 1))) { start--; }
        return number(qualifier.substring(start));
    }

    private static int number(String digits) {
        if (digits == null || digits.isEmpty()) { return 0; }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException tooLarge) {
            return Integer.MAX_VALUE;
        }
    }
}
