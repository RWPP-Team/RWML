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
import android.widget.ScrollView;
import android.widget.SeekBar;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.rwml.android.R;
import io.github.rwml.api.Rwml;
import io.github.rwml.api.ui.GameRulesUi;

/**
 * 多人对战房间：使用游戏自身布局配置多人游戏。
 *
 * <p>主机选择游戏类型和地图，并添加电脑玩家；所有人都能查看队伍表格和聊天内容。主机负责开始游戏，获得主机控制权的客户端则通过引擎提供的
 * 可变设置请求相同操作，由引擎将改动转换为发送给服务器的游戏命令。何时满足开始条件由引擎决定，并通过 {@link HostNetworkCallbacks} 发出通知；
 * 此界面也通过该回调接收聊天内容和队伍变化。</p>
 *
 * <p>两个下拉框沿用游戏自身的组织方式，其内容由游戏类型决定：类型决定地图列表读取哪个目录，因此必须先有游戏类型才能填充地图下拉框。
 * 游戏从 {@code maps/skirmish} 读取遭遇战地图，从 {@code /SD/rusted_warfare_maps} 读取玩家添加的地图，并从存档目录读取已保存的游戏；
 * 每个列表都会排序，并按名称而不是文件名显示。如果没有按当前游戏类型填充列表，对战房间就会显示空的地图下拉框。游戏会记住上次填充列表时使用的类型，
 * 因此即使尚未选择类型，也必须执行首次填充。</p>
 *
 * <p>选择地图后会将其写入引擎的游戏设置；该设置会发送给客户端，并在游戏开始时用于加载地图。获得服务器控制权的客户端也会通过游戏自身的聊天命令
 * 执行相同操作，因为客户端无法直接配置服务器上的游戏。</p>
 *
 * <p>游戏有两个按钮属于 Android 版本的连接和文件浏览功能：通过蓝牙开始游戏，以及导入地图时使用的文件浏览器。本宿主没有包含这些功能，
 * 因此隐藏蓝牙按钮；导入地图改用平台自身的文件选择器。</p>
 */
public final class MultiplayerBattleroomActivity extends BuiltinActivity {

    private static final String TAG = "RWML";

    /** 导入地图时系统文件选择器返回的请求码。 */
    private static final int REQUEST_IMPORT = 6;

    /** 游戏存放遭遇战地图的目录，联机对局也从中选择地图。 */
    private static final String SKIRMISH_FOLDER = "maps/skirmish";

    /** 游戏读取玩家导入到设备上的地图的目录。 */
    private static final String CUSTOM_FOLDER = "/SD/rusted_warfare_maps";

    /** 游戏写入存档的目录。 */
    private static final String SAVES_FOLDER = "/SD/rustedWarfare/saves/";

    /** 地图下拉框中的游戏类型：遭遇战地图。 */
    private static final int TYPE_SKIRMISH = 0;

    /** 地图下拉框中的游戏类型：玩家添加的地图。 */
    private static final int TYPE_CUSTOM = 1;

    /** 地图下拉框中的游戏类型：存档。 */
    private static final int TYPE_SAVED = 2;

    /** 下拉框未选择地图时传给引擎的值。 */
    private static final String NO_MAP = "<No Map>";

    /** 房间向引擎查询状态变化的间隔。 */
    private static final int POLL_MILLIS = 1500;

    /**
     * 游戏自身数组中列出的信用点选项数量。
     *
     * <p>设置中保存的是游戏选项的索引，而非具体金额；引擎会在配置游戏时将该选项转换为金额。第一项为默认值，也是对战房间打开时使用的选项，
     * 因此选项数量同时也是最后一项的索引值。</p>
     */
    private static final int CREDIT_CHOICES = 9;

    /** 引擎中的收入倍率，顺序与游戏自带数组一致。 */
    private static final float[] INCOME = {1.0F, 1.5F, 2.0F, 2.5F};

    /** 引擎中的队伍布局；去掉数组首项（当前布局）。 */
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
     * 地图列表条目，名称与文件层提供的名称完全一致。
     *
     * <p>下拉框显示的是地图名称，但传给引擎的是这些文件层名称，因此需要并行保存两种名称，且下拉框中的索引与此数组中的索引一致。</p>
     */
    private String[] mapEntries = new String[0];

    /**
     * 上次填充地图列表时使用的游戏类型。
     *
     * <p>初始值为 -1，而不是第一个类型，因为游戏类型未变化时会跳过列表填充。游戏原生界面也会将初始值设为 -1：否则首次填充会被跳过，
     * 下拉框就会一直为空。</p>
     */
    private int filledForType = -1;

    /** 缩略图当前对应的地图路径，避免每次重绘都重新解码图片。 */
    private String thumbnailPath;

    /**
     * 上次刷新对战房间时，代理控制器的控制权限是否已经启用。
     *
     * <p>获得主机控制权是房间必须响应一次的状态变化：地图下拉框会切换到服务器当前使用的地图，因为在此之前玩家只能旁观服务器的选择。
     * 游戏原生界面也会为相同原因保留此标志。</p>
     */
    private boolean hadProxyControl;

    /** 标记是否已处理开始请求，避免重复响应引擎的调用。 */
    private boolean starting;

    /** 标记是否正在更新下拉框，避免程序更新被误认为玩家选择。 */
    private boolean filling;

    /** 当前显示的玩家表格单元格，便于下次重绘时回收复用。 */
    private final List<Cell> activeCells = new ArrayList<>();

    /**
     * 玩家表格中已不再显示的单元格。
     *
     * <p>引擎每次报告变化时都会重建表格；每行最多包含五个单元格。保留这些视图可避免每次都重新分配整张表格。单元格只会用于创建它时对应的列，
     * 因为其宽度和外观都是按该列设置的。</p>
     */
    private final List<Cell> spareCells = new ArrayList<>();

    private final Handler poll = new Handler(android.os.Looper.getMainLooper());

    /**
     * 对战房间自身的重绘任务，在房间显示期间重复执行。
     *
     * <p>在此宿主上，只有引擎处理游戏自身的网络数据包时才会收到更新调用。如果会话由其他组件处理（例如使用自有协议的扩展），房间就完全收不到调用，
     * 并会一直显示首次绘制时的状态。使用定时器重绘即可兼顾两种情况：引擎调用仍会优先处理，定时器只会重复显示引擎当前状态。</p>
     */
    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            refresh();
            poll.postDelayed(this, POLL_MILLIS);
        }
    };

    // ------------------------------------------------------------------
    // 打开和关闭
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(LocalizedText.ui("ui.battleroom"));
        AndroidApp.prepare(this, true);
        setContentView(R.layout.multiplayer_battleroom);
        // 对战房间绘制在游戏自身的平铺背景上，因此移除窗口背景。聊天输入框位于长布局底部，打开键盘时应平移窗口，而不是调整整个布局的大小。
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
        // 引擎通过这些回调通知房间状态变化，因此应在填充下拉框前注册回调：不能漏掉界面创建期间到达的开始游戏通知。
        HostNetworkCallbacks.attach(this);

        LocalizedText.applyTo(findViewById(android.R.id.content));
        refresh();
        Log.i(TAG, "Battleroom open (host: " + network.isServer
            + ", proxy: " + network.proxyController + ").");
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 在主服务器列表公开的主机会定时刷新其条目；引擎只会在对战房间显示期间运行此定时器。
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
        // 对战房间关闭可能是玩家主动离开，也可能是游戏已经开始；只有前者需要断开网络，后者正是创建该房间的目的。
        if (isFinishing() && !starting) {
            leave();
        }
    }

    @Override
    public void onBackPressed() {
        // 离开房间意味着退出正在配置的游戏，必须通知引擎，否则其他玩家会继续等待此玩家。先检查房间状态，因为聊天房间也可能在游戏界面后方保持打开。
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
                    // 游戏界面打开后，对战房间会留在后台，网络连接继续保持；游戏自身的最小化操作也是关闭界面而不离开房间。
                    moveTaskToBack(true);
                });
        }
        dialog.show();
    }

    /** 使用游戏提供的原因离开网络，并关闭房间。 */
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

    /** 离开打开房间时加入的网络，即关闭房间时执行的操作。 */
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
    // 布局
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

        // 蓝牙游戏是此宿主缺少的功能；下方表格会取代游戏内嵌的玩家列表；就绪状态通过引擎自身的聊天命令控制，而非复选框：条件满足时由主机开始游戏，
        // 与游戏原生开始按钮的行为一致。
        hide(R.id.battleroom_startBluetoothButton);
        hide(R.id.networkPlayerList);
        hide(R.id.battleroom_ready);

        Button rules = findViewById(R.id.battleroom_rwmlRules);
        if (rules != null) {
            rules.setText(LocalizedText.ui("ui.rules"));
            rules.setOnClickListener(clicked -> showGameRules());
        }

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
     * 游戏类型选择器。
     *
     * <p>列表项使用引擎自身的地图类型及其名称，当前已设置的类型会被选中，因此客户端可看到主机选择的类型。选择其他类型后会重新填充地图列表，
     * 因为类型决定列表读取哪个目录。</p>
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
                // 首次填充会在下方执行；该事件由首次填充引起，因此在此重新填充地图列表，而不在调用方处理。
                updateMapDropdown(false);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    /** 地图下拉框。房主选择条目时直接设置对局，其他玩家则向房主发出请求。 */
    private void wireMapSpinner() {
        if (mapSpinner == null) {
            return;
        }
        // 游戏先填充列表，再注册监听器，以免将填充操作误认为用户选择。
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
    // 地图列表
    // ------------------------------------------------------------------

    /**
     * 根据所选游戏类型填充地图下拉框。
     *
     * <p>游戏类型决定目录，目录由引擎文件层读取，列表经过排序后按名称显示。如果类型没有变化，就不执行操作，以免每次重绘都重建列表；
     * 首次调用无论如何都必须执行，这正是初始类型设为无效值的原因。</p>
     *
     * @param firstRun 是否为界面打开时的首次填充；首次填充还会选中引擎当前持有的地图
     */
    private void updateMapDropdown(boolean firstRun) {
        if (mapSpinner == null) {
            return;
        }
        int type = typeSpinner == null ? TYPE_SKIRMISH : typeSpinner.getSelectedItemPosition();
        if (type < 0) {
            type = TYPE_SKIRMISH;
        }
        // 尚未获得服务器控制权的客户端不能选择游戏类型，因此无论选择器显示什么，其列表都使用遭遇战地图。
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

    /** 某种游戏类型提供的地图，使用文件层名称并附带目录前缀。 */
    private String[] listMaps(int type) {
        String folder = folderOf(type);
        String[] listed = null;
        try {
            listed = FileLoader.listDir(folder, true);
            if (type == TYPE_CUSTOM) {
                // 模组添加的地图不是目录中的文件，因此向引擎请求模组地图列表，并将结果附加到目录列表中。
                ModManager mods = engine == null ? null : engine.modManager;
                if (mods != null) {
                    listed = mods.addExtraMapsForPath(listed, folder);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "The maps in " + folder + " could not be listed.", t);
        }
        if (listed == null || listed.length == 0) {
            // 文件层会报告失败原因，状态行也会显示提示；如果下拉框为空且没有说明，用户会以为目录中没有地图。
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
     * 将单个列表项转换为传给引擎的路径；该项不是地图时返回 {@code null}。
     *
     * <p>文件层会根据目录类型使用不同方式命名文件，而引擎需要完整路径才能加载。因此，如果名称尚未包含目录，就会补上目录前缀。
     * 遭遇战地图会与缩略图一起显示，缩略图也位于同一目录，所以两个地图目录只保留地图文件。</p>
     */
    private static String mapPathOf(String entry, String folder, int type) {
        if (entry == null || entry.isEmpty()) {
            return null;
        }
        // 引擎文件层会识别并解析这两个标记，因此直接传递，不做修改；其他内容均为所列目录中的文件。
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
     * 将下拉框切换到引擎当前使用的地图。
     *
     * <p>地图由服务器决定，因此客户端下拉框必须显示服务器选择的地图，而不是客户端最近点击的选项。由于服务器和此界面从不同来源获取地图名称，
     * 会将两者转换为小写后按路径末段匹配：一方来自此处列出的目录，另一方来自服务器发送的内容。</p>
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

    /** 将路径规范化为指向同一文件的路径共有的形式。 */
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
     * 处理主机或客户端选择地图的操作。
     *
     * <p>主机通过引擎的可变设置将选择写入游戏配置，并广播给客户端。获得服务器控制权的客户端也会调用相同方法，由引擎将其转换为游戏自身的服务器命令。
     * 其他客户端只能旁观，因此会将选择恢复为服务器当前的地图，避免下拉框显示未被接受的选择。</p>
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

    /** @return 下拉框当前地图的文件层名称；未选择时为 {@code null} */
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
     * 地图路径的最后一段，也就是引擎保存的地图名称。
     *
     * <p>下拉框保存完整路径，以便通过文件层查询文件；引擎则只在游戏配置中保存名称，并根据类型推导路径。没有分隔符的路径本身就是名称；
     * 带标记的路径保持原样，因为引擎会自行解析标记。</p>
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
     * 将界面中的选择写入引擎，并通知所有玩家。
     *
     * <p>此操作对应引擎自身的“游戏信息已更改”流程：将地图和类型写入引擎提供的可变设置，再由 {@code applyChangedSetup} 完成其余工作。
     * 无论设备是服务器还是拥有主机控制权的客户端，调用方式都相同。服务器会写入并广播修改；客户端会使用游戏自身命令请求修改；其他客户端没有可更改的设置。
     * 公开游戏在主服务器列表中的条目会随地图更新，缩略图也会随地图更新。</p>
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
     * 根据游戏状态显示或隐藏控件。
     *
     * <p>主机可以选择类型和地图并开始游戏；获得服务器控制权的客户端可以选择地图，但不能选择类型；其他客户端只能旁观。
     * 隐藏地图和类型控件时会显示游戏摘要，因为客户端仍需了解即将开始的游戏内容。</p>
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
            show(findViewById(R.id.battleroom_rwmlRules), View.GONE);
            show(findViewById(R.id.battleroom_addAI), View.GONE);
            show(findViewById(R.id.battleroom_startNetButton), View.GONE);
            show(gameSummary, View.VISIBLE);
        } else {
            show(mapLayout, View.VISIBLE);
            show(typeLayout, proxy ? View.GONE : View.VISIBLE);
            show(findViewById(R.id.battleroom_changeTeam), View.GONE);
            show(findViewById(R.id.battleroom_otherGameOptions), View.VISIBLE);
            show(findViewById(R.id.battleroom_rwmlRules),
                host && gameRulesUi() != null ? View.VISIBLE : View.GONE);
            show(findViewById(R.id.battleroom_addAI), View.VISIBLE);
            show(findViewById(R.id.battleroom_startNetButton), View.VISIBLE);
            show(gameSummary, View.GONE);
        }

        // 只有主机能设置游戏类型；可以设置地图的玩家由引擎决定。
        if (typeSpinner != null) {
            typeSpinner.setEnabled(host);
        }
        if (mapSpinner != null) {
            mapSpinner.setEnabled(host || proxy);
        }

        // 玩家单独创建的游戏没有其他聊天对象，因此不显示聊天面板。
        show(findViewById(R.id.chatLogWrap),
            network.singlePlayerServer ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------------
    // 绘制对战房间
    // ------------------------------------------------------------------

    /**
     * 重绘引擎可能已更改的所有内容。可从任意线程安全调用。
     *
     * <p>此方法对应房间对引擎更新调用的处理，会重绘服务器信息、玩家列表，以及根据游戏状态决定显示的内容。定时轮询覆盖相同内容，因为引擎的更新调用会到达
     * 游戏自身界面，而本宿主不会打开该界面。</p>
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
     * 按游戏原生界面的两个位置显示房间摘要。
     *
     * <p>文本来自引擎的 {@code getGameInfoText}，与游戏原生界面显示的内容相同：服务器地址和端口、是否允许互联网连接，以及游戏模式、地图、信用点、
     * 战争迷雾、初始单位、收入和限制。主机会在房间上方状态栏中看到这些信息，其他玩家则会在地图旁的摘要中看到；客户端的状态栏为空，
     * 获得主机控制权的客户端两处都不显示，因为房间会改为显示控制项。</p>
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
     * 将地图下拉框切换到引擎当前使用的地图。
     *
     * <p>地图由服务器决定，因此客户端下拉框必须显示服务器的选择，而不是客户端最近点击的选项。客户端刚获得主机控制权时也会执行一次同样的切换，
     * 与游戏自身行为一致：在此之前玩家一直旁观服务器的选择，因此首次显示控制项时应展示当前游戏状态。之后即可由玩家修改下拉框。</p>
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
     * 显示游戏即将加载的地图，与游戏原生界面一样放在地图下拉框旁边。
     *
     * <p>地图路径由引擎根据游戏配置生成，主机和客户端使用相同规则。地图图片会以对应名称保存在地图旁边，并通过引擎文件层读取，以便找到模组添加的地图。
     * 缩略图超过游戏自身尺寸上限时会被拒绝，没有缩略图的地图也不会显示图片。</p>
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
            // 隐藏图片后方已经加载了相同地图，因此只需显示图片。
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
                // 使用游戏自身的尺寸上限，因为完整地图图片不应作为缩略图使用。
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
                    // 无法关闭缩略图不应导致整个房间界面加载失败。
                }
            }
        }
    }

    /**
     * 玩家表格，每个玩家一行：名称、出生点、队伍和延迟。
     *
     * <p>各行是布局中未命名的表格子视图，游戏原生界面也用这种方式区分数据行和标题，并在重绘时保留标题。每行会以玩家颜色显示名称，
     * 并以队伍颜色显示出生点和队伍列；当前设备所对应玩家的行会加粗，并可点击更改队伍。</p>
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
            // 已分配颜色的玩家显示为该颜色，其他玩家显示为白色。
            name.setTextColor(team.colorOverride == null
                ? Color.WHITE : Team.lookupTeamColor(team.colorOverride));

            TextView spawn = addCell(row, spawnOf(team), 2);
            spawn.setTypeface(null, Typeface.BOLD);
            // 观战者的出生点使用观战者颜色显示，其他玩家则根据其出生点所属阵营着色。
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
     * 玩家将从何处开始。
     *
     * <p>出生点从 1 开始编号，观战者没有出生点。如果玩家分配到的初始单位数量与游戏设置不同，界面会特别说明，因为仅凭出生点无法看出玩家的初始单位数量。</p>
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

    /** 从备用池取出或新建一个单元格，并将其放入指定行列。 */
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

    /** 将一行中的所有单元格回收到备用池，供下次重绘复用。 */
    private void markAllActiveCellsDeleted() {
        for (int i = activeCells.size() - 1; i >= 0; i--) {
            Cell cell = activeCells.get(i);
            cell.row.removeView(cell.view);
            cell.row = null;
        }
        spareCells.addAll(activeCells);
        activeCells.clear();
    }

    /** 玩家表格中的一个单元格，保留其视图以便下次重绘复用。 */
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
            // 日志由引擎提供；日志无法渲染时，不应连带关闭整个房间界面。
            Log.w(TAG, "The chat log could not be rendered.", t);
        }
    }

    // ------------------------------------------------------------------
    // 房间操作
    // ------------------------------------------------------------------

    /**
     * 开始按钮：主机直接开始游戏，持有主机控制权的客户端则发送开始请求。
     *
     * <p>游戏是否可以开始由引擎决定，因为只有引擎知道哪些客户端已就绪。引擎调用会返回是否接受开始请求，并通过
     * {@link HostNetworkCallbacks} 发布结果；该回调负责加载地图并打开游戏界面。游戏原生界面在当前设备不是服务器时会使用开始命令发送请求，
     * 服务器端会执行相同流程；其他客户端没有可点击的开始按钮，因为该按钮对它们是隐藏的。</p>
     *
     * <p>向引擎发起请求前，会根据游戏配置写入待加载的地图路径，因为游戏自身脚本也是这样处理的：{@code gameSetup.mapName} 保存纯名称，
     * {@code networkEngine.mapName} 保存引擎加载所用路径。两者分开保存，使下拉框能显示名称，而引擎读取路径。若开始游戏时没有该路径，
     * 引擎就会向网络写入 null 地图名称，进而崩溃；此处用于防止该问题。</p>
     */
    private void startPressed() {
        if (network.isServer) {
            try {
                String bareName = network.gameSetup == null ? null : network.gameSetup.mapName;
                if (bareName == null || bareName.isEmpty() || NO_MAP.equals(bareName)) {
                    Toast.makeText(this, LocalizedText.ui("ui.noMap"), Toast.LENGTH_SHORT).show();
                    return;
                }
                // 按照游戏自身脚本的规则，根据类型生成引擎要加载的路径。引擎没有对应路径的类型无法启动。
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
                GameRulesUi rules = gameRulesUi();
                if (rules == null) {
                    if (!network.startGame()) {
                        Toast.makeText(this, LocalizedText.ui("ui.waitingPlayers"), Toast.LENGTH_SHORT).show();
                    }
                } else {
                    rules.startGame(new GameRulesUi.Callback() {
                        @Override public void onSuccess(GameRulesUi.Snapshot snapshot) { refresh(); }
                        @Override public void onFailure(String message) {
                            showRulesFailure(message);
                        }
                    });
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

    /** 添加电脑玩家，由引擎同步到各客户端。 */
    private void addAI() {
        if (network.isServer) {
            GameRulesUi rules = gameRulesUi();
            if (rules != null) {
                rules.addAi(GameRulesUi.AiOptions.defaults(), new GameRulesUi.Callback() {
                    @Override public void onSuccess(GameRulesUi.Snapshot snapshot) { refresh(); }
                    @Override public void onFailure(String message) {
                        runOnUiThread(() -> Toast.makeText(MultiplayerBattleroomActivity.this,
                            LocalizedText.ui("ui.addAiFailed") + " " + message, Toast.LENGTH_LONG).show());
                    }
                });
                return;
            }
            try {
                network.addAIToGame();
            } catch (Throwable t) {
                Log.e(TAG, "A computer player could not be added.", t);
                Toast.makeText(this, LocalizedText.ui("ui.addAiFailed"), Toast.LENGTH_SHORT).show();
                return;
            }
        } else if (network.proxyController) {
            // 沿用游戏自身处理方式：主机控制界面通过游戏命令发起请求。
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

    private GameRulesUi gameRulesUi() {
        try {
            return Rwml.get() == null ? null : Rwml.get().ui().find(GameRulesUi.class);
        } catch (Throwable failure) {
            Log.w(TAG, "The game-rule UI service is not available.", failure);
            return null;
        }
    }

    private void showGameRules() {
        GameRulesUi rules = gameRulesUi();
        if (rules == null) {
            Toast.makeText(this, LocalizedText.ui("ui.rulesUnavailable"), Toast.LENGTH_SHORT).show();
            return;
        }
        rules.refresh(new GameRulesUi.Callback() {
            @Override public void onSuccess(GameRulesUi.Snapshot snapshot) {
                runOnUiThread(() -> showGameRulesDialog(rules, snapshot));
            }
            @Override public void onFailure(String message) { showRulesFailure(message); }
        });
    }

    private void showGameRulesDialog(GameRulesUi rules, GameRulesUi.Snapshot snapshot) {
        if (isFinishing() || !snapshot.available()) {
            Toast.makeText(this, LocalizedText.ui("ui.rulesUnavailable"), Toast.LENGTH_SHORT).show();
            return;
        }
        if (!snapshot.canEditRules()) {
            Toast.makeText(this, LocalizedText.ui("ui.hostOnly"), Toast.LENGTH_SHORT).show();
            return;
        }

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dip(18), dip(8), dip(18), dip(8));
        scroll.addView(content);

        TextView modeLabel = new TextView(this);
        modeLabel.setText(LocalizedText.ui("ui.rulesMode"));
        content.addView(modeLabel);
        Spinner modeSpinner = new Spinner(this);
        List<Option> modes = new ArrayList<>();
        modes.add(new Option("", LocalizedText.ui("ui.rulesMapMode"), null));
        for (GameRulesUi.ModeOption mode : snapshot.baseModes()) {
            modes.add(new Option(mode.id(), mode.displayName(), null));
        }
        modeSpinner.setAdapter(new OptionAdapter(modes));
        choose(modeSpinner, snapshot.activeBaseModeId() == null ? "" : snapshot.activeBaseModeId());
        content.addView(modeSpinner);

        TextView speedLabel = new TextView(this);
        content.addView(speedLabel);
        SeekBar speed = new SeekBar(this);
        speed.setMax(100);
        speed.setProgress(Math.max(0, Math.min(100, Math.round(snapshot.gameSpeed() * 20f))));
        speedLabel.setText(LocalizedText.ui("ui.rulesSpeed", String.format(Locale.US, "%.2fx", speed.getProgress() / 20f)));
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                speedLabel.setText(LocalizedText.ui("ui.rulesSpeed", String.format(Locale.US, "%.2fx", progress / 20f)));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        content.addView(speed);

        TextView banLabel = new TextView(this);
        banLabel.setPadding(0, dip(10), 0, dip(4));
        banLabel.setText(LocalizedText.ui("ui.rulesBanUnits"));
        content.addView(banLabel);
        Set<String> banned = new HashSet<>(snapshot.bannedUnitIds());
        Set<String> listedIds = new HashSet<>();
        List<CheckBox> checks = new ArrayList<>();
        List<String> checkIds = new ArrayList<>();
        for (GameRulesUi.UnitOption unit : snapshot.units()) {
            addBanUnitCheck(content, checks, checkIds, listedIds, banned, unit.id(), unit.displayName());
        }
        // Keep bans for unit types from currently unavailable mods instead of silently dropping them.
        for (String id : banned) {
            if (!listedIds.contains(id)) addBanUnitCheck(content, checks, checkIds, listedIds, banned, id, id);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(LocalizedText.ui("ui.rulesTitle"))
            .setView(scroll)
            .setPositiveButton(LocalizedText.ui("ui.apply"), null)
            .setNegativeButton(LocalizedText.ui("common.cancel"), null)
            .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(clicked -> {
            List<String> selectedBans = new ArrayList<>();
            for (int index = 0; index < checks.size(); index++) {
                if (checks.get(index).isChecked()) selectedBans.add(checkIds.get(index));
            }
            Option selectedMode = (Option) modeSpinner.getSelectedItem();
            String modeId = selectedMode == null || selectedMode.value.isEmpty() ? null : selectedMode.value;
            float multiplier = speed.getProgress() / 20f;
            rules.apply(new GameRulesUi.Options(modeId, multiplier, selectedBans), new GameRulesUi.Callback() {
                @Override public void onSuccess(GameRulesUi.Snapshot updated) {
                    runOnUiThread(() -> {
                        dialog.dismiss();
                        refresh();
                    });
                }
                @Override public void onFailure(String message) { showRulesFailure(message); }
            });
        }));
        dialog.show();
    }

    private void addBanUnitCheck(LinearLayout parent, List<CheckBox> checks, List<String> checkIds,
        Set<String> listedIds, Set<String> banned, String id, String name) {
        if (id == null || id.isEmpty() || !listedIds.add(id)) return;
        CheckBox check = new CheckBox(this);
        check.setText(name);
        check.setChecked(banned.contains(id));
        parent.addView(check);
        checks.add(check);
        checkIds.add(id);
    }

    private void showRulesFailure(String message) {
        runOnUiThread(() -> Toast.makeText(this,
            LocalizedText.ui("ui.rulesFailedPrefix") + message, Toast.LENGTH_LONG).show());
    }

    private void showAiPauseControl(View content, Team team) {
        Button control = content.findViewById(R.id.rwml_aiPauseResume);
        GameRulesUi rules = gameRulesUi();
        if (control == null || !team.isAI || !network.isServer || rules == null) {
            show(control, View.GONE);
            return;
        }
        control.setEnabled(false);
        show(control, View.VISIBLE);
        final boolean[] paused = {false};
        rules.refresh(new GameRulesUi.Callback() {
            @Override public void onSuccess(GameRulesUi.Snapshot snapshot) {
                runOnUiThread(() -> {
                    GameRulesUi.AiOption ai = findAi(snapshot, team.teamId);
                    if (ai == null) {
                        show(control, View.GONE);
                        return;
                    }
                    paused[0] = ai.paused();
                    control.setText(LocalizedText.ui(paused[0] ? "ui.aiResume" : "ui.aiPause"));
                    control.setEnabled(snapshot.canManageAi());
                });
            }
            @Override public void onFailure(String message) { showRulesFailure(message); }
        });
        control.setOnClickListener(clicked -> {
            control.setEnabled(false);
            rules.setAiPaused(team.teamId, !paused[0], new GameRulesUi.Callback() {
                @Override public void onSuccess(GameRulesUi.Snapshot snapshot) {
                    runOnUiThread(() -> {
                        GameRulesUi.AiOption ai = findAi(snapshot, team.teamId);
                        if (ai == null) {
                            show(control, View.GONE);
                            return;
                        }
                        paused[0] = ai.paused();
                        control.setText(LocalizedText.ui(paused[0] ? "ui.aiResume" : "ui.aiPause"));
                        control.setEnabled(snapshot.canManageAi());
                        refresh();
                    });
                }
                @Override public void onFailure(String message) {
                    runOnUiThread(() -> control.setEnabled(true));
                    showRulesFailure(message);
                }
            });
        });
    }

    private static GameRulesUi.AiOption findAi(GameRulesUi.Snapshot snapshot, int teamId) {
        for (GameRulesUi.AiOption ai : snapshot.ais()) {
            if (ai.teamId() == teamId) return ai;
        }
        return null;
    }

    /** 将本地玩家移至另一队伍，并由引擎发送到服务器。 */
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

    /** 发送玩家输入的聊天内容。 */
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
    // 单个玩家的设置
    // ------------------------------------------------------------------

    /**
     * 编辑单个玩家设置的对话框：选择出生点、盟友、电脑难度、初始配置和玩家颜色。
     *
     * <p>该对话框沿用游戏自身的布局和下拉框。每个下拉框保存实际值而非列表位置，因为传给引擎的就是这些值：出生点和阵营使用数字表示；
     * 表示“保持游戏当前设置”的选项会携带 -99，供引擎识别为不覆盖设置。</p>
     *
     * <p>对话框中的可用选项取决于玩家身份。只有主机能写入单个玩家的覆盖设置，因此其他玩家看不到整个覆盖设置区域。电脑玩家可以设置难度，
     * 人类玩家则没有该选项。获得主机控制权的客户端唯一能做的是移交玩家控制权，因此只有它能看到相应按钮；玩家也不能从自己的行中将自己踢出。</p>
     */
    private void showPlayerPopup(final Team team) {
        if (network == null || team == null) {
            return;
        }
        final boolean ownRow = network.localTeam == team;
        // 只有主机可以设置游戏；其他玩家只能编辑自己的行，且必须在队伍锁定前操作。锁定队伍意味着禁止此类更改。
        if (!network.canChangeGameSetup()
            && !(ownRow && network.gameSetup != null && !network.gameSetup.teamsLocked)) {
            return;
        }

        View content = LayoutInflater.from(this)
            .inflate(R.layout.multiplayer_battleroom_playerpopup, null);
        final Dialog dialog = new Dialog(this);
        dialog.setContentView(content);
        dialog.setTitle(LocalizedText.ui("ui.playerPrefix") + nameOf(team));
        // 对话框顶部有输入框，因此打开键盘时平移窗口，而不是调整布局大小。
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);

        final Spinner spawn = content.findViewById(R.id.teamId);
        final Spinner ally = content.findViewById(R.id.teamAllyGroup);
        final Spinner difficulty = content.findViewById(R.id.aiDifficulty);
        final Spinner units = content.findViewById(R.id.startingUnits);
        final Spinner color = content.findViewById(R.id.playerColor);

        setupSpawnPositionDropdown(spawn);
        choose(spawn, team.isSpectator() ? "-3" : Integer.toString(team.teamId));

        setupTeamAllyDropdown(ally);
        // 如果玩家的阵营与出生点所属阵营一致，就使用自动设置，也就是主机未手动更改的默认状态。
        choose(ally, team.teamId % 2 == team.allyTeam ? "0" : Integer.toString(team.allyTeam + 1));

        setupAIDifficultyDropdown(difficulty);
        choose(difficulty, team.aiDifficultyOverride == null
            ? "-99" : Integer.toString(team.aiDifficultyOverride));

        setupStartingUnitsDropdown(units);
        choose(units, team.startingUnitsOverride == null
            ? "-99" : Integer.toString(team.startingUnitsOverride));

        setupPlayerColorDropdown(color, team);
        choose(color, team.colorOverride == null ? "-99" : Integer.toString(team.colorOverride));

        // 只有主机能写入覆盖设置；难度仅对电脑玩家有意义。
        show(content.findViewById(R.id.playerOverridesSection),
            network.isServer ? View.VISIBLE : View.GONE);
        show(content.findViewById(R.id.aiDifficultyWrap),
            team.isAI ? View.VISIBLE : View.GONE);
        showAiPauseControl(content, team);
        // 主机未另行选择时，队伍下拉框会根据出生点自动确定阵营；只有获得主机控制权的客户端可以执行该操作。
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
     * 玩家出生位置：按阵营分组显示所有出生点，并提供观战选项。
     *
     * <p>标签显示玩家在游戏中看到的编号和阵营名称；值则是传给引擎的槽位编号，因此两者相差一。</p>
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
     * 玩家与哪一方结盟。
     *
     * <p>第一项表示根据出生点自动确定阵营，这是引擎未收到其他设置时的默认行为。其余选项对应各个阵营，并使用相应阵营的颜色显示。</p>
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

    /** 电脑玩家的难度，以及游戏提供的默认难度选项。 */
    private void setupAIDifficultyDropdown(Spinner spinner) {
        List<Option> options = new ArrayList<>();
        options.add(new Option("-99", LocalizedText.word("menus.settings.option.default", "Default"), null));
        for (int level = -2; level <= 3; level++) {
            options.add(new Option(Integer.toString(level),
                LocalizedText.word("menus.settings.option.ai." + level, Integer.toString(level)), null));
        }
        spinner.setAdapter(new OptionAdapter(options));
    }

    /** 玩家开局资源，取自引擎提供的可选开局资源列表。 */
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
     * 玩家可选颜色；已分配给其他玩家的颜色会标记为占用。
     *
     * <p>已被占用的颜色不可选择，因此会灰显，并放在代表不指定颜色的选项之后：下拉框会回退到游戏为该玩家分配的颜色。</p>
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
                // 使用引擎绘制不可用颜色时的灰色；该值会让玩家保留游戏分配的颜色，而不是使用已被占用的颜色。
                options.add(new Option("-99", label + " (used)", Team.lookupTeamColor(TAKEN_COLOR)));
            } else {
                options.add(new Option(Integer.toString(value), label, Team.lookupTeamColor(value)));
            }
        }
        spinner.setAdapter(new OptionAdapter(options));
    }

    /** 引擎用于标记已被占用选项的颜色。 */
    private static final int TAKEN_COLOR = -7829368;

    /**
     * 将对话框中的选择写回引擎。
     *
     * <p>每项改动都通过引擎自身方法执行，而不是直接修改字段，因为其他玩家只会收到这些方法广播的变更：当前设备只是客户端时，移动玩家会将移动请求发送给服务器，
     * 具体含义由引擎决定。获得主机控制权的客户端会使用游戏自身命令请求修改；其他客户端只能更改自己，且只能编辑自己的行。</p>
     *
     * <p>出生点和阵营需要一起决定，因为阵营可以跟随出生点：选择自动阵营时，阵营由出生点所属阵营决定；引擎会将其记录为由槽位决定。</p>
     */
    private void applyPlayerPopup(Team team, Spinner spawn, Spinner ally, Spinner difficulty,
        Spinner units, Spinner color) {
        try {
            boolean mayChangeSide = false;
            boolean aiDifficultyHandledByApi = false;

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
                team.explicitAllyTeam = false;
            } else if (chosen(ally, 0) == 0) {
                side = slot % 2;
                sideFollowsSlot = true;
                team.explicitAllyTeam = false;
            } else {
                side = chosen(ally, 0) - 1;
                sideFollowsSlot = false;
                team.explicitAllyTeam = true;
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
                    // 引擎会将其转换为游戏自身的移动命令，客户端据此请求移动，而不是直接执行移动。
                    network.sendMoveTeamCommand(team, slot,
                        sideFollowsSlot ? Integer.valueOf(-1) : Integer.valueOf(side));
                } else {
                    Log.d(TAG, "A player row was clicked by someone who may not move it.");
                }
            }

            if (team.isAI) {
                Integer level = override(chosen(difficulty, -99));
                if (!equal(team.aiDifficultyOverride, level) && network.isServer) {
                    GameRulesUi rules = gameRulesUi();
                    if (rules == null) {
                        team.aiDifficultyOverride = level;
                    } else {
                        aiDifficultyHandledByApi = true;
                        rules.setAiDifficulty(team.teamId, level, new GameRulesUi.Callback() {
                            @Override public void onSuccess(GameRulesUi.Snapshot snapshot) { refresh(); }
                            @Override public void onFailure(String message) { showRulesFailure(message); }
                        });
                    }
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
                    // 若阵营跟随出生点，则传入“未指定阵营”，由引擎根据槽位决定阵营。
                    network.sendSetTeamCommand(team, sideFollowsSlot ? -1 : side);
                }
            }

            if (!aiDifficultyHandledByApi) network.updateAIDifficulty();
            network.assignTeamDisplaySlots();
            refresh();
        } catch (Throwable t) {
            Log.e(TAG, "The player's settings could not be applied.", t);
            Toast.makeText(this, LocalizedText.ui("ui.applyFailedPrefix") + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 移除服务器上的玩家前显示确认提示，与原界面的行为一致。 */
    private void confirmKick(final Team team) {
        Runnable kick = () -> {
            GameRulesUi rules = team.isAI && network.isServer ? gameRulesUi() : null;
            if (rules != null) {
                rules.removeAi(team.teamId, new GameRulesUi.Callback() {
                    @Override public void onSuccess(GameRulesUi.Snapshot snapshot) { refresh(); }
                    @Override public void onFailure(String message) { showRulesFailure(message); }
                });
                return;
            }
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
     * 在移交玩家控制权前询问用户，并使用游戏自身命令执行移交。
     *
     * <p>移交操作使用引擎提供的命令，因为客户端无法直接写入服务器的玩家列表；这与游戏自身脚本使用的流程相同。</p>
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

    /** @return 该玩家是否为本设备当前操控的玩家 */
    private boolean ownRow(Team team) {
        return network.localTeam == team;
    }

    /** @return 当前选项的值；未选择时返回 {@code fallback} */
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

    /** 根据给定值设置下拉框选项，以显示当前设置。 */
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

    /** @return 引擎用于表示不覆盖设置的值，即 {@code null} */
    private static Integer override(int value) {
        return value == -99 ? null : Integer.valueOf(value);
    }

    private static boolean equal(Integer left, Integer right) {
        return left == null ? right == null : left.equals(right);
    }

    /**
     * 一个下拉框选项。
     *
     * <p>值会传给引擎，标签则供玩家查看，两者分开保存，因为表示“保持不变”的下拉选项会显示游戏对应文本，但其值并非实际选项。</p>
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

    /** 下拉框会用选项对应的颜色绘制当前条目。 */
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
    // 游戏选项
    // ------------------------------------------------------------------

    /**
     * 游戏自身的选项对话框，通过引擎的可变设置写回选项。
     *
     * <p>此对话框仅供主机和代理控制器使用：服务器上，引擎提供的可变设置就是当前游戏配置；客户端获得主机控制权后，该设置则是当前配置的副本。
     * 应用设置时，服务器会写入并广播改动，客户端则会使用游戏自身命令发起请求。对话框会保留获得控制权的客户端可见的队伍布局控件，
     * 与游戏自身行为一致；游戏原生对话框完全不显示已揭示地图复选框。两个按钮也沿用布局和游戏界面中的原有按钮。</p>
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
        // 游戏自身对话框会为获得控制权的客户端保留队伍布局选项，并完全隐藏已揭示地图复选框。
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
     * 引擎允许此设备修改的游戏配置；没有可修改配置时返回 {@code null}。
     *
     * <p>服务器获得的是当前游戏配置，持有主机控制权的客户端获得的是副本；两者都会通过 {@code applyChangedSetup} 写回，这是引擎的统一处理方式。</p>
     */
    private GameSetup currentChangeableSetup() {
        try {
            return network.getChangeableSetup();
        } catch (Throwable t) {
            Log.w(TAG, "The changeable game setup could not be read.", t);
            return null;
        }
    }

    /** 将对话框中的选择写入可变对局设置，并交由引擎应用。 */
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
            // 游戏数组的第一项表示保留现有队伍设置，因此不发送该选项。
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
    // 启动游戏
    // ------------------------------------------------------------------

    /** @return 是否已处理开始请求，引擎开始前会查询此状态 */
    public boolean isGameStarting() {
        return starting;
    }

    /**
     * 加载多人游戏地图并打开游戏界面。
     *
     * <p>这是主机处理引擎自身开始通知的方式：通过游戏自身加载器加载地图，与桌面宿主使用相同调用，然后启动游戏界面。引擎可能重复发送通知，
     * 因此第二次及之后的通知会被忽略。</p>
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
    // 导入地图
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
        // 从目录中重新填充列表；该目录现在多了一张地图。
        filledForType = -1;
        updateMapDropdown(false);
    }

    // ------------------------------------------------------------------
    // 引擎状态
    // ------------------------------------------------------------------

    /**
     * 房间中的队伍，按游戏原生房间列表的顺序排列。
     *
     * <p>排序规则由游戏决定，而不是此界面。游戏按阵营对队伍排序，因此同一阵营的玩家会排在一起，而不会按分配槽位的先后顺序交错显示；
     * 观战者排在最后。如果直接按队伍当前保存顺序显示，不同阵营就会交错排列，表格中出现 A、B、A、B 时就无法看出谁属于哪一方。</p>
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

    /** 将选项索引限制在指定长度的下拉框范围内。 */
    private static int clamp(int value, int length) {
        return Math.max(0, Math.min(length - 1, value));
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    /** 更新并显示一行状态文本，替换之前显示的内容。 */
    private void setStatus(String message) {
        if (statusInfo == null) {
            return;
        }
        statusInfo.setText(message);
    }

    /** 使用游戏自带数组填充下拉框，并选中一个条目。 */
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
