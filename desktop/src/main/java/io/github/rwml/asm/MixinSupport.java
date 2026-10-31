/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.asm;

import io.github.rwml.api.ExtensionException;
import io.github.rwml.api.MixinBridge;
import io.github.rwml.core.transform.ArchiveClassPatches;
import io.github.rwml.core.transform.ClassFilePatchException;

import kim.der.asm.AsmRegistry;
import kim.der.asm.transformer.AsmTransformException;
import kim.der.asm.agent.AsmBootstrap;
import kim.der.asm.transformer.AsmProcessor;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Map;
import java.util.LinkedHashMap;
import java.net.URLClassLoader;
import java.util.function.BiConsumer;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * RWML 扩展系统与 IronCore ASM-Framework 之间的桥接层。
 *
 * <p>框架会在加载过程中改写 {@code @AsmMixin} 指定的目标类。为此必须完成两项工作：通过自附加 agent JAR 的方式在当前 JVM 中安装
 * 框架转换器（只需安装一次）；扫描每个归档中的 mixin，并注册到共享注册表。</p>
 *
 * <p>这是 {@link MixinBridge} 的桌面端实现；Android 宿主也实现了相同接口，但不使用 agent，而是在加载前预先应用 mixin。</p>
 */
public final class MixinSupport implements MixinBridge {

    /** 框架 Mixin 标记注解的 JVM 内部描述符。 */
    private static final String ASM_MIXIN_DESCRIPTOR = "Lkim/der/asm/api/annotation/AsmMixin;";

    private static final MixinSupport INSTANCE = new MixinSupport();

    private volatile boolean transformerInstalled;
    private volatile BiConsumer<Path, Throwable> failureListener = (archive, failure) -> { };
    private final Map<String, Path> owners = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Set<Path>> targetOwners = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> registeredTargets = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final ArchiveClassPatches classPatches = new ArchiveClassPatches();
    private final Map<Path, URLClassLoader> archiveLoaders = new LinkedHashMap<>();

    public void onFailure(BiConsumer<Path, Throwable> listener) { failureListener = listener; }

    /** 必须报告转换器异常；否则 JVM 会忽略异常并直接定义原始字节码。 */
    void failed(String target, Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ClassFilePatchException) {
                failureListener.accept(((ClassFilePatchException) current).archive(), failure);
                return;
            }
            if (current instanceof AsmTransformException) {
                Path owner = owners.get(((AsmTransformException) current).getAsmClassName());
                if (owner != null) { failureListener.accept(owner, failure); }
                return;
            }
            current = current.getCause();
        }
        Set<Path> paths = targetOwners.get(target);
        if (paths != null) {
            for (Path owner : paths) { failureListener.accept(owner, failure); }
        }
    }

    /** 验证原始目标类，不定义这些类，也不运行游戏初始化逻辑。 */
    public void validateRegistered() throws ExtensionException {
        for (String target : registeredTargets) {
            try (InputStream stream = getClass().getClassLoader().getResourceAsStream(target + ".class")) {
                if (stream == null) { throw new IOException("Mixin target is missing: " + target); }
                transform(target, readFully(stream), getClass().getClassLoader());
            } catch (Throwable failure) {
                failed(target, failure);
                throw new ExtensionException("Mixin validation failed for " + target, failure);
            }
        }
    }

    /** 已交给系统类加载器的归档文件规范路径。 */
    private final Set<String> exposedArchives = new LinkedHashSet<>();

    private MixinSupport() {
    }

    public static MixinSupport instance() {
        return INSTANCE;
    }

    @Override
    public void install() {
        installTransformer();
    }

    /** 安装 ASM 转换器；可安全重复调用，最多只附加一次。 */
    public synchronized void installTransformer() {
        if (transformerInstalled) {
            return;
        }
        if (isAsmAgentInstalled()) {
            transformerInstalled = true;
            return;
        }
        String jar = findAsmFrameworkJar();
        if (jar == null) {
            System.err.println("[rwml-asm] ASM-Framework jar not found on the classpath; "
                + "mixin injection is disabled. Launch with -javaagent:rwml.jar to enable it.");
            return;
        }
        try {
            attachAgent(jar);
            transformerInstalled = true;
        } catch (Throwable t) {
            System.err.println("[rwml-asm] Could not self-attach the ASM agent (" + t + "). "
                + "Mixins will only apply if the host was launched with -javaagent:rwml.jar.");
        }
    }

    /** @return ASM 转换器当前是否在此 JVM 中运行 */
    public boolean isInstalled() {
        return transformerInstalled || isAsmAgentInstalled();
    }

    /**
     * 首先读取类头信息，并且只加载 mixin 类。普通扩展类和目标类都会保持不变；只有已经定义的目标类需要重新转换。
     */
    @Override
    public synchronized void registerArchive(Path archive) throws ExtensionException {
        Path key = archive.toAbsolutePath().normalize();
        if (archiveLoaders.containsKey(key)) { return; }
        URLClassLoader loader = null;
        try {
            Map<String, Set<String>> mixins = new LinkedHashMap<>();
            try (JarFile jar = new JarFile(archive.toFile())) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().endsWith(".class")
                        || entry.getName().startsWith("META-INF/")) { continue; }
                    Set<String> targets = new LinkedHashSet<>();
                    try (InputStream stream = jar.getInputStream(entry)) { readMixinTargets(readFully(stream), targets); }
                    if (!targets.isEmpty()) {
                        mixins.put(entry.getName().substring(0, entry.getName().length() - 6).replace('/', '.'), targets);
                    }
                }
            }
            Map<String, String> patchDeclarations = ArchiveClassPatches.readDeclarations(key);
            boolean hasPatches = !patchDeclarations.isEmpty();
            boolean needsTransformer = !mixins.isEmpty() || hasPatches;
            if (needsTransformer && !isInstalled()) {
                throw new ExtensionException("A JVM agent is required to apply mixins in " + archive.getFileName());
            }
            if (needsTransformer) { exposeToHostLoader(archive); }
            loader = new URLClassLoader(new URL[]{archive.toUri().toURL()}, getClass().getClassLoader());
            Set<String> patchTargets = classPatches.registerArchive(key, loader);
            for (Map.Entry<String, Set<String>> mixin : mixins.entrySet()) {
                owners.put(mixin.getKey(), key);
                AsmRegistry.register(Class.forName(mixin.getKey(), false, loader));
                registeredTargets.addAll(mixin.getValue());
                for (String target : mixin.getValue()) {
                    targetOwners.computeIfAbsent(target, ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(key);
                }
            }
            registeredTargets.addAll(patchTargets);
            for (String target : patchTargets) {
                targetOwners.computeIfAbsent(target, ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(key);
            }
            archiveLoaders.put(key, loader);
            retransformTargets(registeredTargets);
        } catch (Throwable failure) {
            if (loader != null && !archiveLoaders.containsKey(key)) {
                classPatches.unregisterArchive(key);
                try { loader.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            }
            throw new ExtensionException("Mixins or class-file patches in " + archive.getFileName()
                + " could not be registered", failure);
        }
    }

    /**
     * 让宿主自身类所使用的加载器可以访问指定归档。
     *
     * <p>mixin 会变成写入目标方法中的静态调用，调用通过目标类的定义加载器解析。游戏类由系统类加载器定义，而系统类加载器无法访问
     * {@code extensions/} 中的 JAR，因此注入的调用会因 {@code NoClassDefFoundError} 失败。{@link JarFile} 不会关闭：JVM 在进程剩余
     * 生命周期中仍会从中读取内容。</p>
     */
    private void exposeToHostLoader(Path archive) throws IOException {
        Instrumentation instrumentation = RwmlAgent.instrumentation();
        if (instrumentation == null) {
            // 未安装 agent 就无法进行运行时改写，因此无需公开归档。
            return;
        }
        String key;
        try {
            key = archive.toRealPath().toString();
        } catch (IOException e) {
            key = archive.toAbsolutePath().toString();
        }
        synchronized (exposedArchives) {
            if (!exposedArchives.add(key)) {
                return;
            }
        }
        instrumentation.appendToSystemClassLoaderSearch(new JarFile(archive.toFile()));
    }

    /** 提取类上可能存在的 {@code @AsmMixin} 注解中的 {@code value} / {@code targets}。 */
    private static void readMixinTargets(byte[] classBytes, Set<String> targets) {
        try {
            new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    if (!ASM_MIXIN_DESCRIPTOR.equals(descriptor)) {
                        return null;
                    }
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public void visit(String name, Object value) {
                            if ("value".equals(name) && value instanceof String text) {
                                add(text);
                            }
                        }

                        @Override
                        public AnnotationVisitor visitArray(String name) {
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override
                                public void visit(String name, Object value) {
                                    if (value instanceof String text) {
                                        add(text);
                                    }
                                }
                            };
                        }

                        private void add(String text) {
                            if (text != null && !text.trim().isEmpty()) {
                                targets.add(text.trim());
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Cannot inspect extension class metadata", failure);
        }
    }

    private void retransformTargets(Set<String> targets) throws Exception {
        Instrumentation instrumentation = RwmlAgent.instrumentation();
        if (instrumentation == null || targets.isEmpty()) { return; }
        for (Class<?> target : instrumentation.getAllLoadedClasses()) {
            if (targets.contains(target.getName().replace('.', '/'))) {
                if (!instrumentation.isModifiableClass(target)) {
                    throw new ExtensionException("Already loaded mixin target cannot be changed: " + target.getName());
                }
                instrumentation.retransformClasses(target);
            }
        }
    }

    /** 直接注册单个 Mixin 类（例如运行时生成的类）。 */
    @Override
    public void register(Class<?> mixin) throws ExtensionException {
        try {
            kim.der.asm.AsmRegistry.register(mixin);
        } catch (Throwable t) {
            throw new ExtensionException("Mixin " + mixin.getName() + " could not be registered", t);
        }
    }

    /**
     * 将所有已注册的 mixin 应用于类缓冲区。此方法主要供自行加载或生成类的扩展使用；宿主加载的类已由安装的转换器负责处理。
     */
    @Override
    public byte[] transform(String internalName, byte[] classfileBuffer, ClassLoader loader) {
        try {
            byte[] transformed = new AsmProcessor().transform(internalName, classfileBuffer, loader);
            return classPatches.apply(internalName, transformed, loader);
        }
        catch (Throwable failure) {
            failed(internalName, failure);
            throw failure;
        }
    }

    /** @return whether the RWML host has a registered class-file patch for this class */
    boolean hasClassPatch(String internalName) {
        return classPatches.has(internalName);
    }

    /** Applies the registered patch chain after the ASM-Framework mixins. */
    byte[] applyClassPatches(String internalName, byte[] classfileBuffer, ClassLoader loader) {
        return classPatches.apply(internalName, classfileBuffer, loader);
    }

    // ------------------------------------------------------------------
    // 检测和附加 Agent（使用反射，避免宿主编译时依赖类路径中的 JDK attach 模块）
    // ------------------------------------------------------------------

    /**
     * 框架的 agent 会将当前实例保存在 {@code AsmCore} 的私有静态字段中。读取该字段可避免重复安装转换器，否则 mixin 会被应用两次。
     */
    private static boolean isAsmAgentInstalled() {
        if (RwmlAgent.instrumentation() != null) { return true; }
        try {
            Class<?> core = Class.forName("kim.der.asm.agent.AsmCore");
            Field agentField = core.getDeclaredField("agent");
            agentField.setAccessible(true);
            return agentField.get(null) != null;
        } catch (Throwable ignored) {
            // 如果无法确认已安装，就视为尚未安装，并尝试调用 installTransformer。
            return false;
        }
    }

    /** 根据 ASM-Framework 的 Agent 类解析其 JAR 的磁盘路径。 */
    private static String findAsmFrameworkJar() {
        try {
            URL location = AsmBootstrap.class.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) {
                return null;
            }
            URI uri = location.toURI();
            if ("jar".equals(uri.getScheme())) {
                String part = uri.getSchemeSpecificPart();
                if (part.startsWith("file:")) {
                    part = part.substring("file:".length());
                }
                while (part.endsWith("!") || part.endsWith("/")) {
                    part = part.substring(0, part.length() - 1);
                }
                return part.isEmpty() ? null : absolutePath(part);
            }
            // 使用 URI 而不是 URL.getPath()：后者在 Windows 上给出 /D:/... 形式，作为 agent 参数会被
            // 当成根目录下的路径。
            return Path.of(uri).toFile().getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 把 file URL 的文件部分还原为当前平台的绝对路径。 */
    private static String absolutePath(String fileUrlPath) {
        try {
            return Path.of(URI.create("file:" + fileUrlPath)).toFile().getAbsolutePath();
        } catch (RuntimeException notAFileUrl) {
            return new java.io.File(fileUrlPath).getAbsolutePath();
        }
    }

    /** 自行附加框架 Agent JAR，以注册其中的转换器。 */
    private static void attachAgent(String jarPath) throws Exception {
        Class<?> vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
        Method attach = vmClass.getMethod("attach", String.class);
        Method loadAgent = vmClass.getMethod("loadAgent", String.class, String.class);
        Method detach = vmClass.getMethod("detach");
        Object vm = attach.invoke(null, currentProcessId());
        try {
            loadAgent.invoke(vm, jarPath, "");
        } finally {
            detach.invoke(vm);
        }
    }

    /** 获取当前进程 ID，不依赖 {@code ProcessHandle}（Java 9 及以上版本）。 */
    private static String currentProcessId() {
        String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        return at > 0 ? name.substring(0, at) : name;
    }

    /** Java 8 版 {@code InputStream.readAllBytes} 替代实现（Android API 33 以下不可用）。 */
    private static byte[] readFully(InputStream stream) throws java.io.IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = stream.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
