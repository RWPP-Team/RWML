/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.ui;

/** Creates native UI content when a deferred host surface becomes available. */
@FunctionalInterface
public interface UiContentFactory {

    /**
     * Creates platform-native content for the object that owns the target surface.
     *
     * <p>For example, Android may call this after its existing game Activity starts so a framework can create a
     * view with that Activity as its context. Other platforms may pass their native window or host object.</p>
     *
     * @param platform the target surface's opaque native owner
     * @return content accepted by the surface
     */
    Object create(Object platform);
}
