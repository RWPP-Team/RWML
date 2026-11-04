/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.repository;

import io.github.rwml.api.extensions.ExtensionEntry;

import java.io.IOException;
import java.util.List;

/**
 * 扩展仓库：搜索目录、下载归档并安装。
 *
 * <p>仓库只处理归档，不执行扩展代码，因此可以在启动游戏之前、任何线程上使用。目录缓存与网络细节由实现决定；调用方
 * 负责把耗时调用放到工作线程，并把进度回调切回界面线程。</p>
 *
 * <p>安装通过宿主的 {@link io.github.rwml.api.extensions.ExtensionLibrary} 完成，因此与手工导入走同一条写入路径：
 * 同一 ID 只保留一个版本，启用状态不变，改动在下一个宿主进程中生效。</p>
 */
public interface ExtensionRepository {

    /**
     * 读取目录。实现可以返回缓存结果；需要保证读到最新内容时使用 {@link #refresh()}。
     *
     * @return 目录，不为 {@code null}
     * @throws IOException 目录无法获取或解析
     */
    Catalog catalog() throws IOException;

    /**
     * 忽略缓存重新获取目录，成功后替换缓存。
     *
     * @return 新获取的目录
     * @throws IOException 目录无法获取或解析
     */
    Catalog refresh() throws IOException;

    /**
     * 按关键词搜索目录。
     *
     * <p>查询为空时返回全部条目。匹配不区分大小写，覆盖 ID、名称、摘要、标签与分类；多个关键词之间按“全部命中”处理，
     * 结果按匹配质量排序。</p>
     *
     * @param query 查询文本，可以为 {@code null}
     * @return 匹配的条目，按相关度降序
     * @throws IOException 目录无法获取
     */
    List<CatalogEntry> search(String query) throws IOException;

    /**
     * 比较已安装扩展与目录版本。
     *
     * <p>只为目录中确实更新、且支持当前平台的扩展返回结果。同一 ID 装有多个版本时，以最高的已安装版本为准。</p>
     *
     * @param installed 已安装条目，通常来自 {@code ExtensionLibrary.entries()}
     * @return 可更新的扩展，按 ID 排序
     * @throws IOException 目录无法获取
     */
    List<AvailableUpdate> updates(List<ExtensionEntry> installed) throws IOException;

    /**
     * 为此平台安装目录中的最新版本。
     *
     * @param id       扩展 ID
     * @param listener 下载进度监听器
     * @return 安装结果
     * @throws IOException 条目不存在、没有可用归档、校验失败或写入失败
     */
    default InstallReport install(String id, TransferListener listener) throws IOException {
        return install(id, platform(), listener);
    }

    /**
     * 为指定平台安装目录中的最新版本。
     *
     * @param id       扩展 ID
     * @param platform 目标平台，取 {@code jvm} 或 {@code android}
     * @param listener 下载进度监听器
     * @return 安装结果
     * @throws IOException 条目不存在、没有可用归档、校验失败或写入失败
     */
    InstallReport install(String id, String platform, TransferListener listener) throws IOException;

    /** @return 此仓库安装归档时使用的平台，取 {@code jvm} 或 {@code android} */
    String platform();
}
