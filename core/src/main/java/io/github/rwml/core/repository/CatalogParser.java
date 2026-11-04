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
import io.github.rwml.core.ExtensionVersion;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把目录 JSON 转换为 {@link Catalog}。
 *
 * <p>读取对未知字段保持宽容：发布者添加展示字段不会让旧客户端失败。关键字段则严格校验，缺少 ID 或版本的条目会被跳过，
 * 因为这类条目既不能比较版本也不能安装。同一 ID 出现多次时保留版本较高的一个。</p>
 */
public final class CatalogParser {

    private CatalogParser() {
    }

    /**
     * @param json   目录 JSON 文本
     * @param source 目录地址，用于解析相对下载地址并写入诊断信息
     * @return 解析后的目录
     * @throws RepositoryException 文档结构不可识别
     */
    public static Catalog parse(String json, URI source) throws RepositoryException {
        Object root;
        try {
            root = Json.parse(json);
        } catch (IllegalArgumentException invalid) {
            throw new RepositoryException("The extension repository returned invalid JSON: " + invalid.getMessage(),
                invalid);
        }
        if (!(root instanceof Map<?, ?>)) {
            throw new RepositoryException("The extension repository document must be a JSON object");
        }
        Map<String, Object> document = Json.object(root);
        List<Object> raw = document.containsKey("extensions")
            ? Json.array(document.get("extensions"))
            : Json.array(document.get("entries"));

        Map<String, CatalogEntry> entries = new LinkedHashMap<>();
        int position = 0;
        for (Object element : raw) {
            CatalogEntry entry = entry(Json.object(element), source, position++);
            if (entry == null) {
                continue;
            }
            CatalogEntry previous = entries.get(entry.id());
            if (previous == null || ExtensionVersion.compare(entry.version(), previous.version()) > 0) {
                entries.put(entry.id(), entry);
            }
        }
        return new Catalog(
            Json.string(document.get("name"), "RWML extension repository"),
            source == null ? "" : source.toString(),
            Json.string(document.get("generatedAt"), ""),
            new ArrayList<>(entries.values()));
    }

    private static CatalogEntry entry(Map<String, Object> object, URI source, int position) {
        String id = Json.string(object.get("id"), "").trim();
        if (id.isEmpty()) {
            System.err.println("[rwml] Skipping repository entry #" + position + ": it has no id");
            return null;
        }
        List<ExtensionArtifact> artifacts = new ArrayList<>();
        for (Object element : Json.array(object.get("artifacts"))) {
            ExtensionArtifact artifact = artifact(Json.object(element), source, id);
            if (artifact != null) {
                artifacts.add(artifact);
            }
        }
        String version = Json.string(object.get("version"), "").trim();
        if (version.isEmpty() && !artifacts.isEmpty()) {
            version = version(artifacts.get(0).url());
        }
        if (version.isEmpty()) {
            System.err.println("[rwml] Skipping repository entry " + id + ": it has no version");
            return null;
        }
        return new CatalogEntry(
            id,
            Json.string(object.get("name"), id),
            version,
            Json.string(object.get("apiVersion"), ""),
            Json.string(object.get("summary"), ""),
            Json.string(object.get("description"), ""),
            Json.strings(object.get("authors")),
            Json.strings(object.get("tags")),
            Json.strings(object.get("categories")),
            Json.string(object.get("license"), ""),
            Json.string(object.get("homepage"), ""),
            Json.string(object.get("icon"), Json.string(object.get("iconUrl"), "")),
            Json.strings(object.get("platforms")),
            Json.integer(object.get("downloads"), 0L),
            Json.string(object.get("updatedAt"), ""),
            Json.string(object.get("notes"), Json.string(object.get("changelog"), "")),
            artifacts);
    }

    private static ExtensionArtifact artifact(Map<String, Object> object, URI source, String entryId) {
        String url = Json.string(object.get("url"), Json.string(object.get("downloadUrl"), "")).trim();
        if (url.isEmpty()) {
            return null;
        }
        String sha256 = Json.string(object.get("sha256"), Json.string(object.get("digest"), "")).trim();
        if (sha256.regionMatches(true, 0, "sha256:", 0, 7)) {
            sha256 = sha256.substring(7);
        }
        String platform = Json.string(object.get("platform"), "").trim();
        if (platform.isEmpty()) {
            platform = Json.string(object.get("target"), "").trim();
        }
        return new ExtensionArtifact(
            Json.string(object.get("id"), entryId),
            platform,
            resolve(source, url),
            sha256,
            Json.integer(object.get("size"), 0L));
    }

    /** 相对地址按目录地址解析，使同一份 JSON 可以托管在任意预览域名下。 */
    private static String resolve(URI source, String url) {
        if (source == null) {
            return url;
        }
        try {
            return source.resolve(url).toString();
        } catch (IllegalArgumentException invalid) {
            return url;
        }
    }

    private static String version(String url) {
        String name = url.substring(url.lastIndexOf('/') + 1);
        int dash = name.lastIndexOf('-');
        return dash > 0 ? name.substring(dash + 1) : "";
    }
}
