package com.corrodinggames.rts.appFramework.common;

/**
 * SAF 目录列表返回的条目载体。
 *
 * <p>跨平台游戏归档不包含此类，但文件读取器会通过此确切名称引用它（这些读取器是针对包含该类的 Android 构建编译的）。
 * 此垫片会补回该类，确保设备挂载的归档能够解析自身类中引用的所有名称。</p>
 */
public interface SAFInterface {

    /** 列出的文档信息：ID、显示名称，以及是否为目录。 */
    class FileRow {

        public String id;
        public String name;
        public boolean isDirectory;
    }
}
