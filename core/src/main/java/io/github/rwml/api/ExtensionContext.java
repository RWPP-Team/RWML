/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api;

import io.github.rwml.api.ui.UiServices;

import java.io.IOException;
import java.nio.file.Path;

/** 扩展加载时提供给它的运行时服务。 */
public interface ExtensionContext {

    /** 游戏初始化前、执行 onLoad 时即可使用界面服务。 */
    default UiServices ui() {
        RwmlRuntime runtime = Rwml.get();
        if (runtime == null) { throw new IllegalStateException("No runtime installed"); }
        return runtime.ui();
    }

    /** @return 所属扩展声明的 ID。 */
    String extensionId();

    /** @return 专供此扩展使用的私有可写目录。 */
    Path dataDirectory();

    /** @return 负责加载此扩展的隔离类加载器。 */
    ClassLoader classLoader();

    /**
     * Owns a registration or other resource until this extension is unloaded or fails to load.
     * Resources close in reverse registration order. Closing one early must be idempotent.
     */
    default <T extends AutoCloseable> T own(T resource) {
        throw new UnsupportedOperationException("This context does not support resource ownership");
    }

    /**
     * @return 游戏资源树的只读访问接口；桌面版将资源存放在目录中，Android 应用则将资源放在 APK 内
     */
    default GameAssets gameAssets() {
        return GameAssets.none();
    }

    /**
     * Extracts a native library entry embedded in this extension archive to a stable private file.
     * The caller can pass the returned absolute path to {@link System#load(String)}.
     *
     * <p>{@code archiveEntry} is an exact JAR entry path, for example
     * {@code jni/arm64-v8a/liblua54.so}. The host does not select an ABI or load the library on the
     * extension's behalf.</p>
     *
     * @throws IOException if the archive entry cannot be read or extracted
     * @throws UnsupportedOperationException if this context is not backed by an extension archive
     */
    default Path extractNativeLibrary(String archiveEntry) throws IOException {
        throw new UnsupportedOperationException("This extension context cannot extract native libraries");
    }

    /**
     * @return mixin 桥接接口，可用于以编程方式注册 mixin，或转换扩展自行加载的类字节缓冲区。
     */
    MixinBridge mixin();

    /** 向宿主日志写入一条信息。 */
    void log(String message);

    /** 向宿主日志写入错误信息和堆栈跟踪。 */
    void log(String message, Throwable error);
}
