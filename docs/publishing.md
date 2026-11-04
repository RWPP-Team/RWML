# Publishing libraries

Coordinates: `io.github.rwpp-team:rwml-core:1.0.2` and `io.github.rwpp-team:game-lib:v3-1.15`. The `v3` prefix is the mapping revision; the game version remains 1.15. Game classes retain their `com.corrodinggames.rts` package. The game library uses CC0 1.0 Universal, as confirmed by the project maintainer; core uses RWPP-Team Shield.

Requires Windows, JDK 25, Python 3.10+, GPG, a verified Central namespace, and a [Portal User Token](https://central.sonatype.com/usertoken).

```powershell
# 首次设置；SaveToken 会在本机提示输入令牌，且不会显示密码。
.\scripts\publish\publish-core.ps1 -Action PrepareSigning
.\scripts\publish\publish-core.ps1 -Action SaveToken

# 构建并校验签名和校验和，不执行上传。
.\scripts\publish\publish-core.ps1 -Action Build -Offline

# 上传、验证并发布；可恢复已保存的部署，无需重复上传。
.\scripts\publish\publish-core.ps1 -Action Publish -Offline
.\scripts\publish\publish-core.ps1 -Action Status

# 映射后的游戏库：独立发布，使用相同的受保护凭据。
.\scripts\publish\publish-core.ps1 -Target GameLib -Action Build -Offline
.\scripts\publish\publish-core.ps1 -Target GameLib -Action Publish -Offline
.\scripts\publish\publish-core.ps1 -Target GameLib -Action Status
```

Credentials and the dedicated RSA-4096 signing key live outside the repository in `%USERPROFILE%\.gradle\rwml-central`. The Token and key password use Windows current-user encryption. Keep the encrypted private-key backup and a separately secured copy of its password for migration to another Windows account or computer.

The bundle is `build/rwml-1.0.2-central-bundle.zip`; the deployment ID is recorded in `build/central-deployment-rwml-core-1.0.2.json`. The build includes core checks, POM metadata, sources, Javadoc, Gradle metadata, signatures, and checksums. GPG signatures include the full issuer fingerprint, and the public key is submitted to `keyserver.ubuntu.com`.

`bundleGameLibForCentral` writes the game bundle to `build/game-lib-v3-1.15-central-bundle.zip`; the publishing script saves its deployment record at `build/central-deployment-game-lib-v3-1.15.json`. The bundle task packages the mapped binary, generated Java sources, POM, and a documentation placeholder pointing to those sources; the publishing flow signs its artifacts. Game library consumers use `compileOnly`; core remains independent of the game.

For a new core release, update `version` in `gradle.properties`, the bundled descriptors and README examples. The Gradle build and PowerShell script derive the core coordinates and bundle path from that version, and save a separate deployment record per release. For the independent game library, update its version in `gradle/libs.versions.toml`; the Gradle bundle task and PowerShell publishing script derive the coordinates and output paths from it. The Python tools require explicit coordinates. Published Central versions cannot be overwritten.

This release adds host-owned native UI surfaces, dependency class-loader delegation for shared extension libraries, Android extension-archive resource access, and a safe native-library extraction API. See [the 1.0.2 release notes](release-notes-1.0.2.md) and [UI framework services](ui-framework.md).

References: [Central requirements](https://central.sonatype.org/publish/requirements/) · [Publisher API](https://central.sonatype.org/publish/publish-portal-api/).
