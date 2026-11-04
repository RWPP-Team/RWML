package io.github.rwml.android.builtin;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import io.github.rwml.android.AndroidBootstrap;
import io.github.rwml.android.EngineContext;
import io.github.rwml.api.ui.GameRulesUi;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises public game services and Activity lifecycles without simulating user input. */
public final class NativeHandoffRunner extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle arguments) {
        android.util.Log.i("RWML-TEST","Instrumentation onCreate");
        Thread diagnostic=new Thread(()->{
            try { Thread.sleep(8000); } catch(InterruptedException ignored) { return; }
            for(java.util.Map.Entry<Thread,StackTraceElement[]> entry:Thread.getAllStackTraces().entrySet()) {
                String name=entry.getKey().getName();
                if(name.equals("main") || name.contains("Instr") || name.contains("rwml") || name.contains("GameBoot")) {
                    StringBuilder stack=new StringBuilder(name+" "+entry.getKey().getState());
                    for(StackTraceElement line:entry.getValue())stack.append("\n    ").append(line);
                    android.util.Log.i("RWML-TEST",stack.toString());
                }
            }
        },"rwml-test-stack-watchdog");diagnostic.setDaemon(true);diagnostic.start();
        this.arguments=arguments;super.onCreate(arguments); start();
    }

    @Override public void onStart() {
        android.util.Log.i("RWML-TEST","Instrumentation onStart");
        Bundle result = new Bundle();
        try {
            getTargetContext().startActivity(new Intent().setClassName(getTargetContext(),
                "io.github.rwml.android.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> AndroidBootstrap.get(getTargetContext()).launcherReady(), "launcher metadata preparation");
            // Match a real Start press: metadata may be ready before LauncherActivity.onCreate.
            // Starting the synchronized preparation in that gap blocks the Activity's ready check.
            await(() -> EngineContext.foregroundActivity() instanceof LauncherActivity, "launcher Activity resumed");
            waitForIdleSync();
            if(arguments!=null && "true".equalsIgnoreCase(arguments.getString("importOnly","false"))) {
                ArchiveImportChecks.verify(this,result);
                finish(Activity.RESULT_OK,result);
                return;
            }
            if (AndroidBootstrap.get(getTargetContext()).gameBoot().isStarted()) {
                throw new AssertionError("The bootstrap must wait for an explicit engine-start request");
            }
            if(!AndroidBootstrap.get(getTargetContext()).cacheKey().isEmpty() || AndroidBootstrap.get(getTargetContext()).patchedClasses()!=0)
                throw new AssertionError("Game/mixin preparation ran before the explicit start request");
            result.putString("deferredBoot","DEFERRED_MIXINS_PASS: launcher ready with no game cache key or transformed game classes before start");
            AndroidBootstrap runtime=AndroidBootstrap.get(getTargetContext());
            runtime.requestGameStart();
            runtime.prepareGameRuntime((percent,message) -> android.util.Log.i("RWML", "CONSOLE_PREPARE " + message));
            if(runtime.gameBoot().isStarted()) throw new AssertionError("Engine boot started during console preparation");
            result.putString("consoleBoot","CONSOLE_BEFORE_LOADING_PASS: game/mixins prepared while engine boot has not started");
            getTargetContext().startActivity(new Intent().setClassName(getTargetContext(),
                "io.github.rwml.android.builtin.IntroScreen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> AndroidBootstrap.get(getTargetContext()).gameBoot() != null
                && AndroidBootstrap.get(getTargetContext()).gameBoot().isStarted(), "native boot");
            // Load game-dependent code only after the original bootstrap mounts the game DEX.
            new NativeHandoffChecks(this).verifyGame(result,arguments);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", android.util.Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    interface Check { boolean ready() throws Exception; }
    static void await(Check check, String name) throws Exception {
        long deadline = System.currentTimeMillis() + 60000;
        while (!check.ready()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("Timed out: " + name);
            Thread.sleep(100);
        }
    }
}
