/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import io.github.rwml.api.ExtensionException;
import io.github.rwml.api.MixinBridge;
import io.github.rwml.core.transform.ArchiveClassPatches;
import io.github.rwml.core.transform.ClassFilePatchException;

import kim.der.asm.AsmRegistry;
import kim.der.asm.transformer.AsmProcessor;
import kim.der.asm.transformer.AsmTransformException;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Android 版 {@link MixinBridge}：使用与桌面端相同的注册表，但不依赖 agent。
 *
 * <p>无需安装任何组件，因为类会在运行时加载前完成改写。根据 mixin 改写游戏之前，必须先将其解析为有效的
 * {@code Class}；在 Android 上，这只能通过加载器持有的 dex 实现，因此启动器会先挂载扩展。
 */
public final class OfflineMixinBridge implements MixinBridge {

    /** 框架 mixin 标记注解的 JVM 描述符。 */
    private static final String ASM_MIXIN_DESCRIPTOR = "Lkim/der/asm/api/annotation/AsmMixin;";

    private volatile ClassLoader loader;
    private final java.util.Map<String, Path> owners = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<Path> registered = new java.util.HashSet<>();
    private final AsmProcessor processor = new AsmProcessor();
    private final ArchiveClassPatches classPatches = new ArchiveClassPatches();

    OfflineMixinBridge(ClassLoader loader) {
        this.loader = loader;
    }

    void updateLoader(ClassLoader loader) { this.loader = loader; }

    /**
     * Starts a fresh registration pass against the temporary offline loader.
     *
     * <p>Extensions are loaded once before the game archives are staged so their lifecycle hooks
     * can run. That pass can register mixins through this bridge using the normal extension
     * loader. Offline transformation must register them again from the isolated loader, where
     * game stand-ins are visible. Clearing both caches prevents a prior registration from
     * retaining parent-loaded mixin classes.</p>
     */
    synchronized void resetForOfflineRegistration(ClassLoader registrationLoader) {
        AsmRegistry.clear();
        owners.clear();
        registered.clear();
        classPatches.reset();
        loader = registrationLoader;
    }

    Path failedArchive(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ClassFilePatchException) {
                return ((ClassFilePatchException) current).archive();
            }
            if (current instanceof AsmTransformException) {
                return owners.get(((AsmTransformException) current).getAsmClassName());
            }
            current = current.getCause();
        }
        return null;
    }

    @Override
    public void install() {
        // 此处有意留空：类已在加载前完成改写。
    }

    @Override
    public synchronized void registerArchive(Path archive) throws ExtensionException {
        Path key = archive.toAbsolutePath().normalize();
        if (registered.contains(key)) { return; }
        try {
            classPatches.registerArchive(key, loader);
            for (String name : mixinClassNames(archive)) {
                owners.put(name, key);
                AsmRegistry.register(Class.forName(name, false, loader));
            }
            registered.add(key);
        } catch (Throwable t) {
            classPatches.unregisterArchive(key);
            throw new ExtensionException("Mixins or class-file patches in " + archive.getFileName()
                + " could not be registered", t);
        }
    }

    @Override
    public void register(Class<?> mixin) throws ExtensionException {
        try {
            AsmRegistry.register(mixin);
        } catch (Throwable t) {
            throw new ExtensionException("Mixin " + mixin.getName() + " could not be registered", t);
        }
    }

    /**
     * 将注册表应用于单个类。
     *
     * <p>此前置检查使离线处理的开销可控：只改写已注册 mixin 指定的类；为其他数千个类计算栈映射帧不会带来
     * 任何改变。</p>
     */
    @Override
    public byte[] transform(String internalName, byte[] classfileBuffer, ClassLoader classLoader) {
        boolean hasMixins = processor.shouldTransform(internalName);
        boolean hasPatches = classPatches.has(internalName);
        if (!hasMixins && !hasPatches) {
            return classfileBuffer;
        }
        byte[] transformed = hasMixins
            ? processor.transform(internalName, classfileBuffer, classLoader)
            : classfileBuffer;
        return hasPatches ? classPatches.apply(internalName, transformed, classLoader) : transformed;
    }

    /**
     * @return {@code archive} 中 {@code @AsmMixin} 类的二进制内容
     * @throws ExtensionException 无法读取归档时抛出
     */
    static List<String> mixinClassNames(Path archive) throws ExtensionException {
        List<String> names = new ArrayList<>();
        try (JarFile jar = new JarFile(archive.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                byte[] bytes;
                try (InputStream in = jar.getInputStream(entry)) {
                    bytes = JarResources.readFully(in);
                }
                if (carriesAsmMixin(bytes)) {
                    String name = entry.getName();
                    names.add(name.substring(0, name.length() - ".class".length()).replace('/', '.'));
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new ExtensionException("The mixins of " + archive.getFileName()
                + " cannot be listed", e);
        }
        return names;
    }

    /** 只读取类头及其注解；不会访问方法体。 */
    private static boolean carriesAsmMixin(byte[] classBytes) {
        final boolean[] found = {false};
        try {
            new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    if (ASM_MIXIN_DESCRIPTOR.equals(descriptor)) {
                        found[0] = true;
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Cannot inspect extension class metadata", failure);
        }
        return found[0];
    }
}
