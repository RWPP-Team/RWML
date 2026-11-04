package android.support.v4.content;

import android.content.Context;

/**
 * 为跨平台游戏归档中同名占位类提供真实实现。
 *
 * <p>游戏归档保留了支持库成员名称，却没有实现：其 {@code checkSelfPermission} 会抛出异常，且未声明其他内容。
 * 暂存时会用此垫片替换对应条目，使游戏自身的权限检查（返回值为 {@code 0} 时表示已授权）与游戏 Android 版本中的行为一致。
 * 从 API 23 开始，平台自身就提供了 {@link Context#checkSelfPermission}，而本应用的最低版本远高于此。</p>
 */
public class ContextCompat {

    public static int checkSelfPermission(Context context, String permission) {
        return context.checkSelfPermission(permission);
    }
}
