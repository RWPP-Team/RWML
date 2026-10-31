/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core.transform;

import io.github.rwml.api.ClassFilePatch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Registers and applies the class-file patches declared by extension archives. */
public final class ArchiveClassPatches {

    public static final String MANIFEST_PATH = "META-INF/rwml/class-patches.properties";

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<RegisteredPatch>> patches =
        new ConcurrentHashMap<>();
    private final Set<Path> registeredArchives = ConcurrentHashMap.newKeySet();

    /**
     * Reads declarations without loading patch classes. This is useful to decide whether the
     * archive needs to be exposed to the host class loader before registration.
     */
    public static Map<String, String> readDeclarations(Path archive) throws IOException {
        Map<String, String> declarations = new TreeMap<>();
        try (JarFile jar = new JarFile(archive.toFile())) {
            Set<String> manifests = new TreeSet<>();
            jar.stream().filter(entry -> !entry.isDirectory()).map(JarEntry::getName)
                .filter(name -> name.equals(MANIFEST_PATH)
                    || (name.startsWith("META-INF/rwml/class-patches-") && name.endsWith(".properties")
                        && name.indexOf('/', "META-INF/rwml/".length()) < 0))
                .forEach(manifests::add);
            for (String manifest : manifests) {
                JarEntry entry = jar.getJarEntry(manifest);
                Properties properties = new Properties() {
                    @Override
                    public synchronized Object put(Object key, Object value) {
                        Object previous = get(key);
                        if (previous != null && !previous.equals(value)) {
                            throw new IllegalArgumentException("Conflicting class patch target " + key);
                        }
                        return super.put(key, value);
                    }
                };
                try (InputStream input = jar.getInputStream(entry)) {
                    properties.load(input);
                } catch (IllegalArgumentException failure) {
                    throw new IOException("Invalid declarations in " + manifest + " of " + archive, failure);
                }
                for (Map.Entry<Object, Object> item : properties.entrySet()) {
                    if (!(item.getKey() instanceof String) || !(item.getValue() instanceof String)) {
                        throw new IOException("Invalid declaration in " + MANIFEST_PATH + " of " + archive);
                    }
                    String target = ((String) item.getKey()).trim();
                    String patchClass = ((String) item.getValue()).trim();
                    if (target.isEmpty() || target.indexOf('.') >= 0 || target.indexOf('\\') >= 0
                        || target.startsWith("/") || target.endsWith("/")) {
                        throw new IOException("Invalid internal target name '" + item.getKey()
                            + "' in " + MANIFEST_PATH + " of " + archive);
                    }
                    if (patchClass.isEmpty() || patchClass.indexOf('/') >= 0 || patchClass.indexOf('\\') >= 0) {
                        throw new IOException("Invalid patch class name '" + item.getValue()
                            + "' in " + MANIFEST_PATH + " of " + archive);
                    }
                    String previous = declarations.putIfAbsent(target, patchClass);
                    if (previous != null && !previous.equals(patchClass)) {
                        throw new IOException("Duplicate class patch target " + target + " in " + archive);
                    }
                }
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(declarations));
    }

    /**
     * Instantiates declarations from one archive and appends them atomically to each target's
     * patch chain. Archives are appended in the order this method is called.
     *
     * @return the targets declared by this archive
     */
    public synchronized Set<String> registerArchive(Path archive, ClassLoader archiveLoader)
        throws IOException {
        Path key = archive.toAbsolutePath().normalize();
        if (registeredArchives.contains(key)) {
            return targetsDeclaredBy(key);
        }

        Map<String, String> declarations = readDeclarations(key);
        if (declarations.isEmpty()) {
            registeredArchives.add(key);
            return Collections.emptySet();
        }

        List<PendingPatch> pending = new ArrayList<>(declarations.size());
        for (Map.Entry<String, String> declaration : declarations.entrySet()) {
            String target = declaration.getKey();
            String patchClassName = declaration.getValue();
            try {
                Class<?> implementation = Class.forName(patchClassName, true, archiveLoader);
                if (!ClassFilePatch.class.isAssignableFrom(implementation)) {
                    throw new IllegalArgumentException(patchClassName + " does not implement "
                        + ClassFilePatch.class.getName());
                }
                ClassFilePatch patch = (ClassFilePatch) implementation.getConstructor().newInstance();
                pending.add(new PendingPatch(target,
                    new RegisteredPatch(key, patchClassName, patch)));
            } catch (Throwable failure) {
                if (failure instanceof ThreadDeath) {
                    throw (ThreadDeath) failure;
                }
                if (failure instanceof VirtualMachineError) {
                    throw (VirtualMachineError) failure;
                }
                throw new IOException("Cannot instantiate class-file patch " + patchClassName
                    + " for " + target + " from " + key, failure);
            }
        }

        for (PendingPatch item : pending) {
            patches.computeIfAbsent(item.target, ignored -> new CopyOnWriteArrayList<>())
                .add(item.patch);
        }
        registeredArchives.add(key);
        Set<String> targets = new LinkedHashSet<>();
        for (PendingPatch item : pending) {
            targets.add(item.target);
        }
        return Collections.unmodifiableSet(targets);
    }

    /** Removes patch instances declared by an archive if its host registration later fails. */
    public synchronized void unregisterArchive(Path archive) {
        Path key = archive.toAbsolutePath().normalize();
        if (!registeredArchives.remove(key)) {
            return;
        }
        for (Map.Entry<String, CopyOnWriteArrayList<RegisteredPatch>> entry : patches.entrySet()) {
            CopyOnWriteArrayList<RegisteredPatch> chain = entry.getValue();
            chain.removeIf(patch -> patch.archive.equals(key));
            if (chain.isEmpty()) {
                patches.remove(entry.getKey(), chain);
            }
        }
    }

    public boolean has(String internalName) {
        List<RegisteredPatch> chain = patches.get(internalName);
        return chain != null && !chain.isEmpty();
    }

    /** Returns a stable snapshot of all registered patch targets. */
    public Set<String> targets() {
        List<String> sorted = new ArrayList<>(patches.keySet());
        Collections.sort(sorted);
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    /** Applies patches sequentially to an already mixin-transformed class. */
    public byte[] apply(String internalName, byte[] classfileBuffer, ClassLoader loader) {
        List<RegisteredPatch> chain = patches.get(internalName);
        if (chain == null || chain.isEmpty()) {
            return classfileBuffer;
        }
        byte[] current = classfileBuffer;
        for (RegisteredPatch registered : chain) {
            try {
                byte[] transformed = registered.patch.transform(internalName, current, loader);
                if (transformed == null) {
                    throw new IllegalStateException("ClassFilePatch.transform returned null");
                }
                current = transformed;
            } catch (Throwable failure) {
                if (failure instanceof ThreadDeath) {
                    throw (ThreadDeath) failure;
                }
                if (failure instanceof VirtualMachineError) {
                    throw (VirtualMachineError) failure;
                }
                throw new ClassFilePatchException(registered.archive, internalName,
                    registered.className, failure);
            }
        }
        return current;
    }

    /** Removes registrations and patch instances, for hosts that rebuild their offline registry. */
    public synchronized void reset() {
        patches.clear();
        registeredArchives.clear();
    }

    private Set<String> targetsDeclaredBy(Path archive) throws IOException {
        return Collections.unmodifiableSet(new LinkedHashSet<>(readDeclarations(archive).keySet()));
    }

    private static final class PendingPatch {
        private final String target;
        private final RegisteredPatch patch;

        private PendingPatch(String target, RegisteredPatch patch) {
            this.target = target;
            this.patch = patch;
        }
    }

    private static final class RegisteredPatch {
        private final Path archive;
        private final String className;
        private final ClassFilePatch patch;

        private RegisteredPatch(Path archive, String className, ClassFilePatch patch) {
            this.archive = archive;
            this.className = className;
            this.patch = patch;
        }
    }
}
