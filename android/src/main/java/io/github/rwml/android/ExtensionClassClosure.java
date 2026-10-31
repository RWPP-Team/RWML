/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import io.github.rwml.api.ExtensionException;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds extension-owned types needed to load mixins during offline registration.
 *
 * <p>The registration loader also contains game stand-ins. Classes from an extension's own
 * mixin signature hierarchy must be loaded by that loader too, while host and platform types
 * must keep their shared class identity and be delegated to the parent.</p>
 */
final class ExtensionClassClosure {

    private static final Pattern SIGNATURE_TYPE = Pattern.compile("L([^;<]+)");

    private ExtensionClassClosure() {
    }

    /**
     * @param archives enabled extension archives, in class path order
     * @param mixins mixin binary class names found in those archives
     * @return extension-owned types reachable from the mixins, in local-first class-loader form
     */
    static Set<String> localFirstClassNames(List<Path> archives, Iterable<String> mixins)
        throws ExtensionException {
        Map<String, ClassEntry> classes = index(archives);
        ArrayDeque<String> pending = new ArrayDeque<>();
        for (String mixin : mixins) {
            pending.add(mixin);
        }

        Set<String> visited = new HashSet<>();
        Set<String> localFirst = new HashSet<>();
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            if (!visited.add(name) || isSharedType(name)) {
                continue;
            }
            ClassEntry entry = classes.get(name);
            if (entry == null) {
                continue;
            }
            localFirst.add(name);
            for (String referenced : references(entry)) {
                if (!visited.contains(referenced) && !isSharedType(referenced)
                    && classes.containsKey(referenced)) {
                    pending.addLast(referenced);
                }
            }
        }
        return localFirst;
    }

    private static Map<String, ClassEntry> index(List<Path> archives) throws ExtensionException {
        Map<String, ClassEntry> classes = new HashMap<>();
        for (Path archive : archives) {
            try (JarFile jar = new JarFile(archive.toFile())) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String path = entry.getName();
                    if (entry.isDirectory() || !path.endsWith(".class")
                        || path.startsWith("META-INF/")) {
                        continue;
                    }
                    String name = path.substring(0, path.length() - ".class".length())
                        .replace('/', '.');
                    if (!isSharedType(name)) {
                        classes.putIfAbsent(name, new ClassEntry(archive, path));
                    }
                }
            } catch (IOException | RuntimeException failure) {
                throw new ExtensionException("Extension classes in " + archive.getFileName()
                    + " cannot be indexed", failure);
            }
        }
        return classes;
    }

    private static Set<String> references(ClassEntry classEntry) throws ExtensionException {
        Set<String> references = new HashSet<>();
        try (JarFile jar = new JarFile(classEntry.archive.toFile())) {
            JarEntry entry = jar.getJarEntry(classEntry.path);
            if (entry == null) {
                throw new IOException("class entry disappeared: " + classEntry.path);
            }
            try (InputStream input = jar.getInputStream(entry)) {
                new ClassReader(JarResources.readFully(input)).accept(
                    new ReferenceCollector(references),
                    ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        } catch (IOException | RuntimeException failure) {
            throw new ExtensionException("Extension class " + classEntry.path
                + " cannot be inspected", failure);
        }
        return references;
    }

    private static boolean isSharedType(String name) {
        return name.startsWith("java.")
            || name.startsWith("javax.")
            || name.startsWith("jdk.")
            || name.startsWith("sun.")
            || name.startsWith("android.")
            || name.startsWith("androidx.")
            || name.startsWith("com.android.")
            || name.startsWith("dalvik.")
            || name.startsWith("kotlin.")
            || name.startsWith("kotlinx.")
            || name.startsWith("org.jetbrains.")
            || name.startsWith("org.objectweb.asm.")
            || name.startsWith("org.koin.")
            || name.startsWith("kim.der.asm.")
            || name.startsWith("io.github.rwml.");
    }

    private static void addInternalName(Set<String> references, String internalName) {
        if (internalName == null) {
            return;
        }
        if (internalName.startsWith("[")) {
            addType(references, Type.getType(internalName));
            return;
        }
        references.add(internalName.replace('/', '.'));
    }

    private static void addDescriptor(Set<String> references, String descriptor) {
        if (descriptor != null) {
            addType(references, Type.getType(descriptor));
        }
    }

    private static void addType(Set<String> references, Type type) {
        switch (type.getSort()) {
            case Type.ARRAY:
                addType(references, type.getElementType());
                break;
            case Type.OBJECT:
                addInternalName(references, type.getInternalName());
                break;
            case Type.METHOD:
                addType(references, type.getReturnType());
                for (Type argument : type.getArgumentTypes()) {
                    addType(references, argument);
                }
                break;
            default:
                break;
        }
    }

    private static void addSignature(Set<String> references, String signature) {
        if (signature == null) {
            return;
        }
        Matcher matcher = SIGNATURE_TYPE.matcher(signature);
        while (matcher.find()) {
            addInternalName(references, matcher.group(1));
        }
    }

    private static void addConstant(Set<String> references, Object value) {
        if (value instanceof Type) {
            addType(references, (Type) value);
        } else if (value instanceof Handle) {
            Handle handle = (Handle) value;
            addInternalName(references, handle.getOwner());
            addDescriptor(references, handle.getDesc());
        } else if (value instanceof ConstantDynamic) {
            ConstantDynamic dynamic = (ConstantDynamic) value;
            addDescriptor(references, dynamic.getDescriptor());
            addConstant(references, dynamic.getBootstrapMethod());
            for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++) {
                addConstant(references, dynamic.getBootstrapMethodArgument(index));
            }
        } else if (value instanceof Object[]) {
            for (Object item : (Object[]) value) {
                addConstant(references, item);
            }
        }
    }

    private static AnnotationVisitor annotation(Set<String> references, String descriptor) {
        addDescriptor(references, descriptor);
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public void visit(String name, Object value) {
                addConstant(references, value);
            }

            @Override
            public void visitEnum(String name, String descriptor, String value) {
                addDescriptor(references, descriptor);
            }

            @Override
            public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                return annotation(references, descriptor);
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                return annotation(references, null);
            }
        };
    }

    private static final class ClassEntry {
        private final Path archive;
        private final String path;

        private ClassEntry(Path archive, String path) {
            this.archive = archive;
            this.path = path;
        }
    }

    private static final class ReferenceCollector extends ClassVisitor {
        private final Set<String> references;

        private ReferenceCollector(Set<String> references) {
            super(Opcodes.ASM9);
            this.references = references;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            addSignature(references, signature);
            addInternalName(references, superName);
            if (interfaces != null) {
                for (String type : interfaces) {
                    addInternalName(references, type);
                }
            }
        }

        @Override
        public void visitOuterClass(String owner, String name, String descriptor) {
            addInternalName(references, owner);
            addDescriptor(references, descriptor);
        }

        @Override
        public void visitNestHost(String nestHost) {
            addInternalName(references, nestHost);
        }

        @Override
        public void visitNestMember(String nestMember) {
            addInternalName(references, nestMember);
        }

        @Override
        public void visitPermittedSubclass(String permittedSubclass) {
            addInternalName(references, permittedSubclass);
        }

        @Override
        public void visitInnerClass(String name, String outerName, String innerName, int access) {
            addInternalName(references, name);
            addInternalName(references, outerName);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return annotation(references, descriptor);
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(int typeRef, org.objectweb.asm.TypePath typePath,
                                                     String descriptor, boolean visible) {
            return annotation(references, descriptor);
        }

        @Override
        public RecordComponentVisitor visitRecordComponent(String name, String descriptor,
                                                           String signature) {
            addDescriptor(references, descriptor);
            addSignature(references, signature);
            return new RecordComponentVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    return annotation(references, descriptor);
                }

                @Override
                public AnnotationVisitor visitTypeAnnotation(int typeRef,
                        org.objectweb.asm.TypePath typePath, String descriptor, boolean visible) {
                    return annotation(references, descriptor);
                }
            };
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                       String signature, Object value) {
            addDescriptor(references, descriptor);
            addSignature(references, signature);
            addConstant(references, value);
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    return annotation(references, descriptor);
                }

                @Override
                public AnnotationVisitor visitTypeAnnotation(int typeRef,
                        org.objectweb.asm.TypePath typePath, String descriptor, boolean visible) {
                    return annotation(references, descriptor);
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            addDescriptor(references, descriptor);
            addSignature(references, signature);
            if (exceptions != null) {
                for (String exception : exceptions) {
                    addInternalName(references, exception);
                }
            }
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotationDefault() {
                    return annotation(references, null);
                }

                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    return annotation(references, descriptor);
                }

                @Override
                public AnnotationVisitor visitTypeAnnotation(int typeRef,
                        org.objectweb.asm.TypePath typePath, String descriptor, boolean visible) {
                    return annotation(references, descriptor);
                }

                @Override
                public AnnotationVisitor visitParameterAnnotation(int parameter,
                        String descriptor, boolean visible) {
                    return annotation(references, descriptor);
                }

                @Override
                public void visitTypeInsn(int opcode, String type) {
                    addInternalName(references, type);
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String name,
                                           String descriptor) {
                    addInternalName(references, owner);
                    addDescriptor(references, descriptor);
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String name,
                                            String descriptor, boolean isInterface) {
                    addInternalName(references, owner);
                    addDescriptor(references, descriptor);
                }

                @Override
                public void visitInvokeDynamicInsn(String name, String descriptor,
                                                   Handle bootstrapMethod, Object... arguments) {
                    addDescriptor(references, descriptor);
                    addConstant(references, bootstrapMethod);
                    for (Object argument : arguments) {
                        addConstant(references, argument);
                    }
                }

                @Override
                public void visitLdcInsn(Object value) {
                    addConstant(references, value);
                }

                @Override
                public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
                    addDescriptor(references, descriptor);
                }

                @Override
                public void visitTryCatchBlock(org.objectweb.asm.Label start,
                        org.objectweb.asm.Label end, org.objectweb.asm.Label handler, String type) {
                    addInternalName(references, type);
                }

                @Override
                public void visitLocalVariable(String name, String descriptor, String signature,
                        org.objectweb.asm.Label start, org.objectweb.asm.Label end, int index) {
                    addDescriptor(references, descriptor);
                    addSignature(references, signature);
                }

                @Override
                public void visitFrame(int type, int numLocal, Object[] local, int numStack,
                                       Object[] stack) {
                    addFrameTypes(references, local, numLocal);
                    addFrameTypes(references, stack, numStack);
                }
            };
        }
    }

    private static void addFrameTypes(Set<String> references, Object[] values, int count) {
        if (values == null) {
            return;
        }
        for (int index = 0; index < count; index++) {
            if (values[index] instanceof String) {
                addInternalName(references, (String) values[index]);
            }
        }
    }
}
