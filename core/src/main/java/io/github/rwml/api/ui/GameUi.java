/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.ui;

/** 引擎就绪后由宿主提供给游戏界面的数据；引擎对象不会通过此 API 暴露。 */
public interface GameUi {

    record Snapshot(boolean available, boolean demo, boolean canResume, String version) {
        public static final Snapshot UNAVAILABLE = new Snapshot(false, false, false, "");
    }

    Snapshot snapshot();

    /** 游戏没有对应键的译文时返回 null。 */
    String text(String key);

    String mapName(String displayName);

    /** 应用界面语言偏好；语言标签为空时使用系统语言。 */
    void language(String tag);
}
