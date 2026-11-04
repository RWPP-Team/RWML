/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.mixin;

import kim.der.asm.api.annotation.*;

/** Use the ordinary filesystem after the host has obtained all-files access. No document grants. */
public final class DirectStorageMixins {
    private DirectStorageMixins() {}

    public interface AccessConfig {
        void rwmlDirect(boolean value);
        void rwmlSaf(boolean value);
        void rwmlOverride(boolean value);
    }

    @AsmMixin("com/corrodinggames/rts/gameFramework/files/FileAccessConfig")
    @AddInterface("io/github/rwml/android/mixin/DirectStorageMixins$AccessConfig")
    public abstract static class Access {
        @Accessor("useDirectExternalAccess") public abstract void rwmlDirect(boolean value);
        @Accessor("useSafExternalAccess") public abstract void rwmlSaf(boolean value);
        @Accessor("useOverriddenExternalPath") public abstract void rwmlOverride(boolean value);
    }

    @AsmMixin("com/corrodinggames/rts/gameFramework/files/FileLoader")
    public static class Loader {
        @ModifyReturnValue(method = "computeFileAccessConfig(Z)Lcom/corrodinggames/rts/gameFramework/files/FileAccessConfig;", require = 1)
        public static Object direct(Object result) {
            AccessConfig access = (AccessConfig) result;
            access.rwmlDirect(true);
            access.rwmlSaf(false);
            access.rwmlOverride(false);
            return result;
        }
    }

    public interface SettingsAccess {
        void rwmlStorage(int value);
        void rwmlSelected(boolean value);
        void rwmlExternalLink(String value);
        void rwmlExternalWorking(boolean value);
    }

    @AsmMixin("com/corrodinggames/rts/gameFramework/SettingsEngine")
    @AddInterface("io/github/rwml/android/mixin/DirectStorageMixins$SettingsAccess")
    public abstract static class Settings {
        @Accessor("storageType") public abstract void rwmlStorage(int value);
        @Accessor("hasSelectedAStorageType") public abstract void rwmlSelected(boolean value);
        @Accessor("externalSAFLink") public abstract void rwmlExternalLink(String value);
        @Accessor("externalSAFWorking") public abstract void rwmlExternalWorking(boolean value);

        @ModifyReturnValue(method = "getInstance(Landroid/content/Context;)Lcom/corrodinggames/rts/gameFramework/SettingsEngine;", require = 1)
        public static Object configure(Object result) {
            SettingsAccess settings = (SettingsAccess) result;
            // External first, with existing private game files still available as a fallback.
            settings.rwmlStorage(2);
            settings.rwmlSelected(true);
            settings.rwmlExternalLink(null);
            settings.rwmlExternalWorking(false);
            return result;
        }

        @AsmInject(method = "loadMainExternalFolder(Z)Z", target = InjectionPoint.HEAD, cancellable = true, require = 1)
        public static void noDocumentMount(CallbackInfo callback) { callback.setReturnValue(false); }
    }

    @AsmMixin("com/corrodinggames/rts/gameFramework/utility/FileReaderManager")
    public static class Readers {
        @AsmInject(method = "safSupported()Z", target = InjectionPoint.HEAD, cancellable = true, require = 1)
        public static void noSaf(CallbackInfo callback) { callback.setReturnValue(false); }
    }

    @AsmMixin("com/corrodinggames/rts/appFramework/AppContext")
    public static class Permissions {
        private static boolean granted(android.content.Context context) {
            return android.os.Build.VERSION.SDK_INT >= 30
                ? android.os.Environment.isExternalStorageManager()
                : context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }

        @AsmInject(method = "hasWriteStoragePermission(Landroid/content/Context;)Z", target = InjectionPoint.HEAD, cancellable = true, require = 1)
        public static void check(CallbackInfo callback, android.content.Context context) {
            callback.setReturnValue(granted(context));
        }

        @AsmInject(method = "checkAndRequestWriteStoragePermission(Landroid/app/Activity;)Z", target = InjectionPoint.HEAD, cancellable = true, require = 1)
        public static void noLegacyRequest(CallbackInfo callback, android.app.Activity activity) {
            // The launcher owns permission requests; game screens never ask for a second grant.
            callback.setReturnValue(granted(activity));
        }

        @AsmInject(method = "showStorageSetupDialog(Landroid/app/Activity;Ljava/lang/Runnable;Z)Z", target = InjectionPoint.HEAD, cancellable = true, require = 1)
        public static void noDocumentSetup(CallbackInfo callback) { callback.setReturnValue(false); }
    }
}
