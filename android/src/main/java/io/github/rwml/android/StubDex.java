/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * Compiles a stand-in dex for every class of the game archives, with no members at all.
 *
 * <p>An extension mixin names the game types it patches in its own signatures, and the runtime
 * resolves those signatures when the mixin class is defined. On Android that happens during
 * {@code Class.forName}, which is how the mixins are registered, and the game only reaches the
 * runtime as dex after they have been applied. So at registration time nothing on any loader can
 * answer for a single game type, and the mixin cannot be defined at all. This is the step that makes
 * the registration possible: it gives the loader a type of the right name, right shape and right
 * place in the hierarchy, and nothing else.</p>
 *
 * <p>The members are deliberately absent. Everything the framework reads off a referenced type is
 * either a name, a descriptor, or an inheritance answer, and all three come from the header this
 * class keeps. The field a {@code @Accessor} binds to is read from the target class's own bytes, and
 * the method the injectors bind to is named as a descriptor string in the annotation, so no code
 * path ever looks inside a stand-in.</p>
 *
 * <p>Two access flags have to go. {@code ACC_FINAL} is dropped because a final type cannot be
 * extended and the archive's own subtypes are in the same dex. {@code ACC_ANNOTATION} is dropped
 * because the dexer treats an annotation as a contract to carry out rather than as a shape to copy.
 * Neither flag takes part in any decision the framework makes about a referenced type.</p>
 *
 * <p>The result is keyed by a digest of the archives it was built from, so a launch that finds a
 * valid cache compiles nothing. Nothing here depends on the extensions, which is what lets the cache
 * survive a change of them.</p>
 */
final class StubDex {

    /** Name of the jar handed to dx, inside the output directory and deleted again right after. */
    private static final String INPUT_FILE = "stub.jar";

    private static final int ACC_FINAL = 0x0010;

    private static final int ACC_ANNOTATION = 0x2000;

    /** The version dx accepts, and the one every stub is written at; see {@link Dexer}. */
    private static final int STUB_VERSION = Opcodes.V1_7;

    private StubDex() {
    }

    /**
     * Compiles one stand-in dex covering every class of {@code gameArchives}.
     *
     * @param gameArchives the game jars, which are the types a mixin may name
     * @param cacheDirectory the directory the keyed result is cached in
     * @return the dex files that were written, or the cached ones when the key matches
     */
    static List<Path> build(List<Path> gameArchives, Path cacheDirectory) throws IOException {
        if (gameArchives.isEmpty()) {
            return List.of();
        }
        Path output = cacheDirectory.resolve(key(gameArchives));
        List<Path> cached = Dexer.dexFiles(output);
        if (!cached.isEmpty()) {
            return cached;
        }

        Path jar = output.resolve(INPUT_FILE);
        writeStubs(gameArchives, jar);
        try {
            return Dexer.toDex(jar, output);
        } finally {
            Files.deleteIfExists(jar);
        }
    }

    /**
     * @return the cache key, which is a digest of the archives and of nothing else
     *
     * <p>The extensions are not part of it. A stand-in only describes the game, so it stays valid
     * across every change an extension can undergo.</p>
     */
    private static String key(List<Path> gameArchives) {
        // The layout belongs in the key: stand-ins used to be written for every class the archives
        // carry, including the copies of the Android API, and the dex cached under the bare digest
        // would keep answering with them.
        StringBuilder material = new StringBuilder("stand-in-2\n");
        for (Path archive : gameArchives) {
            material.append(AssetInstaller.Digest.sha256(archive));
        }
        return AssetInstaller.Digest.sha256(material.toString());
    }

    /**
     * Writes one stub class per class of the archives into {@code target}, keeping the header of
     * each: its access flags without the two that cannot be copied, its superclass and its
     * interfaces. Those four are the whole content, which is why an archive of 1 700 classes compiles
     * in a fraction of the time the real one takes.
     */
    private static void writeStubs(List<Path> gameArchives, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Map<String, Header> headers = new LinkedHashMap<>();
        for (Path archive : gameArchives) {
            readHeaders(archive, headers);
        }

        try (JarOutputStream out =
                 new JarOutputStream(new BufferedOutputStream(Files.newOutputStream(target)))) {
            for (Map.Entry<String, Header> entry : headers.entrySet()) {
                String name = entry.getKey();
                Header header = entry.getValue();
                ClassWriter writer = new ClassWriter(0);
                writer.visit(STUB_VERSION, header.access, name, null, header.superName,
                    header.interfaces.isEmpty() ? null
                        : header.interfaces.toArray(new String[0]));
                writer.visitEnd();
                out.putNextEntry(new JarEntry(name + ".class"));
                out.write(writer.toByteArray());
                out.closeEntry();
            }
        }
    }

    /** @return whether a stand-in for {@code internalName} has any use, or the platform answers it. */
    static boolean standsIn(String internalName) {
        // The archives carry a copy of the Android API, which the rewrite drops for the same reason:
        // a device already has it, and a stand-in would hide the real one.
        return !internalName.startsWith("android/") || internalName.startsWith("android/support/");
    }

    /**
     * @return the dotted names of the classes the stand-in dex would carry, in archive order
     *
     * <p>The registration loader asks its own dex before its parent, and only for these names. A
     * broader rule would answer for the platform's own types out of a stand-in as well.</p>
     */
    static Set<String> classNames(List<Path> gameArchives) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        for (Path archive : gameArchives) {
            try (JarFile jar = new JarFile(archive.toFile())) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                        continue;
                    }
                    String name = entry.getName();
                    name = name.substring(0, name.length() - ".class".length());
                    if (standsIn(name)) {
                        names.add(name.replace('/', '.'));
                    }
                }
            }
        }
        return names;
    }

    /** Reads the header of every class of one archive into {@code headers}. */
    private static void readHeaders(Path archive, Map<String, Header> headers) throws IOException {
        try (JarFile jar = new JarFile(archive.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                byte[] bytes;
                try (InputStream stream = jar.getInputStream(entry)) {
                    bytes = JarResources.readFully(stream);
                }
                String name = entry.getName();
                name = name.substring(0, name.length() - ".class".length());
                if (!standsIn(name) || headers.containsKey(name)) {
                    continue;
                }
                Header header = new Header();
                new ClassReader(bytes).accept(new HeaderReader(header),
                    ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
                headers.put(name, header);
            }
        }
    }

    /** What the framework can ask about a type it does not define: flags, superclass, interfaces. */
    private static final class Header {

        private int access;

        private String superName;

        private final List<String> interfaces = new ArrayList<>();
    }

    /** Collects the four header values and ignores everything the class body carries. */
    private static final class HeaderReader extends ClassVisitor {

        private final Header header;

        private HeaderReader(Header header) {
            super(Opcodes.ASM9);
            this.header = header;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName,
                          String[] interfaces) {
            header.access = (access & ~ACC_FINAL) & ~ACC_ANNOTATION;
            header.superName = superName;
            if (interfaces != null) {
                for (String name_ : interfaces) {
                    header.interfaces.add(name_);
                }
            }
        }
    }
}
