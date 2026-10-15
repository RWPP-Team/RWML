/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import io.github.rwml.api.ExtensionException;
import io.github.rwml.api.MixinBridge;

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
 * The Android {@link MixinBridge}: the same registry as the desktop, driven without an agent.
 *
 * <p>There is nothing to install, because the classes are rewritten before the runtime loads them.
 * A mixin has to be a live {@code Class} before the game can be rewritten against it, and on Android
 * that only comes from dex a loader owns, which is why the bootstrapper mounts the extensions first.
 */
public final class OfflineMixinBridge implements MixinBridge {

    /** JVM descriptor of the framework's mixin marker annotation. */
    private static final String ASM_MIXIN_DESCRIPTOR = "Lkim/der/asm/api/annotation/AsmMixin;";

    private volatile ClassLoader loader;
    private final java.util.Map<String, Path> owners = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<Path> registered = new java.util.HashSet<>();
    private final AsmProcessor processor = new AsmProcessor();

    OfflineMixinBridge(ClassLoader loader) {
        this.loader = loader;
    }

    void updateLoader(ClassLoader loader) { this.loader = loader; }

    Path failedArchive(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof AsmTransformException) {
                return owners.get(((AsmTransformException) current).getAsmClassName());
            }
            current = current.getCause();
        }
        return null;
    }

    @Override
    public void install() {
        // Intentionally empty: the classes were rewritten before they were loaded.
    }

    @Override
    public synchronized void registerArchive(Path archive) throws ExtensionException {
        Path key = archive.toAbsolutePath().normalize();
        if (registered.contains(key)) { return; }
        for (String name : mixinClassNames(archive)) {
            try {
                owners.put(name, key);
                AsmRegistry.register(Class.forName(name, false, loader));
            } catch (Throwable t) {
                throw new ExtensionException("Mixin " + name + " of " + archive.getFileName()
                    + " could not be registered", t);
            }
        }
        registered.add(key);
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
     * Applies the registry to one class.
     *
     * <p>The guard is what makes an offline pass affordable: only a class a registered mixin names is
     * rewritten, and computing stack map frames for the other thousands would change nothing.</p>
     */
    @Override
    public byte[] transform(String internalName, byte[] classfileBuffer, ClassLoader classLoader) {
        if (!processor.shouldTransform(internalName)) {
            return classfileBuffer;
        }
        return processor.transform(internalName, classfileBuffer, classLoader);
    }

    /**
     * @return the {@code @AsmMixin} classes of {@code archive}, in binary form
     * @throws ExtensionException when the archive cannot be read
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

    /** Reads the class header and its annotations only; the bodies are never visited. */
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
