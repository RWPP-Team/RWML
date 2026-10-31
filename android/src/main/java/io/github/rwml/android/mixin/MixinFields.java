/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.mixin;

/**
 * 按名称读写游戏对象字段。
 *
 * <p>资源包的图片、音效与音乐处理需要构造游戏类型并设置其私有字段。字段名来自游戏归档，处理方法在应用加载器中定义，
 * 因此只能通过反射访问；包装类型使用 {@link Object}，避免在处理方法签名中出现游戏类型。</p>
 */
final class MixinFields {

    private MixinFields() {
    }

    /** @return 字段值；字段不存在时返回 null */
    static Object read(Object instance, String name) {
        if (instance == null) {
            return null;
        }
        for (Class<?> type = instance.getClass(); type != null; type = type.getSuperclass()) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(instance);
            } catch (NoSuchFieldException missing) {
                // 继续查找父类。
            } catch (ReflectiveOperationException | RuntimeException unreadable) {
                return null;
            }
        }
        return null;
    }

    /** 写入字段；失败时抛出异常，使 mixin 放弃接管并让游戏使用原版资源。 */
    static void write(Object instance, String name, Object value) throws ReflectiveOperationException {
        for (Class<?> type = instance.getClass(); type != null; type = type.getSuperclass()) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(instance, value);
                return;
            } catch (NoSuchFieldException missing) {
                // 继续查找父类。
            }
        }
        throw new NoSuchFieldException(name + " on " + instance.getClass().getName());
    }
}
