/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api;

import java.nio.file.Path;

/**
 * 扩展通过 {@link ExtensionContext#mixin()} 获取的字节码补丁服务。
 *
 * <p>两个宿主都会实现此接口，方法的具体行为取决于宿主。桌面宿主会安装实时转换器，因此任何时候注册的 mixin
 * 都会应用于尚未加载的类。Android 宿主没有类文件转换器，会在进程开始使用已修补类之前应用 mixin；运行时只有
 * {@link #transform} 可用，并且它会处理调用方提供的缓冲区。</p>
 */
public interface MixinBridge {

    /**
     * 使已注册的 mixin 生效。此方法可重复调用，且在注册任何 mixin 前调用也是安全的。
     */
    void install();

    /**
     * 注册扩展归档中声明的所有 mixin。
     *
     * @throws ExtensionException 任一 mixin 无法注册时抛出。若继续运行，扩展会在补丁未生效的情况下启动，
     *     这比不加载扩展更糟。
     */
    void registerArchive(Path archive) throws ExtensionException;

    /**
     * 注册单个 mixin 类。
     *
     * @throws ExtensionException 无法注册时抛出
     */
    void register(Class<?> mixin) throws ExtensionException;

    /**
     * 将所有已注册的 mixin 应用于一个类字节缓冲区。
     *
     * @param internalName 内部形式的类名，例如 {@code com/example/Foo}
     * @return 改写后的缓冲区；未匹配任何 mixin 时返回未修改的输入
     */
    byte[] transform(String internalName, byte[] classfileBuffer, ClassLoader loader);
}
