/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.host;

import io.github.rwml.api.GameAssets;
import io.github.rwml.api.MixinBridge;
import io.github.rwml.api.ProgressListener;
import io.github.rwml.core.ExtensionDescriptor;
import io.github.rwml.api.ExtensionException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * RWML 中因宿主而异的部分。
 *
 * <p>{@link io.github.rwml.core.ExtensionManager} 负责所有宿主共用的功能：发现扩展、读取描述信息、排序依赖、实例化扩展，
 * 以及管理启用或禁用状态。平台相关决策取决于 RWML 的运行环境，因此在此接口中分别定义方法，使桌面和 Android 宿主
 * 可以实现同一接口，而无需维护两份加载器。</p>
 */
public interface HostPlatform {

    /** @return 此宿主提供的字节码补丁服务。 */
    MixinBridge mixins();

    /**
     * @return 游戏资源目录的只读访问接口。
     *
     * <p>资源存放在调用方可直接打开的目录中的宿主会返回 {@link GameAssets#none()}，由扩展自行读取。Android 应用
     * 没有这样的目录，因此其宿主会返回一个用于读取打包资源的实现。</p>
     */
    default GameAssets gameAssets() {
        return GameAssets.none();
    }

    /**
     * @return 宿主为资源包提供的目录与基础资源；不支持资源包时返回 {@link ResourcePackEnvironment#unsupported()}
     */
    default ResourcePackEnvironment resourcePacks() {
        return ResourcePackEnvironment.unsupported();
    }

    /**
     * 描述宿主自身携带的扩展。
     *
     * <p>这些扩展与其他扩展采用相同的加载方式：会排序、实例化、可按 ID 禁用，并出现在加载报告中；但它们没有归档，
     * 类来自宿主自己的类路径。RWML 自带一个用于显示游戏界面的扩展，因此全新安装无需下载任何内容即可启动游戏。</p>
     *
     * <p>宿主不需要此类扩展时返回空列表。若没有扩展声明 {@code ui-framework}，运行时会保持无界面模式。</p>
     *
     * @return 内置扩展描述信息，按加载顺序排列
     */
    List<ExtensionDescriptor> builtInExtensions();

    /**
     * 将宿主自身携带的归档复制到扩展目录，使发现流程能像处理其他扩展一样处理它们。
     *
     * <p>桌面版无需提供归档；Android 应用将扩展打包在 APK 中，因此必须先解压。实现类会比较内容摘要，只复制发生变化的文件，
     * 所以重复调用的开销很小。</p>
     *
     * @return 每个归档一行，描述所执行的操作，供加载报告使用
     */
    List<String> stageArchives(Path extensionDirectory) throws IOException;

    /**
     * @return 负责加载某个扩展的类加载器。
     *
     * <p>桌面宿主为每个扩展提供独立的类加载器。Android 宿主无法如此处理：mixin 会被编译为被修补类中的静态调用，
     * 该调用通过定义被修补类的加载器解析，因此 mixin 必须能从该加载器访问。</p>
     */
    ClassLoader createExtensionLoader(ExtensionDescriptor descriptor) throws IOException;

    /**
     * Creates an extension loader whose parent can see the extension's declared dependencies.
     *
     * <p>The original overload remains the compatibility hook for existing host implementations. Hosts
     * that isolate extensions should use {@code dependencyParent} as the archive loader's parent so
     * shared library classes stay owned by their provider loader.</p>
     */
    default ClassLoader createExtensionLoader(ExtensionDescriptor descriptor, ClassLoader dependencyParent)
        throws IOException {
        return createExtensionLoader(descriptor);
    }

    /**
     * 协调扩展要求的 Java 版本与此宿主实际可运行的版本。
     *
     * <p>只有 JVM 宿主能够根据此结果采取行动。Android 使用平台提供的 Java 版本，因此其实现会原样返回描述信息。</p>
     */
    ExtensionDescriptor reconcileJavaRequirement(ExtensionDescriptor descriptor, ProgressListener progress)
        throws ExtensionException;
}
