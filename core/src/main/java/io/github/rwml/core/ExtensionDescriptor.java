/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 单个扩展的完整解析后不可变元数据。此记录会合并 {@code META-INF/extension.properties} 中的值，以及从归档本身
 * 获取的信息（大小、摘要和所需字节码级别）。
 *
 * <p>{@code resourcePack} 标记的扩展会提供游戏资源，宿主在加载扩展前把原版资源与该包内容物化到一个目录，并让
 * 资源读取经过该目录。同一时间只有一个资源包生效，选择规则见 {@link ExtensionSelection}；其他资源包会像禁用
 * 扩展一样被跳过。资源包通常不含入口点，{@link #withOptionalEntryPoint()} 会把缺失的入口点置空。</p>
 */
public record ExtensionDescriptor(
    String id,
    String name,
    String version,
    String description,
    List<String> authors,
    String license,
    String licenseUrl,
    String homepage,
    String icon,
    List<String> tags,
    String apiVersion,
    int requiredJavaVersion,
    String mainClass,
    String uiFramework,
    boolean resourcePack,
    List<String> dependsOn,
    List<String> loadBefore,
    List<String> loadAfter,
    Path source,
    long sourceSize,
    String sourceSha256) {

    /** 返回一个副本，并替换实际 Java 版本要求。 */
    public ExtensionDescriptor withRequiredJavaVersion(int javaVersion) {
        return new ExtensionDescriptor(id, name, version, description, authors, license, licenseUrl,
            homepage, icon, tags, apiVersion, javaVersion, mainClass, uiFramework, resourcePack,
            dependsOn, loadBefore, loadAfter,
            source, sourceSize, sourceSha256);
    }

    /**
     * 返回一个不带入口点的副本。
     *
     * <p>资源包只提供资源，通常没有需要执行的代码。宿主会在缺少入口点时实例化空实现，因此加载、禁用与报告流程
     * 与其他扩展一致。</p>
     */
    public ExtensionDescriptor withOptionalEntryPoint() {
        return new ExtensionDescriptor(id, name, version, description, authors, license, licenseUrl,
            homepage, icon, tags, apiVersion, requiredJavaVersion, "", uiFramework, resourcePack,
            dependsOn, loadBefore, loadAfter, source, sourceSize, sourceSha256);
    }

    /**
     * 描述由宿主自身携带的扩展。
     *
     * <p>此类扩展没有归档：它的类位于宿主自身的类路径中，因此 RWML 无需额外安装 jar，也无需使用下载扩展所需的归档处理逻辑。
     * 因此 Java 版本要求无关紧要，无需检查或协调版本，且 {@link #source()} 为 null。</p>
     *
     * @param id 扩展 ID，也是禁用标记使用的键
     * @param name 显示名称
     * @param mainClass 入口点；它是宿主类路径中的 {@link io.github.rwml.api.Extension}
     * @param uiFramework 实现 {@link io.github.rwml.api.ui.UiFramework} 的类；也可以为 null
     * @return {@link #isBuiltIn()} 为 true 的描述信息
     */
    public static ExtensionDescriptor builtIn(String id, String name, String version,
                                              String description, String mainClass,
                                              String uiFramework) {
        return new ExtensionDescriptor(id, name, version, description,
            Collections.<String>emptyList(), "Apache-2.0", "", "", "",
            Collections.<String>emptyList(), "1.0", 8, mainClass, uiFramework, false,
            Collections.<String>emptyList(), Collections.<String>emptyList(),
            Collections.<String>emptyList(), null, 0L, "");
    }

    /**
     * @return 宿主是否自行携带此扩展，而非从扩展目录中的 jar 读取
     */
    public boolean isBuiltIn() {
        return source == null;
    }

    /**
     * @return 解析后的硬性依赖，含各自的版本约束。{@code dependsOn} 保持原始文本，以便界面展示作者写下的内容。
     */
    public List<ExtensionRequirement> requirements() {
        List<ExtensionRequirement> result = new ArrayList<>(dependsOn.size());
        for (String token : dependsOn) {
            ExtensionRequirement requirement = ExtensionRequirement.parse(token);
            if (requirement != null) { result.add(requirement); }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * @return 硬性依赖的 ID，按声明顺序去重。排序、类加载器父级和卸载检查都只关心 ID。
     */
    public List<String> dependencyIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (ExtensionRequirement requirement : requirements()) { ids.add(requirement.id()); }
        return Collections.unmodifiableList(new ArrayList<>(ids));
    }

    /** @return 声明的要求是否被 {@code version} 对应的提供方满足 */
    public boolean accepts(String dependencyId, String version) {
        for (ExtensionRequirement requirement : requirements()) {
            if (requirement.id().equals(dependencyId) && !requirement.satisfiedBy(version)) { return false; }
        }
        return true;
    }

    @Override
    public String toString() {
        return "ExtensionDescriptor{id='" + id + "', name='" + name + "', version='" + version
            + "', java=" + requiredJavaVersion + ", dependsOn=" + dependsOn
            + ", mainClass='" + mainClass + "', uiFramework='" + uiFramework + "'}";
    }
}