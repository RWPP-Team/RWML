package android.support.v4.app;

import android.app.Activity;

/**
 * 为跨平台游戏归档中同名占位类提供真实实现。
 *
 * <p>游戏归档将此方法声明为空桩。暂存时会用此垫片替换对应条目，使游戏的存储权限请求能够传递到平台。
 * 从 API 23 开始，Activity 自身就提供了 {@link Activity#requestPermissions}，而本应用的最低版本远高于此。</p>
 */
public class ActivityCompat {

    public static void requestPermissions(Activity activity, String[] permissions, int requestCode) {
        activity.requestPermissions(permissions, requestCode);
    }
}
