/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.desktop;

import io.github.rwml.api.ExtensionException;
import io.github.rwml.api.MixinBridge;
import io.github.rwml.api.ProgressListener;
import io.github.rwml.asm.MixinSupport;
import io.github.rwml.builtin.BuiltinExtension;
import io.github.rwml.core.ExtensionDescriptor;
import io.github.rwml.host.HostPlatform;
import io.github.rwml.host.ResourcePackEnvironment;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * JVM 宿主平台：提供运行时转换器、为每个扩展创建独立类加载器，并在实例化扩展前检查 Java 版本。
 */
public final class DesktopHost implements HostPlatform {

    private final ResourcePackEnvironment resourcePacks;

    /** 没有游戏目录时无法物化资源包，适合不需要资源的测试宿主。 */
    public DesktopHost() {
        this(null);
    }

    /**
     * @param gameDirectory 游戏所在目录，资源包从其中的 {@code assets} 与 {@code res} 复制基础资源
     */
    public DesktopHost(Path gameDirectory) {
        this.resourcePacks = gameDirectory == null
            ? ResourcePackEnvironment.unsupported()
            : new DesktopResourcePacks(gameDirectory);
    }

    @Override
    public MixinBridge mixins() {
        return MixinSupport.instance();
    }

    @Override
    public ResourcePackEnvironment resourcePacks() {
        return resourcePacks;
    }

    @Override
    public List<String> stageArchives(Path extensionDirectory) throws IOException {
        return Collections.emptyList();
    }

    /**
     * RWML 为桌面端提供的界面。
     *
     * <p>桌面端供玩家使用，因此需要显示游戏；内置扩展负责提供该界面。除此之外不打包其他内容。</p>
     */
    @Override
    public List<ExtensionDescriptor> builtInExtensions() {
        return Collections.singletonList(BuiltinExtension.descriptor());
    }

    /** 每个扩展使用独立的类加载器，因此不同扩展可以使用不同版本的库。 */
    @Override
    public ClassLoader createExtensionLoader(ExtensionDescriptor descriptor) throws IOException {
        return createExtensionLoader(descriptor, DesktopHost.class.getClassLoader());
    }

    /** Each child sees only the dependencies named in its descriptor, through their owning loaders. */
    @Override
    public ClassLoader createExtensionLoader(ExtensionDescriptor descriptor, ClassLoader dependencyParent)
        throws IOException {
        try {
            URL url = descriptor.source().toUri().toURL();
            return new URLClassLoader(new URL[]{url}, dependencyParent);
        } catch (Exception e) {
            throw new IOException("Unable to open " + descriptor.source().getFileName(), e);
        }
    }

    /**
     * 将描述符声明的 Java 要求与归档实际字节码版本、当前运行的 JVM 进行核对。
     */
    @Override
    public ExtensionDescriptor reconcileJavaRequirement(ExtensionDescriptor descriptor,
                                                        ProgressListener progress) throws ExtensionException {
        int bytecodeLevel;
        try {
            bytecodeLevel = JavaVersionUtil.requiredJavaVersion(descriptor.source());
        } catch (IOException e) {
            throw new ExtensionException("Unable to inspect bytecode of "
                + descriptor.source().getFileName(), e);
        }

        int declared = descriptor.requiredJavaVersion();
        int required = Math.max(declared, bytecodeLevel);
        int runtime = JavaVersionUtil.currentJavaVersion();

        if (required > runtime) {
            Path alternative = JavaVersionUtil.findJvmAtLeast(required);
            String hint = alternative != null
                ? " A compatible JVM was found at " + alternative
                + "; restart the host with that JVM to load this extension."
                : " No compatible JVM was found on this machine.";
            throw new ExtensionException(String.format(
                "Extension '%s' requires Java %d (declared %d, bytecode %d) but the running JVM is Java %d.%s",
                descriptor.id(), required, declared, bytecodeLevel, runtime, hint));
        }
        if (bytecodeLevel > declared) {
            progress.onProgress(45, "Raised Java requirement of " + descriptor.id()
                + " from " + declared + " to " + bytecodeLevel);
        }
        return descriptor.withRequiredJavaVersion(required);
    }
}
