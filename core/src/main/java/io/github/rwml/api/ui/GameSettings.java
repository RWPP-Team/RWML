/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.ui;

/** 宿主提供的具名、类型化设置；不可用或不受支持的属性会明确标示。 */
public interface GameSettings {
    <T> T get(String name, Class<T> type);
    void set(String name, Object value);
    void save();
}
