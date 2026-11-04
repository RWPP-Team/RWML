/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JavaRuntime} 通过实际运行 JDK 并读取其版本来验证安装，因此此处会分别测试 {@code java -version} 和 Adoptium 响应的解析。
 */
class JavaRuntimeTest {

    @Test
    void acceptsAJdkHomeOnlyAfterRunningIt() {
        // JAVA_HOME 指向 JDK 主目录，当前 JVM 的 java.home 也采用相同结构：包含 bin/java 和运行时。只有启动后能报告主版本的目录才会被接受。
        File home = new File(System.getProperty("java.home"));
        assertTrue(JavaRuntime.versionOf(home) >= 8, "no usable JVM at " + home);
    }

    @Test
    void rejectsADirectoryThatHoldsNoJvm() {
        assertEquals(-1, JavaRuntime.versionOf(new File("build/definitely-not-a-jdk")));
    }

    @Test
    void readsTheVersionOutOfJavaDashVersionOutput() {
        assertEquals(25, JavaRuntime.parseJavaVersionOutput(
            "openjdk version \"25.0.4.1\" 2026-08-18\n"
                + "OpenJDK Runtime Environment Temurin-25.0.4.1+1 (build 25.0.4.1+1)\n"
                + "OpenJDK 64-Bit Server VM Temurin-25.0.4.1+1 (build 25.0.4.1+1, mixed mode)"));
        assertEquals(8, JavaRuntime.parseJavaVersionOutput(
            "java version \"1.8.0_422\"\nJava(TM) SE Runtime Environment (build 1.8.0_422-b05)"));
        assertEquals(11, JavaRuntime.parseJavaVersionOutput("openjdk version \"11.0.2\" 2019-01-15"));
    }

    @Test
    void reportsNothingForOutputItCannotRead() {
        assertEquals(-1, JavaRuntime.parseJavaVersionOutput(null));
        assertEquals(-1, JavaRuntime.parseJavaVersionOutput(""));
        assertEquals(-1, JavaRuntime.parseJavaVersionOutput("no quotes here"));
    }

    @Test
    void extractsTheFieldsOfTheAdoptiumAssetsResponse() {
        // 与 /v3/assets/latest/<v>/hotspot 响应结构相同：所需值位于校验和之后的“package”对象中，旁边还有一个名称相似的字段，容易让简单搜索误判。
        String json = "{\"binary\":{\"package\":{\"checksum\":\"00c847d8\",\"checksum_link\":\"https://x\","
            + "\"download_count\":42,\"link\":\"https://example.invalid/jdk.zip\",\"size\":141167264}}}";
        String scope = json.substring(json.indexOf("\"package\""));

        assertEquals("00c847d8", JavaRuntime.firstQuoted(scope, "checksum"));
        assertEquals("https://example.invalid/jdk.zip", JavaRuntime.firstQuoted(scope, "link"));
        assertEquals(141167264L, JavaRuntime.firstNumber(scope, "size"));
    }

    @Test
    void returnsNothingForFieldsThatAreAbsent() {
        assertNull(JavaRuntime.firstQuoted("{}", "checksum"));
        assertNull(JavaRuntime.firstQuoted("{\"checksum\":}", "checksum"));
        assertEquals(-1L, JavaRuntime.firstNumber("{}", "size"));
        assertEquals(-1L, JavaRuntime.firstNumber("{\"size\":\"not-a-number\"}", "size"));
    }

    @Test
    void prefersTheDomesticMirrorAndKeepsTheOfficialAddressAsFallback() {
        List<String> sources = JavaRuntime.archiveSources(25, "windows", "x64",
            "OpenJDK25U-jdk_x64_windows_hotspot_25.0.5_8.zip",
            "https://github.com/adoptium/temurin25-binaries/releases/download/x/file.zip");

        assertEquals(3, sources.size());
        assertEquals("https://mirrors.tuna.tsinghua.edu.cn/Adoptium/25/jdk/x64/windows/"
            + "OpenJDK25U-jdk_x64_windows_hotspot_25.0.5_8.zip", sources.get(0));
        assertEquals("http://mirrors.tuna.tsinghua.edu.cn/Adoptium/25/jdk/x64/windows/"
            + "OpenJDK25U-jdk_x64_windows_hotspot_25.0.5_8.zip", sources.get(1));
        assertEquals("https://github.com/adoptium/temurin25-binaries/releases/download/x/file.zip",
            sources.get(2));
    }

    @Test
    void fallsBackToTheDirectEndpointWithoutAPackageName() {
        List<String> sources = JavaRuntime.archiveSources(25, "linux", "aarch64", null, null);
        assertEquals(1, sources.size());
        assertEquals("https://api.adoptium.net/v3/binary/latest/25/ga/linux/aarch64/jdk/hotspot/"
            + "normal/eclipse", sources.get(0));
        assertEquals("jdk-25-linux-aarch64.tar.gz",
            JavaRuntime.fileName(null, null, 25, "linux", "aarch64"));
        assertEquals("jdk-25-windows-x64.zip",
            JavaRuntime.fileName(null, null, 25, "windows", "x64"));
    }

    @Test
    void mapsThePlatformToAnAdoptiumTarget() throws Exception {
        // 该测试只对当前实际运行平台有意义，但对于 JVM 声称支持的平台，解析过程绝不能抛出异常。
        String os = JavaRuntime.osName();
        String arch = JavaRuntime.archName();
        org.junit.jupiter.api.Assertions.assertTrue(
            os.equals("windows") || os.equals("linux") || os.equals("mac"), "unexpected os: " + os);
        org.junit.jupiter.api.Assertions.assertFalse(arch.isEmpty(), "empty architecture");
    }
}
