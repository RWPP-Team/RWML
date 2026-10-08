/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JavaRuntime} validates a JDK by running it and reading the version back, so the parsing of
 * {@code java -version} and of the Adoptium response are both covered here.
 */
class JavaRuntimeTest {

    @Test
    void acceptsAJdkHomeOnlyAfterRunningIt() {
        // JAVA_HOME names a JDK home, and the running JVM's java.home has the same shape: bin/java
        // plus the runtime. A directory is accepted only if starting it reports a feature version.
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
        // Mirrors the shape of /v3/assets/latest/<v>/hotspot: the values we need live in the
        // "package" object, after a checksum that has a similarly named neighbour to trip up a
        // naive search.
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
    void mapsThePlatformToAnAdoptiumTarget() throws Exception {
        // Only meaningful on the platform actually running the test, but it must never throw for a
        // platform the JVM claims to support.
        String os = JavaRuntime.osName();
        String arch = JavaRuntime.archName();
        org.junit.jupiter.api.Assertions.assertTrue(
            os.equals("windows") || os.equals("linux") || os.equals("mac"), "unexpected os: " + os);
        org.junit.jupiter.api.Assertions.assertFalse(arch.isEmpty(), "empty architecture");
    }
}
