/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core;

import io.github.rwml.api.ui.GameSettings;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.function.Supplier;

/** Adapts a host's public scalar settings without exposing its engine or platform classes. */
public final class PropertySettings implements GameSettings {

    private final Supplier<Object> settings;

    public PropertySettings(Supplier<Object> settings) {
        this.settings = settings;
    }

    @Override
    public <T> T get(String name, Class<T> type) {
        Object owner = owner();
        try {
            return type.cast(property(owner, name).get(owner));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("Cannot read setting: " + name, failure);
        }
    }

    @Override
    public void set(String name, Object value) {
        Object owner = owner();
        try {
            Field field = property(owner, name);
            if (Modifier.isFinal(field.getModifiers())) {
                throw new IllegalArgumentException("Read-only setting: " + name);
            }
            field.set(owner, value);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("Cannot write setting: " + name, failure);
        }
    }

    @Override
    public void save() {
        Object owner = owner();
        try {
            Object result = owner.getClass().getMethod("save").invoke(owner);
            if (Boolean.FALSE.equals(result)) { throw new IllegalStateException("The game could not save settings"); }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot save settings", failure);
        }
    }

    private Object owner() {
        Object value = settings.get();
        if (value == null) { throw new IllegalStateException("Game settings are not available"); }
        return value;
    }

    private static Field property(Object owner, String name) throws NoSuchFieldException {
        Field field = owner.getClass().getField(name);
        Class<?> type = field.getType();
        if (Modifier.isStatic(field.getModifiers()) || !(type.isPrimitive() || type == String.class)) {
            throw new IllegalArgumentException("Unsupported setting: " + name);
        }
        return field;
    }
}
