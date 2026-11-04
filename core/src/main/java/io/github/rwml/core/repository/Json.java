/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core.repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 目录使用的严格 JSON 读取器。
 *
 * <p>核心模块不依赖任何第三方库，因此仓库自带的目录解析只使用这段实现。它按 RFC 8259 读取一个值，遇到多余内容、
 * 未闭合的字符串或过深的嵌套都会拒绝，而不是返回部分结果。解析结果为 {@code Map}、{@code List}、{@code String}、
 * {@code Double}、{@code Boolean} 或 {@code null}。</p>
 */
public final class Json {

    private static final int MAX_DEPTH = 64;

    private final String text;
    private int index;

    private Json(String text) {
        this.text = text;
    }

    /**
     * @param text JSON 文档
     * @return 解析后的值
     * @throws IllegalArgumentException 文档格式错误
     */
    public static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("JSON text is null");
        }
        Json reader = new Json(text);
        reader.skipWhitespace();
        Object value = reader.value(0);
        reader.skipWhitespace();
        if (reader.index != text.length()) {
            throw reader.error("trailing content");
        }
        return value;
    }

    /** @return {@code value} 作为对象；类型不符时返回空映射 */
    public static Map<String, Object> object(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return result;
        }
        return Collections.emptyMap();
    }

    /** @return {@code value} 作为数组；类型不符时返回空列表 */
    public static List<Object> array(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return Collections.emptyList();
    }

    /** @return {@code value} 作为字符串；类型不符时返回 {@code fallback} */
    public static String string(Object value, String fallback) {
        return value instanceof String string ? string : fallback;
    }

    /** @return {@code value} 作为整数；类型不符时返回 {@code fallback} */
    public static long integer(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String string) {
            try {
                return Long.parseLong(string.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /** @return 字符串数组；非字符串成员被忽略 */
    public static List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        for (Object element : array(value)) {
            if (element instanceof String string && !string.isBlank()) {
                result.add(string.trim());
            }
        }
        return result;
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) {
            throw error("nesting is too deep");
        }
        if (index >= text.length()) {
            throw error("unexpected end of input");
        }
        char character = text.charAt(index);
        return switch (character) {
            case '{' -> objectValue(depth);
            case '[' -> arrayValue(depth);
            case '"' -> stringValue();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> numberValue();
        };
    }

    private Map<String, Object> objectValue(int depth) {
        index++;
        Map<String, Object> result = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            index++;
            return result;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("object key must be a string");
            }
            String key = stringValue();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            result.put(key, value(depth + 1));
            skipWhitespace();
            char separator = next();
            if (separator == '}') {
                return result;
            }
            if (separator != ',') {
                throw error("expected ',' or '}'");
            }
        }
    }

    private List<Object> arrayValue(int depth) {
        index++;
        List<Object> result = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            index++;
            return result;
        }
        while (true) {
            skipWhitespace();
            result.add(value(depth + 1));
            skipWhitespace();
            char separator = next();
            if (separator == ']') {
                return result;
            }
            if (separator != ',') {
                throw error("expected ',' or ']'");
            }
        }
    }

    private String stringValue() {
        expect('"');
        StringBuilder builder = new StringBuilder();
        while (true) {
            if (index >= text.length()) {
                throw error("unterminated string");
            }
            char character = text.charAt(index++);
            if (character == '"') {
                return builder.toString();
            }
            if (character != '\\') {
                if (character < 0x20) {
                    throw error("control character in string");
                }
                builder.append(character);
                continue;
            }
            if (index >= text.length()) {
                throw error("unterminated escape");
            }
            char escape = text.charAt(index++);
            switch (escape) {
                case '"' -> builder.append('"');
                case '\\' -> builder.append('\\');
                case '/' -> builder.append('/');
                case 'b' -> builder.append('\b');
                case 'f' -> builder.append('\f');
                case 'n' -> builder.append('\n');
                case 'r' -> builder.append('\r');
                case 't' -> builder.append('\t');
                case 'u' -> builder.append(unicode());
                default -> throw error("unknown escape \\" + escape);
            }
        }
    }

    private char unicode() {
        if (index + 4 > text.length()) {
            throw error("truncated unicode escape");
        }
        String digits = text.substring(index, index + 4);
        try {
            char value = (char) Integer.parseInt(digits, 16);
            index += 4;
            return value;
        } catch (NumberFormatException invalid) {
            throw error("invalid unicode escape \\u" + digits);
        }
    }

    private Object numberValue() {
        int start = index;
        if (peek() == '-') {
            index++;
        }
        while (index < text.length() && Character.isDigit(text.charAt(index))) {
            index++;
        }
        boolean fractional = false;
        if (index < text.length() && text.charAt(index) == '.') {
            fractional = true;
            index++;
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
        }
        if (index < text.length() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
            fractional = true;
            index++;
            if (index < text.length() && (text.charAt(index) == '+' || text.charAt(index) == '-')) {
                index++;
            }
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
        }
        String literal = text.substring(start, index);
        if (literal.isEmpty() || literal.equals("-")) {
            throw error("expected a value");
        }
        try {
            return fractional ? Double.valueOf(literal) : Long.valueOf(literal);
        } catch (NumberFormatException invalid) {
            throw error("invalid number " + literal);
        }
    }

    private Object literal(String literal, Object value) {
        if (!text.startsWith(literal, index)) {
            throw error("expected " + literal);
        }
        index += literal.length();
        return value;
    }

    private void skipWhitespace() {
        while (index < text.length()) {
            char character = text.charAt(index);
            if (character == ' ' || character == '\t' || character == '\n' || character == '\r') {
                index++;
            } else {
                return;
            }
        }
    }

    private char peek() {
        if (index >= text.length()) {
            throw error("unexpected end of input");
        }
        return text.charAt(index);
    }

    private char next() {
        char character = peek();
        index++;
        return character;
    }

    private void expect(char expected) {
        if (peek() != expected) {
            throw error("expected '" + expected + "'");
        }
        index++;
    }

    private IllegalArgumentException error(String message) {
        int line = 1;
        int column = 1;
        for (int position = 0; position < Math.min(index, text.length()); position++) {
            if (text.charAt(position) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new IllegalArgumentException("Invalid JSON at line " + line + ", column " + column + ": " + message);
    }
}
