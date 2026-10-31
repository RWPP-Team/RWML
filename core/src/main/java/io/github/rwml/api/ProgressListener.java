/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api;

/** 接收加载过程中的粗略进度通知。 */
@FunctionalInterface
public interface ProgressListener {

    /** 调用方不关心进度时使用的共享空操作实例。 */
    ProgressListener NOOP = (percent, message) -> {
    };

    /**
     * @param percent 完成百分比，范围为 {@code [0, 100]}
     * @param message 简短易读的状态信息
     */
    void onProgress(int percent, String message);
}