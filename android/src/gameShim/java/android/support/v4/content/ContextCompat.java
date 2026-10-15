package android.support.v4.content;

import android.content.Context;

/**
 * Real body for the placeholder the platform-independent game archive ships under this name.
 *
 * <p>The archive keeps the support library's member names but no bodies: its
 * {@code checkSelfPermission} throws and nothing else is declared. This shim replaces that entry at
 * staging time, so the game's own permission check - which reads the return as granted when it is
 * {@code 0} - behaves as it does in the game's Android build. The platform has carried
 * {@link Context#checkSelfPermission} itself since API 23, and the app's minimum is far above
 * that.</p>
 */
public class ContextCompat {

    public static int checkSelfPermission(Context context, String permission) {
        return context.checkSelfPermission(permission);
    }
}
