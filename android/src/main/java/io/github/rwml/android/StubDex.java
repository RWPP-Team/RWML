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
 * 为游戏归档中的每个类编译一个完全不含成员的替身 dex。
 *
 * <p>扩展 mixin 会在自身签名中引用要修补的游戏类型，而运行时会在定义 mixin 类时解析这些签名。在 Android
 * 上，此过程发生于注册 mixin 时调用的 {@code Class.forName} 期间；只有应用完 mixin 后，游戏才会以 dex
 * 形式进入运行时。因此，注册期间任何加载器都无法提供游戏类型，mixin 也无法完成定义。此步骤通过为加载器
 * 提供名称、结构和继承层级都正确的类型，使注册成为可能；除此之外不提供其他信息。</p>
 *
 * <p>替身会有意省略所有成员。框架从引用类型读取的信息只有名称、描述符和继承关系，这三者都来自此类保留的
 * 类头。{@code @Accessor} 绑定的字段从目标类自身的字节码中读取；注入器绑定的方法则以描述符字符串的形式
 * 写在注解中，因此任何代码路径都不会读取替身的内部成员。</p>
 *
 * <p>有两个访问标志必须移除。由于 final 类型不可继承，且归档自己的子类型位于同一个 dex 中，因此移除
 * {@code ACC_FINAL}。dex 编译器会将注解视为需要实现的约定，而非待复制的结构，因此移除
 * {@code ACC_ANNOTATION}。框架对引用类型所做的判断都不会用到这两个标志。</p>
 *
 * <p>结果以源归档的摘要作为缓存键，因此启动时若找到有效缓存，就无需重新编译。此处的内容都不依赖扩展，
 * 所以扩展变化后缓存仍然有效。</p>
 */
final class StubDex {

    /** 传给 dx 的 JAR 名称；文件位于输出目录中，并会在之后立即删除。 */
    private static final String INPUT_FILE = "stub.jar";

    private static final int ACC_FINAL = 0x0010;

    private static final int ACC_ANNOTATION = 0x2000;

    /** dx 接受的版本，也是所有替身采用的版本；详见 {@link Dexer}。 */
    private static final int STUB_VERSION = Opcodes.V1_7;

    private StubDex() {
    }

    /**
     * 编译单个替身 dex，覆盖 {@code gameArchives} 中的所有类。
     *
     * @param gameArchives 游戏 JAR；mixin 可能引用其中的类型
     * @param cacheDirectory 按键缓存结果的目录
     * @return 写入的 dex 文件；缓存键匹配时返回缓存文件
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
     * @return 缓存键，只由归档摘要构成
     *
     * <p>扩展不属于缓存键的一部分。替身只描述游戏，因此扩展发生任何变化时，缓存依然有效。</p>
     */
    private static String key(List<Path> gameArchives) {
        // 目录布局也属于缓存键的一部分：以前会为归档中的所有类生成替身，包括 Android API 的副本；若只按摘要
        // 缓存，旧 dex 就会继续为这些类型提供替身。
        StringBuilder material = new StringBuilder("stand-in-2\n");
        for (Path archive : gameArchives) {
            material.append(AssetInstaller.Digest.sha256(archive));
        }
        return AssetInstaller.Digest.sha256(material.toString());
    }

    /**
     * 将归档中的每个类各写入一个替身类到 {@code target}，并保留其类头信息：移除两个不可复制标志后的访问
     * 标志、父类和接口。这些就是替身的全部内容，因此包含 1,700 个类的归档只需实际编译时间的一小部分。
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

    /** @return 是否需要为 {@code internalName} 创建替身；平台已提供该类时则返回 false */
    static boolean standsIn(String internalName) {
        // 归档中包含 Android API 的副本，改写时会出于相同原因将其移除：设备已提供这些 API，替身会遮蔽真实类型。
        return !internalName.startsWith("android/") || internalName.startsWith("android/support/");
    }

    /**
     * @return 替身 dex 将包含的类名，使用点号分隔并按归档顺序排列
     *
     * <p>注册加载器会先查询自身 dex 再查询父加载器，并且只对这些类名执行此操作。扩大范围会导致平台自身的
     * 类型也被替身提供。</p>
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

    /** 将一个归档中所有类的类头读取到 {@code headers}。 */
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

    /** 框架可查询的非自身定义类型信息：标志、父类和接口。 */
    private static final class Header {

        private int access;

        private String superName;

        private final List<String> interfaces = new ArrayList<>();
    }

    /** 收集四项类头信息，忽略类体中的所有内容。 */
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
