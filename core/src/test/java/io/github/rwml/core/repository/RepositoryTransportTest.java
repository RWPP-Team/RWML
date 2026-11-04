/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core.repository;

import io.github.rwml.api.extensions.ExtensionEntry;
import io.github.rwml.api.extensions.ExtensionLibrary;
import io.github.rwml.api.repository.InstallReport;
import io.github.rwml.api.repository.RepositoryException;
import io.github.rwml.api.repository.TransferListener;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证仓库传输层可在没有 java.net.http 的平台上完成读取与安装。 */
class RepositoryTransportTest {

    private static final URI INDEX = URI.create("https://registry.example/api/v1/index.json");
    private static final URI ARCHIVE = URI.create("https://registry.example/alpha.jar");

    @Test
    void transportRejectsInsecureAddressesBeforeOpeningAConnection() {
        RepositoryException failure = assertThrows(RepositoryException.class,
            () -> RepositoryTransport.system().get(URI.create("http://registry.example/index.json"),
                "application/json", 1000, 1000));
        assertTrue(failure.getMessage().contains("insecure"));
    }

    @Test
    void indexReadReportsHttpFailures() {
        FakeTransport transport = new FakeTransport();
        transport.status = 503;
        RepositoryClient client = client(transport, null, new RecordingLibrary());
        RepositoryException failure = assertThrows(RepositoryException.class, client::catalog);
        assertTrue(failure.getMessage().contains("HTTP 503"));
    }

    @Test
    void installDownloadsVerifiesDigestAndCleansUpTheHostsTemporaryDirectory() throws Exception {
        byte[] archive = "rwml-repository-test-archive".getBytes(StandardCharsets.UTF_8);
        String digest = hex(MessageDigest.getInstance("SHA-256").digest(archive));
        String index = """
            {"name":"Test","extensions":[{
              "id":"io.example.alpha","name":"Alpha","version":"2.0.0","platforms":["android"],
              "artifacts":[{"platform":"android","url":"%s","sha256":"%s","size":%d}]
            }]}
            """.formatted(ARCHIVE, digest, archive.length);

        FakeTransport transport = new FakeTransport();
        transport.documents.put(INDEX, index.getBytes(StandardCharsets.UTF_8));
        transport.documents.put(ARCHIVE, archive);
        Path temporary = Files.createTempDirectory("rwml-repository-test");
        RecordingLibrary library = new RecordingLibrary();
        RepositoryClient client = client(transport, temporary, library);

        InstallReport report = client.install("io.example.alpha", TransferListener.NOOP);

        assertEquals("2.0.0", report.version());
        assertTrue(report.complete());
        assertTrue(report.requiresRestart());
        assertEquals(1, report.imported().size());
        assertArrayEquals(archive, library.imported);
        try (Stream<Path> files = Files.list(temporary)) {
            assertEquals(0, files.count(), "The downloaded archive must not remain in the host directory");
        }
    }

    @Test
    void installDiscardsAnArchiveThatFailsItsDeclaredDigest() throws Exception {
        byte[] archive = "rwml-repository-test-archive".getBytes(StandardCharsets.UTF_8);
        String index = """
            {"extensions":[{
              "id":"io.example.alpha","name":"Alpha","version":"2.0.0","platforms":["android"],
              "artifacts":[{"platform":"android","url":"%s","sha256":"%s","size":%d}]
            }]}
            """.formatted(ARCHIVE, "0".repeat(64), archive.length);

        FakeTransport transport = new FakeTransport();
        transport.documents.put(INDEX, index.getBytes(StandardCharsets.UTF_8));
        transport.documents.put(ARCHIVE, archive);
        Path temporary = Files.createTempDirectory("rwml-repository-test");
        RecordingLibrary library = new RecordingLibrary();

        RepositoryException failure = assertThrows(RepositoryException.class,
            () -> client(transport, temporary, library).install("io.example.alpha", TransferListener.NOOP));
        assertTrue(failure.getMessage().contains("SHA-256"));
        assertEquals(0, library.calls);
        try (Stream<Path> files = Files.list(temporary)) {
            assertEquals(0, files.count());
        }
    }

    private static RepositoryClient client(FakeTransport transport, Path temporary,
                                           RecordingLibrary library) {
        try {
            return new RepositoryClient(INDEX, library, "android", transport, temporary);
        } catch (RepositoryException invalid) {
            throw new AssertionError(invalid);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xFF));
        }
        return result.toString();
    }

    private static final class FakeTransport implements RepositoryTransport {
        final Map<URI, byte[]> documents = new LinkedHashMap<>();
        int status = 200;

        @Override
        public Response get(URI uri, String accept, int connectMillis, int readMillis) {
            byte[] body = documents.getOrDefault(uri, new byte[0]);
            return new Response(uri.equals(INDEX) ? status : 200, uri, body.length,
                new ByteArrayInputStream(body));
        }
    }

    private static final class RecordingLibrary implements ExtensionLibrary {
        byte[] imported;
        int calls;

        @Override public Path directory() { return Path.of("."); }
        @Override public List<ExtensionEntry> entries() { return List.of(); }
        @Override public void setEnabled(String id, boolean enabled) { }
        @Override public void remove(String id) { }
        @Override public void removeArchive(Path archive) { }
        @Override public boolean restartRequired() { return false; }

        @Override
        public ExtensionEntry importArchive(InputStream input, boolean replace) throws IOException {
            calls++;
            imported = input.readAllBytes();
            return new ExtensionEntry("io.example.alpha", "Alpha", "2.0.0", "", List.of(), List.of(),
                null, false, true, false, "");
        }
    }
}
