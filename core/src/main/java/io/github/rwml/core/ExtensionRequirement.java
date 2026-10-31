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

/**
 * {@code dependsOn} 中的一项：扩展 ID 加可选的版本约束。
 *
 * <p>写法是 {@code id}、{@code id>=1.2.0}、{@code id^1.2}、{@code id[1.0,2.0)}。约束语法由
 * {@link VersionConstraint} 定义。未写约束时接受任何版本，这与 1.0.2 及更早版本的说明文件完全兼容。</p>
 */
public record ExtensionRequirement(String id, VersionConstraint constraint) {

    public ExtensionRequirement {
        if (id == null || id.trim().isEmpty()) { throw new IllegalArgumentException("A dependency needs an id"); }
        if (constraint == null) { constraint = VersionConstraint.ANY; }
    }

    /**
     * 解析一项依赖声明。
     *
     * @param token 类似 {@code lib>=2.0} 的文本
     * @return 解析结果；空文本或缺少依赖 ID 时返回 {@code null}，调用方应忽略该项
     */
    public static ExtensionRequirement parse(String token) {
        if (token == null) { return null; }
        String text = token.trim();
        if (text.isEmpty()) { return null; }
        int split = -1;
        for (int index = 0; index < text.length(); index++) {
            if (VersionConstraint.isOperatorStart(text.charAt(index))) { split = index; break; }
        }
        if (split < 0) { return new ExtensionRequirement(text, VersionConstraint.ANY); }
        String id = text.substring(0, split).trim();
        if (id.isEmpty()) { return null; }
        return new ExtensionRequirement(id, VersionConstraint.parse(text.substring(split)));
    }

    /**
     * 把 {@code dependsOn} 的原始值拆成若干项。
     *
     * <p>逗号与分号分隔各项；空白在旧写法中同样分隔各项（{@code a b}），但在约束内部只表示排版，因此
     * {@code a >= 1.2} 与 {@code a>=1.2} 得到同一项。</p>
     *
     * @return 每项一条的文本，尚未解析
     */
    public static List<String> tokenize(String raw) {
        List<String> tokens = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) { return Collections.emptyList(); }
        int index = 0;
        int length = raw.length();
        while (index < length) {
            char character = raw.charAt(index);
            if (isSeparator(character)) { index++; continue; }
            int start = index;
            while (index < length && !isSeparator(raw.charAt(index)) && !VersionConstraint.isOperatorStart(raw.charAt(index))) {
                index++;
            }
            if (index == start) {
                // 孤立的运算符或括号：跳过该字符，避免死循环。
                index++;
                continue;
            }
            while (index < length) {
                int checkpoint = index;
                while (index < length && Character.isWhitespace(raw.charAt(index))) { index++; }
                if (index >= length) { break; }
                char next = raw.charAt(index);
                if (next == ',' || next == ';') { break; }
                if (!VersionConstraint.isOperatorStart(next)) {
                    index = checkpoint;
                    break;
                }
                while (index < length && isOperatorCharacter(raw.charAt(index))) { index++; }
                while (index < length && Character.isWhitespace(raw.charAt(index))) { index++; }
                if (index < length && (raw.charAt(index) == '[' || raw.charAt(index) == '(')) {
                    index++;
                    while (index < length && raw.charAt(index) != ']' && raw.charAt(index) != ')') { index++; }
                    if (index < length) { index++; }
                } else {
                    while (index < length && isVersionCharacter(raw.charAt(index))) { index++; }
                }
            }
            tokens.add(compact(raw.substring(start, index)));
        }
        return Collections.unmodifiableList(tokens);
    }

    /** @return {@code version} 是否满足本要求 */
    public boolean satisfiedBy(String version) {
        return constraint.matches(version);
    }

    /** @return 是否接受任何版本 */
    public boolean isUnconstrained() { return constraint == VersionConstraint.ANY; }

    /** @return 便于阅读的文本，例如 {@code lib>=2.0} */
    public String text() {
        return isUnconstrained() ? id : id + constraint.text();
    }

    @Override
    public String toString() { return text(); }

    /**
     * 去掉排版用的空白，同时保留各项之间的一个空格。
     *
     * <p>约束内部不允许空白，因此 {@code lib >= 1.2} 归并为 {@code lib>=1.2}；相邻的两条约束保留分隔，
     * {@code lib>=1.2 <2.0} 不会变成 {@code lib>=1.2<2.0}，以免界面把两条约束读成一条无法理解的字符串。</p>
     */
    private static String compact(String token) {
        StringBuilder result = new StringBuilder(token.length());
        boolean sawConstraint = false;
        boolean pendingSpace = false;
        for (int index = 0; index < token.length(); index++) {
            char character = token.charAt(index);
            if (Character.isWhitespace(character)) { pendingSpace = true; continue; }
            boolean constraintStart = isConstraintStart(character);
            if (constraintStart && sawConstraint && pendingSpace) { result.append(' '); }
            result.append(character);
            if (constraintStart) { sawConstraint = true; }
            pendingSpace = false;
        }
        return result.toString();
    }

    private static boolean isConstraintStart(char character) {
        return VersionConstraint.isOperatorStart(character) || character == '<' || character == '>'
            || character == '=' || character == '^' || character == '~';
    }

    private static boolean isSeparator(char character) {
        return Character.isWhitespace(character) || character == ',' || character == ';';
    }

    private static boolean isOperatorCharacter(char character) {
        return character == '<' || character == '>' || character == '=' || character == '^'
            || character == '~' || character == '!';
    }

    private static boolean isVersionCharacter(char character) {
        return Character.isLetterOrDigit(character) || character == '.' || character == '-'
            || character == '_' || character == '+' || character == '*';
    }
}
