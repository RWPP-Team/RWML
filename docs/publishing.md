# Publishing libraries

Coordinates: `io.github.rwpp-team:rwml-core:1.0.1` and `io.github.rwpp-team:game-lib:v2-1.15`. The `v2` prefix is the mapping revision; the game version remains 1.15. Game classes retain their `com.corrodinggames.rts` package. The game library uses CC0 1.0 Universal, as confirmed by the project maintainer; core uses RWPP-Team Shield.

Requires Windows, JDK 25, Python 3.10+, GPG, a verified Central namespace, and a [Portal User Token](https://central.sonatype.com/usertoken).

```powershell
# First-time setup; SaveToken prompts locally without displaying the password.
.\scripts\publish-core.ps1 -Action PrepareSigning
.\scripts\publish-core.ps1 -Action SaveToken

# Build and verify signatures/checksums without uploading.
.\scripts\publish-core.ps1 -Action Build -Offline

# Upload, validate, and publish; resumes a saved deployment without re-uploading.
.\scripts\publish-core.ps1 -Action Publish -Offline
.\scripts\publish-core.ps1 -Action Status

# Mapped game library: independent release, using the same protected credentials.
.\scripts\publish-core.ps1 -Target GameLib -Action Build -Offline
.\scripts\publish-core.ps1 -Target GameLib -Action Publish -Offline
.\scripts\publish-core.ps1 -Target GameLib -Action Status
```

Credentials and the dedicated RSA-4096 signing key live outside the repository in `%USERPROFILE%\.gradle\rwml-central`. The Token and key password use Windows current-user encryption. Keep the encrypted private-key backup and a separately secured copy of its password for migration to another Windows account or computer.

The bundle is `build/rwml-1.0.1-central-bundle.zip`; the deployment ID is recorded in `build/central-deployment-rwml-core-1.0.1.json`. The build includes core checks, POM metadata, sources, Javadoc, Gradle metadata, signatures, and checksums. GPG signatures include the full issuer fingerprint, and the public key is submitted to `keyserver.ubuntu.com`.

`bundleGameLibForCentral` writes the game bundle to `build/game-lib-v2-1.15-central-bundle.zip`; the publishing script saves its deployment record at `build/central-deployment-game-lib-v2-1.15.json`. The bundle task packages the mapped binary, generated Java sources, POM, and a documentation placeholder pointing to those sources; the publishing flow signs its artifacts. Game library consumers use `compileOnly`; core remains independent of the game.

For a new core release, update `version` in `gradle.properties`, the bundled descriptors and README examples. The Gradle build and PowerShell script derive the core coordinates and bundle path from that version, and save a separate deployment record per release. For the independent game library, update its version in `gradle/libs.versions.toml`; the Gradle bundle task and PowerShell publishing script derive the coordinates and output paths from it. The Python tools require explicit coordinates. Published Central versions cannot be overwritten.

References: [Central requirements](https://central.sonatype.org/publish/requirements/) · [Publisher API](https://central.sonatype.org/publish/publish-portal-api/).
