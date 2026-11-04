/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 目录中一个扩展的最新发布信息。
 *
 * <p>目录只描述已经发布的版本，安装与否由 {@link ExtensionRepository} 决定。条目字段与
 * {@code META-INF/extension.properties} 中的元数据同名，因此界面可以直接展示而无需再次读取归档。</p>
 *
 * @param id          扩展 ID，与已安装条目比对时使用
 * @param name        显示名称
 * @param version     目录中的最新版本
 * @param apiVersion  此版本要求的 RWML API 版本
 * @param summary     一行摘要，用于列表
 * @param description 完整说明，可为多行
 * @param authors     作者列表
 * @param tags        标签，用于搜索
 * @param categories  分类，用于筛选
 * @param license     许可证名称
 * @param homepage    项目主页
 * @param iconUrl     图标地址
 * @param platforms   支持的平台
 * @param downloads   累计下载数；未知时为 0
 * @param updatedAt   发布日期，ISO 8601 文本
 * @param notes       此版本的更新说明
 * @param artifacts   可下载的归档
 */
public record CatalogEntry(
    String id,
    String name,
    String version,
    String apiVersion,
    String summary,
    String description,
    List<String> authors,
    List<String> tags,
    List<String> categories,
    String license,
    String homepage,
    String iconUrl,
    List<String> platforms,
    long downloads,
    String updatedAt,
    String notes,
    List<ExtensionArtifact> artifacts) {

    public CatalogEntry {
        id = id == null ? "" : id.trim();
        name = name == null || name.isBlank() ? id : name.trim();
        version = version == null ? "" : version.trim();
        apiVersion = apiVersion == null ? "" : apiVersion.trim();
        summary = summary == null ? "" : summary.trim();
        description = description == null ? "" : description.trim();
        authors = copy(authors);
        tags = copy(tags);
        categories = copy(categories);
        license = license == null ? "" : license.trim();
        homepage = homepage == null ? "" : homepage.trim();
        iconUrl = iconUrl == null ? "" : iconUrl.trim();
        platforms = copy(platforms);
        updatedAt = updatedAt == null ? "" : updatedAt.trim();
        notes = notes == null ? "" : notes.trim();
        artifacts = Collections.unmodifiableList(new ArrayList<>(artifacts == null ? List.of() : artifacts));
    }

    private static List<String> copy(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(values.size());
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                result.add(value.trim());
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** @return 适用于给定平台的归档，按目录中的顺序 */
    public List<ExtensionArtifact> artifactsFor(String platform) {
        List<ExtensionArtifact> result = new ArrayList<>();
        for (ExtensionArtifact artifact : artifacts) {
            if (artifact.matches(platform)) {
                result.add(artifact);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** @return 此条目是否声明支持给定平台；未声明平台时视为支持全部 */
    public boolean supports(String platform) {
        if (platforms.isEmpty()) {
            return true;
        }
        for (String value : platforms) {
            if (value.equalsIgnoreCase(platform)) {
                return true;
            }
        }
        return false;
    }

    /** @return 可用于界面展示的关键词，包含 ID、名称、摘要、标签与分类 */
    public Set<String> keywords() {
        Set<String> keywords = new LinkedHashSet<>();
        keywords.add(id.toLowerCase(Locale.ROOT));
        keywords.add(name.toLowerCase(Locale.ROOT));
        for (String value : tags) {
            keywords.add(value.toLowerCase(Locale.ROOT));
        }
        for (String value : categories) {
            keywords.add(value.toLowerCase(Locale.ROOT));
        }
        for (String word : summary.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!word.isEmpty()) {
                keywords.add(word);
            }
        }
        return Collections.unmodifiableSet(keywords);
    }

    @Override
    public String toString() {
        return "CatalogEntry{" + id + " " + version + ", " + artifacts.size() + " artifact(s)}";
    }
}
