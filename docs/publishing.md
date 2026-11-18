# Publishing libraries

`io.github.rwpp-team:rwml-core` and `io.github.rwpp-team:game-lib` use the same release version from `gradle.properties`, starting with `1.0.3`. The game version remains 1.15. Game classes retain their `com.corrodinggames.rts` package. The game library uses CC0 1.0 Universal, as confirmed by the project maintainer; core uses RWPP-Team Shield.

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

# 发布同一 RWML 版本的映射游戏库。
.\scripts\publish\publish-core.ps1 -Target GameLib -Action Build -Offline
.\scripts\publish\publish-core.ps1 -Target GameLib -Action Publish -Offline
.\scripts\publish\publish-core.ps1 -Target GameLib -Action Status
```

Credentials and the dedicated RSA-4096 signing key live outside the repository in `%USERPROFILE%\.gradle\rwml-central`. The Token and key password use Windows current-user encryption. Keep the encrypted private-key backup and a separately secured copy of its password for migration to another Windows account or computer.

The core bundle uses `build/rwml-<version>-central-bundle.zip`, with its deployment ID in `build/central-deployment-rwml-core-<version>.json`. The build includes core checks, POM metadata, sources, Javadoc, Gradle metadata, signatures, and checksums. GPG signatures include the full issuer fingerprint, and the public key is submitted to `keyserver.ubuntu.com`.

`bundleGameLibForCentral` writes `build/game-lib-<version>-central-bundle.zip`, with its deployment record in `build/central-deployment-game-lib-<version>.json`. The bundle contains the mapped binary generated from the same pinned Anvil checkout as the hosts, plus Java sources, POM, and Javadoc. Game library consumers use `compileOnly`.

For a new RWML release, update `version` in `gradle.properties` and update the README examples. Build both bundles from the same release checkout. Gradle and the PowerShell script derive both coordinates and bundle paths from that one version. The Python tools require explicit coordinates. Published Central versions cannot be overwritten.

Preview versions carry a suffix such as `-rc01` or `-beta01` in `gradle.properties` and in the tag. The release workflow treats any suffixed version as a prerelease, so GitHub keeps `releases/latest` on the last stable build; the publish script accepts the same suffixes and uploads them to Central unchanged.

RWML 1.0.2 introduced host-owned native UI surfaces, dependency class-loader delegation for shared extension libraries, Android extension-archive resource access, and a native-library extraction API. See [the 1.0.2 release notes](release-notes-1.0.2.md) and [UI framework services](ui-framework.md).

References: [Central requirements](https://central.sonatype.org/publish/requirements/) · [Publisher API](https://central.sonatype.org/publish/publish-portal-api/).
