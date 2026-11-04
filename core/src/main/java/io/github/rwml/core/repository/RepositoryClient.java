/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core.repository;

import io.github.rwml.api.Platform;
import io.github.rwml.api.extensions.ExtensionEntry;
import io.github.rwml.api.extensions.ExtensionLibrary;
import io.github.rwml.api.repository.AvailableUpdate;
import io.github.rwml.api.repository.Catalog;
import io.github.rwml.api.repository.CatalogEntry;
import io.github.rwml.api.repository.ExtensionArtifact;
import io.github.rwml.api.repository.ExtensionRepository;
import io.github.rwml.api.repository.InstallReport;
import io.github.rwml.api.repository.RepositoryException;
import io.github.rwml.api.repository.TransferListener;
import io.github.rwml.core.ExtensionVersion;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 通过 HTTPS 读取目录 JSON 并安装归档的仓库实现。
 *
 * <p>安全边界集中在此类：目录与归档都必须是无凭据的 HTTPS 地址，归档必须带有 SHA-256 摘要并在写入前后校验，单个归档
 * 不得超过 256 MiB，重定向后的地址也必须仍是 HTTPS。目录内容来自第三方，因此条目中的版本、ID 与摘要都只作为数据使用，
 * 从不参与路径拼接。</p>
 *
 * <p>安装写入宿主的扩展目录，与手工导入共用 {@link ExtensionLibrary#importArchive}，因此不会绕过版本选择或启用状态。</p>
 */
public class RepositoryClient implements ExtensionRepository {

    /** 目录文档的大小上限。目录只含元数据，超过此值说明地址指向了错误的内容。 */
    static final long MAX_INDEX_BYTES = 8L * 1024 * 1024;

    /** 单个归档的大小上限，与宿主导入归档的限制保持同一量级。 */
    static final long MAX_ARTIFACT_BYTES = 256L * 1024 * 1024;

    /** 目录缓存时间；界面刷新会显式绕过缓存，因此此值只影响连续搜索的重复请求。 */
    private static final long CACHE_MILLIS = 10L * 60L * 1000L;

    private static final String USER_AGENT = "RWML-Extension-Browser/1.0";

    private final URI index;
    private final ExtensionLibrary library;
    private final String platform;
    private final HttpClient http;

    private Catalog cached;
    private long cachedAt;

    public RepositoryClient(URI index, ExtensionLibrary library, String platform) throws RepositoryException {
        this(index, library, platform, HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build());
    }

    RepositoryClient(URI index, ExtensionLibrary library, String platform, HttpClient http)
        throws RepositoryException {
        this.index = RepositoryConfig.validate(index == null ? null : index.toString());
        this.library = library;
        this.platform = normalizePlatform(platform);
        this.http = http;
    }

    /**
     * 按宿主数据目录中的配置创建仓库。
     *
     * @param library       安装目标
     * @param platform      宿主平台
     * @param dataDirectory 保存目录地址覆盖值的数据目录
     * @return 仓库实例
     * @throws IOException 目录地址缺失或不合法
     */
    public static RepositoryClient open(ExtensionLibrary library, Platform platform, Path dataDirectory)
        throws IOException {
        return new RepositoryClient(RepositoryConfig.index(dataDirectory), library, platformName(platform));
    }

    /**
     * @param platform 宿主平台
     * @return 目录中使用的平台名称
     */
    public static String platformName(Platform platform) {
        return platform == Platform.ANDROID ? "android" : "jvm";
    }

    @Override
    public String platform() {
        return platform;
    }

    /** @return 目录地址 */
    public URI location() {
        return index;
    }

    @Override
    public Catalog catalog() throws IOException {
        synchronized (this) {
            if (cached != null && System.currentTimeMillis() - cachedAt < CACHE_MILLIS) {
                return cached;
            }
        }
        return refresh();
    }

    @Override
    public synchronized Catalog refresh() throws IOException {
        String document = readIndex();
        Catalog catalog = CatalogParser.parse(document, index);
        cached = catalog;
        cachedAt = System.currentTimeMillis();
        return catalog;
    }

    @Override
    public List<CatalogEntry> search(String query) throws IOException {
        Catalog catalog = catalog();
        List<String> tokens = tokens(query);
        if (tokens.isEmpty()) {
            return catalog.entries();
        }
        List<Match> matches = new ArrayList<>();
        for (CatalogEntry entry : catalog.entries()) {
            int score = score(entry, tokens);
            if (score > 0) {
                matches.add(new Match(entry, score));
            }
        }
        matches.sort(Comparator.comparingInt(Match::score).reversed()
            .thenComparing(match -> match.entry().name(), String.CASE_INSENSITIVE_ORDER));
        List<CatalogEntry> result = new ArrayList<>(matches.size());
        for (Match match : matches) {
            result.add(match.entry());
        }
        return List.copyOf(result);
    }

    @Override
    public List<AvailableUpdate> updates(List<ExtensionEntry> installed) throws IOException {
        Catalog catalog = catalog();
        Map<String, String> versions = new LinkedHashMap<>();
        for (ExtensionEntry entry : installed == null ? List.<ExtensionEntry>of() : installed) {
            if (entry == null || entry.id() == null || entry.builtIn() || entry.version() == null) {
                continue;
            }
            String current = versions.get(entry.id());
            if (current == null || ExtensionVersion.compare(entry.version(), current) > 0) {
                versions.put(entry.id(), entry.version());
            }
        }
        List<AvailableUpdate> result = new ArrayList<>();
        for (Map.Entry<String, String> installedVersion : versions.entrySet()) {
            CatalogEntry entry = catalog.find(installedVersion.getKey());
            if (entry == null || !entry.supports(platform)) {
                continue;
            }
            if (ExtensionVersion.compare(entry.version(), installedVersion.getValue()) > 0) {
                result.add(new AvailableUpdate(entry.id(), installedVersion.getValue(), entry));
            }
        }
        result.sort(Comparator.comparing(AvailableUpdate::id));
        return List.copyOf(result);
    }

    @Override
    public InstallReport install(String id, String targetPlatform, TransferListener listener) throws IOException {
        if (id == null || id.isBlank()) {
            throw new RepositoryException("No extension id was given to install");
        }
        String target = normalizePlatform(targetPlatform == null || targetPlatform.isBlank()
            ? platform : targetPlatform);
        CatalogEntry entry = catalog().find(id);
        if (entry == null) {
            throw new RepositoryException("The repository has no entry for " + id);
        }
        if (!entry.supports(target)) {
            throw new RepositoryException(entry.name() + " " + entry.version() + " does not support " + target);
        }
        List<ExtensionArtifact> artifacts = entry.artifactsFor(target);
        if (artifacts.isEmpty()) {
            throw new RepositoryException(entry.name() + " " + entry.version() + " has no " + target + " archive");
        }
        for (ExtensionArtifact artifact : artifacts) {
            if (!artifact.usable()) {
                throw new RepositoryException("The " + target + " archive of " + entry.name()
                    + " has no SHA-256 digest; RWML refuses to install unverified code");
            }
        }

        TransferListener progress = listener == null ? TransferListener.NOOP : listener;
        long total = 0;
        for (ExtensionArtifact artifact : artifacts) {
            total += Math.max(artifact.size(), 0L);
        }

        List<ExtensionEntry> imported = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Map<String, String> failed = new LinkedHashMap<>();
        long downloaded = 0;
        synchronized (this) {
            for (ExtensionArtifact artifact : artifacts) {
                Path archive = download(artifact, downloaded, total, progress);
                try (InputStream input = Files.newInputStream(archive)) {
                    ExtensionEntry installed = library.importArchive(input, true);
                    if (installed == null) {
                        failed.put(artifact.url(), "The archive could not be installed");
                    } else if (installed.valid()) {
                        imported.add(installed);
                    } else {
                        skipped.add(artifact.id() + ": " + installed.problem());
                    }
                } catch (IOException failure) {
                    failed.put(artifact.id(), failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage());
                    // 同一扩展的归档通常相互匹配；继续安装其余归档会留下版本不一致的组合。
                    break;
                } finally {
                    Files.deleteIfExists(archive);
                }
                downloaded += Math.max(artifact.size(), 0L);
            }
        }
        return new InstallReport(entry.id(), entry.version(), imported, skipped, failed);
    }

    /** 读取目录文档；只接受 200 响应与 JSON 内容，大小受 {@link #MAX_INDEX_BYTES} 限制。 */
    /**
     * 读取目录文档。
     *
     * <p>子类可以覆盖此方法以提供内置目录，例如完全离线的宿主；其余流程（搜索排序、摘要校验、写入归档）保持不变。</p>
     */
    String readIndex() throws IOException {
        HttpRequest request = HttpRequest.newBuilder(index)
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .GET()
            .build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RepositoryException("Reading the extension repository was interrupted", interrupted);
        } catch (IOException failure) {
            throw new RepositoryException("Cannot reach the extension repository at " + index + ": "
                + failure.getMessage(), failure);
        }
        try (InputStream body = response.body()) {
            requireHttps(response.uri());
            if (response.statusCode() != 200) {
                throw new RepositoryException("The extension repository returned HTTP " + response.statusCode());
            }
            byte[] document = readBounded(body, MAX_INDEX_BYTES, "The extension repository document");
            return new String(document, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** 下载一个归档到临时文件，校验大小与摘要后返回路径；失败时不留下文件。 */
    private Path download(ExtensionArtifact artifact, long alreadyDownloaded, long total,
                          TransferListener progress) throws IOException {
        URI uri;
        try {
            uri = URI.create(artifact.url());
        } catch (IllegalArgumentException invalid) {
            throw new RepositoryException("The archive address is invalid: " + artifact.url(), invalid);
        }
        requireHttps(uri);
        if (uri.getUserInfo() != null) {
            throw new RepositoryException("The archive address must not contain credentials: " + artifact.url());
        }
        if (artifact.size() > MAX_ARTIFACT_BYTES) {
            throw new RepositoryException("The archive of " + artifact.id() + " exceeds 256 MiB");
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMinutes(10))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/octet-stream")
            .GET()
            .build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RepositoryException("The download was interrupted", interrupted);
        } catch (IOException failure) {
            throw new RepositoryException("Cannot download " + artifact.url() + ": " + failure.getMessage(), failure);
        }

        Path target = Files.createTempFile("rwml-repository-", ".part");
        try (InputStream body = response.body()) {
            requireHttps(response.uri());
            if (response.statusCode() != 200) {
                throw new RepositoryException("Downloading " + artifact.url() + " returned HTTP "
                    + response.statusCode());
            }
            long declared = response.headers().firstValueAsLong("content-length").orElse(-1L);
            if (declared > MAX_ARTIFACT_BYTES) {
                throw new RepositoryException("The archive of " + artifact.id() + " exceeds 256 MiB");
            }
            MessageDigest digest = sha256();
            long received = 0;
            long lastReport = 0;
            byte[] buffer = new byte[32 * 1024];
            try (OutputStream output = Files.newOutputStream(target)) {
                while (true) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new RepositoryException("The download was interrupted");
                    }
                    int count = body.read(buffer);
                    if (count < 0) {
                        break;
                    }
                    received += count;
                    if (received > MAX_ARTIFACT_BYTES || (artifact.size() > 0 && received > artifact.size())) {
                        throw new RepositoryException("The archive of " + artifact.id()
                            + " is larger than the repository declared");
                    }
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    long now = System.nanoTime();
                    if (now - lastReport >= 100_000_000L) {
                        lastReport = now;
                        progress.onProgress(alreadyDownloaded + received, total, "Downloading " + artifact.id());
                    }
                }
            }
            if (artifact.size() > 0 && received != artifact.size()) {
                throw new RepositoryException("The archive of " + artifact.id() + " has "
                    + received + " bytes, but the repository declared " + artifact.size());
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(artifact.sha256())) {
                throw new RepositoryException("The archive of " + artifact.id()
                    + " failed SHA-256 verification; the download was discarded");
            }
            progress.onProgress(alreadyDownloaded + received, total, "Downloaded " + artifact.id());
            return target;
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(target);
            throw failure;
        }
    }

    /** @return 新的 SHA-256 摘要实例；JVM 必须提供该算法，缺失时按运行时故障处理 */
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException missing) {
            throw new IllegalStateException("This JVM does not provide SHA-256", missing);
        }
    }

    private static byte[] readBounded(InputStream input, long limit, String description) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(16 * 1024);
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        while (true) {
            int count = input.read(buffer);
            if (count < 0) {
                break;
            }
            total += count;
            if (total > limit) {
                throw new RepositoryException(description + " exceeds " + (limit / (1024 * 1024)) + " MiB");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void requireHttps(URI uri) throws RepositoryException {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new RepositoryException("The repository redirected to an insecure address: " + uri);
        }
    }

    private static String normalizePlatform(String platform) {
        String value = platform == null ? "" : platform.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "android" -> "android";
            case "jvm", "desktop", "" -> "jvm";
            default -> throw new IllegalArgumentException("Unsupported platform: " + platform);
        };
    }

    private static List<String> tokens(String query) {
        Set<String> tokens = new LinkedHashSet<>();
        if (query == null) {
            return List.of();
        }
        for (String token : query.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return List.copyOf(tokens);
    }

    /** 每个关键词都必须命中；命中位置越靠前、字段越重要，得分越高。 */
    static int score(CatalogEntry entry, List<String> tokens) {
        String id = entry.id().toLowerCase(Locale.ROOT);
        String name = entry.name().toLowerCase(Locale.ROOT);
        String summary = entry.summary().toLowerCase(Locale.ROOT);
        String description = entry.description().toLowerCase(Locale.ROOT);
        Set<String> keywords = entry.keywords();
        int total = 0;
        for (String token : tokens) {
            int best = 0;
            if (id.equals(token)) {
                best = 120;
            } else if (id.startsWith(token)) {
                best = 90;
            } else if (id.contains(token)) {
                best = 70;
            }
            if (name.equals(token)) {
                best = Math.max(best, 110);
            } else if (name.startsWith(token)) {
                best = Math.max(best, 85);
            } else if (name.contains(token)) {
                best = Math.max(best, 65);
            }
            if (keywords.contains(token)) {
                best = Math.max(best, 45);
            }
            if (summary.contains(token)) {
                best = Math.max(best, 25);
            }
            if (description.contains(token)) {
                best = Math.max(best, 10);
            }
            if (best == 0) {
                return 0;
            }
            total += best;
        }
        return total;
    }

    private record Match(CatalogEntry entry, int score) {
    }
}
