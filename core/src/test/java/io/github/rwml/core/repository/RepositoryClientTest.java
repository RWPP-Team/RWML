/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core.repository;

import io.github.rwml.api.extensions.ExtensionEntry;
import io.github.rwml.api.extensions.ExtensionLibrary;
import io.github.rwml.api.extensions.ExtensionPackReport;
import io.github.rwml.api.repository.AvailableUpdate;
import io.github.rwml.api.repository.CatalogEntry;
import io.github.rwml.api.repository.RepositoryException;
import io.github.rwml.api.repository.TransferListener;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryClientTest {

    private static final String INDEX = """
        {
          "name": "Test registry",
          "extensions": [
            {
              "id": "io.example.alpha", "name": "Alpha Client", "version": "2.0.0",
              "summary": "Chat overlay for rooms", "description": "Adds a chat overlay.",
              "tags": ["chat"], "platforms": ["jvm"],
              "artifacts": [{"platform": "jvm", "url": "https://registry.example/alpha.jar",
                             "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "size": 8}]
            },
            {
              "id": "io.example.beta", "name": "Beta Tools", "version": "1.0.0",
              "summary": "Map helpers", "description": "Draws markers.",
              "artifacts": [{"platform": "jvm", "url": "https://registry.example/beta.jar",
                             "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "size": 4}]
            },
            {
              "id": "io.example.gamma", "name": "Gamma", "version": "3.0.0",
              "summary": "Android only",
              "platforms": ["android"],
              "artifacts": [{"platform": "jvm", "url": "https://registry.example/gamma.jar",
                             "sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc", "size": 4}]
            }
          ]
        }
        """;

    @Test
    void searchRequiresEveryKeywordAndRanksIdentifiersFirst() throws Exception {
        RepositoryClient repository = repository(INDEX, "jvm");

        assertEquals(List.of("io.example.alpha"), ids(repository.search("alpha")));
        assertEquals(3, repository.search("").size());
        assertEquals(List.of(), ids(repository.search("chat nonexistent")));
        assertEquals(List.of("io.example.alpha"), ids(repository.search("CHAT")));
    }

    @Test
    void updatesReportsOnlyNewerVersionsThatSupportThisPlatform() throws Exception {
        RepositoryClient repository = repository(INDEX, "jvm");
        List<AvailableUpdate> updates = repository.updates(List.of(
            entry("io.example.alpha", "1.9.0", false),
            entry("io.example.beta", "1.0.0", false),
            entry("io.example.gamma", "1.0.0", false),
            entry("io.rwml.builtin", "1.0.0", true)));

        assertEquals(1, updates.size());
        assertEquals("io.example.alpha", updates.get(0).id());
        assertEquals("1.9.0", updates.get(0).installedVersion());
        assertEquals("2.0.0", updates.get(0).entry().version());
    }

    @Test
    void installRejectsEntriesWithoutCatalogDigest() throws Exception {
        RepositoryClient repository = repository("""
            {"extensions": [
              {"id": "unsigned", "version": "1.0.0", "artifacts": [
                {"platform": "jvm", "url": "https://registry.example/unsigned.jar", "size": 10}
              ]}
            ]}
            """, "jvm");
        RepositoryException failure = assertThrows(RepositoryException.class,
            () -> repository.install("unsigned", "jvm", TransferListener.NOOP));
        assertTrue(failure.getMessage().contains("SHA-256"));
    }

    @Test
    void installReportsMissingEntriesAndMissingPlatformArchives() throws Exception {
        RepositoryClient repository = repository(INDEX, "jvm");
        assertThrows(RepositoryException.class,
            () -> repository.install("io.example.missing", "jvm", TransferListener.NOOP));
        RepositoryException failure = assertThrows(RepositoryException.class,
            () -> repository.install("io.example.gamma", "jvm", TransferListener.NOOP));
        assertTrue(failure.getMessage().contains("does not support jvm"));
    }

    @Test
    void cachedCatalogIsReusedUntilRefreshed() throws Exception {
        RepositoryClient repository = repository(INDEX, "jvm");
        assertTrue(repository.catalog().size() == 3);
        assertEquals(3, repository.refresh().size());
        assertEquals("jvm", repository.platform());
        assertEquals("android", RepositoryClient.platformName(io.github.rwml.api.Platform.ANDROID));
        assertEquals("jvm", RepositoryClient.platformName(io.github.rwml.api.Platform.DESKTOP));
    }

    private static List<String> ids(List<CatalogEntry> entries) {
        return entries.stream().map(CatalogEntry::id).toList();
    }

    private static ExtensionEntry entry(String id, String version, boolean builtIn) {
        return new ExtensionEntry(id, id, version, "", List.of(), List.of(), null, builtIn,
            true, builtIn, "");
    }

    private static RepositoryClient repository(String document, String platform) throws RepositoryException {
        ExtensionLibrary library = new EmptyLibrary();
        return new RepositoryClient(URI.create("https://registry.example/api/v1/index.json"), library, platform) {
            @Override
            String readIndex() {
                return document;
            }
        };
    }

    /** 只满足构造函数需要的空实现；这些测试不会写入归档。 */
    private static final class EmptyLibrary implements ExtensionLibrary {
        @Override public Path directory() { return Path.of("."); }
        @Override public List<ExtensionEntry> entries() { return List.of(); }
        @Override public void setEnabled(String id, boolean enabled) { }
        @Override public ExtensionEntry importArchive(InputStream input, boolean replace) throws IOException {
            throw new IOException("No archives are installed in this test");
        }
        @Override public ExtensionPackReport importPack(InputStream input, boolean replace) {
            throw new UnsupportedOperationException();
        }
        @Override public void remove(String id) { }
        @Override public void removeArchive(Path archive) { }
        @Override public boolean restartRequired() { return false; }
    }
}
