/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * A class loader that serves {@code .class} resources out of jar files, and a stand-in for the ones
 * that cannot be read.
 *
 * <p>The rewriter needs the superclass hierarchy of the class it is writing and asks for it through
 * {@code getResourceAsStream}, giving up the moment one link cannot be read. On Android that link is
 * missing for every type the app does not carry, so the bytes from the jars cover the game and the
 * extensions and a stand-in covers the rest.</p>
 *
 * <p>It never defines the classes it describes: it answers lookups and is dropped with the rewriter.</p>
 */
final class JarResources extends ClassLoader implements Closeable {

    private static final String CLASS_SUFFIX = ".class";

    private static final String OBJECT = "java/lang/Object";

    private final List<JarFile> jars = new ArrayList<>();

    JarResources(List<Path> jars, ClassLoader parent) {
        super(parent);
        for (Path jar : jars) {
            try {
                this.jars.add(new JarFile(jar.toFile()));
            } catch (IOException ignored) {
                // A jar that cannot be opened simply contributes nothing.
            }
        }
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        if (name.endsWith(CLASS_SUFFIX)) {
            InputStream fromJar = openFromJars(name);
            if (fromJar != null) {
                return fromJar;
            }
            InputStream fromParent = super.getResourceAsStream(name);
            if (fromParent != null) {
                return fromParent;
            }
            String internalName = name.substring(0, name.length() - CLASS_SUFFIX.length());
            if (isInternalName(internalName)) {
                return new ByteArrayInputStream(standIn(internalName));
            }
        }
        return super.getResourceAsStream(name);
    }

    @Override
    protected Enumeration<java.net.URL> findResources(String name) {
        return Collections.emptyEnumeration();
    }

    @Override
    public void close() {
        for (JarFile jar : jars) {
            try {
                jar.close();
            } catch (IOException ignored) {
                // Nothing is left to report a failed close to.
            }
        }
        jars.clear();
    }

    private InputStream openFromJars(String name) {
        for (JarFile jar : jars) {
            JarEntry entry = jar.getJarEntry(name);
            if (entry == null) {
                continue;
            }
            try (InputStream in = jar.getInputStream(entry)) {
                // The stream outlives the entry, so the bytes are read out in full here.
                return new ByteArrayInputStream(readFully(in));
            } catch (IOException ignored) {
                // Try the next jar.
            }
        }
        return null;
    }

    /**
     * A class file that stands in for a type nothing can read: the type itself, extending Object.
     *
     * <p>Refusing is not an option, because the framework treats an unreadable type as fatal.</p>
     */
    private static byte[] standIn(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null,
            OBJECT, null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** @return whether {@code name} is a class name in internal form rather than some other resource */
    private static boolean isInternalName(String name) {
        // A resource name is always path shaped. A dotted name is a lookup of a different kind, and
        // an answer to it would be an answer to the wrong question.
        return name.indexOf('/') > 0 && name.indexOf('.') < 0;
    }

    static byte[] readFully(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = stream.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
