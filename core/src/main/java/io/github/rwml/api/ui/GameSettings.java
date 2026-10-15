/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.ui;

/** Named, typed settings supplied by a host. An unavailable or unsupported property is explicit. */
public interface GameSettings {
    <T> T get(String name, Class<T> type);
    void set(String name, Object value);
    void save();
}
