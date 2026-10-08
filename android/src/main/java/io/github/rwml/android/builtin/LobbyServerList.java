/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.os.Handler;
import android.os.Looper;
import com.corrodinggames.rts.gameFramework.GameEngine;
import com.corrodinggames.rts.gameFramework.CommonUtils;
import com.corrodinggames.rts.gameFramework.network.DiscoveredServer;
import com.corrodinggames.rts.gameFramework.network.MasterServerConnector;
import io.github.rwml.android.RequestScope;
import io.github.rwml.android.MasterServerRows;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** The resumed lobby exclusively owns list requests. All commits happen on its UI thread. */
public final class LobbyServerList {
    private static MultiplayerLobbyActivity owner;
    private static RequestScope scope;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private LobbyServerList() { }
    static void enter(MultiplayerLobbyActivity activity) { leave(owner); owner = activity; }
    static void leave(MultiplayerLobbyActivity activity) {
        if (owner != activity) { return; }
        owner = null;
        if (scope != null) { scope.close(); scope = null; }
    }
    public static void request(Runnable completion) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // A request made off the lobby UI thread is never started after a later navigation.
            return;
        }
        MultiplayerLobbyActivity activity = owner;
        if (activity == null || activity.isFinishing()) { return; }
        if (scope != null) { scope.close(); }
        RequestScope request = scope = new RequestScope();
        GameEngine engine = GameEngine.getInstance();
        String query = "/interface?action=list&game_version=" + engine.getVersionCode(true)
            + "&game_version_beta=" + CommonUtils.booleanToString(engine.isSaveSupported());
        String language = com.corrodinggames.rts.gameFramework.localization.LocaleEngine.getLanguageCode();
        String userAgent = "rw android " + engine.getVersionCode(true) + " " + language;
        String[] hosts = MasterServerConnector.masterServerUrls.clone();
        AtomicInteger pending = new AtomicInteger(hosts.length);
        List<String[]> rows = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        AtomicInteger succeeded = new AtomicInteger();
        if (hosts.length == 0) { request.close(); return; }
        for (String host : hosts) {
            request.execute(() -> {
                try {
                    List<String[]> result = MasterServerRows.parse(request.get(new URL(host + query), userAgent, language));
                    synchronized (rows) { rows.addAll(result); }
                    succeeded.incrementAndGet();
                } catch (Exception failure) {
                    synchronized (failures) { failures.add(failure.toString()); }
                } finally {
                    if (pending.decrementAndGet() == 0 && request.isActive()) {
                        main.post(() -> {
                            if (owner != activity || scope != request || !request.isActive()) { return; }
                            if (succeeded.get() > 0) {
                                apply(engine, rows);
                                engine.networkEngine.serverListLoadError = null;
                            } else {
                                engine.networkEngine.serverListLoadError = String.join("; ", failures);
                            }
                            if (completion != null) { completion.run(); }
                        });
                    }
                }
            });
        }
    }
    private static void apply(GameEngine engine, List<String[]> rows) {
        Map<String, DiscoveredServer> found = new LinkedHashMap<>();
        for (String[] r : rows) {
            DiscoveredServer s = new DiscoveredServer();
            s.serverId = r[18]; s.host = r[3]; s.lanAddress = r[4];
            if (r[3].startsWith("url:") && Boolean.parseBoolean(r[17])) {
                s.url = r[3].substring(4); s.description = r[1];
                if (!CommonUtils.sha256Hex14(s.url + ";" + s.description).equals(r[4])) { continue; }
            }
            s.port = Integer.parseInt(r[5]); s.portOpen = Boolean.parseBoolean(r[6]);
            s.playerName = r[7]; s.passwordRequired = Boolean.parseBoolean(r[8]);
            s.mapName = r[9]; s.PENDING_masterListColumn10 = r[10];
            s.gameState = r[11]; s.gameVersion = r[12]; s.isLan = Boolean.parseBoolean(r[13]);
            s.protocolVersionText = r[2]; s.protocolVersion = number(r[2], 0);
            s.playerCountText = r[15]; s.playerCount = number(r[15], -1);
            s.maxPlayersText = r[16]; s.maxPlayers = number(r[16], 8);
            s.allowsAnyVersion = Boolean.parseBoolean(r[17]);
            s.PENDING_masterListColumn19 = Boolean.parseBoolean(r[19]);
            s.requiredMods = r[20].isEmpty() ? null : r[20]; s.serverType = number(r[21], 0);
            s.lastSeenTimeMillis = System.currentTimeMillis();
            found.put(s.serverId, s);
        }
        synchronized (MasterServerConnector.PENDING_lock_f) {
            engine.networkEngine.discoveredServers.removeIf(server -> !((DiscoveredServer) server).isLan);
            for (DiscoveredServer server : found.values()) {
                engine.networkEngine.discoveredServers.removeIf(existing -> server.serverId.equals(((DiscoveredServer) existing).serverId));
                engine.networkEngine.discoveredServers.add(server);
            }
        }
    }
    private static int number(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (NumberFormatException invalid) { return fallback; }
    }
}
