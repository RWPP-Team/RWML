/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import com.corrodinggames.rts.appFramework.MapSelectActivity;
import com.corrodinggames.rts.gameFramework.CommonUtils;
import com.corrodinggames.rts.gameFramework.GameEngine;
import com.corrodinggames.rts.gameFramework.SettingsEngine;
import com.corrodinggames.rts.gameFramework.files.FileLoader;
import com.corrodinggames.rts.gameFramework.network.ConnectSocketToServerRunnable;
import com.corrodinggames.rts.gameFramework.network.DiscoveredServer;
import com.corrodinggames.rts.gameFramework.network.GameSetupMapType;
import com.corrodinggames.rts.gameFramework.network.MasterServerConnector;
import com.corrodinggames.rts.gameFramework.network.NetworkEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import io.github.rwml.android.R;

/**
 * The multiplayer lobby: the game's own lobby layout, the list of games the master server has
 * published, the name to play under, and the two ways into a game of one's own.
 *
 * <p>Everything a game of Rusted Warfare does over the network belongs to the engine, and this
 * screen is a view onto it rather than a second implementation of it. The list is the engine's own
 * set of discovered games, read in the order the game's own list screen puts it in; asking for the
 * list is the engine's own request against its own master servers; and joining is the engine's own
 * connector, which is what knows that a game announced on a master list carries its identity rather
 * than a reachable address, that the address has to be asked for separately, and that a game behind
 * a password has to be asked for it before it answers. This screen therefore hands the engine an
 * entry and a name and reads back what came of it.</p>
 *
 * <p>Hosting is the game's own host path too: the engine's network state is set from the host
 * dialog, the server is started, and a game whose chosen map is no longer on the device falls back
 * to the game's default skirmish map, since a battleroom cannot set up a map that cannot be
 * loaded.</p>
 *
 * <p>The engine announces a changed game list by calling a static method on the class that used to
 * be the game's own list screen. That class is a shell in the archive this app runs, so the call is
 * redirected to this screen; see {@code ServerListNotifyMixin}.</p>
 *
 * <p>Joining Bluetooth and linking a mod folder belong to the Android build's own connectivity and
 * file browser, which this host does not carry, so those controls stay hidden.</p>
 */
public final class MultiplayerLobbyActivity extends Activity {

    private static final String TAG = "RWML";

    /** The number of columns the layout's heading row declares. */
    private static final int COLUMNS = 6;

    /** How wide a row of the table is: as wide as the table it is in. */
    private static final int ROW_WIDTH = ViewGroup.LayoutParams.MATCH_PARENT;

    /** The column the map is in, which is the one the game marks for the game being played. */
    private static final int COLUMN_MAP = 3;

    /** The column the version is in, which is the one the game greys for another protocol. */
    private static final int COLUMN_VERSION = 4;

    /** The colour of a cell the game draws nothing special about. */
    private static final int COLOR_PLAIN = Color.argb(255, 255, 255, 255);

    /** The colour of a game that is a chat room or a link, both of which open outside the game. */
    private static final int COLOR_LINK = Color.argb(255, 152, 236, 249);

    /** The colour of a game whose host answered from a port that is open to the internet. */
    private static final int COLOR_OPEN = Color.argb(255, 240, 240, 240);

    /** The colour of a game found on the local network. */
    private static final int COLOR_LAN = Color.argb(255, 229, 149, 35);

    /** The colour of the game this device is in. */
    private static final int COLOR_CURRENT = Color.argb(255, 35, 35, 200);

    /** The colour of a game speaking another protocol than this build. */
    private static final int COLOR_OTHER_VERSION = Color.argb(255, 155, 147, 147);

    /**
     * The lobby that is on screen.
     *
     * <p>The engine's "the list changed" call is a static one on a game class, so the screen it is
     * about has to be reachable without an instance to call it on.</p>
     */
    private static volatile MultiplayerLobbyActivity open;

    private GameEngine engine;
    private EditText playerName;
    private EditText joinAddress;
    private TableLayout gameList;
    private Button refresh;
    private String refreshLabel;
    private String refreshingLabel;

    /** The wait shown while the engine opens the socket, and the call that opened it. */
    private ProgressDialog connecting;

    /** The connector of the join that is running, so the wait can be taken down with its answer. */
    private ConnectSocketToServerRunnable joinInProgress;

    /** Whether a rebuild has already been asked for, so a burst of notices is one redraw. */
    private boolean rebuildPosted;

    /**
     * Whether this screen is the one in front.
     *
     * <p>The engine keeps reporting games it has discovered, and it does that whatever is on
     * screen, so a room opened over this one would keep this screen's table rebuilding behind it.
     * The reports are acted on only while this screen is the one being looked at.</p>
     */
    private boolean resumed;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Multiplayer");
        AndroidApp.prepare(this, true);
        setContentView(R.layout.multiplayer_lobby);

        engine = GameEngine.getInstance();
        if (engine == null || engine.networkEngine == null) {
            Log.w(TAG, "The engine is not up; the lobby cannot bind.");
            Toast.makeText(this, "The game is not loaded yet.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        playerName = findViewById(R.id.networkPlayerName);
        joinAddress = findViewById(R.id.joinIpAddress);
        gameList = findViewById(R.id.gameListTable);
        refresh = findViewById(R.id.refreshServersButton);

        // The engine drives whichever screen is setting the game up through these, and a game the
        // server starts while this screen is still open has to reach a screen that can show it.
        HostNetworkCallbacks.install(engine.networkEngine);

        refreshLabel = LocalizedText.word("menus.lobby.button.refresh", "Refresh");
        refreshingLabel = LocalizedText.word("menus.lobby.button.refreshing", "Refreshing...");
        if (refresh != null) {
            refresh.setText(refreshLabel);
        }

        fillDefaults();
        hide(R.id.battleroom_joinBluetoothButton);

        onclick(R.id.hostButton, this::host);
        onclick(R.id.joinButton, this::joinTypedAddress);
        onclick(R.id.watchReplayButton, this::watchReplay);
        onclick(R.id.refreshServersButton, this::loadServerList);

        LocalizedText.applyTo(findViewById(android.R.id.content));
        open = this;
        resumed = false;
        // The engine goes on drawing the menu map into the surface behind this screen, which is a
        // surface nobody is looking at once this screen covers it. The game's own battleroom is
        // drawn over a screen whose drawing has stopped for the same reason.
        AndroidGameView.setEngineDrawingEnabled(engine, false);
        // The games the engine already knows about are put on screen now; the request for the
        // current ones is made as the screen comes back to the front.
        refreshServers();
        Log.i(TAG, "Lobby open.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        open = this;
        resumed = true;
        LobbyServerList.enter(this);
        AndroidGameView.setEngineDrawingEnabled(engine, false);
        // Coming back from a battleroom is a reason to ask again, which is what the game's own
        // lobby does as it resumes.
        loadServerList();
    }

    @Override
    protected void onPause() {
        resumed = false;
        LobbyServerList.leave(this);
        if (open == this) { open = null; }
        super.onPause();
        // A room opened over this one is where the player is now, so the games the engine keeps
        // reporting are left alone until this screen is the one in front again.
        resumed = false;
    }

    @Override
    protected void onDestroy() {
        LobbyServerList.leave(this);
        resumed = false;
        if (open == this) {
            open = null;
        }
        // The screen behind this one gets its drawing back, which is the menu's own map.
        AndroidGameView.setEngineDrawingEnabled(engine, true);
        dismissConnecting();
        super.onDestroy();
    }

    /**
     * The engine has heard of a game it had not heard of, or has dropped one it has not seen for a
     * while, so the list on screen is out of date.
     *
     * <p>The call arrives on whichever thread the engine was reading the master server on, and it
     * arrives once per game, so the redraw is asked for on the main thread and only once until it
     * has happened.</p>
     */
    public static void onServerListChanged() {
        MultiplayerLobbyActivity activity = open;
        if (activity != null && activity.resumed) {
            activity.postRebuild();
        }
    }

    private void postRebuild() {
        if (rebuildPosted) {
            return;
        }
        rebuildPosted = true;
        runOnUiThread(() -> {
            rebuildPosted = false;
            if (resumed && open == this) { refreshServers(); }
        });
    }

    /** The name to play under and the last address joined, both remembered by the engine. */
    private void fillDefaults() {
        SettingsEngine settings = SettingsEngine.getInstance(getBaseContext());
        String rememberedName = settings == null ? null : settings.lastNetworkPlayerName;
        if (rememberedName != null && !rememberedName.isEmpty()) {
            if (playerName != null) {
                playerName.setText(rememberedName);
            }
        } else if (playerName != null) {
            playerName.setText("Unnamed" + (100 + new Random().nextInt(900)));
        }
        if (joinAddress != null && settings != null && settings.lastNetworkIP != null) {
            joinAddress.setText(settings.lastNetworkIP);
        }
    }

    // ------------------------------------------------------------------
    // Hosting
    // ------------------------------------------------------------------

    /** Opens the host dialog, whose answers decide how the game is hosted. */
    private void host() {
        if (!rememberName()) {
            return;
        }
        openHostDialog();
    }

    /**
     * Asks the three questions the game's own host dialog asks: the password the game needs,
     * whether it should be visible on the network, and whether the mods that are on should be part
     * of it. The two start buttons differ only in the second answer.
     */
    private void openHostDialog() {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.multiplayer_lobby_hostgame);
        dialog.setTitle("Host game");
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        LocalizedText.applyTo(dialog.getWindow() == null
            ? null : dialog.getWindow().getDecorView());

        final EditText password = dialog.findViewById(R.id.gamePassword);
        final CheckBox useMods = dialog.findViewById(R.id.useMods);
        Button startPublic = dialog.findViewById(R.id.hostgame_startPublic);
        Button startPrivate = dialog.findViewById(R.id.hostgame_startPrivate);
        View cancel = dialog.findViewById(R.id.hostgame_cancel);
        if (startPublic == null || startPrivate == null) {
            // The dialog is not in the layout; hosting still works, it just cannot be configured.
            Log.w(TAG, "The host-game dialog is not in the lobby layout; hosting directly.");
            startServer(true, null, null);
            return;
        }

        View.OnClickListener start = clicked -> {
            startServer(clicked == startPublic, password, useMods);
            dialog.dismiss();
        };
        startPublic.setOnClickListener(start);
        startPrivate.setOnClickListener(start);
        if (cancel != null) {
            cancel.setOnClickListener(clicked -> dialog.dismiss());
        }
        dialog.show();
    }

    /**
     * Opens this device as a server with the chosen answers and goes to the battleroom.
     *
     * <p>The engine refuses to start a server over a network session that is still up, so whatever
     * the last one was is closed first. That is also why the server is started with the flag that
     * makes the host an ordinary player: a previous session's administrator player is not carried
     * into this one.</p>
     */
    private void startServer(boolean visible, EditText password, CheckBox useMods) {
        final NetworkEngine network = engine.networkEngine;
        String secret = password == null ? null : password.getText().toString().trim();
        try {
            // The engine's own host path, in the order the game's own lobby takes it.
            network.disconnect("starting new");
            network.serverPassword = secret == null || secret.isEmpty() ? null : secret;
            network.modsEnabled = useMods == null || useMods.isChecked();
            network.serverVisible = visible;
            if (!network.startServer(false)) {
                return;
            }
        } catch (Throwable t) {
            Log.e(TAG, "A server could not be started.", t);
            Toast.makeText(this, "Could not host: " + t.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        ensureHostMapIsLoadable(network);
        Log.i(TAG, "Hosting on port " + network.networkPort + '.');
        openBattleroom();
    }

    /**
     * Falls back to the game's default skirmish map when the map the host would start on cannot be
     * loaded.
     *
     * <p>A battleroom sets up a game against a map the device has, and the map the engine would
     * start on is whatever the last game left behind. A path that is empty, or that names a file
     * that has been removed since, leaves the room unable to choose or show anything, so it is
     * replaced with the map the game itself falls back to.</p>
     */
    private void ensureHostMapIsLoadable(NetworkEngine network) {
        try {
            String path = network.getNetworkMapPath();
            if (path != null && FileLoader.fileExists(path)) {
                return;
            }
            Log.i(TAG, "The host has no loadable map of its own (" + path + "); using the default.");
            network.gameSetup.mapType = GameSetupMapType.skirmishMap;
            network.mapName = DEFAULT_SKIRMISH_MAP;
            network.gameSetup.mapName = DEFAULT_SKIRMISH_MAP_NAME;
        } catch (Throwable t) {
            Log.w(TAG, "The host map could not be checked.", t);
        }
    }

    /** The map the game's own lobby starts a host on when there is nothing to carry over. */
    private static final String DEFAULT_SKIRMISH_MAP = "maps/skirmish/[p8]Many Islands (8p).tmx";

    /** The same map as the battleroom names it, which is the name without its folder. */
    private static final String DEFAULT_SKIRMISH_MAP_NAME = "[p8]Many Islands (8p).tmx";

    // ------------------------------------------------------------------
    // The game list
    // ------------------------------------------------------------------

    /**
     * Asks the game for its list of games.
     *
     * <p>The list is the game's, and so is the request: the connector the game uses asks each of its
     * master servers in turn, merges the answers into the engine's own set of discovered games, and
     * reports the whole attempt as an error string on the engine when every one of them refused.
     * What this screen does is start that request and put its answer on screen, which is what the
     * game's own list screen does with the same call.</p>
     *
     * <p>The request runs on a thread of its own and reports back through the runnable, which is why
     * the table is rebuilt from the main thread.</p>
     */
    private void loadServerList() {
        if (!resumed || open != this || engine == null || engine.networkEngine == null) {
            return;
        }
        engine.networkEngine.serverListLoadError = null;
        if (refresh != null) {
            refresh.setText(refreshingLabel);
            refresh.setEnabled(false);
        }
        try {
            MasterServerConnector.startLoadRequest(() -> runOnUiThread(this::serverListLoaded));
        } catch (Throwable t) {
            Log.e(TAG, "The game list could not be asked for.", t);
            serverListLoaded();
        }
    }

    /** Puts the answer of the request on screen: the reason it failed, or the list it produced. */
    private void serverListLoaded() {
        if (!resumed || open != this || isFinishing()) {
            return;
        }
        refreshServers();
    }

    /** Rebuilds the table from the games the engine has heard from. */
    private void refreshServers() {
        if (gameList == null) {
            return;
        }
        // Every row but the heading belongs to the last rebuild, and the layout's own sample and
        // loading rows go with them.
        for (int i = gameList.getChildCount() - 1; i >= 0; i--) {
            if (gameList.getChildAt(i).getId() == View.NO_ID) {
                gameList.removeViewAt(i);
            }
        }

        List<DiscoveredServer> servers = discovered();
        for (final DiscoveredServer server : servers) {
            addServerRow(server);
        }
        if (servers.isEmpty()) {
            addEmptyRow();
        }
        if (refresh != null) {
            refresh.setText(refreshLabel);
            refresh.setEnabled(true);
        }
    }

    /**
     * One game, in the six columns the heading row declares: what the game is doing, who is hosting
     * it, how full it is, the map, the version it is on, and whether anyone can walk in.
     */
    private void addServerRow(final DiscoveredServer server) {
        TableRow row = new TableRow(this);
        row.setClickable(true);
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        row.setOnClickListener(clicked -> confirmJoin(server));

        addCell(row, stateOf(server), server);
        addCell(row, truncate(server.playerName, 15), server);
        addCell(row, truncate(countOf(server), 15), server);
        addCell(row, truncate(prettyMapName(server.mapName), 40), server);
        addCell(row, versionOf(server), server);
        addCell(row, openOf(server), server);

        gameList.addView(row);
        row.getLayoutParams().width = ROW_WIDTH;
    }

    /** The game's own reading of a map file's name, or nothing when the game names no map. */
    private static String prettyMapName(String path) {
        if (path == null) {
            return "";
        }
        try {
            return MapSelectActivity.getPrettyMapName(path);
        } catch (Throwable t) {
            Log.w(TAG, "The map name " + path + " could not be read.");
            return path;
        }
    }

    /** The row a list with nothing in it still needs, so the table does not look broken. */
    private void addEmptyRow() {
        String failure = engine == null || engine.networkEngine == null
            ? null : engine.networkEngine.serverListLoadError;
        String message = failure == null || failure.isEmpty()
            ? "No Active Games :(" : "ERROR: " + failure;

        TableRow row = new TableRow(this);
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        TextView cell = addCell(row, message, null);
        cell.setGravity(Gravity.CENTER);
        TableRow.LayoutParams params = (TableRow.LayoutParams) cell.getLayoutParams();
        params.span = COLUMNS;
        cell.setLayoutParams(params);

        gameList.addView(row);
        row.getLayoutParams().width = ROW_WIDTH;
    }

    /** The name of the game's state, which the game's own words replace the raw value with. */
    private static String stateOf(DiscoveredServer server) {
        if (server.gameState == null) {
            return "";
        }
        return server.gameState
            .replace("battleroom", LocalizedText.word("menus.lobby.gameState.battleroom", "battleroom"))
            .replace("ingame", LocalizedText.word("menus.lobby.gameState.ingame", "ingame"))
            .replace("chat", LocalizedText.word("menus.lobby.gameState.chat", "chat"));
    }

    /** How many players are in the game of how many it takes. */
    private static String countOf(DiscoveredServer server) {
        if (server.playerCountText == null) {
            return "?";
        }
        if ("?".equals(server.playerCountText)) {
            return "?";
        }
        return server.playerCountText + "\\" + server.maxPlayersText;
    }

    /** The game's version, or the word it uses for a game that takes any version. */
    private static String versionOf(DiscoveredServer server) {
        if (server.gameVersion == null) {
            return "";
        }
        if ("ANY".equalsIgnoreCase(server.gameVersion)) {
            return server.gameVersion;
        }
        return "v" + truncate(server.gameVersion, 8);
    }

    /**
     * Whether anyone can walk in: {@code N} when the host's port is not open to the internet, which
     * is when a direct connection may fail, {@code Y} when it is, {@code P} when it is as well but a
     * password is needed, and {@code L} for a game on the local network.
     */
    private static String openOf(DiscoveredServer server) {
        if (server.isLan) {
            return "L";
        }
        if (!server.portOpen) {
            return "N";
        }
        return server.passwordRequired ? "P" : "Y";
    }

    /**
     * The games the engine has heard from, in the order the game's own list shows them.
     *
     * <p>The order is not this screen's to decide. The game's list screen keeps that rule in one
     * place, and it is not a plain sort: games whose version matches this build come before the rest,
     * and the two groups are ordered within themselves. The set is therefore asked for rather than
     * rebuilt, and the call is safe from any thread because it copies the engine's set under the
     * same lock the connector writes it through.</p>
     */
    private List<DiscoveredServer> discovered() {
        List<DiscoveredServer> servers = new ArrayList<>();
        try {
            List<?> listed = com.corrodinggames.rts.appFramework.ServerListActivity.m();
            if (listed == null) {
                return servers;
            }
            for (Object entry : listed) {
                if (entry instanceof DiscoveredServer) {
                    servers.add((DiscoveredServer) entry);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "The discovered games could not be read.", t);
        }
        return servers;
    }

    // ------------------------------------------------------------------
    // Joining
    // ------------------------------------------------------------------

    /** Joins the address in the field. */
    private void joinTypedAddress() {
        String address = joinAddress == null ? null : joinAddress.getText().toString().trim();
        if (address == null || address.isEmpty()) {
            Toast.makeText(this, "Enter the address of a game to join.", Toast.LENGTH_SHORT).show();
            return;
        }
        // The game remembers the last address it was asked to reach, and this is where it is set.
        try {
            SettingsEngine settings = SettingsEngine.getInstance(getBaseContext());
            if (settings != null) {
                settings.lastNetworkIP = address;
                settings.save();
            }
        } catch (Throwable t) {
            Log.w(TAG, "The last address could not be remembered.", t);
        }
        joinServerWithId(address, null);
    }

    /**
     * Asks whether to join one of the listed games, and says what the game is before it does.
     *
     * <p>A listed game's entry says what the game looks like, and it is the engine that turns that
     * into a reachable address. The entry is looked up again first, because a game can have closed
     * between the list being drawn and the row being touched.</p>
     */
    private void confirmJoin(final DiscoveredServer listed) {
        DiscoveredServer server = listed;
        try {
            DiscoveredServer found = MasterServerConnector.findDiscoveredServer(listed.serverId);
            if (found == null) {
                alert("Join Server?", "That server no longer exists");
                return;
            }
            server = found;
        } catch (Throwable t) {
            Log.w(TAG, "The game " + listed.serverId + " could not be looked up again.", t);
        }

        final boolean link = server.hasUrl();
        final String address = link ? null
            : server.isLan ? server.getLanConnectAddress() : server.getConnectAddress();
        String label = link ? "Open"
            : server.isLan ? "Join over LAN" : "Join";
        final DiscoveredServer game = server;

        new AlertDialog.Builder(this)
            .setIcon(android.R.drawable.ic_dialog_info)
            .setTitle(link ? "Open Link?" : "Join Server?")
            .setMessage(infoTextOf(game))
            .setPositiveButton(label, (dialog, which) -> {
                if (link) {
                    openLink(game.getUrl());
                } else {
                    joinServerWithId(address, game.serverId);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /**
     * What the game's own join prompt says about a game: the link, or what the game looks like and
     * what joining it costs.
     */
    private static String infoTextOf(DiscoveredServer server) {
        try {
            return server.getServerInfoText();
        } catch (Throwable t) {
            Log.w(TAG, "The details of the game " + server.serverId + " could not be read.");
            return server.playerName == null ? "" : server.playerName;
        }
    }

    /**
     * Joins whatever the given address names.
     *
     * <p>The entry of a game that came from a master list is not an address but the game's identity
     * and where the master server keeps it, which the engine knows how to read: it asks the master
     * server for the host and port of that one game, asks for the password if the game needs one,
     * and connects to the answer. A game on the local network carries an ordinary address and is
     * joined directly.</p>
     */
    private void joinServerWithId(String address, String serverId) {
        if (address == null || address.trim().isEmpty()) {
            return;
        }
        if (!rememberName()) {
            return;
        }
        // The engine keeps the identity of the game being joined, and asks its own screens about it.
        engine.networkEngine.currentServerId = serverId;
        joinServer(address);
    }

    /**
     * Opens the socket to one address, through the engine's own connector.
     *
     * <p>The connector runs on a thread of its own and calls back when it is done, so the wait is
     * put on screen and the answer is brought back to the main thread. A connector can finish
     * before the call that started it returns, which is why the field is filled in before the wait
     * is shown and the callback never reads it from the connector's thread.</p>
     */
    private void joinServer(String address) {
        Log.i(TAG, "Joining " + address + '.');
        final ConnectSocketToServerRunnable[] started = new ConnectSocketToServerRunnable[1];
        try {
            started[0] = engine.networkEngine.connectToServerAsync(address, false,
                () -> runOnUiThread(this::joinFinished));
        } catch (Throwable t) {
            Log.e(TAG, "The connection to " + address + " could not be started.", t);
            alert("Connection failed", "Connection failed.\n" + t.getMessage());
            return;
        }
        showConnecting();
        joinInProgress = started[0];
    }

    /**
     * The engine's connector has either opened the socket or given up.
     *
     * <p>On an answer the socket is handed to the engine, which is what turns an open connection
     * into a battleroom, and the screen that shows it is opened. A refusal carries the reason the
     * engine was given, which is what a wrong password, a game that has closed, or a host that
     * cannot be reached over the internet report.</p>
     */
    private void joinFinished() {
        ConnectSocketToServerRunnable connector = joinInProgress;
        if (connector == null) {
            return;
        }
        joinInProgress = null;
        dismissConnecting();

        String failure = connector.PENDING_string_e;
        if (failure != null) {
            if ("CANCELLED".equals(failure)) {
                Log.i(TAG, "The join was cancelled.");
                return;
            }
            Log.w(TAG, "The connection failed: " + failure);
            alert("Connection failed", "Connection failed.\n" + failure
                + "\nTarget server may not be open to the internet");
            return;
        }

        try {
            NetworkEngine network = engine.networkEngine;
            // A session that is still up would keep the new socket out, and the engine's own join
            // path closes the last one before it takes the new game on.
            network.disconnect("starting new");
            if (!network.startClientConnection(connector.connectedSocket)) {
                alert("Connection failed", "The connection to the game was refused.");
                return;
            }
        } catch (Throwable t) {
            Log.e(TAG, "The connection could not be handed to the engine.", t);
            alert("Connection failed", "Connection failed.\n" + t.getMessage());
            return;
        }
        Log.i(TAG, "Connected.");
        openBattleroom();
    }

    /** Stores the name the player typed, which every connection and the engine both read. */
    private boolean rememberName() {
        String name = playerName == null ? null : playerName.getText().toString();
        if (name == null || name.trim().isEmpty()) {
            Toast.makeText(this, "Enter a name to play under.", Toast.LENGTH_SHORT).show();
            return false;
        }
        try {
            engine.networkEngine.playerName = engine.networkEngine.sanitiseAndStorePlayerName(name);
        } catch (Throwable t) {
            Log.w(TAG, "The player name could not be stored.", t);
            engine.networkEngine.playerName = name.trim();
        }
        return true;
    }

    /** Opens the address of a game that is a link rather than a match. */
    private void openLink(String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        try {
            Intent view = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            startActivity(view);
        } catch (Throwable t) {
            Log.w(TAG, "The link " + url + " could not be opened.", t);
            alert("Open Link?", url);
        }
    }

    private void openBattleroom() {
        startActivity(new Intent(this, MultiplayerBattleroomActivity.class));
    }

    private void watchReplay() {
        startActivity(new Intent(this, ReplaySelectActivity.class));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** The wait shown while the engine opens the socket and asks the master server for the game. */
    private void showConnecting() {
        dismissConnecting();
        ProgressDialog dialog = new ProgressDialog(this);
        dialog.setMessage("Connecting...");
        dialog.setCancelable(false);
        connecting = dialog;
        dialog.show();
    }

    private void dismissConnecting() {
        ProgressDialog dialog = connecting;
        connecting = null;
        if (dialog != null) {
            try {
                dialog.dismiss();
            } catch (Throwable dismissedAlready) {
                Log.d(TAG, "The connecting wait was already gone.");
            }
        }
    }

    /**
     * One cell of a row, drawn like the ones the layout's own rows are made of, and in the colour
     * the game gives the game the row is about.
     *
     * @param server the game the row is about, or null for a row that is about no game
     */
    private TextView addCell(TableRow row, String text, DiscoveredServer server) {
        TextView cell = new TextView(this);
        cell.setText(text == null ? "" : text);
        cell.setBackgroundResource(R.drawable.cell_shape);
        cell.setTextAppearance(this, android.R.style.TextAppearance_Medium);
        int padding = dip(5);
        cell.setPadding(padding, padding, padding, padding);
        styleCell(cell, server, row.getChildCount());
        row.addView(cell);
        return cell;
    }

    /**
     * Puts the colour and the weight the game's own list gives a cell onto it.
     *
     * <p>The list is not one flat colour, and what a cell is drawn as is read off the game as well
     * as off the column. A game that takes any version is a chat room or a link, and the game draws
     * those bold; a link, which opens outside the game rather than joining one, is drawn in the
     * light blue it uses for that. A game on the local network is drawn orange, and one whose host
     * answered from a port open to the internet a shade off white. Two things are marked on one
     * column each: the map of the game this device is in is drawn in blue, and the version of a game
     * speaking another protocol is drawn grey.</p>
     */
    private void styleCell(TextView cell, DiscoveredServer server, int column) {
        int color = COLOR_PLAIN;
        int weight = Typeface.NORMAL;
        if (server != null) {
            boolean takesAnyVersion = server.allowsAnyVersion;
            boolean link = takesAnyVersion && server.hasUrl();
            boolean chat = takesAnyVersion && "chat".equalsIgnoreCase(server.gameState);
            if (takesAnyVersion) {
                weight = Typeface.BOLD;
                if (link || chat) {
                    color = COLOR_LINK;
                }
            } else {
                if (server.portOpen) {
                    color = COLOR_OPEN;
                }
                if (server.isLan) {
                    color = COLOR_LAN;
                }
            }
            if (column == COLUMN_MAP && isCurrentServer(server)) {
                color = COLOR_CURRENT;
            }
            if (!link && !chat && column == COLUMN_VERSION && !isSameProtocol(server)) {
                color = COLOR_OTHER_VERSION;
            }
        }
        cell.setTextColor(color);
        cell.setTypeface(null, weight);
    }

    /** Whether the game listed is the one this device is connected to. */
    private boolean isCurrentServer(DiscoveredServer server) {
        NetworkEngine network = engine == null ? null : engine.networkEngine;
        String current = network == null ? null : network.currentServerId;
        return current != null && current.equals(server.serverId);
    }

    /** Whether the game listed speaks the protocol this build does. */
    private boolean isSameProtocol(DiscoveredServer server) {
        if (engine == null) {
            return true;
        }
        try {
            return engine.getVersionCode(true) == server.protocolVersion;
        } catch (Throwable t) {
            Log.w(TAG, "The protocol version of " + server.serverId + " could not be compared.", t);
            return true;
        }
    }

    private void alert(String title, String message) {
        new AlertDialog.Builder(this)
            .setIcon(android.R.drawable.ic_dialog_info)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private static String truncate(String text, int length) {
        if (text == null) {
            return "";
        }
        return CommonUtils.truncateString(text, length);
    }

    private void onclick(int id, Runnable action) {
        View view = findViewById(id);
        if (view == null) {
            Log.w(TAG, "The lobby layout has no view for " + id + ".");
            return;
        }
        view.setOnClickListener(clicked -> action.run());
    }

    private void hide(int id) {
        View view = findViewById(id);
        if (view != null) {
            view.setVisibility(View.GONE);
        }
    }

    private int dip(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5F);
    }
}
