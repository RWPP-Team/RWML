# Extension repository

RWML can read a published extension catalog, search it, and install archives without leaving the
host. The repository lives in `core`, so desktop and Android share one implementation; only the
platform name differs.

## What the host provides

`io.github.rwml.api.repository.ExtensionRepository` is the public surface:

| Method | Use |
| --- | --- |
| `catalog()` | Cached catalog; no network call while the cache is fresh |
| `refresh()` | Fetch the catalog again and replace the cache |
| `search(query)` | Ranked matches over ID, name, summary, tags and categories |
| `updates(installed)` | Installed entries that are older than the catalog |
| `install(id, listener)` | Download, verify and write the archives for `platform()` |
| `install(id, platform, listener)` | Same, for an explicit target platform |

The host registers an implementation on `UiServices`:

```java
ui.repository(RepositoryClient.open(ui.extensions(), Platform.DESKTOP, manager.dataDirectory()));
```

`ExtensionCatalogPanel` in the desktop launcher reads it through
`UiServices.repository()`. When a host does not register one, `hasRepository()` is false and the
browse tab explains that the build has no repository instead of failing.

## Catalog format

`/api/v1/index.json` is a JSON object. Unknown fields are ignored; entries without an ID or a
version are skipped, and duplicate IDs keep the highest version.

```json
{
  "name": "RWML Extension Registry",
  "generatedAt": "2026-11-03T14:36:37Z",
  "extensions": [
    {
      "id": "io.github.rwpp",
      "name": "RWPP",
      "version": "2.0.0",
      "apiVersion": "1.0.2",
      "summary": "One-line summary",
      "description": "Longer text",
      "authors": ["RWPP Team"],
      "tags": ["client"],
      "categories": ["Client"],
      "license": "RWPP-Team Shield 1.0.0",
      "homepage": "https://example.com",
      "platforms": ["jvm", "android"],
      "downloads": 0,
      "updatedAt": "2026-11-03",
      "notes": "Release notes",
      "artifacts": [
        {"platform": "jvm", "url": "https://example.com/rwpp-client-2.0.0-jvm.jar",
         "sha256": "8545f6…", "size": 79041523}
      ]
    }
  ]
}
```

One entry may carry several archives, either per platform or as an API jar plus an implementation
jar. `install` writes every archive matching the target platform in catalog order, so a client and
its API stay on the same release.

## Installation rules

- Archives are written through `ExtensionLibrary.importArchive`, exactly like a manual import.
  Version selection and the enabled flag are therefore untouched: an update keeps the extension
  enabled or disabled as the user set it, and takes effect on the next host process.
- A URL is accepted only when it is HTTPS without credentials. The address after a redirect must
  also be HTTPS.
- `sha256` is mandatory. An entry without a digest is rejected with an explicit error instead of
  being downloaded unverified.
- The received byte count must match `size`, and a single archive may not exceed 256 MiB. Catalog
  documents are limited to 8 MiB.
- Any mismatch deletes the temporary file before reporting the failure.

## Choosing the catalog address

`RepositoryConfig` resolves the address in this order:

1. system property `rwml.repository.index`
2. environment variable `RWML_REPOSITORY_INDEX`
3. `repository.properties` in the host data directory
4. `https://rwpp-team.top/api/v1/index.json`

The file holds a single key:

```properties
index=https://example.com/api/v1/index.json
```

`RepositoryConfig.index(dataDirectory, url)` writes it atomically, so a launcher can offer a
settings field without touching the file format.

## Publishing a catalog

The documentation site in this repository's sibling project generates the catalog from
`data/extensions.json` and serves it from `/api/v1`. Any static host works, as long as the
following exist:

- `/api/v1/index.json` — the document above
- one archive per entry and platform, reachable over HTTPS
- matching `sha256` and `size` values, refreshed whenever an archive is rebuilt
