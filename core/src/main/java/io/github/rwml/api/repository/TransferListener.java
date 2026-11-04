/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.repository;

/**
 * 接收归档下载进度。
 *
 * <p>回调在工作线程上执行，因此界面实现必须自行切换到界面线程。进度以字节为单位，一个条目有多个归档时，进度覆盖全部
 * 归档的总量。</p>
 */
@FunctionalInterface
public interface TransferListener {

    /** 调用方不关心进度时使用的共享空操作实例。 */
    TransferListener NOOP = (downloaded, total, message) -> {
    };

    /**
     * @param downloaded 已接收字节数
     * @param total      预期总字节数；未知时为 0
     * @param message    当前步骤的简短说明
     */
    void onProgress(long downloaded, long total, String message);
}
