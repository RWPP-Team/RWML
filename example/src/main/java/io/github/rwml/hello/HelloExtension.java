/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.hello;

import io.github.rwml.api.Extension;
import io.github.rwml.api.ExtensionContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * 示例扩展的入口。
 *
 * <p>示例展示扩展加载后通常会立即执行的两项操作：</p>
 * <ol>
 *   <li>通过上下文日志记录器报告自身已加载；</li>
 *   <li>在私有数据目录中持久化状态；</li>
 * </ol>
 */
public final class HelloExtension implements Extension {

    @Override
    public void onLoad(ExtensionContext context) throws Exception {
        context.log("Hello Extension starting up");

        Path marker = context.dataDirectory().resolve("last-load.txt");
        // 使用 Files.write 而不是 Files.writeString：此扩展也会在 Android 上运行，而 API 33 以下没有 writeString 方法。
        Files.write(marker, Instant.now().toString().getBytes(StandardCharsets.UTF_8));
        context.log("Recorded this load in " + marker);
    }
}
