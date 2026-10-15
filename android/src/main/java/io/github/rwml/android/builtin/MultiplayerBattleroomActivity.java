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
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import com.corrodinggames.rts.appFramework.DynamicImageView;
import com.corrodinggames.rts.game.Team;
import com.corrodinggames.rts.gameFramework.GameEngine;
import com.corrodinggames.rts.gameFramework.files.FileLoader;
import com.corrodinggames.rts.gameFramework.mod.ModManager;
import com.corrodinggames.rts.gameFramework.network.GameSetup;
import com.corrodinggames.rts.gameFramework.network.GameSetupMapType;
import com.corrodinggames.rts.gameFramework.network.MasterServerConnector;
import com.corrodinggames.rts.gameFramework.network.NetworkEngine;
import com.corrodinggames.rts.gameFramework.network.TeamLayout;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import io.github.rwml.android.R;

/**
 * The battleroom: where a multiplayer game is set up, over the game's own layout.
 *
 * <p>The host chooses the game type and the map and adds computer players; everyone sees the team
 * table and the chat; the host starts the game, and a client that has been handed the host's
 * controls asks for the same things with the engine's own changeable setup, which turns the changes
 * into the game's own commands to the server. The engine decides when that adds up to a start, and
 * announces it through {@link HostNetworkCallbacks}, which is also where this screen hears about
 * chat lines and team changes.</p>
 *
 * <p>The two dropdowns are the game's own arrangement, and the shape of it is the game type's: the
 * type decides which folder the map list is read from, so the map dropdown cannot be filled without
 * one. The game reads the skirmish maps from {@code maps/skirmish}, the maps the player added from
 * {@code /SD/rusted_warfare_maps}, and the saved games from the save folder; each list is sorted and
 * shown by its name rather than by its file name. A list that is not filled for the type the player
 * is looking at is why a room can show an empty map dropdown: the game remembers which type it last
 * filled for, and the first fill has to run even though no type has been chosen yet.</p>
 *
 * <p>Choosing a map writes it into the engine's game setup, which is what the clients are sent and
 * what the map is loaded from when the game begins. A client that has been given control of the
 * server does the same thing through the game's own chat command, because a client cannot set the
 * server's game up directly.</p>
 *
 * <p>Two of the game's buttons belong to the Android build's own connectivity: starting the game over
 * Bluetooth, and the file browser behind importing a map. This host carries neither, so the Bluetooth
 * button stays hidden; importing is done through the platform's own file picker instead.</p>
 */
public final class MultiplayerBattleroomActivity extends BuiltinActivity {

    private static final String TAG = "RWML";

    /** The request code the platform's file picker answers with when a map is imported. */
    private static final int REQUEST_IMPORT = 6;

    /** The folder the game keeps its skirmish maps in, which is what a network game picks from. */
    private static final String SKIRMISH_FOLDER = "maps/skirmish";

    /** The folder the game reads maps the player put on the device from. */
    private static final String CUSTOM_FOLDER = "/SD/rusted_warfare_maps";

    /** The folder the game writes saved games into. */
    private static final String SAVES_FOLDER = "/SD/rustedWarfare/saves/";

    /** The game type the map dropdown lists: the skirmish maps. */
    private static final int TYPE_SKIRMISH = 0;

    /** The game type the map dropdown lists: the maps the player added. */
    private static final int TYPE_CUSTOM = 1;

    /** The game type the map dropdown lists: the saved games. */
    private static final int TYPE_SAVED = 2;

    /** What the engine is given for the map when the dropdown is on nothing at all. */
    private static final String NO_MAP = "<No Map>";

    /** How often the room asks the engine whether anything changed. */
    private static final int POLL_MILLIS = 1500;

    /**
     * How many credit choices the game's own array lists.
     *
     * <p>The setup carries which of the game's choices was made rather than a sum of money, and the
     * engine turns that choice into a sum when the game is set up. The first choice is the default,
     * which is what a room opens with, so the number is also the last choice there is.</p>
     */
    private static final int CREDIT_CHOICES = 9;

    /** The engine's income multipliers, in the order the game's own array lists them. */
    private static final float[] INCOME = {1.0F, 1.5F, 2.0F, 2.5F};

    /** The engine's team layouts, without the array's first entry, which keeps the current one. */
    private static final TeamLayout[] LAYOUTS = {
        TeamLayout.layout_2sides, TeamLayout.layout_3sides,
        TeamLayout.layout_ffa, TeamLayout.layout_spectators,
    };

    private GameEngine engine;
    private NetworkEngine network;

    private TextView statusInfo;
    private TextView gameSummary;
    private LinearLayout typeLayout;
    private LinearLayout mapLayout;
    private Spinner typeSpinner;
    private Spinner mapSpinner;
    private DynamicImageView thumbnail;
    private TableLayout playerTable;
    private TextView chatLog;
    private EditText chatText;

    /**
     * The map list's entries exactly as the file layer named them.
     *
     * <p>The dropdown shows a display name, but the engine is given one of these, so the two are kept
     * side by side and an index into the dropdown is an index into this array.</p>
     */
    private String[] mapEntries = new String[0];

    /**
     * The game type the map list was last filled for.
     *
     * <p>It starts at -1 rather than at the first type, because the fill is skipped when the type has
     * not changed, and the game's own screen starts it at -1 for the same reason: with any other
     * starting value the first fill would be skipped and the dropdown would stay empty.</p>
     */
    private int filledForType = -1;

    /** The map path the thumbnail is currently holding, so the image is not decoded for every redraw. */
    private String thumbnailPath;

    /**
     * Whether the proxy controller's controls were already on when the room last looked.
     *
     * <p>Being handed the host's controls is a change the room has to react to once: the map dropdown
     * is moved onto the map the server holds, because until that moment the player was a spectator of
     * the server's choices rather than the one making them. The game's own screen keeps the same
     * flag for the same reason.</p>
     */
    private boolean hadProxyControl;

    /** Whether a start has already been acted on, so the engine's repeated calls land once. */
    private boolean starting;

    /** Whether the dropdowns are being written, so a write does not look like the player choosing. */
    private boolean filling;

    /** The player table's cells that are on screen, so the next redraw can take them back. */
    private final List<Cell> activeCells = new ArrayList<>();

    /**
     * The player table's cells that are no longer on screen.
     *
     * <p>The table is rebuilt every time the engine reports a change, and a row is up to five cells;
     * keeping the views is what keeps that from allocating a new table each time. A cell is reused for
     * the column it was made for, which is what its width and appearance were chosen for.</p>
     */
    private final List<Cell> spareCells = new ArrayList<>();

    private final Handler poll = new Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshTask = this::refresh;

    // ------------------------------------------------------------------
    // Opening and closing
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(LocalizedText.ui("ui.battleroom"));
        AndroidApp.prepare(this, true);
        setContentView(R.layout.multiplayer_battleroom);
        // The room is drawn over the game's own tiled background, so the window's is taken away; the
        // chat field is at the bottom of a long layout, which is why the window pans rather than the
        // whole layout being resized when the keyboard opens.
        getWindow().setBackgroundDrawable(null);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);

        engine = GameEngine.getInstance();
        network = engine == null ? null : engine.networkEngine;
        if (network == null) {
            Log.w(TAG, "The engine is not up; the battleroom cannot bind.");
            Toast.makeText(this, LocalizedText.ui("ui.notLoaded"), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        bind();
        // The engine's calls arrive through these, so the room is handed over before the dropdowns
        // are filled: a start announced while the screen is being built must not be missed.
        HostNetworkCallbacks.attach(this);

        LocalizedText.applyTo(findViewById(android.R.id.content));
        refresh();
        Log.i(TAG, "Battleroom open (host: " + network.isServer
            + ", proxy: " + network.proxyController + ").");
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A host that is public on the master list keeps its entry fresh, which is a timer the engine
        // runs only while the room is on screen.
        if (network != null) {
            try {
                network.startMasterServerTimer();
            } catch (Throwable t) {
                Log.w(TAG, "The server's master list entry cannot be kept fresh.", t);
            }
        }
        poll.removeCallbacks(refreshTask);
        poll.postDelayed(refreshTask, POLL_MILLIS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        poll.removeCallbacks(refreshTask);
        if (network != null) {
            try {
                network.stopMasterServerTimer();
            } catch (Throwable t) {
                Log.w(TAG, "The server's master list timer could not be stopped.", t);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        poll.removeCallbacks(refreshTask);
        HostNetworkCallbacks.detach(this);
        // The room closes either because the player left it or because the game started, and only the
        // first is a reason to walk away from the network: the second is the game it was set up for.
        if (isFinishing() && !starting) {
            leave();
        }
    }

    @Override
    public void onBackPressed() {
        // Leaving the room is leaving the game that was being set up, which the engine has to be told
        // about or the other players keep waiting for this one. It is asked about first, because a
        // chat room can also be left open behind the game.
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setTitle(LocalizedText.word("menus.ingame.multiplayerClose.title", "Close?"))
            .setNegativeButton(LocalizedText.word("menus.ingame.multiplayerClose.stayButton", "Stay"), null);
        if (network != null && network.singlePlayerServer) {
            dialog.setMessage(LocalizedText.ui("ui.chooseAction"));
            dialog.setPositiveButton(LocalizedText.word("menus.ingame.exitGame", "Exit"),
                (clicked, which) -> leaveAndClose());
        } else {
            dialog.setMessage(LocalizedText.word("menus.ingame.multiplayerClose.message",
                "A multiplayer game is in progress. What would you like to do?"));
            dialog.setPositiveButton(LocalizedText.word("menus.ingame.multiplayerClose.disconnectButton", "Disconnect"),
                (clicked, which) -> leaveAndClose());
            dialog.setNeutralButton(LocalizedText.word("menus.ingame.multiplayerClose.minimizeButton", "Minimize"),
                (clicked, which) -> {
                    // The room is left on screen behind the game: the network stays up, which is what
                    // the game's own minimize does by closing the screen without leaving it.
                    moveTaskToBack(true);
                });
        }
        dialog.show();
    }

    /** Leaves the network with the game's own reason and closes the room. */
    private void leaveAndClose() {
        try {
            if (network != null) {
                network.disconnect("exited");
            }
        } catch (Throwable t) {
            Log.w(TAG, "The network could not be left cleanly.", t);
        }
        finish();
    }

    /** Leaves whatever network the room was opened on, which is what closing it means. */
    private void leave() {
        if (network == null) {
            return;
        }
        try {
            if (network.networked) {
                network.disconnect("exited");
            }
        } catch (Throwable t) {
            Log.w(TAG, "The network could not be left cleanly.", t);
        }
    }

    // ------------------------------------------------------------------
    // The layout
    // ------------------------------------------------------------------

    private void bind() {
        statusInfo = findViewById(R.id.battleroom_status_info);
        gameSummary = findViewById(R.id.battleroom_game_summary);
        typeLayout = findViewById(R.id.battleroom_typeLayout);
        mapLayout = findViewById(R.id.battleroom_mapLayout);
        typeSpinner = findViewById(R.id.battleroom_type);
        mapSpinner = findViewById(R.id.battleroom_map);
        thumbnail = findViewById(R.id.battleroom_thumbnail);
        playerTable = findViewById(R.id.battleroom_playerTable);
        chatLog = findViewById(R.id.chatLog);
        chatText = findViewById(R.id.battleroom_text);

        // Bluetooth play is this host's own gap, the game's inline player list is superseded by the
        // table below, and readiness is the engine's own chat command rather than a checkbox: the
        // host starts when it is satisfied, which is what the game's own start button does.
        hide(R.id.battleroom_startBluetoothButton);
        hide(R.id.networkPlayerList);
        hide(R.id.battleroom_ready);

        wireTypeSpinner();
        wireMapSpinner();

        onclick(R.id.battleroom_startNetButton, this::startPressed);
        onclick(R.id.battleroom_addAI, this::addAI);
        onclick(R.id.battleroom_changeTeam, this::changeTeam);
        onclick(R.id.battleroom_otherGameOptions, this::showGameOptions);
        onclick(R.id.battleroom_send, this::sendChat);

        updateControlVisibility();
    }

    /**
     * The game type spinner.
     *
     * <p>The entries are the engine's own map types with the engine's own names, and the one that is
     * already set is the one selected, so a client sees the type the host chose. Choosing another one
     * refills the map list, because the type is which folder the list comes from.</p>
     */
    private void wireTypeSpinner() {
        if (typeSpinner == null) {
            return;
        }
        GameSetupMapType[] types = GameSetupMapType.values();
        List<String> names = new ArrayList<>();
        for (GameSetupMapType type : types) {
            names.add(type.getDisplayName());
        }
        filling = true;
        typeSpinner.setAdapter(adapter(names));
        typeSpinner.setSelection(indexOf(types, network.gameSetup == null ? null : network.gameSetup.mapType));
        filling = false;
        typeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (filling) {
                    return;
                }
                // The first fill is done below and this one is its consequence, so the map list is
                // refilled here rather than in the caller.
                updateMapDropdown(false);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    /** The map spinner. Choosing an entry is the host setting the game up, or asking the host to. */
    private void wireMapSpinner() {
        if (mapSpinner == null) {
            return;
        }
        // The game fills the list before it listens to it, so that filling it is not read as a choice.
        updateMapDropdown(true);
        mapSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!filling) {
                    mapChosen();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    // ------------------------------------------------------------------
    // The map list
    // ------------------------------------------------------------------

    /**
     * Fills the map dropdown for the chosen game type.
     *
     * <p>The type decides the folder, the folder is read through the engine's file layer, the list is
     * sorted, and each entry is shown by its name. Nothing happens when the type has not changed,
     * which is what keeps the list from being rebuilt for every redraw; the first call has to run
     * regardless, which is what the type that starts at nothing is for.</p>
     *
     * @param firstRun whether this is the fill the screen opens with, which also selects the map the
     *                 engine already holds
     */
    private void updateMapDropdown(boolean firstRun) {
        if (mapSpinner == null) {
            return;
        }
        int type = typeSpinner == null ? TYPE_SKIRMISH : typeSpinner.getSelectedItemPosition();
        if (type < 0) {
            type = TYPE_SKIRMISH;
        }
        // A client that has not been given control of the server cannot choose the game type, so its
        // list is the skirmish one whatever the spinner shows.
        if (!network.isServer && !network.proxyController) {
            type = TYPE_SKIRMISH;
        }
        if (filledForType == type) {
            return;
        }
        filledForType = type;

        mapEntries = listMaps(type);
        Arrays.sort(mapEntries);

        List<String> shown = new ArrayList<>();
        for (String entry : mapEntries) {
            String name = MapNames.of(entry);
            shown.add(name == null ? entry : name);
        }
        filling = true;
        mapSpinner.setAdapter(adapter(shown));
        filling = false;

        if (firstRun) {
            selectMapFromEngine();
        }
        gameInfoChanged();
    }

    /** The maps one game type offers, as the file layer names them, with the folder in front. */
    private String[] listMaps(int type) {
        String folder = folderOf(type);
        String[] listed = null;
        try {
            listed = FileLoader.listDir(folder, true);
            if (type == TYPE_CUSTOM) {
                // The maps a mod adds are not files in the folder, so the engine's own mod list is
                // asked for them and its answer is added to the folder's.
                ModManager mods = engine == null ? null : engine.modManager;
                if (mods != null) {
                    listed = mods.addExtraMapsForPath(listed, folder);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "The maps in " + folder + " could not be listed.", t);
        }
        if (listed == null || listed.length == 0) {
            // The file layer says what went wrong and the status line carries a reason; an empty
            // dropdown with nothing said about it reads as a folder that holds no maps.
            String reason = FileLoader.takeErrorMessage();
            setStatus(reason == null || reason.isEmpty()
                ? "No maps were found in " + folder + "." : reason);
            return new String[0];
        }
        List<String> paths = new ArrayList<>();
        for (String entry : listed) {
            String path = mapPathOf(entry, folder, type);
            if (path != null) {
                paths.add(path);
            }
        }
        return paths.toArray(new String[0]);
    }

    private static String folderOf(int type) {
        if (type == TYPE_CUSTOM) {
            return CUSTOM_FOLDER;
        }
        if (type == TYPE_SAVED) {
            return SAVES_FOLDER;
        }
        return SKIRMISH_FOLDER;
    }

    /**
     * The path the engine is given for one listed entry, or {@code null} when the entry is not a map.
     *
     * <p>The file layer names a folder's files in different ways depending on which folder it is, and
     * the path the engine loads has to be the whole one, so a name that is not already in the folder
     * is put in it. A skirmish map is drawn beside its thumbnail, and the thumbnail is a file in the
     * same folder, which is why the two folders of maps keep only the maps.</p>
     */
    private static String mapPathOf(String entry, String folder, int type) {
        if (entry == null || entry.isEmpty()) {
            return null;
        }
        // The engine's file layer understands these two tags and resolves them itself, so they are
        // passed through untouched; anything else is a file in the folder that was listed.
        if (entry.startsWith("MOD|") || entry.startsWith("NEW_PATH|")) {
            return entry;
        }
        if (entry.endsWith("_map.png") || entry.endsWith("_map.jpg")) {
            return null;
        }
        if (type != TYPE_SAVED && !entry.endsWith(".tmx")) {
            return null;
        }
        if (entry.startsWith(folder) || entry.startsWith(SKIRMISH_FOLDER)
            || entry.startsWith(CUSTOM_FOLDER) || entry.startsWith(SAVES_FOLDER)
            || entry.indexOf('/') >= 0) {
            return entry;
        }
        return folder.endsWith("/") ? folder + entry : folder + "/" + entry;
    }

    /**
     * Moves the dropdown onto the map the engine already holds.
     *
     * <p>The server owns the map, so a client's dropdown has to show what the server chose rather than
     * what the client last clicked. The two are matched on their last path segments in lower case,
     * because the server and this screen name the map from different places: one from the folder that
     * was listed here, the other from whatever the client was told.</p>
     */
    private void selectMapFromEngine() {
        if (network == null || network.gameSetup == null || mapSpinner == null) {
            return;
        }
        String wanted = network.gameSetup.mapName;
        if (wanted == null || wanted.isEmpty()) {
            return;
        }
        String key = comparable(wanted);
        for (int i = 0; i < mapEntries.length; i++) {
            if (("/" + key).endsWith("/" + comparable(mapEntries[i]))) {
                filling = true;
                mapSpinner.setSelection(i);
                filling = false;
                return;
            }
        }
        Log.d(TAG, "The map the engine holds is not in the dropdown: " + wanted);
    }

    /** A path reduced to what two paths that name the same file have in common. */
    private static String comparable(String path) {
        if (path == null) {
            return "";
        }
        String name = path.replace('\\', '/').toLowerCase(Locale.US);
        if (name.endsWith(".tmx")) {
            name = name.substring(0, name.length() - 4);
        }
        return name;
    }

    /**
     * Acts on the host or the client choosing a map.
     *
     * <p>The host writes the choice into the game setup through the engine's own changeable setup,
     * which announces it to the clients. A client that has been given control of the server does the
     * same through the same call, which the engine turns into the game's own commands to the server.
     * Any other client is only watching, and the selection is put back to what the server holds so
     * the dropdown does not show a choice that was refused.</p>
     */
    private void mapChosen() {
        if (network.isServer) {
            gameInfoChanged();
            return;
        }
        GameSetup editable = network.getChangeableSetup();
        if (editable == null) {
            selectMapFromEngine();
            return;
        }
        String bareName = bareNameOf(selectedMap());
        if (bareName != null && bareName.equalsIgnoreCase(editable.mapName)) {
            return;
        }
        editable.mapName = bareName == null ? NO_MAP : bareName;
        try {
            network.applyChangedSetup(editable);
        } catch (Throwable t) {
            Log.e(TAG, "The map change could not be applied.", t);
        }
        updateThumbnail();
    }

    /** @return the map the dropdown is on, as the file layer named it, or {@code null} for none */
    private String selectedMap() {
        if (mapSpinner == null) {
            return null;
        }
        int position = mapSpinner.getSelectedItemPosition();
        if (position < 0 || position >= mapEntries.length) {
            return null;
        }
        return mapEntries[position];
    }

    /**
     * The last segment of a map path, which is what the engine stores as the map's name.
     *
     * <p>The dropdown holds whole paths so the file layer can be asked for the file, while the engine
     * keeps only the bare name in the game setup and derives the path from the type. A path with no
     * separator is already the name; a tagged path is left as it is, because the engine resolves those
     * tags itself.</p>
     */
    private static String bareNameOf(String path) {
        if (path == null || path.isEmpty() || NO_MAP.equals(path)) {
            return null;
        }
        if (path.startsWith("MOD|") || path.startsWith("NEW_PATH|")) {
            return path;
        }
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /**
     * Writes the screen's choices into the engine and tells everyone.
     *
     * <p>This is the engine's own "the game information changed": the map and the type go into the
     * changeable setup the engine hands out, and {@code applyChangedSetup} does the rest, which is
     * the same call whether this device is the server or a client holding the host's controls. On a
     * server the change is written and announced, on a client it is asked for with the game's own
     * commands, and everybody else has no setup to change. A public game's entry on the master list
     * follows the map, and the thumbnail follows the map.</p>
     */
    private void gameInfoChanged() {
        GameSetup editable = network.getChangeableSetup();
        if (editable != null) {
            String bareName = bareNameOf(selectedMap());
            editable.mapName = bareName == null ? NO_MAP : bareName;
            if (network.isServer && typeSpinner != null) {
                GameSetupMapType[] types = GameSetupMapType.values();
                int position = typeSpinner.getSelectedItemPosition();
                if (position >= 0 && position < types.length) {
                    editable.mapType = types[position];
                }
            }
            try {
                network.applyChangedSetup(editable);
            } catch (Throwable t) {
                Log.e(TAG, "The game setup could not be changed.", t);
            }
        }
        if (network.isServer && network.serverVisible) {
            try {
                MasterServerConnector.startUpdateServerRequest();
            } catch (Throwable t) {
                Log.w(TAG, "The server's entry on the master list could not be refreshed.", t);
            }
        }
        updateThumbnail();
    }

    /**
     * Shows and hides what the state of the game decides.
     *
     * <p>The host chooses the type and the map and starts the game; a client that has been given the
     * server's controls chooses the map but not the type; any other client only watches. The game
     * summary stands in for the map and type controls where they are hidden, because a client still
     * needs to see what it is about to play.</p>
     */
    private void updateControlVisibility() {
        boolean host = network.isServer;
        boolean proxy = network.proxyController;

        if (!host && !proxy) {
            show(mapLayout, View.GONE);
            show(typeLayout, View.GONE);
            boolean locked = network.gameSetup != null && network.gameSetup.teamsLocked;
            show(findViewById(R.id.battleroom_changeTeam), locked ? View.GONE : View.VISIBLE);
            show(findViewById(R.id.battleroom_otherGameOptions), View.GONE);
            show(findViewById(R.id.battleroom_addAI), View.GONE);
            show(findViewById(R.id.battleroom_startNetButton), View.GONE);
            show(gameSummary, View.VISIBLE);
        } else {
            show(mapLayout, View.VISIBLE);
            show(typeLayout, proxy ? View.GONE : View.VISIBLE);
            show(findViewById(R.id.battleroom_changeTeam), View.GONE);
            show(findViewById(R.id.battleroom_otherGameOptions), View.VISIBLE);
            show(findViewById(R.id.battleroom_addAI), View.VISIBLE);
            show(findViewById(R.id.battleroom_startNetButton), View.VISIBLE);
            show(gameSummary, View.GONE);
        }

        // Only the host sets the game type; whoever may set the map is who the engine allows.
        if (typeSpinner != null) {
            typeSpinner.setEnabled(host);
        }
        if (mapSpinner != null) {
            mapSpinner.setEnabled(host || proxy);
        }

        // A game the player hosts alone has nobody to talk to, so the chat panel is not shown for it.
        show(findViewById(R.id.chatLogWrap),
            network.singlePlayerServer ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------------
    // Drawing the room
    // ------------------------------------------------------------------

    /**
     * Redraws everything the engine may have changed. Safe to call from any thread.
     *
     * <p>This is the room's answer to the engine's own update call, which redraws the server info,
     * the player list and what the state of the game decides to show; the poll covers the same
     * ground because the engine's call reaches the game's own screen, which this host does not
     * open.</p>
     */
    public void refresh() {
        if (isFinishing() || network == null) {
            return;
        }
        runOnUiThread(() -> {
            updateStatus();
            syncMapSelection();
            updatePlayers();
            updateChat();
            updateControlVisibility();
            updateThumbnail();
        });
    }

    /**
     * The game's own summary of the room, in the two places the game's own screen puts it.
     *
     * <p>The text is the engine's {@code getGameInfoText}, the same build-up the game's own screen
     * shows: the addresses and port the server is reachable on, whether it is open to the internet,
     * and the game's mode, map, credits, fog, starting units, income and restrictions. The host
     * reads it in the status line above the room and everybody else in the summary beside the map,
     * which is the split the game's own screen makes; a client's status line is empty, and a client
     * that has been given the host's controls reads neither, because the room then shows the
     * controls instead.</p>
     */
    private void updateStatus() {
        String info;
        try {
            info = network.getGameInfoText();
        } catch (Throwable t) {
            Log.w(TAG, "The game info text could not be read.", t);
            return;
        }
        if (statusInfo != null) {
            statusInfo.setText(network.isServer ? info : "");
        }
        if (gameSummary != null) {
            gameSummary.setText(network.isServer ? "" : info);
        }
    }

    /**
     * Moves the map dropdown onto the map the engine holds.
     *
     * <p>The server owns the map, so a client's dropdown has to show what the server chose rather
     * than what the client last clicked. A client that has just been handed the host's controls gets
     * the same move once, which is the game's own arrangement: the player was watching the server's
     * choices up to that moment, and the first thing the controls should show is the game as it
     * stands. After that the dropdown is theirs to move.</p>
     */
    private void syncMapSelection() {
        if (network.isServer) {
            return;
        }
        if (!network.proxyController || !hadProxyControl) {
            hadProxyControl = network.proxyController;
            selectMapFromEngine();
        }
    }

    /**
     * Shows the map the game will load, which the game's own screen shows beside the map dropdown.
     *
     * <p>The map's path comes from the engine, which builds it out of the game setup the same way
     * for the host and for a client, and the image is the one the map keeps beside itself under a
     * matching name, read through the engine's file layer so the maps a mod adds are found too. A
     * thumbnail larger than the game's own limit is refused, and so is a map without one.</p>
     */
    private void updateThumbnail() {
        if (thumbnail == null) {
            return;
        }
        thumbnail.setVisibility(View.GONE);
        String path;
        try {
            path = network.getNetworkMapPath();
        } catch (Throwable t) {
            Log.w(TAG, "The map path could not be read.", t);
            return;
        }
        if (path == null || path.isEmpty()) {
            return;
        }
        if (path.equals(thumbnailPath)) {
            // The same map is already loaded behind the hidden image, so it only has to be shown.
            thumbnail.setVisibility(View.VISIBLE);
            return;
        }
        InputStream stream = null;
        try {
            String file = path.replace(".tmx", "") + "_map.png";
            if (!FileLoader.fileExists(file)) {
                return;
            }
            stream = FileLoader.openByPath(file);
            Bitmap image = stream == null ? null : BitmapFactory.decodeStream(stream);
            if (image == null) {
                return;
            }
            if (image.getWidth() > 500 || image.getHeight() > 500) {
                // The game's own limit, because a full map image is not what a thumbnail is for.
                image.recycle();
                return;
            }
            thumbnailPath = path;
            thumbnail.setImageBitmap(image);
            thumbnail.setVisibility(View.VISIBLE);
        } catch (Throwable t) {
            Log.w(TAG, "The thumbnail of " + path + " could not be read.", t);
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Throwable ignored) {
                    // A thumbnail that will not close is not worth failing the room over.
                }
            }
        }
    }

    /**
     * The player table, one row per player: name, spawn point, team and ping.
     *
     * <p>The rows are the table's children that the layout did not name, which is how the game's own
     * screen tells its rows from the heading and how the heading survives a redraw. Each row shows the
     * player's colour on the name and their team's colour on the spawn point and the team column, and
     * the row of the player at this device is bold and can be tapped to change teams.</p>
     */
    private void updatePlayers() {
        if (playerTable == null) {
            return;
        }
        for (int i = playerTable.getChildCount() - 1; i >= 0; i--) {
            View child = playerTable.getChildAt(i);
            if (child.getId() == View.NO_ID) {
                playerTable.removeView(child);
            }
        }
        markAllActiveCellsDeleted();

        for (Team team : teams()) {
            if (team == null) {
                continue;
            }
            TableRow row = new TableRow(this);
            row.setBackgroundResource(android.R.drawable.list_selector_background);

            TextView name = addCell(row, nameOf(team), 1);
            name.setTypeface(null, team.isLocalPlayerTeam() ? Typeface.BOLD : Typeface.NORMAL);
            // A player who has been given a colour is shown in it, and everyone else in white.
            name.setTextColor(team.colorOverride == null
                ? Color.WHITE : Team.lookupTeamColor(team.colorOverride));

            TextView spawn = addCell(row, spawnOf(team), 2);
            spawn.setTypeface(null, Typeface.BOLD);
            // A spectator's spawn point is coloured as the spectator colour, and everyone else's by
            // the side their spawn point number puts them on.
            spawn.setTextColor(team.allyTeam == -3
                ? Team.lookupTeamColor(-3) : Team.getAllyTeamColor(team.teamId));

            TextView teamCell = addCell(row, Team.getLetterForTeamId(team.allyTeam), 3);
            teamCell.setTypeface(null, Typeface.BOLD);
            teamCell.setTextColor(Team.lookupTeamColor(team.allyTeam));

            addCell(row, team.getPingStatusText(), 4);

            row.setClickable(true);
            row.setOnClickListener(clicked -> showPlayerPopup(team));
            playerTable.addView(row);
            ViewGroup.LayoutParams params = row.getLayoutParams();
            params.width = ViewGroup.LayoutParams.MATCH_PARENT;
            row.setLayoutParams(params);
        }
    }

    /**
     * Where a player will start.
     *
     * <p>The spawn points are numbered from one and a spectator has none. A player who has been given
     * more or fewer starting units than the game is set to is told which, because the spawn point
     * alone does not say what they will begin with.</p>
     */
    private String spawnOf(Team team) {
        if (team.isSpectator()) {
            return "S";
        }
        String slot = Integer.toString(team.getDisplaySlot() + 1);
        Integer override = team.startingUnitsOverride;
        int gameUnits = network.gameSetup == null ? 0 : network.gameSetup.startingUnits;
        if (override == null || override == gameUnits) {
            return slot;
        }
        try {
            return slot + " - " + network.getStartingUnitsName(override);
        } catch (Throwable t) {
            Log.w(TAG, "The starting unit option " + override + " has no name.", t);
            return slot;
        }
    }

    /** Takes one cell from the spare pool, or makes one, and puts it in a row at a column. */
    private TextView addCell(TableRow row, String text, int column) {
        TextView view = null;
        for (int i = spareCells.size() - 1; i >= 0; i--) {
            Cell spare = spareCells.get(i);
            if (spare.column == column) {
                view = spare.view;
                spareCells.remove(i);
                break;
            }
        }
        if (view == null) {
            view = new TextView(row.getContext());
            view.setBackgroundResource(R.drawable.cell_shape);
            view.setTextAppearance(this, android.R.style.TextAppearance_Medium);
            int padding = dip(5);
            view.setPadding(padding, padding, padding, padding);
        }
        Cell cell = new Cell();
        cell.column = column;
        cell.row = row;
        cell.view = view;
        activeCells.add(cell);

        view.setText(text);
        row.addView(view);
        return view;
    }

    /** Takes every cell back out of its row, so the next redraw can reuse it. */
    private void markAllActiveCellsDeleted() {
        for (int i = activeCells.size() - 1; i >= 0; i--) {
            Cell cell = activeCells.get(i);
            cell.row.removeView(cell.view);
            cell.row = null;
        }
        spareCells.addAll(activeCells);
        activeCells.clear();
    }

    /** One cell of the player table, kept so that the next redraw reuses its view. */
    private static final class Cell {
        int column;
        TableRow row;
        TextView view;
    }

    private void updateChat() {
        if (chatLog == null || network.chatLog == null) {
            return;
        }
        try {
            CharSequence rendered = network.chatLog.getHtmlSpanned(true);
            if (rendered != null) {
                chatLog.setText(rendered);
            }
        } catch (Throwable t) {
            // The log is the engine's; a log that will not render must not take the room with it.
            Log.w(TAG, "The chat log could not be rendered.", t);
        }
    }

    // ------------------------------------------------------------------
    // Acting on the room
    // ------------------------------------------------------------------

    /**
     * The start button: the host starts, and a client that holds the host's controls asks for it.
     *
     * <p>The engine is what decides whether a game can start, because it is what knows which clients
     * are ready. Its own call answers whether the start was accepted and announces the result through
     * {@link HostNetworkCallbacks}, which is where the map is loaded and the play screen opened. The
     * game's own screen asks with the start command when it is not the server itself, which reaches
     * the same sequence on the other end; any other client has no start to press, because the button
     * is hidden for it.</p>
     *
     * <p>Before the engine is asked, the map path it will read is written from the game setup, because
     * that is what the game's own script does: {@code gameSetup.mapName} holds the bare name and
     * {@code networkEngine.mapName} holds the path the engine loads, and the two are kept apart so the
     * dropdown can show one while the engine reads the other. A start without the path leaves the
     * engine writing a null map name to the network, which is the crash this guards.</p>
     */
    private void startPressed() {
        if (network.isServer) {
            try {
                String bareName = network.gameSetup == null ? null : network.gameSetup.mapName;
                if (bareName == null || bareName.isEmpty() || NO_MAP.equals(bareName)) {
                    Toast.makeText(this, LocalizedText.ui("ui.noMap"), Toast.LENGTH_SHORT).show();
                    return;
                }
                // The path the engine loads, derived from the type the way the game's own script derives
                // it. A type the engine does not carry a path for is a start that cannot be made.
                if (network.gameSetup.mapType == GameSetupMapType.skirmishMap) {
                    network.mapName = SKIRMISH_FOLDER + "/" + bareName;
                } else if (network.gameSetup.mapType == GameSetupMapType.customMap) {
                    network.mapName = CUSTOM_FOLDER + "/" + bareName;
                } else if (network.gameSetup.mapType == GameSetupMapType.savedGame) {
                    network.mapName = null;
                } else {
                    Toast.makeText(this, LocalizedText.ui("ui.noMapType"), Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!network.startGame()) {
                    Toast.makeText(this, LocalizedText.ui("ui.waitingPlayers"), Toast.LENGTH_SHORT).show();
                }
            } catch (Throwable t) {
                Log.e(TAG, "The game could not be started.", t);
                Toast.makeText(this, LocalizedText.ui("ui.startFailedPrefix") + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else if (network.proxyController) {
            try {
                network.sendChatCommand("-start");
            } catch (Throwable t) {
                Log.e(TAG, "The start could not be asked for.", t);
            }
        }
        refresh();
    }

    /** Adds a computer player, which the engine replicates to the clients. */
    private void addAI() {
        if (network.isServer) {
            try {
                network.addAIToGame();
            } catch (Throwable t) {
                Log.e(TAG, "A computer player could not be added.", t);
                Toast.makeText(this, LocalizedText.ui("ui.addAiFailed"), Toast.LENGTH_SHORT).show();
                return;
            }
        } else if (network.proxyController) {
            // The game's own arrangement: the host's controls ask with the game's own command.
            try {
                network.sendChatCommand("-addai");
            } catch (Throwable t) {
                Log.e(TAG, "The computer player could not be asked for.", t);
            }
        } else {
            return;
        }
        refresh();
    }

    /** Moves the local player to another team, which the engine sends to the server. */
    private void changeTeam() {
        final Team local = network.localTeam;
        if (local == null) {
            Toast.makeText(this, LocalizedText.ui("ui.noTeam"), Toast.LENGTH_SHORT).show();
            return;
        }
        final List<Team> teams = teams();
        final List<String> names = new ArrayList<>();
        for (Team team : teams) {
            names.add(nameOf(team) + "  (spawn " + team.displaySlot + ")");
        }
        if (names.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle(LocalizedText.ui("ui.changeTeam"))
            .setItems(names.toArray(new String[0]), (dialog, which) -> {
                try {
                    network.sendSetTeamCommand(local, teams.get(which).teamId);
                } catch (Throwable t) {
                    Log.e(TAG, "The team could not be changed.", t);
                }
                refresh();
            })
            .setNegativeButton(LocalizedText.ui("common.cancel"), null)
            .show();
    }

    /** Sends the chat line the player typed. */
    private void sendChat() {
        if (chatText == null) {
            return;
        }
        String text = chatText.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        try {
            network.sendChatMessage(text);
        } catch (Throwable t) {
            Log.e(TAG, "The chat message could not be sent.", t);
            return;
        }
        chatText.setText("");
        refresh();
    }

    // ------------------------------------------------------------------
    // One player's settings
    // ------------------------------------------------------------------

    /**
     * The dialog that edits one player: where they start, who they are allied with, how hard their
     * computer is, what they begin with and what colour they are.
     *
     * <p>It is the game's own dialog, over the game's own layout, with the game's own dropdowns. Each
     * dropdown holds values rather than positions, because the values are what the engine is given:
     * the spawn point and the side are numbers, and a dropdown that means "leave this as the game
     * has it" carries the value -99, which is what the engine reads back as no override at all.</p>
     *
     * <p>What the dialog offers is what the player's role allows. Only the host writes the
     * per-player overrides, so the whole override section is hidden from everyone else. A computer
     * player has a difficulty and a human one does not. Handing a player over is the one thing a
     * client that was given the host's controls can do, so the button is theirs alone, and a player
     * cannot be kicked from their own row.</p>
     */
    private void showPlayerPopup(final Team team) {
        if (network == null || team == null) {
            return;
        }
        final boolean ownRow = network.localTeam == team;
        // The host sets the game up; everyone else may only edit their own row, and only while the
        // teams have not been locked, which is what locking them means.
        if (!network.canChangeGameSetup()
            && !(ownRow && network.gameSetup != null && !network.gameSetup.teamsLocked)) {
            return;
        }

        View content = LayoutInflater.from(this)
            .inflate(R.layout.multiplayer_battleroom_playerpopup, null);
        final Dialog dialog = new Dialog(this);
        dialog.setContentView(content);
        dialog.setTitle(LocalizedText.ui("ui.playerPrefix") + nameOf(team));
        // The dialog has a field at its top, so the window pans rather than resizing the layout.
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);

        final Spinner spawn = content.findViewById(R.id.teamId);
        final Spinner ally = content.findViewById(R.id.teamAllyGroup);
        final Spinner difficulty = content.findViewById(R.id.aiDifficulty);
        final Spinner units = content.findViewById(R.id.startingUnits);
        final Spinner color = content.findViewById(R.id.playerColor);

        setupSpawnPositionDropdown(spawn);
        choose(spawn, team.isSpectator() ? "-3" : Integer.toString(team.teamId));

        setupTeamAllyDropdown(ally);
        // A player whose side is the one their spawn point puts them on is on the automatic setting,
        // which is where the host left it.
        choose(ally, team.teamId % 2 == team.allyTeam ? "0" : Integer.toString(team.allyTeam + 1));

        setupAIDifficultyDropdown(difficulty);
        choose(difficulty, team.aiDifficultyOverride == null
            ? "-99" : Integer.toString(team.aiDifficultyOverride));

        setupStartingUnitsDropdown(units);
        choose(units, team.startingUnitsOverride == null
            ? "-99" : Integer.toString(team.startingUnitsOverride));

        setupPlayerColorDropdown(color, team);
        choose(color, team.colorOverride == null ? "-99" : Integer.toString(team.colorOverride));

        // The overrides are the host's to write, and a difficulty only means something for a computer.
        show(content.findViewById(R.id.playerOverridesSection),
            network.isServer ? View.VISIBLE : View.GONE);
        show(content.findViewById(R.id.aiDifficultyWrap),
            team.isAI ? View.VISIBLE : View.GONE);
        // The team dropdown follows the spawn point where the host has not chosen otherwise, which is
        // only a thing a client with the host's controls may do.
        show(content.findViewById(R.id.teamAllyGroupWrap),
            !network.canChangeGameSetup() && network.gameSetup != null
                && network.gameSetup.teamsLocked ? View.GONE : View.VISIBLE);

        View give = content.findViewById(R.id.battleroom_playerpopup_give);
        show(give, network.proxyController && !ownRow ? View.VISIBLE : View.GONE);
        give.setOnClickListener(clicked -> {
            dialog.dismiss();
            confirmGive(team);
        });

        View kick = content.findViewById(R.id.battleroom_playerpopup_kick);
        show(kick, ownRow ? View.GONE : View.VISIBLE);
        kick.setOnClickListener(clicked -> {
            dialog.dismiss();
            confirmKick(team);
        });

        content.findViewById(R.id.battleroom_playerpopup_apply).setOnClickListener(clicked -> {
            applyPlayerPopup(team, spawn, ally, difficulty, units, color);
            dialog.dismiss();
        });
        content.findViewById(R.id.battleroom_playerpopup_cancel)
            .setOnClickListener(clicked -> dialog.dismiss());

        LocalizedText.applyTo(content);
        dialog.show();
    }

    /**
     * Where a player starts: every spawn point, grouped by the side it belongs to, and the
     * spectator entry.
     *
     * <p>The labels are the numbers the player sees in the game with the side spelled out, while the
     * values are the slot numbers the engine is given, which is why the two differ by one.</p>
     */
    private void setupSpawnPositionDropdown(Spinner spinner) {
        List<Option> options = new ArrayList<>();
        for (int side = 0; side <= 1; side++) {
            String suffix = " - Side " + (side == 0 ? "A" : "B");
            for (int slot = side; slot <= 9; slot += 2) {
                options.add(new Option(Integer.toString(slot), (slot + 1) + suffix,
                    Team.lookupTeamColor(side)));
            }
        }
        options.add(new Option("-3", "Spectator", null));
        spinner.setAdapter(new OptionAdapter(options));
    }

    /**
     * Who a player is allied with.
     *
     * <p>The first entry leaves it to the spawn point, which is what the engine does when it is not
     * told otherwise. The rest are the sides, each in the colour of that side.</p>
     */
    private void setupTeamAllyDropdown(Spinner spinner) {
        List<Option> options = new ArrayList<>();
        options.add(new Option("0", "auto", null));
        for (int side = 0; side <= 9; side++) {
            options.add(new Option(Integer.toString(side + 1),
                "Side " + Team.getLetterForTeamId(side), Team.lookupTeamColor(side)));
        }
        spinner.setAdapter(new OptionAdapter(options));
    }

    /** How hard a computer player is, plus the entry that leaves it as the game has it. */
    private void setupAIDifficultyDropdown(Spinner spinner) {
        List<Option> options = new ArrayList<>();
        options.add(new Option("-99", LocalizedText.word("menus.settings.option.default", "Default"), null));
        for (int level = -2; level <= 3; level++) {
            options.add(new Option(Integer.toString(level),
                LocalizedText.word("menus.settings.option.ai." + level, Integer.toString(level)), null));
        }
        spinner.setAdapter(new OptionAdapter(options));
    }

    /** What a player begins with, taken from the engine's own list of what a game may start with. */
    private void setupStartingUnitsDropdown(Spinner spinner) {
        List<Option> options = new ArrayList<>();
        options.add(new Option("-99", LocalizedText.word("menus.settings.option.default", "Default"), null));
        try {
            for (Object entry : network.getStartingUnitOptions()) {
                if (entry instanceof Integer) {
                    int value = (Integer) entry;
                    options.add(new Option(Integer.toString(value),
                        network.getStartingUnitsName(value), null));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "The starting unit options could not be listed.", t);
        }
        spinner.setAdapter(new OptionAdapter(options));
    }

    /**
     * The colours a player may be given, with the ones another player already has marked as taken.
     *
     * <p>A colour that is taken cannot be chosen, so it is shown greyed out and behind the value that
     * means no choice at all: the dropdown falls back to the game's own colour for that player.</p>
     */
    private void setupPlayerColorDropdown(Spinner spinner, Team team) {
        List<Option> options = new ArrayList<>();
        options.add(new Option("-99", LocalizedText.word("menus.settings.option.default", "Default"), null));
        for (int value = 0; value <= 9; value++) {
            boolean taken = false;
            try {
                taken = network.isSlotUsedByOtherTeam(value, team);
            } catch (Throwable t) {
                Log.w(TAG, "Whether colour " + value + " is taken could not be read.", t);
            }
            String label = Team.lookupTeamColorName(value);
            if (label == null) {
                label = Integer.toString(value);
            } else if (label.isEmpty()) {
                label = label.toUpperCase(Locale.US);
            } else {
                label = label.substring(0, 1).toUpperCase(Locale.US)
                    + label.substring(1).toLowerCase(Locale.US);
            }
            if (taken) {
                // The grey the engine paints a colour that cannot be had, and the value that leaves
                // the player in the game's own colour rather than in one that is spoken for.
                options.add(new Option("-99", label + " (used)", Team.lookupTeamColor(TAKEN_COLOR)));
            } else {
                options.add(new Option(Integer.toString(value), label, Team.lookupTeamColor(value)));
            }
        }
        spinner.setAdapter(new OptionAdapter(options));
    }

    /** The colour the engine paints an option that is spoken for. */
    private static final int TAKEN_COLOR = -7829368;

    /**
     * Writes the dialog's choices back to the engine.
     *
     * <p>Every change goes through the engine's own calls rather than through the fields, because
     * those calls are what the other players are told about: moving a player sends the move to the
     * server when this device is only a client, and the engine decides what that means. A client that
     * was given the host's controls asks for the change with the game's own command; any other client
     * may only change itself, and only its own row.</p>
     *
     * <p>The spawn point and the side are decided together, because the side may follow the spawn
     * point: choosing the automatic side means the side the spawn point belongs to, which is what the
     * engine records as the slot deciding it.</p>
     */
    private void applyPlayerPopup(Team team, Spinner spawn, Spinner ally, Spinner difficulty,
        Spinner units, Spinner color) {
        try {
            boolean mayChangeSide = false;

            int slot = chosen(spawn, 0);
            boolean spectator = slot == -3;
            if (!spectator) {
                slot = Math.max(0, Math.min(Team.maxTeamId - 1, slot));
            }

            int side;
            boolean sideFollowsSlot;
            if (spectator) {
                side = -3;
                sideFollowsSlot = true;
                team.PENDING_flag_u = false;
            } else if (chosen(ally, 0) == 0) {
                side = slot % 2;
                sideFollowsSlot = true;
                team.PENDING_flag_u = false;
            } else {
                side = chosen(ally, 0) - 1;
                sideFollowsSlot = false;
                team.PENDING_flag_u = true;
            }

            if (team.allyTeam != side) {
                if (network.isServer || network.proxyController || ownRow(team)) {
                    mayChangeSide = true;
                } else {
                    Log.d(TAG, "A player row was clicked by someone who may not change it.");
                }
            }

            if (team.teamId != slot) {
                if (network.isServer) {
                    mayChangeSide = false;
                    network.moveTeamToSlot(team, slot);
                    team.allyTeam = side;
                } else if (network.proxyController || ownRow(team)) {
                    mayChangeSide = false;
                    // The engine turns this into the game's own move command, which is how a client
                    // asks for a move rather than performing one.
                    network.sendMoveTeamCommand(team, slot,
                        sideFollowsSlot ? Integer.valueOf(-1) : Integer.valueOf(side));
                } else {
                    Log.d(TAG, "A player row was clicked by someone who may not move it.");
                }
            }

            if (team.isAI) {
                Integer level = override(chosen(difficulty, -99));
                if (!equal(team.aiDifficultyOverride, level) && network.isServer) {
                    team.aiDifficultyOverride = level;
                }
            }
            Integer startingUnits = override(chosen(units, -99));
            if (!equal(team.startingUnitsOverride, startingUnits) && network.isServer) {
                team.startingUnitsOverride = startingUnits;
            }
            Integer chosenColor = override(chosen(color, -99));
            if (!equal(team.colorOverride, chosenColor) && network.isServer) {
                team.colorOverride = chosenColor;
            }

            if (mayChangeSide) {
                if (network.isServer) {
                    team.allyTeam = side;
                } else {
                    // A side that follows the spawn point is asked for as "no side", which is what
                    // the engine reads as the slot deciding it.
                    network.sendSetTeamCommand(team, sideFollowsSlot ? -1 : side);
                }
            }

            network.updateAIDifficulty();
            network.assignTeamDisplaySlots();
            refresh();
        } catch (Throwable t) {
            Log.e(TAG, "The player's settings could not be applied.", t);
            Toast.makeText(this, LocalizedText.ui("ui.applyFailedPrefix") + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** Asks before a player is taken off the server, which is what the original asks before it. */
    private void confirmKick(final Team team) {
        Runnable kick = () -> {
            try {
                network.kickTeamAndAttachedPlayer(team);
            } catch (Throwable t) {
                Log.e(TAG, "The player could not be kicked.", t);
            }
            refresh();
        };
        if (team.isAI) {
            kick.run();
            return;
        }
        new AlertDialog.Builder(this)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setTitle(LocalizedText.ui("ui.kickPrompt"))
            .setMessage(LocalizedText.ui("ui.kickConfirmPrefix") + nameOf(team) + "?")
            .setPositiveButton(LocalizedText.ui("ui.kick"), (clicked, which) -> kick.run())
            .setNegativeButton(LocalizedText.ui("common.cancel"), null)
            .show();
    }

    /**
     * Asks before control of a player is handed over, and hands it over with the game's own command.
     *
     * <p>The command is the engine's, because a client cannot write to the server's player list
     * itself; this is the same path the game's own script takes.</p>
     */
    private void confirmGive(final Team team) {
        new AlertDialog.Builder(this)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setTitle(LocalizedText.ui("ui.giveControlPrompt"))
            .setMessage(LocalizedText.ui("ui.giveConfirmPrefix")
                + nameOf(team) + "?")
            .setPositiveButton(LocalizedText.ui("ui.give"), (clicked, which) -> {
                if (!network.proxyController) {
                    Log.d(TAG, "A player was offered, but this device does not hold the host's controls.");
                    return;
                }
                try {
                    network.sendChatCommand("-give " + (team.teamId + 1));
                } catch (Throwable t) {
                    Log.e(TAG, "Control of the player could not be transferred.", t);
                }
                refresh();
            })
            .setNegativeButton(LocalizedText.ui("common.cancel"), null)
            .show();
    }

    /** @return whether the player is the one this device is playing */
    private boolean ownRow(Team team) {
        return network.localTeam == team;
    }

    /** @return the value of the selected entry, or {@code fallback} when there is none */
    private static int chosen(Spinner spinner, int fallback) {
        if (spinner == null) {
            return fallback;
        }
        Object entry = spinner.getSelectedItem();
        if (!(entry instanceof Option)) {
            return fallback;
        }
        try {
            return Integer.parseInt(((Option) entry).value);
        } catch (Throwable notANumber) {
            return fallback;
        }
    }

    /** Puts a dropdown on the entry with a value, which is how the current setting is shown. */
    private static void choose(Spinner spinner, String value) {
        if (spinner == null) {
            return;
        }
        SpinnerAdapter entries = spinner.getAdapter();
        int count = entries == null ? 0 : entries.getCount();
        for (int i = 0; i < count; i++) {
            Object entry = entries.getItem(i);
            if (entry instanceof Option && value.equals(((Option) entry).value)) {
                spinner.setSelection(i);
                return;
            }
        }
        Log.d(TAG, "No dropdown entry holds the value " + value + ".");
    }

    /** @return the value the engine reads as no override at all, as {@code null} */
    private static Integer override(int value) {
        return value == -99 ? null : Integer.valueOf(value);
    }

    private static boolean equal(Integer left, Integer right) {
        return left == null ? right == null : left.equals(right);
    }

    /**
     * One dropdown entry.
     *
     * <p>The value is what the engine is given and the label is what the player reads, and the two
     * are kept apart because a dropdown that means "leave this alone" shows the game's word for it
     * while carrying a value that is not a choice at all.</p>
     */
    private static final class Option {
        final String value;
        final String label;
        final Integer color;

        Option(String value, String label, Integer color) {
            this.value = value;
            this.label = label;
            this.color = color;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** The dropdown, which paints an entry in the colour that entry stands for. */
    private final class OptionAdapter extends ArrayAdapter<Option> {
        OptionAdapter(List<Option> entries) {
            super(MultiplayerBattleroomActivity.this,
                android.R.layout.simple_spinner_item, entries);
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            View view = super.getDropDownView(position, convertView, parent);
            if (view instanceof TextView) {
                Option entry = getItem(position);
                if (entry != null && entry.color != null) {
                    ((TextView) view).setTextColor(entry.color);
                }
            }
            return view;
        }
    }

    // ------------------------------------------------------------------
    // The game options
    // ------------------------------------------------------------------

    /**
     * The game's own options dialog, written back through the engine's changeable setup.
     *
     * <p>The dialog is the host's and the proxy controller's: the changeable setup the engine hands
     * out is the live game setup on a server and a copy on a client holding the host's controls, and
     * applying it either writes and announces the change or asks for it with the game's own commands.
     * The team layout is the one control the dialog keeps from a client holding the controls, which
     * is the game's own arrangement, and the revealed-map box is one the game's own dialog does not
     * show at all. The two buttons are the layout's own, as they are in the game's own screen.</p>
     */
    private void showGameOptions() {
        final GameSetup editable = currentChangeableSetup();
        if (editable == null) {
            Toast.makeText(this, LocalizedText.ui("ui.hostOnly"), Toast.LENGTH_SHORT).show();
            return;
        }
        View content = LayoutInflater.from(this)
            .inflate(R.layout.multiplayer_battleroom_gameoptions, null);
        final Dialog dialog = new Dialog(this);
        dialog.setContentView(content);
        dialog.setTitle(LocalizedText.ui("ui.options"));
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        final Spinner difficulty = content.findViewById(R.id.netgameoptions_aiDifficulty);
        final Spinner units = content.findViewById(R.id.startingUnits);
        final Spinner fog = content.findViewById(R.id.fogMode);
        final Spinner credits = content.findViewById(R.id.startingCredits);
        final Spinner income = content.findViewById(R.id.incomeMultiplier);
        final Spinner setTeams = content.findViewById(R.id.netgameoptions_setTeams);
        final CheckBox noNukes = content.findViewById(R.id.noNukes);
        final CheckBox shared = content.findViewById(R.id.sharedControl);

        fill(difficulty, R.array.aidifficulty_array, editable.aiDifficulty + 2);
        fill(units, R.array.startingUnits_array, editable.startingUnits);
        fill(fog, R.array.fog_array, editable.fogMode);
        fill(credits, R.array.credits_array, clamp(editable.startingCredits, CREDIT_CHOICES));
        fill(income, R.array.income_array, indexOf(INCOME, editable.incomeMultiplier));
        fill(setTeams, R.array.setTeams_array, 0);
        check(noNukes, editable.noNukes);
        check(shared, editable.sharedControl);
        // The game's own dialog keeps the team layout from a client holding the controls, and does
        // not show the revealed-map box at all.
        show(setTeams, network.isServer ? View.VISIBLE : View.GONE);
        show(content.findViewById(R.id.revealedMap), View.GONE);

        LocalizedText.applyTo(content);

        content.findViewById(R.id.applyButton).setOnClickListener(clicked -> {
            applyGameOptions(editable, difficulty, units, fog, credits, income, setTeams,
                noNukes, shared);
            dialog.dismiss();
            refresh();
        });
        content.findViewById(R.id.cancelButton).setOnClickListener(clicked -> dialog.dismiss());
        dialog.show();
    }

    /**
     * The setup the engine lets this device change, or {@code null} when it lets nobody change one.
     *
     * <p>This is the live game setup on a server and a copy on a client that holds the host's
     * controls, and {@code applyChangedSetup} takes it back, which is the engine's own arrangement
     * for both of them.</p>
     */
    private GameSetup currentChangeableSetup() {
        try {
            return network.getChangeableSetup();
        } catch (Throwable t) {
            Log.w(TAG, "The changeable game setup could not be read.", t);
            return null;
        }
    }

    /** Writes the dialog's choices into the changeable setup and lets the engine apply them. */
    private void applyGameOptions(GameSetup editable, Spinner difficulty, Spinner units, Spinner fog,
        Spinner credits, Spinner income, Spinner setTeams, CheckBox noNukes, CheckBox shared) {
        try {
            if (difficulty != null) {
                editable.aiDifficulty = difficulty.getSelectedItemPosition() - 2;
            }
            if (units != null) {
                editable.startingUnits = units.getSelectedItemPosition();
            }
            if (fog != null) {
                editable.fogMode = fog.getSelectedItemPosition();
            }
            if (credits != null) {
                editable.startingCredits = clamp(credits.getSelectedItemPosition(), CREDIT_CHOICES);
            }
            if (income != null) {
                editable.incomeMultiplier = INCOME[clamp(income.getSelectedItemPosition(), INCOME.length)];
            }
            if (noNukes != null) {
                editable.noNukes = noNukes.isChecked();
            }
            if (shared != null) {
                editable.sharedControl = shared.isChecked();
            }
            // The first entry of the game's array keeps the teams as they are, so it is not sent.
            if (setTeams != null && network.isServer) {
                int position = setTeams.getSelectedItemPosition() - 1;
                if (position >= 0 && position < LAYOUTS.length) {
                    network.overrideTeamLayout(LAYOUTS[position]);
                }
            }
            network.applyChangedSetup(editable);
            if (network.isServer && network.serverVisible) {
                MasterServerConnector.startUpdateServerRequest();
            }
        } catch (Throwable t) {
            Log.e(TAG, "The game options could not be applied.", t);
        }
    }

    // ------------------------------------------------------------------
    // Starting
    // ------------------------------------------------------------------

    /** @return whether a start has been acted on, which is what the engine asks before one. */
    public boolean isGameStarting() {
        return starting;
    }

    /**
     * Loads the multiplayer map and opens the play screen.
     *
     * <p>This is what the engine's own start announcement amounts to on a host: the map is loaded
     * through the game's own loader, which is the same call the desktop host makes, and then the play
     * screen is started. A second announcement is ignored, because the engine repeats it.</p>
     */
    public void startMultiplayerGame() {
        if (starting || isFinishing() || network == null) {
            return;
        }
        starting = true;
        try {
            com.corrodinggames.rts.appFramework.MultiplayerBattleroomActivity.loadMultiplayerMap();
        } catch (Throwable t) {
            Log.e(TAG, "The multiplayer map could not be loaded.", t);
            starting = false;
            Toast.makeText(this, LocalizedText.ui("ui.startFailedPrefix") + t.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(new Intent(this, InGameActivity.class));
        finish();
    }

    // ------------------------------------------------------------------
    // Importing a map
    // ------------------------------------------------------------------

    private void chooseMap() {
        if (!FileImport.choose(this, REQUEST_IMPORT)) {
            Toast.makeText(this, LocalizedText.ui("ui.noPicker"), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request != REQUEST_IMPORT) {
            super.onActivityResult(request, result, data);
            return;
        }
        if (result != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        String name = FileImport.nameOf(this, data.getData());
        if (name == null || !name.endsWith(".tmx")) {
            Toast.makeText(this, LocalizedText.ui("ui.mapExpected") + name,
                Toast.LENGTH_LONG).show();
            return;
        }
        String failure = FileImport.copyInto(this, data.getData(), FileImport.join(CUSTOM_FOLDER, name));
        if (failure != null) {
            Toast.makeText(this, LocalizedText.ui("ui.importFailedPrefix") + name + ": " + failure,
                Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, LocalizedText.ui("ui.importedPrefix") + name + ".", Toast.LENGTH_SHORT).show();
        // The list is refilled from the folder, which now holds one more map.
        filledForType = -1;
        updateMapDropdown(false);
    }

    // ------------------------------------------------------------------
    // The engine's state
    // ------------------------------------------------------------------

    /**
     * The teams in the room, in the order the game's own room lists them.
     *
     * <p>The order is the game's, not this screen's. The game sorts the teams by the side they are
     * on, which is what puts everyone on one side above everyone on the other instead of listing
     * them in the order they were given their slots; spectators come last. Taking the teams as they
     * are held instead leaves the sides interleaved, and a table that reads A, B, A, B says nothing
     * about who is on whose side.</p>
     */
    private List<Team> teams() {
        List<Team> teams = new ArrayList<>();
        try {
            for (Object entry : Team.getSortedTeams(true)) {
                if (entry instanceof Team) {
                    teams.add((Team) entry);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "The teams could not be read.", t);
        }
        return teams;
    }

    private static String nameOf(Team team) {
        String name = team.name;
        if (name == null || name.trim().isEmpty()) {
            name = "Team " + team.teamId;
        }
        return team.isAI ? name + " (AI)" : name;
    }

    private static int indexOf(Object[] values, Object wanted) {
        if (wanted == null) {
            return 0;
        }
        for (int i = 0; i < values.length; i++) {
            if (wanted.equals(values[i])) {
                return i;
            }
        }
        return 0;
    }

    private static int indexOf(float[] values, float wanted) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == wanted) {
                return i;
            }
        }
        return 0;
    }

    /** Keeps a choice inside the range a dropdown of the given length has. */
    private static int clamp(int value, int length) {
        return Math.max(0, Math.min(length - 1, value));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Fills and shows one line of status, replacing whatever the last one said. */
    private void setStatus(String message) {
        if (statusInfo == null) {
            return;
        }
        statusInfo.setText(message);
    }

    /** Fills a spinner from one of the game's own arrays and selects an entry. */
    private void fill(Spinner spinner, int arrayId, int selection) {
        if (spinner == null) {
            return;
        }
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
            this, arrayId, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        if (selection >= 0 && selection < adapter.getCount()) {
            spinner.setSelection(selection);
        }
    }

    private static void check(CheckBox box, boolean value) {
        if (box != null) {
            box.setChecked(value);
        }
    }

    private <T> ArrayAdapter<T> adapter(List<T> entries) {
        ArrayAdapter<T> adapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, entries);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private void onclick(int id, Runnable action) {
        View view = findViewById(id);
        if (view == null) {
            Log.w(TAG, "The battleroom layout has no view for " + id + ".");
            return;
        }
        view.setOnClickListener(clicked -> action.run());
    }

    private void hide(int id) {
        show(findViewById(id), View.GONE);
    }

    private static void show(View view, int visibility) {
        if (view != null) {
            view.setVisibility(visibility);
        }
    }

    private int dip(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5F);
    }
}
