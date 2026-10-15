package android.support.v4.app;

import android.app.Activity;

/**
 * Real body for the placeholder the platform-independent game archive ships under this name.
 *
 * <p>The archive declares this method as an empty stub. This shim replaces that entry at staging
 * time, so the game's request for the storage permission reaches the platform. The activity has
 * carried {@link Activity#requestPermissions} itself since API 23, and the app's minimum is far
 * above that.</p>
 */
public class ActivityCompat {

    public static void requestPermissions(Activity activity, String[] permissions, int requestCode) {
        activity.requestPermissions(permissions, requestCode);
    }
}
