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

import io.github.rwml.android.R;
import io.github.rwml.api.Rwml;
import io.github.rwml.api.ui.UiHooks;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 多人游戏大厅：使用游戏自身的大厅布局，显示主服务器发布的游戏列表、玩家名称，以及创建自己游戏的两种方式。
 *
 * <p>Rusted Warfare 的所有网络操作都属于引擎，此界面只是展示引擎状态，而不是重新实现网络逻辑。游戏列表来自引擎发现的游戏集合，
 * 并按游戏原生列表界面的顺序读取；获取列表也是由引擎向自己的主服务器发起请求；加入游戏则使用引擎自身的连接器。连接器知道：
 * 主服务器列表中的游戏条目表示游戏身份，而不是可直接连接的地址；需要另外查询地址；若游戏设有密码，则必须先请求密码才能继续连接。
 * 因此此界面只需将条目和玩家名称交给引擎，再读取连接结果。</p>
 *
 * <p>创建游戏也沿用游戏自身的主机流程：根据主机对话框设置引擎网络状态，启动服务器；若所选地图已不在设备上，就回退到游戏默认的
 * 遭遇战地图，因为对战房间无法配置无法加载的地图。</p>
 *
 * <p>引擎会调用原游戏列表界面类中的静态方法，通知列表发生变化。当前应用运行的归档中，该类只是空壳，因此调用会重定向到此界面；
 * 详见 {@code ServerListNotifyMixin}。</p>
 *
 * <p>通过蓝牙加入游戏和关联模组目录依赖 Android 版本自带的连接功能和文件浏览器；本宿主未包含这些功能，因此隐藏对应控件。</p>
 */
public final class MultiplayerLobbyActivity extends BuiltinActivity {

    private static final String TAG = "RWML";

    /** 布局标题行声明的列数。 */
    private static final int COLUMNS = 6;

    /** 表格行的宽度，与所在表格等宽。 */
    private static final int ROW_WIDTH = ViewGroup.LayoutParams.MATCH_PARENT;

    /** 地图所在列，游戏用它标记正在进行的对局。 */
    private static final int COLUMN_MAP = 3;

    /** 版本所在列；协议不兼容时游戏会将此列标灰。 */
    private static final int COLUMN_VERSION = 4;

    /** 普通单元格的颜色。 */
    private static final int COLOR_PLAIN = Color.argb(255, 255, 255, 255);

    /** 聊天室或链接类型对局的颜色；这两类内容都会在游戏外打开。 */
    private static final int COLOR_LINK = Color.argb(255, 152, 236, 249);

    /** 房主通过互联网开放端口响应时，对局显示的颜色。 */
    private static final int COLOR_OPEN = Color.argb(255, 240, 240, 240);

    /** 在局域网中发现的对局颜色。 */
    private static final int COLOR_LAN = Color.argb(255, 229, 149, 35);

    /** 本设备当前所在对局的颜色。 */
    private static final int COLOR_CURRENT = Color.argb(255, 35, 35, 200);

    /** 使用与当前构建不同协议的对局颜色。 */
    private static final int COLOR_OTHER_VERSION = Color.argb(255, 155, 147, 147);

    /**
     * 当前显示的大厅界面。
     *
     * <p>引擎通过游戏类上的静态方法通知列表变化，因此必须无需实例即可访问当前相关界面。</p>
     */
    private static volatile MultiplayerLobbyActivity open;

    private GameEngine engine;
    private EditText playerName;
    private EditText joinAddress;
    private TableLayout gameList;
    private Button refresh;
    private String refreshLabel;
    private String refreshingLabel;

    /** 引擎打开套接字期间显示的等待提示，以及启动该操作的调用。 */
    private ProgressDialog connecting;

    /** 当前加入请求的连接器，完成后可据此关闭等待提示。 */
    private ConnectSocketToServerRunnable joinInProgress;

    /** 标记是否已请求重建，避免一连串通知触发多次重绘。 */
    private boolean rebuildPosted;

    /**
     * 此界面是否位于前台。
     *
     * <p>引擎会持续报告发现的游戏，无论当前显示哪个界面。因此，在此界面上方打开房间后，后台列表仍会继续重建。
     * 只有此界面位于前台时才处理这些报告。</p>
     */
    private boolean resumed;
    private AutoCloseable serverChanges;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(LocalizedText.ui("ui.multiplayer"));
        AndroidApp.prepare(this, true);
        setContentView(R.layout.multiplayer_lobby);

        engine = GameEngine.getInstance();
        if (engine == null || engine.networkEngine == null) {
            Log.w(TAG, "The engine is not up; the lobby cannot bind.");
            Toast.makeText(this, LocalizedText.ui("ui.notLoaded"), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        playerName = findViewById(R.id.networkPlayerName);
        joinAddress = findViewById(R.id.joinIpAddress);
        gameList = findViewById(R.id.gameListTable);
        refresh = findViewById(R.id.refreshServersButton);

        // 引擎通过这些回调驱动当前负责设置游戏的界面。如果服务器在当前界面仍打开时启动游戏，也必须进入能够显示游戏的界面。
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
        // 引擎会继续将菜单地图绘制到此界面后方的表面上；当前界面覆盖后，就没有人能看到该表面。游戏原生对战房间也会在停止绘制的
        // 后台界面上方显示，原因相同。
        AndroidGameView.setEngineDrawingEnabled(engine, false);
        // 现在显示引擎已经发现的游戏；界面重新回到前台时，再请求最新列表。
        refreshServers();
        Log.i(TAG, "Lobby open.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        open = this;
        resumed = true;
        serverChanges = Rwml.get().ui().hooks().subscribe(
            UiHooks.SERVERS_CHANGED, this::runOnUiThread, ignored -> postRebuild());
        LobbyServerList.enter(this);
        AndroidGameView.setEngineDrawingEnabled(engine, false);
        // 从对战房间返回后应重新请求列表，与游戏原生大厅恢复时的行为一致。
        loadServerList();
    }

    @Override
    protected void onPause() {
        try { if (serverChanges != null) { serverChanges.close(); serverChanges = null; } }
        catch (Exception failure) { Log.w(TAG, "Cannot close server subscription", failure); }
        resumed = false;
        LobbyServerList.leave(this);
        if (open == this) { open = null; }
        super.onPause();
        // 对战房间覆盖此界面时，玩家当前正在查看房间，因此暂不处理引擎持续报告的游戏列表，直到此界面重新回到前台。
        resumed = false;
    }

    @Override
    protected void onDestroy() {
        LobbyServerList.leave(this);
        resumed = false;
        if (open == this) {
            open = null;
        }
        // 恢复后台界面的绘制，即菜单自身的背景地图。
        AndroidGameView.setEngineDrawingEnabled(engine, true);
        dismissConnecting();
        super.onDestroy();
    }

    /**
     * 引擎发现了新游戏，或移除了长时间未发现的游戏，因此界面中的列表已经过期。
     *
     * <p>调用会从引擎读取主服务器数据的线程到达，并且每个游戏都会触发一次。因此，重绘请求会发送到主线程，且在执行前只保留一次。</p>
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

    /** 游戏中使用的名称和上次加入的地址，均由引擎保存。 */
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
    // 创建游戏
    // ------------------------------------------------------------------

    /** 打开房主设置对话框，并根据选择确定对局的创建方式。 */
    private void host() {
        if (!rememberName()) {
            return;
        }
        openHostDialog();
    }

    /**
     * 询问游戏原生主机对话框中的三个选项：是否设置游戏密码、是否在网络上公开，以及是否启用当前已开启的模组。两个启动按钮的区别
     * 仅在第二个选项上。
     */
    private void openHostDialog() {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.multiplayer_lobby_hostgame);
        dialog.setTitle(LocalizedText.ui("ui.host"));
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
            // 布局中没有该对话框；仍然可以创建游戏，只是无法配置选项。
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
     * 根据所选选项在此设备上启动服务器，并进入对战房间。
     *
     * <p>如果之前的网络会话仍在运行，引擎会拒绝启动服务器，因此先关闭上一个会话。同时，启动服务器时会设置标志，将主机作为普通玩家；
     * 上一会话中的管理员玩家不会沿用到当前会话。</p>
     */
    private void startServer(boolean visible, EditText password, CheckBox useMods) {
        final NetworkEngine network = engine.networkEngine;
        String secret = password == null ? null : password.getText().toString().trim();
        try {
            // 按游戏原生大厅使用的顺序，执行引擎自身的主机流程。
            network.disconnect("starting new");
            network.serverPassword = secret == null || secret.isEmpty() ? null : secret;
            network.modsEnabled = useMods == null || useMods.isChecked();
            network.serverVisible = visible;
            if (!network.startServer(false)) {
                return;
            }
        } catch (Throwable t) {
            Log.e(TAG, "A server could not be started.", t);
            Toast.makeText(this, LocalizedText.ui("ui.hostFailedPrefix") + t.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        ensureHostMapIsLoadable(network);
        Log.i(TAG, "Hosting on port " + network.networkPort + '.');
        openBattleroom();
    }

    /**
     * 主机准备启动的地图无法加载时，回退到游戏默认的遭遇战地图。
     *
     * <p>对战房间会根据设备上已有的地图设置游戏；引擎准备启动的地图则是上一次游戏留下的值。如果路径为空，或对应文件已被删除，
     * 房间就无法选择或显示地图，因此将其替换为游戏自身回退使用的地图。</p>
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

    /** 没有沿用现有设置时，游戏大厅创建房间所用的默认地图。 */
    private static final String DEFAULT_SKIRMISH_MAP = "maps/skirmish/[p8]Many Islands (8p).tmx";

    /** 与对战房间一致的地图名称，不包含目录路径。 */
    private static final String DEFAULT_SKIRMISH_MAP_NAME = "[p8]Many Islands (8p).tmx";

    // ------------------------------------------------------------------
    // 游戏列表
    // ------------------------------------------------------------------

    /**
     * 向游戏请求游戏列表。
     *
     * <p>列表和请求都属于游戏自身：游戏使用的连接器会依次查询各个主服务器，将结果合并到引擎发现的游戏集合中；如果所有服务器都拒绝请求，
     * 就会在引擎中报告错误信息。此界面负责发起请求并显示结果，与游戏原生列表界面执行相同调用。</p>
     *
     * <p>请求在独立线程中执行，并通过 Runnable 返回结果，因此表格重建操作会切回主线程。</p>
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

    /** 在界面显示请求结果：失败原因或请求返回的列表。 */
    private void serverListLoaded() {
        if (!resumed || open != this || isFinishing()) {
            return;
        }
        refreshServers();
    }

    /** 根据引擎发现的对局重新构建列表。 */
    private void refreshServers() {
        if (gameList == null) {
            return;
        }
        // 除标题行外，其余行都来自上一次重建；布局自带的示例行和加载行也一并移除。
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
     * 表格中的一款游戏，包含标题行声明的六列：游戏状态、主机、当前人数、地图、游戏版本，以及是否允许其他人加入。
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

    /** 游戏解析出的地图文件名；未指定地图时为空。 */
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

    /** 列表为空时仍保留一行，避免表格布局异常。 */
    private void addEmptyRow() {
        String failure = engine == null || engine.networkEngine == null
            ? null : engine.networkEngine.serverListLoadError;
        String message = failure == null || failure.isEmpty()
            ? LocalizedText.ui("ui.noGames") : "ERROR: " + failure;

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

    /** 游戏状态名称，用游戏提供的文本替代原始值。 */
    private static String stateOf(DiscoveredServer server) {
        if (server.gameState == null) {
            return "";
        }
        return server.gameState
            .replace("battleroom", LocalizedText.word("menus.lobby.gameState.battleroom", "battleroom"))
            .replace("ingame", LocalizedText.word("menus.lobby.gameState.ingame", "ingame"))
            .replace("chat", LocalizedText.word("menus.lobby.gameState.chat", "chat"));
    }

    /** 当前玩家数与对局所需玩家总数。 */
    private static String countOf(DiscoveredServer server) {
        if (server.playerCountText == null) {
            return "?";
        }
        if ("?".equals(server.playerCountText)) {
            return "?";
        }
        return server.playerCountText + "\\" + server.maxPlayersText;
    }

    /** 游戏版本；若对局不限制版本，则显示游戏对应的文本。 */
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
     * 是否允许加入：主机端口未对互联网开放时返回 {@code N}，此时直连可能失败；端口开放时返回 {@code Y}；开放且需要密码时返回
     * {@code P}；局域网游戏则返回 {@code L}。
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
     * 引擎已发现的游戏，按游戏原生列表的顺序排列。
     *
     * <p>排序规则不由此界面决定。游戏原生列表界面统一管理排序，而且并非简单排序：版本与当前构建相符的游戏排在其他游戏之前，两个分组内部也各自排序。
     * 因此直接请求引擎提供集合，而不在此重建；该调用会在连接器写入时使用的同一把锁下复制集合，所以可从任意线程安全调用。</p>
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
    // 加入游戏
    // ------------------------------------------------------------------

    /** 加入输入框中的地址。 */
    private void joinTypedAddress() {
        String address = joinAddress == null ? null : joinAddress.getText().toString().trim();
        if (address == null || address.isEmpty()) {
            Toast.makeText(this, LocalizedText.ui("ui.enterAddress"), Toast.LENGTH_SHORT).show();
            return;
        }
        // 游戏会记住最近一次尝试连接的地址，此处负责设置该地址。
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
     * 在加入列表中的某款游戏前，询问用户是否加入并显示游戏信息。
     *
     * <p>列表条目描述游戏信息，由引擎将其转换为可连接的地址。首先会重新查询该条目，因为游戏可能在列表绘制后、用户点击行之前关闭。</p>
     */
    private void confirmJoin(final DiscoveredServer listed) {
        DiscoveredServer server = listed;
        try {
            DiscoveredServer found = MasterServerConnector.findDiscoveredServer(listed.serverId);
            if (found == null) {
                alert(LocalizedText.ui("ui.joinPrompt"), LocalizedText.ui("ui.serverGone"));
                return;
            }
            server = found;
        } catch (Throwable t) {
            Log.w(TAG, "The game " + listed.serverId + " could not be looked up again.", t);
        }

        final boolean link = server.hasUrl();
        final String address = link ? null
            : server.isLan ? server.getLanConnectAddress() : server.getConnectAddress();
        String label = link ? LocalizedText.ui("ui.open")
            : server.isLan ? LocalizedText.ui("ui.joinLan") : LocalizedText.ui("ui.join");
        final DiscoveredServer game = server;

        new AlertDialog.Builder(this)
            .setIcon(android.R.drawable.ic_dialog_info)
            .setTitle(link ? LocalizedText.ui("ui.openLinkPrompt") : LocalizedText.ui("ui.joinPrompt"))
            .setMessage(infoTextOf(game))
            .setPositiveButton(label, (dialog, which) -> {
                if (link) {
                    openLink(game.getUrl());
                } else {
                    joinServerWithId(address, game.serverId);
                }
            })
            .setNegativeButton(LocalizedText.ui("common.cancel"), null)
            .show();
    }

    /**
     * 游戏自身加入提示中显示的内容：链接，或游戏信息及加入所需条件。
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
     * 加入给定地址对应的游戏。
     *
     * <p>来自主服务器列表的游戏条目不是地址，而是游戏身份和其在主服务器中的记录位置，引擎知道如何解析：它会向主服务器查询该游戏的主机和端口，
     * 如果需要密码则先请求密码，再连接查询结果。局域网游戏包含普通地址，可直接加入。</p>
     */
    private void joinServerWithId(String address, String serverId) {
        if (address == null || address.trim().isEmpty()) {
            return;
        }
        if (!rememberName()) {
            return;
        }
        // 引擎会保存正在加入的游戏身份，并通过自身界面请求相关信息。
        engine.networkEngine.currentServerId = serverId;
        joinServer(address);
    }

    /**
     * 通过引擎自身的连接器连接到指定地址。
     *
     * <p>连接器在独立线程中运行，完成后会触发回调，因此等待提示会显示在界面上，结果再切回主线程处理。连接器可能在启动调用返回前就已完成，
     * 所以会先填充字段再显示等待界面；回调也不会从连接器线程读取该字段。</p>
     */
    private void joinServer(String address) {
        Log.i(TAG, "Joining " + address + '.');
        final ConnectSocketToServerRunnable[] started = new ConnectSocketToServerRunnable[1];
        try {
            started[0] = engine.networkEngine.connectToServerAsync(address, false,
                () -> runOnUiThread(this::joinFinished));
        } catch (Throwable t) {
            Log.e(TAG, "The connection to " + address + " could not be started.", t);
            alert(LocalizedText.ui("ui.connectionFailed"), LocalizedText.ui("ui.connectionFailedPrefix") + t.getMessage());
            return;
        }
        showConnecting();
        joinInProgress = started[0];
    }

    /**
     * 引擎连接器已打开套接字或放弃连接。
     *
     * <p>连接成功时会将套接字交给引擎，进而建立对战房间并打开对应界面。连接失败时会显示引擎提供的原因，例如密码错误、游戏已关闭，
     * 或无法通过互联网访问主机。</p>
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
            alert(LocalizedText.ui("ui.connectionFailed"), LocalizedText.ui("ui.connectionFailedPrefix") + failure
                + "\nTarget server may not be open to the internet");
            return;
        }

        try {
            NetworkEngine network = engine.networkEngine;
            // 尚未结束的会话会阻止新套接字建立；引擎自身的加入流程会先关闭旧会话，再连接新游戏。
            network.disconnect("starting new");
            if (!network.startClientConnection(connector.connectedSocket)) {
                alert(LocalizedText.ui("ui.connectionFailed"), LocalizedText.ui("ui.connectionRefused"));
                return;
            }
        } catch (Throwable t) {
            Log.e(TAG, "The connection could not be handed to the engine.", t);
            alert(LocalizedText.ui("ui.connectionFailed"), LocalizedText.ui("ui.connectionFailedPrefix") + t.getMessage());
            return;
        }
        Log.i(TAG, "Connected.");
        openBattleroom();
    }

    /** 保存玩家输入的名称，供所有连接及引擎读取。 */
    private boolean rememberName() {
        String name = playerName == null ? null : playerName.getText().toString();
        if (name == null || name.trim().isEmpty()) {
            Toast.makeText(this, LocalizedText.ui("ui.enterName"), Toast.LENGTH_SHORT).show();
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

    /** 打开作为链接的对局地址，而不是加入实际比赛。 */
    private void openLink(String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        try {
            Intent view = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            startActivity(view);
        } catch (Throwable t) {
            Log.w(TAG, "The link " + url + " could not be opened.", t);
            alert(LocalizedText.ui("ui.openLinkPrompt"), url);
        }
    }

    private void openBattleroom() {
        startActivity(new Intent(this, MultiplayerBattleroomActivity.class));
    }

    private void watchReplay() {
        startActivity(new Intent(this, ReplaySelectActivity.class));
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    /** 引擎打开套接字并向主服务器查询对局时显示的等待提示。 */
    private void showConnecting() {
        dismissConnecting();
        ProgressDialog dialog = new ProgressDialog(this);
        dialog.setMessage(LocalizedText.ui("ui.connecting"));
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
     * 表格中的单元格，外观与布局自带的行一致，并使用游戏为该行对应游戏指定的颜色。
     *
     * @param server 此行对应的游戏；若此行不对应任何游戏，则为 null
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
     * 将游戏原生列表为单元格指定的颜色和字重应用到单元格。
     *
     * <p>列表并非使用单一颜色；单元格的显示样式既取决于游戏，也取决于列。接受任意版本的游戏是聊天室或链接，游戏会将其加粗；链接会在游戏外打开，
     * 而不是加入游戏，因此显示为链接专用的浅蓝色。局域网游戏显示为橙色；主机端口对互联网开放的游戏则显示为偏白色。另有两列会标记特殊内容：
     * 当前设备正在参与的游戏地图显示为蓝色，使用其他协议的游戏版本显示为灰色。</p>
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

    /** 列出的对局是否为本设备当前连接的对局。 */
    private boolean isCurrentServer(DiscoveredServer server) {
        NetworkEngine network = engine == null ? null : engine.networkEngine;
        String current = network == null ? null : network.currentServerId;
        return current != null && current.equals(server.serverId);
    }

    /** 列出的对局是否使用当前构建支持的协议。 */
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
