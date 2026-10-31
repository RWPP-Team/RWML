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
 * 从 JAR 文件中提供 {@code .class} 资源，并为无法读取的类提供替身的类加载器。
 *
 * <p>改写器需要获取正在处理的类的父类层级，并通过 {@code getResourceAsStream} 查询；其中任一环节无法
 * 读取时就会放弃。在 Android 上，应用未携带的类型都会缺少对应资源，因此由 JAR 中的字节提供游戏和扩展
 * 类，其余类型则由替身提供。</p>
 *
 * <p>mixin 也通过此加载器注册，从而连接注册流程的两部分。{@code loadClass} 会访问其父级 dex 加载器，
 * 并在那里找到扩展和替身类型；{@code getResourceAsStream} 则优先由此处响应，从 JAR 中读取真实字节码。
 * 如果缺少前者，签名引用游戏类型的 mixin 根本无法定义；如果缺少后者，{@code @Accessor} 指定的继承字段
 * 都无法发现，因为框架通过同一查询读取类型层级。</p>
 *
 * <p>此加载器不会定义它描述的类，只响应查询，并在改写器结束后丢弃。它本来也无法定义这些类，因为运行时
 * 只能从 dex 定义类，而这些归档携带的是类文件。</p>
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
                 // 无法打开的 JAR 不提供任何内容。
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
                 // 没有可用于报告关闭失败的对象。
            }
        }
        jars.clear();
    }

    private InputStream openFromJars(String name) {
        byte[] bytes = classBytes(name);
         // 流的生命周期长于条目，因此会先完整读取字节，再将副本交给调用方。
        return bytes == null ? null : new ByteArrayInputStream(bytes);
    }

    /** @return 任一归档中该条目的字节内容；所有归档都不包含该条目时返回 null */
    private byte[] classBytes(String name) {
        for (JarFile jar : jars) {
            JarEntry entry = jar.getJarEntry(name);
            if (entry == null) {
                continue;
            }
            try (InputStream in = jar.getInputStream(entry)) {
                return readFully(in);
            } catch (IOException ignored) {
                 // 尝试下一个 JAR。
            }
        }
        return null;
    }

    /**
     * 无法读取类型时使用的类文件替身：只包含该类型本身，并继承 Object。
     *
     * <p>不能直接拒绝处理，因为框架会将无法读取类型视为致命错误。</p>
     */
    private static byte[] standIn(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null,
            OBJECT, null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** @return {@code name} 是否为内部格式的类名，而非其他资源名称 */
    private static boolean isInternalName(String name) {
         // 资源名称始终采用路径形式。带点号的名称属于另一种查询；响应它就答非所问。
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
