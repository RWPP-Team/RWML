/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import com.corrodinggames.rts.appFramework.MapSelectActivity;
import com.corrodinggames.rts.gameFramework.GameEngine;

/**
 * 在选定的地图上启动单人游戏。
 *
 * <p>地图的设置方式由游戏决定，此类负责向游戏发出请求。{@code MapSelectActivity.loadMapWithAI} 是游戏提供的入口：
 * 关卡界面会在遭遇战地图上调用它，关卡选项界面会传入玩家选择的队伍，菜单则会将它用于背景地图。它的两个分支就是完整规则：
 * 非遭遇战地图包含自己的队伍信息，由引擎创建队伍，这对应 {@code loadMap(true, …)}；遭遇战地图不包含队伍信息，因此会根据
 * 玩家选择的数值创建队伍，然后由 {@code loadMap(false, …)} 加载对应内容。</p>
 *
 * <p>此类过去曾手动创建这些队伍，但结果并不等价。引擎维护一张以队伍 ID 为索引的队伍表，只有通过注册流程才能将 ID 写入该表；
 * 盟友先使用偶数 ID，对手使用其余 ID。除非地图采用队伍模式，否则每个对手的联盟关系由其 ID 决定。以其他顺序构建队伍表会导致
 * 地图触发器指向无法解析的队伍，从而丢失任务目标以及引用这些目标的提示。这里不再重复实现这些规则，而是使用与游戏原生界面相同的
 * 数值调用游戏自身的加载入口。</p>
 *
 * <p>这些数值来自游戏关卡选项界面的 {@code Root.loadConfigCommon}：该方法从自身控件读取对手数量和游戏模式，将模式转换为
 * 加入玩家一方的对手数量；若所选模式为各自为战，则为队伍参数传入 {@code false}。此时加入玩家一方的对手数也传入 {@code 0}，
 * 因而各自为战模式下不会有对手加入玩家一侧。</p>
 */
final class SinglePlayerGame {

    private static final String TAG = "RWML";

    /** 对局可设置的最大对手数，也是关卡界面列表的宽度。 */
    static final int MAX_AI = 7;

    /** 游戏模式选项：所有队伍彼此对战。 */
    static final int MODE_FREE_FOR_ALL = 4;

    private SinglePlayerGame() {
    }

    /**
     * 加载地图并打开游戏界面。
     *
     * @param from      发起游戏的界面
     * @param mapPath   地图的抽象存储路径，例如 {@code maps/skirmish/x.tmx}
     * @param aiCount   地图中的对手数量，限制在 {@code 1..}{@value #MAX_AI} 范围内
     * @param modeIndex 所选游戏模式项；其位置表示加入玩家一方的对手数量，最后一项代表各自为战
     * @return 游戏是否成功启动
     */
    static boolean start(Activity from, String mapPath, int aiCount, int modeIndex) {
        GameEngine engine = GameEngine.getInstance();
        if (engine == null) {
            Log.w(TAG, "The engine is not up; the map cannot be loaded.");
            Toast.makeText(from, LocalizedText.ui("ui.notLoaded"), Toast.LENGTH_SHORT).show();
            return false;
        }

        int opponents = Math.max(1, Math.min(MAX_AI, aiCount));
        int mode = Math.max(0, Math.min(MODE_FREE_FOR_ALL, modeIndex));
        // 模式列表中的位置表示加入玩家一方的 AI 数量。最后一项并不代表数量，而是让各支队伍相互对战，因此没有队伍加入玩家一侧，
        // 也不会绘制双方队伍。
        boolean freeForAll = mode == MODE_FREE_FOR_ALL;
        int allies = freeForAll ? 0 : mode;
        boolean asTeams = !freeForAll;
        boolean skirmish = MapSelectActivity.isSkirmishMap(mapPath);

        try {
            MapSelectActivity.loadMapWithAI(mapPath, skirmish, opponents, allies, asTeams, false);
        } catch (Throwable t) {
            Log.e(TAG, "The map could not be loaded: " + mapPath, t);
            Toast.makeText(from, LocalizedText.ui("ui.loadFailedPrefix") + mapPath + ": " + t.getMessage(),
                Toast.LENGTH_LONG).show();
            return false;
        }

        Log.i(TAG, "Starting " + mapPath + " against " + opponents + " opponents, "
            + allies + " of them allied" + (asTeams ? "." : ", as a free-for-all."));
        from.startActivity(new Intent(from, InGameActivity.class).putExtra("level", mapPath));
        return true;
    }
}
