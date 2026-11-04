/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core.repository;

import io.github.rwml.api.repository.Catalog;
import io.github.rwml.api.repository.CatalogEntry;
import io.github.rwml.api.repository.ExtensionArtifact;
import io.github.rwml.api.repository.RepositoryException;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogParserTest {

    private static final URI SOURCE = URI.create("https://registry.example/api/v1/index.json");

    @Test
    void readsEntriesAndResolvesRelativeArchiveAddresses() throws Exception {
        Catalog catalog = CatalogParser.parse("""
            {
              "name": "Test registry",
              "generatedAt": "2026-11-01T00:00:00Z",
              "extensions": [
                {
                  "id": "io.example.alpha",
                  "name": "Alpha",
                  "version": "1.2.3",
                  "apiVersion": "1.0",
                  "summary": "A demo extension",
                  "authors": ["Example"],
                  "tags": ["ui"],
                  "categories": ["Client"],
                  "platforms": ["jvm"],
                  "downloads": 42,
                  "artifacts": [
                    {"platform": "jvm", "url": "files/alpha.jar", "sha256": "sha256:ABCDEF", "size": 10}
                  ]
                }
              ]
            }
            """, SOURCE);

        assertEquals("Test registry", catalog.name());
        assertEquals(1, catalog.size());
        CatalogEntry entry = catalog.find("io.example.alpha");
        assertNotNull(entry);
        assertEquals("1.2.3", entry.version());
        assertEquals(42, entry.downloads());
        assertEquals("https://registry.example/api/v1/files/alpha.jar", entry.artifactsFor("jvm").get(0).url());
        assertEquals("abcdef", entry.artifactsFor("jvm").get(0).sha256());
        assertTrue(entry.supports("jvm"));
        assertTrue(!entry.supports("android"));
    }

    @Test
    void keepsTheHighestVersionWhenAnIdAppearsTwice() throws Exception {
        Catalog catalog = CatalogParser.parse("""
            {"extensions": [
              {"id": "a", "version": "1.9.0", "artifacts": []},
              {"id": "a", "version": "1.10.0", "artifacts": []},
              {"id": "b", "version": "0.1.0", "artifacts": []}
            ]}
            """, SOURCE);

        assertEquals(2, catalog.size());
        assertEquals("1.10.0", catalog.find("a").version());
    }

    @Test
    void skipsEntriesWithoutIdOrVersion() throws Exception {
        Catalog catalog = CatalogParser.parse("""
            {"extensions": [
              {"name": "No id", "version": "1.0"},
              {"id": "no-version", "name": "No version"},
              {"id": "empty-platforms", "version": "1.0", "platforms": []}
            ]}
            """, SOURCE);

        assertEquals(1, catalog.size());
        assertNull(catalog.find("no-version"));
        assertEquals("", catalog.find("empty-platforms").apiVersion());
        assertTrue(catalog.find("empty-platforms").supports("android"));
    }

    @Test
    void rejectsDocumentsThatAreNotCatalogObjects() {
        assertThrows(RepositoryException.class, () -> CatalogParser.parse("[]", SOURCE));
        assertThrows(RepositoryException.class, () -> CatalogParser.parse("{", SOURCE));
        assertThrows(RepositoryException.class, () -> CatalogParser.parse("{} trailing", SOURCE));
    }

    @Test
    void marksArtifactsWithoutDigestAsUnusable() throws Exception {
        Catalog catalog = CatalogParser.parse("""
            {"extensions": [
              {"id": "unsigned", "version": "1.0.0", "artifacts": [
                {"platform": "jvm", "url": "https://registry.example/unsigned.jar", "size": 5}
              ]}
            ]}
            """, SOURCE);

        ExtensionArtifact artifact = catalog.find("unsigned").artifactsFor("jvm").get(0);
        assertTrue(!artifact.usable());
    }
}
