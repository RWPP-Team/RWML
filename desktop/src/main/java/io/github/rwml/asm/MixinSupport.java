/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.asm;

import io.github.rwml.api.ExtensionException;
import io.github.rwml.api.MixinBridge;

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
 * Bridge between the RWML extension system and the IronCore ASM-Framework.
 *
 * <p>The framework rewrites the target classes an {@code @AsmMixin} names as they are loaded. Two
 * things have to happen for that: the framework's transformer has to be installed in the running JVM,
 * done once by self-attaching its agent jar, and every archive has to be scanned for mixins and
 * registered into the shared registry.</p>
 *
 * <p>This is the desktop implementation of {@link MixinBridge}; the Android host implements the same
 * interface without an agent, applying the mixins ahead of time instead.</p>
 */
public final class MixinSupport implements MixinBridge {

    /** JVM internal descriptor of the framework's mixin marker annotation. */
    private static final String ASM_MIXIN_DESCRIPTOR = "Lkim/der/asm/api/annotation/AsmMixin;";

    private static final MixinSupport INSTANCE = new MixinSupport();

    private volatile boolean transformerInstalled;
    private volatile BiConsumer<Path, Throwable> failureListener = (archive, failure) -> { };
    private final Map<String, Path> owners = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Set<Path>> targetOwners = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> registeredTargets = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<Path, URLClassLoader> archiveLoaders = new LinkedHashMap<>();

    public void onFailure(BiConsumer<Path, Throwable> listener) { failureListener = listener; }

    /** A transformer exception must be reported: the JVM otherwise ignores it and defines original bytes. */
    void failed(String target, Throwable failure) {
        Throwable current = failure;
        while (current != null) {
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

    /** Validates raw targets without defining them or running game initialization. */
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

    /** Canonical paths of the archives already handed to the system class loader. */
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

    /** Installs the ASM transformer; safe to call many times, attaches at most once. */
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

    /** @return whether the ASM transformer is currently active in this JVM. */
    public boolean isInstalled() {
        return transformerInstalled || isAsmAgentInstalled();
    }

    /**
     * Reads class headers first and loads only mixin classes. Ordinary extension classes and target
     * classes are left alone; only targets that are already defined need retransformation.
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
            if (!mixins.isEmpty() && !isInstalled()) {
                throw new ExtensionException("A JVM agent is required to apply mixins in " + archive.getFileName());
            }
            if (!mixins.isEmpty()) { exposeToHostLoader(archive); }
            loader = new URLClassLoader(new URL[]{archive.toUri().toURL()}, getClass().getClassLoader());
            for (Map.Entry<String, Set<String>> mixin : mixins.entrySet()) {
                owners.put(mixin.getKey(), key);
                AsmRegistry.register(Class.forName(mixin.getKey(), false, loader));
                registeredTargets.addAll(mixin.getValue());
                for (String target : mixin.getValue()) {
                    targetOwners.computeIfAbsent(target, ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(key);
                }
            }
            archiveLoaders.put(key, loader);
            retransformTargets(registeredTargets);
        } catch (Throwable failure) {
            if (loader != null && !archiveLoaders.containsKey(key)) {
                try { loader.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            }
            throw new ExtensionException("Mixins in " + archive.getFileName() + " could not be registered", failure);
        }
    }

    /**
     * Makes an archive visible to the loader that runs the host's own classes.
     *
     * <p>A mixin becomes a static call on the mixin class emitted inside the target method, resolved
     * through the target's defining loader. For a game class that is the system class loader, which
     * knows nothing about a jar in {@code extensions/}, so the injected call would fail with
     * {@code NoClassDefFoundError}. The {@link JarFile} is never closed: the JVM reads out of it for
     * the rest of the process.</p>
     */
    private void exposeToHostLoader(Path archive) throws IOException {
        Instrumentation instrumentation = RwmlAgent.instrumentation();
        if (instrumentation == null) {
            // Without an agent there is no live rewriting either, so there is nothing to expose.
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

    /** Extracts {@code value} / {@code targets} of a possible {@code @AsmMixin} on one class. */
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

    /** Registers a single mixin class directly (e.g. generated at runtime). */
    @Override
    public void register(Class<?> mixin) throws ExtensionException {
        try {
            kim.der.asm.AsmRegistry.register(mixin);
        } catch (Throwable t) {
            throw new ExtensionException("Mixin " + mixin.getName() + " could not be registered", t);
        }
    }

    /**
     * Applies every registered mixin to a class buffer. Mostly useful for extensions that load or
     * generate classes themselves; the installed transformer already covers classes loaded by the
     * host.
     */
    @Override
    public byte[] transform(String internalName, byte[] classfileBuffer, ClassLoader loader) {
        try { return new AsmProcessor().transform(internalName, classfileBuffer, loader); }
        catch (Throwable failure) {
            failed(internalName, failure);
            throw failure;
        }
    }

    // ------------------------------------------------------------------
    // Agent detection / attach (reflective so the host compiles without the
    // JDK attach module on the classpath)
    // ------------------------------------------------------------------

    /**
     * The framework's agent stores its live instance in a private static field of {@code AsmCore}. We
     * read it to avoid installing a second transformer when one is already present (which would
     * double-apply mixins).
     */
    private static boolean isAsmAgentInstalled() {
        if (RwmlAgent.instrumentation() != null) { return true; }
        try {
            Class<?> core = Class.forName("kim.der.asm.agent.AsmCore");
            Field agentField = core.getDeclaredField("agent");
            agentField.setAccessible(true);
            return agentField.get(null) != null;
        } catch (Throwable ignored) {
            // If we cannot prove it is installed, assume not and let installTransformer try.
            return false;
        }
    }

    /** Resolves the on-disk path of the ASM-Framework jar from its agent class. */
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
                return part.isEmpty() ? null : part;
            }
            return location.getPath();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Self-attaches the framework agent jar so its transformer is registered. */
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

    /** Current PID without relying on {@code ProcessHandle} (Java 9+). */
    private static String currentProcessId() {
        String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        return at > 0 ? name.substring(0, at) : name;
    }

    /** Java 8 stand-in for {@code InputStream.readAllBytes} (missing on Android below API 33). */
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
