/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;

import io.github.rwml.android.AndroidBootstrap;
import io.github.rwml.android.EngineContext;
import io.github.rwml.android.R;
import io.github.rwml.api.extensions.ExtensionEntry;
import io.github.rwml.core.ExtensionHandle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Verifies cold-Activity recovery and the Android native UI after disabling RWPP, without UI input. */
public final class NativeBuiltinFallbackRunner extends Instrumentation {
    private static final String TAG = "RWML";
    private static final String RWPP_ID = "io.github.rwpp";
    private static final String API_ID = "io.github.rwpp.api";

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            // Simulate Android restoring a native screen into a fresh process. AndroidApp must route it through
            // MainActivity and the launcher without building game DEX or starting GameBoot.
            getTargetContext().startActivity(new Intent(getTargetContext(), MainMenuActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            AndroidBootstrap runtime = AndroidBootstrap.get(getTargetContext());
            await(() -> runtime.launcherReady(), "cold native screen recovery through MainActivity");
            await(() -> EngineContext.foregroundActivity() instanceof LauncherActivity,
                "launcher after cold native screen recovery");

            if (runtime.gameBoot() == null || runtime.gameBoot().isStarted()
                    || !runtime.cacheKey().isEmpty() || runtime.patchedClasses() != 0) {
                throw new AssertionError("Cold recovery prepared game DEX/mixins or started the engine before the launcher");
            }
            ExtensionEntry rwpp = extension(runtime.ui().extensions().entries(), RWPP_ID);
            ExtensionEntry api = extension(runtime.ui().extensions().entries(), API_ID);
            if (rwpp == null || rwpp.enabled()) {
                throw new AssertionError("The test requires the RWPP extension to be disabled");
            }
            if (api == null || !api.enabled()) {
                throw new AssertionError("The test requires the RWPP API extension to remain enabled");
            }
            if (!AndroidExtension.ID.equals(runtime.uiFrameworkExtensionId())) {
                throw new AssertionError("The Android built-in UI must be selected after disabling RWPP: "
                    + runtime.uiFrameworkExtensionId());
            }
            result.putString("coldRecovery",
                "COLD_NATIVE_REDIRECT_PASS: native Activity returned through MainActivity/Launcher before DEX preparation");
            Log.i(TAG, result.getString("coldRecovery"));

            List<String> console = new ArrayList<>();
            runtime.requestGameStart();
            runtime.prepareGameRuntime((percent, message) -> {
                console.add(message);
                Log.i(TAG, "CONSOLE_PREENGINE " + percent + "% " + message);
            });
            if (console.isEmpty() || runtime.gameBoot().isStarted()) {
                throw new AssertionError("Launcher console preparation must finish before GameBoot starts");
            }
            ExtensionHandle apiHandle = runtime.manager().handle(API_ID);
            if (apiHandle == null || !apiHandle.isEnabled()) {
                throw new AssertionError("The enabled API extension was not loaded during console preparation");
            }
            result.putString("consolePreengine", "CONSOLE_PREENGINE_PASS: " + console.size()
                + " preparation update(s); GameBoot remained unstarted");
            Log.i(TAG, result.getString("consolePreengine"));

            getTargetContext().startActivity(new Intent(getTargetContext(), IntroScreen.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            await(() -> runtime.gameBoot().isStarted()
                    && EngineContext.foregroundActivity() instanceof MainMenuActivity,
                "native MainMenu after IntroScreen engine boot");
            await(this::nativeMenuIsReady, "native menu background and single-player start action");

            String backgroundMap = menuBackgroundPath();
            result.putString("menuBackground", backgroundMap);
            result.putString("nativeMenu", "NATIVE_BUILTIN_MENU_PASS: MainMenuActivity, background map "
                + backgroundMap + ", rendered SurfaceView and enabled Single Player action are present");
            result.putString("stream", result.getString("coldRecovery") + "\n"
                + result.getString("consolePreengine") + "\n"
                + result.getString("nativeMenu") + "\n");
            Log.i(TAG, result.getString("nativeMenu"));
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", Log.getStackTraceString(failure));
            Log.e(TAG, "Native builtin fallback validation failed", failure);
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private boolean nativeMenuIsReady() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> viewReady = new AtomicReference<>(false);
        runOnMainSync(() -> {
            try {
                Activity foreground = EngineContext.foregroundActivity();
                if (!(foreground instanceof MainMenuActivity)) { return; }
                MainMenuActivity menu = (MainMenuActivity) foreground;
                ViewGroup content = menu.findViewById(android.R.id.content);
                if (content == null || content.getChildCount() == 0) { return; }
                View rootView = content.getChildAt(0);
                if (!(rootView instanceof ViewGroup)) { return; }
                ViewGroup root = (ViewGroup) rootView;
                if (root.getChildCount() == 0) { return; }
                View background = root.getChildAt(0);
                if (!(background instanceof SurfaceView)
                        || !background.getClass().getName().equals("io.github.rwml.android.builtin.AndroidGameView")) {
                    return;
                }
                Method drawing = background.getClass().getMethod("isDrawingEnabled");
                if (!Boolean.TRUE.equals(drawing.invoke(background))) { return; }
                SurfaceView surface = (SurfaceView) background;
                if (!surface.getHolder().getSurface().isValid()) { return; }

                View start = menu.findViewById(R.id.menuCustomButton);
                if (start == null || start.getVisibility() != View.VISIBLE || !start.isEnabled()
                        || !start.hasOnClickListeners()) { return; }
                viewReady.set(true);
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        if (failure.get() != null) { throw new Exception("Could not inspect the native menu", failure.get()); }
        String map = menuBackgroundPath();
        return viewReady.get() && map != null && map.startsWith("maps/menu_background/");
    }

    private String menuBackgroundPath() throws Exception {
        ClassLoader loader = AndroidBootstrap.gameLoader();
        if (loader == null) { return null; }
        Class<?> engineType = Class.forName("com.corrodinggames.rts.gameFramework.GameEngine", true, loader);
        Object engine = engineType.getMethod("getInstance").invoke(null);
        if (engine == null) { return null; }
        Field currentMap = engineType.getField("currentMapPath");
        return (String) currentMap.get(engine);
    }

    private static ExtensionEntry extension(List<ExtensionEntry> entries, String id) {
        for (ExtensionEntry entry : entries) {
            if (id.equals(entry.id())) { return entry; }
        }
        return null;
    }

    private static void await(Check check, String name) throws Exception {
        long deadline = System.currentTimeMillis() + 120000;
        while (!check.ready()) {
            if (System.currentTimeMillis() > deadline) { throw new AssertionError("Timed out: " + name); }
            Thread.sleep(100);
        }
    }

    private interface Check { boolean ready() throws Exception; }
}
