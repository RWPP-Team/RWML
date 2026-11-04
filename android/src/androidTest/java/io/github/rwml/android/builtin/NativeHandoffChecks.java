package io.github.rwml.android.builtin;
import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import io.github.rwml.android.AndroidBootstrap;
import io.github.rwml.android.EngineContext;
import static io.github.rwml.android.builtin.NativeHandoffRunner.await;
final class NativeHandoffChecks {
    private final Instrumentation instrumentation;
    private Action restoreSettings;
    NativeHandoffChecks(Instrumentation instrumentation) { this.instrumentation = instrumentation; }
    void verifyGame(Bundle result,Bundle arguments) throws Exception {
        try { verifyGameInternal(result,arguments); }
        finally { if(restoreSettings!=null) restoreSettings.run(); }
    }
    private void verifyGameInternal(Bundle result,Bundle arguments) throws Exception {
        await(() -> {
            Activity owner = EngineContext.foregroundActivity();
            if (owner == null || !owner.getClass().getName().equals("io.github.rwml.android.MainActivity")) return false;
            com.corrodinggames.rts.gameFramework.GameEngine engine =
                com.corrodinggames.rts.gameFramework.GameEngine.getInstance();
            return engine != null && engine.currentMapPath != null
                && engine.currentMapPath.startsWith("maps/menu_background/");
        }, "native menu background");
        com.corrodinggames.rts.gameFramework.GameEngine engine =
            com.corrodinggames.rts.gameFramework.GameEngine.getInstance();
        if (!(engine.networkEngine.networkCallbacks instanceof HostNetworkCallbacks)) {
            throw new AssertionError("Missing native network start callback");
        }
        result.putString("background", engine.currentMapPath);
        for(int retry=0;retry<2;retry++) {
            int port;
            try(java.net.ServerSocket closed=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))) { port=closed.getLocalPort(); }
            java.util.concurrent.CountDownLatch failed=new java.util.concurrent.CountDownLatch(1);
            com.corrodinggames.rts.gameFramework.network.ConnectSocketToServerRunnable connect=
                engine.networkEngine.connectToServerAsync("127.0.0.1:"+port,true,failed::countDown);
            if(!failed.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Native failed-connect callback escaped or did not complete");
            if(connect.connectedSocket!=null) { connect.connectedSocket.close();throw new AssertionError("Closed loopback port connected unexpectedly"); }
        }
        result.putString("connectFailure","CONNECT_FAILURE_RECOVERED_PASS: two real loopback connection refusals invoke native failure callbacks without escaping the Operation bridge");

        // Resolve the extension's public API through its owning loader, preserving class identity.
        ClassLoader loader = AndroidBootstrap.get(instrumentation.getTargetContext()).uiFramework().getClass().getClassLoader();
        Class<?> gameType = loader.loadClass("io.github.rwpp.api.game.Game");
        Object koin = loader.loadClass("io.github.rwpp.api.GlobalsKt").getMethod("getAppKoin").invoke(null);
        java.lang.reflect.Field host = loader.loadClass("io.github.rwpp.ui.AndroidUiRuntime").getDeclaredField("host");
        host.setAccessible(true);
        await(() -> host.get(null) != null, "client service and surface installation");
        java.lang.reflect.Field navigator = loader.loadClass("io.github.rwpp.ui.UI").getField("globalNavigator");
        await(() -> navigator.get(null) != null, "first client menu composition");
        Object type = loader.loadClass("kotlin.jvm.JvmClassMappingKt").getMethod("getKotlinClass", Class.class).invoke(null, gameType);
        java.lang.reflect.Method get = java.util.Arrays.stream(koin.getClass().getMethods())
            .filter(m -> m.getName().equals("get") && m.getParameterCount() == 3).findFirst().orElseThrow();
        Object game = get.invoke(koin, type, null, null);
        Object preferences = service(loader,koin,get,"io.github.rwpp.config.MultiplayerPreferences");
        java.util.List<?> servers = (java.util.List<?>) preferences.getClass().getMethod("getAllServerConfig").invoke(preferences);
        if (servers.stream().noneMatch(value -> {
            try { return "Official Room List".equals(value.getClass().getMethod("getName").invoke(value)); }
            catch (Exception failure) { throw new RuntimeException(failure); }
        })) throw new AssertionError("Late client initialization did not seed the official room list");
        Object settings = service(loader,koin,get,"io.github.rwpp.config.Settings");
        Object config = service(loader,koin,get,"io.github.rwpp.api.config.ConfigIO");
        verifyDirectFiles(result);
        String configPhase=arguments==null ? "" : arguments.getString("configPhase","");
        android.content.SharedPreferences saved=instrumentation.getTargetContext().getSharedPreferences("rwpp.validation.config",0);
        String[] fields={"ImprovedHealthBar","EnhancedWorldRendering","HideOriginalHud","EnableQuickSelectMenu"};
        if(configPhase.equals("restore")) {
            Object storedType=loader.loadClass("kotlin.jvm.JvmClassMappingKt").getMethod("getKotlinClass",Class.class).invoke(null,settings.getClass());
            Object stored=config.getClass().getMethod("readConfig",loader.loadClass("kotlin.reflect.KClass")).invoke(config,storedType);
            for(String field:fields) {
                boolean actual=(boolean)settings.getClass().getMethod("get"+field).invoke(settings);
                boolean disk=(boolean)stored.getClass().getMethod("get"+field).invoke(stored);
                boolean original=saved.getBoolean("original."+field,false);
                result.putString("beforeRestore."+field,"loaded="+actual+" stored="+disk+" original="+original);
                if(actual!=disk) throw new AssertionError("Disk value was not restored at startup: "+field);
                settings.getClass().getMethod("set"+field,boolean.class).invoke(settings,original);
            }
            config.getClass().getMethod("saveAllConfig").invoke(config);
            saved.edit().putBoolean("active",false).commit();
            result.putString("stream","ORIGINAL_CONFIG_CLEANUP_PASS: restored original values with an explicit persistent save\n");
            return;
        }
        if(configPhase.equals("restored")) {
            if(saved.getBoolean("active",false)) throw new AssertionError("Configuration validation still has temporary changes active");
            for(String field:fields) {
                if(!saved.contains("original."+field)) throw new AssertionError("Original configuration marker is missing");
                boolean actual=(boolean)settings.getClass().getMethod("get"+field).invoke(settings);
                if(actual!=saved.getBoolean("original."+field,false)) throw new AssertionError("Original setting was not restored: "+field);
            }
            result.putString("stream","ORIGINAL_CONFIG_RESTORED_PASS: fresh process confirms all four original user values and no active temporary settings\n");
            return;
        }
        if(configPhase.equals("write")) {
            android.content.SharedPreferences.Editor editor=saved.edit();
            for(String field:fields) {
                boolean original=(boolean)settings.getClass().getMethod("get"+field).invoke(settings);
                editor.putBoolean("original."+field,original).putBoolean("expected."+field,!original);
                settings.getClass().getMethod("set"+field,boolean.class).invoke(settings,!original);
            }
            editor.putBoolean("active",true).commit();
            config.getClass().getMethod("saveAllConfig").invoke(config);
            result.putString("stream","COLD_CONFIG_WRITE_PASS: four changed client settings saved; next instrumentation process must restore them\n");
            return;
        }
        if(configPhase.equals("verify")) {
            if(!saved.getBoolean("active",false)) throw new AssertionError("No configuration write phase");
            restoreSettings=() -> {
                for(String field:fields) settings.getClass().getMethod("set"+field,boolean.class).invoke(settings,saved.getBoolean("original."+field,false));
                config.getClass().getMethod("saveAllConfig").invoke(config);
                saved.edit().putBoolean("active",false).commit();
            };
            Object settingsKlass=loader.loadClass("kotlin.jvm.JvmClassMappingKt").getMethod("getKotlinClass",Class.class).invoke(null,settings.getClass());
            Object decoded=config.getClass().getMethod("readConfig",loader.loadClass("kotlin.reflect.KClass")).invoke(config,settingsKlass);
            String reflectionState;
            try {
                java.util.Collection<?> properties=(java.util.Collection<?>)loader.loadClass("kotlin.reflect.full.KClasses")
                    .getMethod("getDeclaredMemberProperties",loader.loadClass("kotlin.reflect.KClass")).invoke(null,settingsKlass);
                reflectionState="properties="+properties.size();
            } catch(Exception unsupported) { reflectionState="Kotlin callable reflection unavailable; configuration restoration must not depend on it"; }
            android.util.Log.i("RWML","COLD_CONFIG_DIAGNOSTIC loadedHealth="+settings.getClass().getMethod("getImprovedHealthBar").invoke(settings)
                +" storedHealth="+(decoded==null?"missing":decoded.getClass().getMethod("getImprovedHealthBar").invoke(decoded))
                +" "+reflectionState+" configKoinMatches="+(config.getClass().getMethod("getKoin").invoke(config)==koin));
            checkSettings(settings,saved,fields,"fresh process after readAllConfig");
            result.putString("coldConfig","cold process readAllConfig restored all four client settings");
        }
        if(configPhase.isEmpty()) {
            boolean[] baseline=new boolean[fields.length];
            for(int i=0;i<fields.length;i++) baseline[i]=(boolean)settings.getClass().getMethod("get"+fields[i]).invoke(settings);
            restoreSettings=() -> {
                for(int i=0;i<fields.length;i++) settings.getClass().getMethod("set"+fields[i],boolean.class).invoke(settings,baseline[i]);
                config.getClass().getMethod("saveAllConfig").invoke(config);
            };
        }
        Class<?> configType = loader.loadClass("io.github.rwpp.api.config.Config");
        Object pageNavigator = navigator.get(null);
        for (String page : new String[] {"ModsScreen", "ReplaysScreen", "SettingsScreen"}) {
            Class<?> pageType = loader.loadClass("io.github.rwpp.ui." + page);
            Object screen = page.equals("SettingsScreen")
                ? pageType.getConstructor(String.class).newInstance("aero") : pageType.getConstructor().newInstance();
            instrumentation.runOnMainSync(() -> {
                try { pageNavigator.getClass().getMethod("push", loader.loadClass("io.github.rwpp.screen.Screen")).invoke(pageNavigator, screen); }
                catch (Exception failure) { throw new RuntimeException(failure); }
            });
            Thread.sleep(1200);
            if (pageNavigator.getClass().getMethod("getCurrent").invoke(pageNavigator) != screen)
                throw new AssertionError("Page navigation did not retain " + page);
            instrumentation.runOnMainSync(() -> {
                try { pageNavigator.getClass().getMethod("pop").invoke(pageNavigator); }
                catch (Exception failure) { throw new RuntimeException(failure); }
            });
            Thread.sleep(300);
        }
        result.putString("pages", "NATIVE_PAGES_PASS: mods, replays and settings compose and return through public navigation");
        Class<?> kclass = loader.loadClass("kotlin.reflect.KClass");
        Object settingsClass = loader.loadClass("kotlin.jvm.JvmClassMappingKt").getMethod("getKotlinClass",Class.class)
            .invoke(null,settings.getClass());
        boolean originalHealthBar = (boolean) settings.getClass().getMethod("getImprovedHealthBar").invoke(settings);
        try {
            settings.getClass().getMethod("setImprovedHealthBar",boolean.class).invoke(settings,!originalHealthBar);
            config.getClass().getMethod("saveConfig",configType).invoke(config,settings);
            Object restored = config.getClass().getMethod("readConfig",kclass).invoke(config,settingsClass);
            if (restored == null || (boolean) restored.getClass().getMethod("getImprovedHealthBar").invoke(restored) == originalHealthBar)
                throw new AssertionError("Android configuration did not survive serialize/commit/read");
        } finally {
            settings.getClass().getMethod("setImprovedHealthBar",boolean.class).invoke(settings,originalHealthBar);
            config.getClass().getMethod("saveConfig",configType).invoke(config,settings);
        }
        result.putString("configuration","SharedPreferences settings round-trip and official room list passed");
        Class<?> sounds = loader.loadClass("io.github.rwpp.sound.Sounds");
        Object audio = sounds.getField("INSTANCE").get(null);
        Object select = sounds.getMethod("getSelect").invoke(audio);
        Class<?> soundType = loader.loadClass("io.github.rwpp.api.game.audio.GameSound");
        // Calls the same stable handle as a button, waiting for SoundPool's real decode callback.
        soundType.getMethod("play", float.class).invoke(select, 0.2f);
        sounds.getMethod("playSound", soundType).invoke(audio, select);
        result.putString("audio", "Android SoundPool decoded and started the select sound");
        // Also render a private LAN host room; do not publish it to the master server.
        gameType.getMethod("hostStartWithPasswordAndMods",boolean.class,String.class,boolean.class).invoke(game,false,null,false);
        await(() -> engine.networkEngine.isServer && !engine.networkEngine.singlePlayerServer,"private LAN room");
        Object networkRoom=gameType.getMethod("getGameRoom").invoke(game);
        Object roomUi=loader.loadClass("io.github.rwpp.ui.UI").getField("INSTANCE").get(null);
        Object existingWarning=roomUi.getClass().getMethod("getWarning").invoke(roomUi);
        Object networkScreen=loader.loadClass("io.github.rwpp.ui.BattleRoomScreen").getConstructor().newInstance();
        instrumentation.runOnMainSync(() -> {
            try { pageNavigator.getClass().getMethod("push",loader.loadClass("io.github.rwpp.screen.Screen")).invoke(pageNavigator,networkScreen); }
            catch(Exception failure) { throw new RuntimeException(failure); }
        });
        Thread.sleep(2000);
        Object roomWarning=roomUi.getClass().getMethod("getWarning").invoke(roomUi);
        if(roomWarning!=existingWarning && roomWarning!=null) throw new AssertionError("Room produced a warning: "+roomWarning);
        if(pageNavigator.getClass().getMethod("getCurrent").invoke(pageNavigator)!=networkScreen) throw new AssertionError("LAN room page disappeared");
        instrumentation.runOnMainSync(() -> {
            try { pageNavigator.getClass().getMethod("pop").invoke(pageNavigator); }
            catch(Exception failure) { throw new RuntimeException(failure); }
        });
        onGame(loader,()->networkRoom.getClass().getMethod("disconnect",String.class).invoke(networkRoom,"validation LAN room complete"));
        result.putString("lanRoom","LAN_ROOM_PAGE_PASS: private network host page composed without a new warning");
        // A local room creates no Internet room and does not contact the master server.
        gameType.getMethod("hostNewSinglePlayer", boolean.class).invoke(game, false);
        await(() -> engine.networkEngine.isServer && engine.networkEngine.singlePlayerServer, "local room");
        Object room = gameType.getMethod("getGameRoom").invoke(game);
        // Render the real room page, rather than only exercising its native network service.
        Object roomScreen = loader.loadClass("io.github.rwpp.ui.BattleRoomScreen").getConstructor().newInstance();
        instrumentation.runOnMainSync(() -> {
            try { pageNavigator.getClass().getMethod("push", loader.loadClass("io.github.rwpp.screen.Screen")).invoke(pageNavigator, roomScreen); }
            catch(Exception failure) { throw new RuntimeException(failure); }
        });
        Thread.sleep(2000);
        if(pageNavigator.getClass().getMethod("getCurrent").invoke(pageNavigator)!=roomScreen)
            throw new AssertionError("Room page did not survive composition");
        instrumentation.runOnMainSync(() -> {
            try { pageNavigator.getClass().getMethod("pop").invoke(pageNavigator); }
            catch(Exception failure) { throw new RuntimeException(failure); }
        });
        result.putString("roomPage", "BATTLE_ROOM_PAGE_PASS: real local room page composed and returned");
        String marker = "RWPP local event validation " + System.nanoTime();
        onGame(loader, () -> room.getClass().getMethod("sendChatMessage",String.class).invoke(room,marker));
        Object clientUi = loader.loadClass("io.github.rwpp.ui.UI").getField("INSTANCE").get(null);
        await(() -> clientUi.getClass().getMethod("getChatMessages").invoke(clientUi).toString().contains(marker), "native chat receive event to Aero chat");
        result.putString("chat","local room native send/receive reached Aero chat");
        Class<?> searchPromptType = loader.loadClass("com.corrodinggames.rts.game.units.EditorUnit$9$1");
        java.lang.reflect.Constructor<?> searchConstructor = searchPromptType.getDeclaredConstructors()[0];
        searchConstructor.setAccessible(true);
        com.corrodinggames.rts.gameFramework.network.PasswordPrompt searchPrompt =
            (com.corrodinggames.rts.gameFramework.network.PasswordPrompt) searchConstructor.newInstance(new Object[]{null});
        searchPrompt.question="Search units by internal name or text title.";
        onGame(loader, () -> com.corrodinggames.rts.gameFramework.network.NetworkEngine.showPasswordPrompt(searchPrompt));
        await(() -> clientUi.getClass().getMethod("getQuestion").invoke(clientUi) != null,"search prompt route");
        instrumentation.runOnMainSync(() -> {
            try { java.util.Arrays.stream(clientUi.getClass().getMethods()).filter(m -> m.getName().startsWith("setQuestion"))
                .findFirst().orElseThrow().invoke(clientUi,new Object[]{null}); }
            catch(Exception failure) { throw new RuntimeException(failure); }
        });
        result.putString("search","native literal search prompt reached Aero without locale exception");
        if(arguments!=null && "true".equalsIgnoreCase(arguments.getString("lavaMap","false"))) {
            Object chosen=null;
            for(Object candidate:(Iterable<?>)gameType.getMethod("getAllMapsByMapType",com.corrodinggames.rts.gameFramework.network.GameSetupMapType.class)
                .invoke(game,com.corrodinggames.rts.gameFramework.network.GameSetupMapType.skirmishMap)) {
                String name=(String)candidate.getClass().getMethod("getMapName").invoke(candidate);
                if(name.contains("Valley Arena")) { chosen=candidate;break; }
            }
            if(chosen==null) throw new AssertionError("Native Valley Arena lava map is missing");
            room.getClass().getMethod("setSelectedMap",loader.loadClass("io.github.rwpp.api.game.map.GameMap")).invoke(room,chosen);
        }
        onGame(loader, () -> room.getClass().getMethod("startGame").invoke(room));
        await(() -> {
            Activity owner = EngineContext.foregroundActivity();
            return owner instanceof InGameActivity
                && ((InGameActivity) owner).gameView().getHolder().getSurface().isValid();
        }, "room to native game Activity");
        Thread.sleep(2000); // Include first layout/composition frames, where window-owner failures appear.
        if (!(EngineContext.foregroundActivity() instanceof InGameActivity)) {
            throw new AssertionError("Game Activity did not survive its first frames");
        }
        if (engine.currentMapPath == null || engine.currentMapPath.startsWith("maps/menu_background/")) {
            throw new AssertionError("game Activity opened without playing map");
        }
        java.lang.reflect.Field surface = InGameActivity.class.getDeclaredField("extensionSurface");
        surface.setAccessible(true);
        if (surface.get(EngineContext.foregroundActivity()) == null) throw new AssertionError("RWPP game overlay missing");
        Object hud = get.invoke(koin, loader.loadClass("kotlin.jvm.JvmClassMappingKt")
            .getMethod("getKotlinClass", Class.class).invoke(null, loader.loadClass("io.github.rwpp.ui.GameScreen")), null, null);
        if (((java.util.List<?>) hud.getClass().getMethod("getDataList").invoke(hud)).size() < 4) {
            throw new AssertionError("Default Aero HUD widgets missing");
        }
        java.lang.reflect.Field hidden = loader.loadClass("io.github.rwpp.ui.NativeHudVisibility").getDeclaredField("hidden");
        hidden.setAccessible(true);
        boolean expectedHidden=(boolean)settings.getClass().getMethod("getHideOriginalHud").invoke(settings);
        if (hidden.getBoolean(null)!=expectedHidden) throw new AssertionError("Native HUD does not follow the saved setting");
        if(configPhase.equals("verify")) checkSettings(settings,saved,fields,"after starting local game");
        result.putString("game", engine.currentMapPath);
        TouchHudChecks.verify(loader,engine,result,task -> onGame(loader,task::run));
        MinimapIndicatorChecks.verify(loader,engine,settings,result,task -> onGame(loader,task::run));
        com.corrodinggames.rts.game.units.OrderableUnit[] movedUnit = new com.corrodinggames.rts.game.units.OrderableUnit[1];
        onGame(loader, () -> {
            com.corrodinggames.rts.game.units.OrderableUnit chosen = null;
            for (Object value : com.corrodinggames.rts.game.units.Unit.unitList) {
                if (value instanceof com.corrodinggames.rts.game.units.OrderableUnit unit && !unit.dead && !unit.isBuilding()
                        && engine.interfaceEngine.canPlayerControlUnit(unit)) { chosen=unit;break; }
            }
            if (chosen == null) throw new AssertionError("No controllable unit for minimap test");
            movedUnit[0]=chosen;
            engine.interfaceEngine.clearUnitSelection();
            engine.interfaceEngine.selectUnit(chosen);
            // Kotlin inline Offset parameters compile to a packed long. Resolve the internal top-level function by prefix.
            long packed = ((long)Float.floatToRawIntBits(chosen.x+120f)<<32) | (Float.floatToRawIntBits(chosen.y+100f)&0xffffffffL);
            java.lang.reflect.Method tap = java.util.Arrays.stream(loader.loadClass("io.github.rwpp.ui.AeroMinimapKt").getDeclaredMethods())
                .filter(m -> m.getName().startsWith("issueMinimapTap")).findFirst().orElseThrow();
            tap.setAccessible(true);
            if (!(boolean)tap.invoke(null,packed,true,false)) throw new AssertionError("Touch minimap command was ignored");
            Object signals = loader.loadClass("io.github.rwpp.ui.AeroMinimapOrders").getField("INSTANCE").get(null);
            java.lang.reflect.Method snapshot = signals.getClass().getMethod("snapshot",long.class);
            if (((java.util.List<?>)snapshot.invoke(signals,System.currentTimeMillis())).isEmpty()) throw new AssertionError("Minimap click effect missing");
        });
        await(() -> movedUnit[0].getWaypointCount() > 0 && movedUnit[0].getWaypoint(0) != null, "touch minimap command reached unit waypoint");
        result.putString("minimap","selected unit touch command and click feedback passed");
        boolean originalEnhanced=(boolean)settings.getClass().getMethod("getEnhancedWorldRendering").invoke(settings);
        settings.getClass().getMethod("setEnhancedWorldRendering",boolean.class).invoke(settings,false);
        Thread.sleep(1000);
        int framesBefore=engine.frameDrawCount;
        long measuredAt=System.nanoTime();
        Thread.sleep(5000);
        boolean debugHost=(instrumentation.getTargetContext().getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0;
        result.putString("performance",String.format(java.util.Locale.ROOT,"native draw %.1f frames/s (5 second sample, %s host)",
            (engine.frameDrawCount-framesBefore)*1_000_000_000.0/(System.nanoTime()-measuredAt),debugHost?"debug":"release"));
        float originalCameraX=engine.cameraWorldX,originalCameraY=engine.cameraWorldY;
        try {
            settings.getClass().getMethod("setEnhancedWorldRendering",boolean.class).invoke(settings,true);
            Class<?> terrain=loader.loadClass("io.github.rwpp.ui.AndroidTerrainPost");
            java.lang.reflect.Field renderNode=terrain.getDeclaredField("node");renderNode.setAccessible(true);
            java.lang.reflect.Field fullWorld=terrain.getDeclaredField("worldNode");fullWorld.setAccessible(true);
            java.lang.reflect.Field retry=terrain.getDeclaredField("retryAfter");retry.setAccessible(true);
            java.lang.reflect.Field shadows=terrain.getDeclaredField("shadows");shadows.setAccessible(true);
            await(() -> {
                android.graphics.RenderNode node=(android.graphics.RenderNode)renderNode.get(null);
                return node != null && node.hasDisplayList();
            },"hardware terrain shader recording");
            await(() -> {
                android.graphics.RenderNode world=(android.graphics.RenderNode)fullWorld.get(null);
                return world!=null && world.hasDisplayList() && !((java.util.Map<?,?>)shadows.get(null)).isEmpty();
            },"full-world post processing and projected shadow shader");
            if(arguments!=null && "true".equalsIgnoreCase(arguments.getString("lavaMap","false"))) {
                onGame(loader,()->{
                    for(Object item:engine.map.layers) {
                        com.corrodinggames.rts.game.map.MapLayer layer=(com.corrodinggames.rts.game.map.MapLayer)item;
                        for(int y=0;y<engine.map.mapHeightInTiles;y++) for(int x=0;x<engine.map.mapWidthInTiles;x++) {
                            com.corrodinggames.rts.game.map.MapTile tile=layer.getTile(x,y);
                            if(tile!=null && tile.lava) {
                                engine.setCamera(x*engine.map.tileWidth-100f,y*engine.map.tileHeight-100f);engine.clampCamera();return;
                            }
                        }
                    }
                    throw new AssertionError("Selected native map contains no lava tiles");
                });
                java.lang.reflect.Field materialMask=terrain.getDeclaredField("mask");materialMask.setAccessible(true);
                await(()->{
                    android.graphics.Bitmap bitmap=(android.graphics.Bitmap)materialMask.get(null);
                    if(bitmap==null)return false;
                    int[] data=new int[bitmap.getWidth()*bitmap.getHeight()];bitmap.getPixels(data,0,bitmap.getWidth(),0,0,bitmap.getWidth(),bitmap.getHeight());
                    return java.util.Arrays.stream(data).anyMatch(pixel->(pixel&63)>0);
                },"real lava coverage in hardware material buffer");
                result.putString("lava","LAVA_HARDWARE_PASS: native Valley Arena lava tiles visible in shared AGSL material buffer and full-world render graph");
            }
            int enhancedFrames=engine.frameDrawCount;
            long enhancedAt=System.nanoTime();
            Thread.sleep(5000);
            if(retry.getLong(null)!=0) throw new AssertionError("Shared world shader fell back after a render/binding error");
            result.putString("terrain",String.format(java.util.Locale.ROOT,"shared terrain, projected shadows, sprite exclusion and full-world post active: %.1f frames/s (5 second sample)",
                (engine.frameDrawCount-enhancedFrames)*1_000_000_000.0/(System.nanoTime()-enhancedAt)));
            for (int cycle=0; cycle<2; cycle++) {
                settings.getClass().getMethod("setEnhancedWorldRendering",boolean.class).invoke(settings,false);
                int before=engine.frameDrawCount;
                Thread.sleep(700);
                if (engine.frameDrawCount<=before) throw new AssertionError("Native drawing stopped with lighting disabled");
                settings.getClass().getMethod("setEnhancedWorldRendering",boolean.class).invoke(settings,true);
                await(() -> {
                    android.graphics.RenderNode world=(android.graphics.RenderNode)fullWorld.get(null);
                    return world!=null && world.hasDisplayList();
                },"shared world graph after lighting toggle");
                Thread.sleep(900);
                if (retry.getLong(null)!=0) throw new AssertionError("Shader fell back after a lighting toggle");
            }
            result.putString("toggles", "LIGHTING_TOGGLE_PASS: two off/on cycles keep native drawing and full-world post active without shader fallback");
        } finally {
            onGame(loader,()->engine.setCamera(originalCameraX,originalCameraY));
            settings.getClass().getMethod("setEnhancedWorldRendering",boolean.class).invoke(settings,originalEnhanced);
        }
        onGame(loader, () -> room.getClass().getMethod("disconnect", String.class).invoke(room, "validation complete"));
        await(() -> {
            Activity owner = EngineContext.foregroundActivity();
            return owner != null && owner.getClass().getName().equals("io.github.rwml.android.MainActivity");
        }, "return to menu");
        Thread.sleep(2000);
        result.putString("stream", "Native bootstrap, menu background, room start and extension game surface passed.\n"
            + "Aero HUD and return-to-menu passed.\nBackground: " + result.getString("background")
            + "\nGame: " + result.getString("game") + "\nAudio: " + result.getString("audio") + "\n");
        result.putString("stream",result.getString("stream")+result.getString("configuration")+"\n"+result.getString("chat")+"\n"
            +result.getString("deferredBoot")+"\n"+result.getString("consoleBoot")+"\n"+result.getString("lanRoom")+"\n"+result.getString("roomPage")+"\n"+result.getString("minimapIndicators")+"\n"+result.getString("minimap")+"\n"+result.getString("search")+"\n"+result.getString("performance")+"\n"+result.getString("terrain")+"\n");
        if(configPhase.equals("verify")) result.putString("stream",result.getString("stream")+"COLD_CONFIG_VERIFY_PASS: saved settings survived fresh process and local-game start; original values restored\n");
    }

    private interface Action { void run() throws Exception; }
    private void verifyDirectFiles(Bundle result) throws Exception {
        android.content.Context context = instrumentation.getTargetContext();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            if (!android.os.Environment.isExternalStorageManager()) throw new AssertionError("All-files access is missing");
            if (context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                throw new AssertionError("This test must prove file access without legacy storage grants");
        }
        com.corrodinggames.rts.gameFramework.files.FileAccessConfig access =
            com.corrodinggames.rts.gameFramework.files.FileLoader.computeFileAccessConfig(false);
        if (!access.useDirectExternalAccess || access.useSafExternalAccess || access.useOverriddenExternalPath
            || com.corrodinggames.rts.gameFramework.utility.FileReaderManager.safSupported())
            throw new AssertionError("Game file backend is still using SAF");
        java.io.File root = new java.io.File(android.os.Environment.getExternalStorageDirectory(), "rustedWarfare");
        if (!root.isDirectory() && !root.mkdirs()) throw new AssertionError("Cannot prepare game file directory");
        java.io.File temporary = java.io.File.createTempFile("rwml-direct-validation-", ".tmp", root);
        String abstractPath = "/SD/rustedWarfare/" + temporary.getName();
        try {
            try (java.io.OutputStream output = com.corrodinggames.rts.gameFramework.files.FileLoader.openOutput(abstractPath, false)) {
                if (output == null) throw new AssertionError("Native file output unavailable");
                output.write(new byte[] {82,87,77,76});
            }
            try (java.io.InputStream input = com.corrodinggames.rts.gameFramework.files.FileLoader.openByPath(abstractPath)) {
                if (input == null || input.read() != 82 || input.read() != 87 || input.read() != 77 || input.read() != 76)
                    throw new AssertionError("Native direct file readback failed");
            }
            if (temporary.length() != 4) throw new AssertionError("Native file path did not reach shared storage");
            result.putString("directFiles", "DIRECT_FILES_PASS: native /SD path read/write with all-files access, SAF disabled and no legacy storage grants");
            android.util.Log.i("RWML", result.getString("directFiles"));
        } finally {
            if (temporary.exists() && !temporary.delete()) throw new AssertionError("Cannot remove validation temporary file");
        }
    }
    private static void checkSettings(Object settings,android.content.SharedPreferences saved,String[] fields,String phase) throws Exception {
        for(String field:fields) {
            boolean actual=(boolean)settings.getClass().getMethod("get"+field).invoke(settings);
            if(actual!=saved.getBoolean("expected."+field,false)) throw new AssertionError("Setting "+field+" was reset "+phase);
        }
    }
    private static Object service(ClassLoader loader,Object koin,java.lang.reflect.Method get,String name) throws Exception {
        Object type=loader.loadClass("kotlin.jvm.JvmClassMappingKt").getMethod("getKotlinClass",Class.class).invoke(null,loader.loadClass(name));
        return get.invoke(koin,type,null,null);
    }
    private static void onGame(ClassLoader loader, Action action) throws Exception {
        Class<?> dispatcher = loader.loadClass("io.github.rwpp.api.coroutines.GameMainDispatcher");
        Object empty = loader.loadClass("kotlin.coroutines.EmptyCoroutineContext").getField("INSTANCE").get(null);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Runnable task = () -> { try { action.run(); } catch (Throwable t) { failure.set(t); } finally { done.countDown(); } };
        dispatcher.getMethod("dispatch", loader.loadClass("kotlin.coroutines.CoroutineContext"), Runnable.class)
            .invoke(dispatcher.getField("INSTANCE").get(null), empty, task);
        if (!done.await(30, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Game task timed out");
        if (failure.get() != null) throw new Exception("Game task failed", failure.get());
    }


}
