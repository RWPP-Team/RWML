/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Portable game-rule controls exposed to host UIs without exposing engine objects. */
public interface GameRulesUi {

    interface Callback {
        void onSuccess(Snapshot snapshot);
        void onFailure(String message);
    }

    /** Reads current room rules and AI state asynchronously. */
    void refresh(Callback callback);

    /** Replaces the selected base mode, speed modifier and banned unit list. */
    void apply(Options options, Callback callback);

    /** Starts through the shared game-room API so active map-transform modes are applied. */
    void startGame(Callback callback);

    /** Adds an AI through the API manager, or sends the native request as a proxy controller. */
    void addAi(AiOptions options, Callback callback);

    void removeAi(int teamId, Callback callback);
    void setAiPaused(int teamId, boolean paused, Callback callback);
    void setAiDifficulty(int teamId, Integer difficulty, Callback callback);

    final class Snapshot {
        private final boolean available;
        private final boolean canEditRules;
        private final boolean canAddAi;
        private final boolean canManageAi;
        private final String activeBaseModeId;
        private final float gameSpeed;
        private final List<ModeOption> baseModes;
        private final List<UnitOption> units;
        private final List<String> bannedUnitIds;
        private final List<AiOption> ais;

        public Snapshot(boolean available, boolean canEditRules, boolean canAddAi, boolean canManageAi,
                        String activeBaseModeId, float gameSpeed, List<ModeOption> baseModes,
                        List<UnitOption> units, List<String> bannedUnitIds, List<AiOption> ais) {
            this.available = available;
            this.canEditRules = canEditRules;
            this.canAddAi = canAddAi;
            this.canManageAi = canManageAi;
            this.activeBaseModeId = activeBaseModeId;
            this.gameSpeed = gameSpeed;
            this.baseModes = immutable(baseModes);
            this.units = immutable(units);
            this.bannedUnitIds = immutable(bannedUnitIds);
            this.ais = immutable(ais);
        }

        public boolean available() { return available; }
        public boolean canEditRules() { return canEditRules; }
        public boolean canAddAi() { return canAddAi; }
        public boolean canManageAi() { return canManageAi; }
        public String activeBaseModeId() { return activeBaseModeId; }
        public float gameSpeed() { return gameSpeed; }
        public List<ModeOption> baseModes() { return baseModes; }
        public List<UnitOption> units() { return units; }
        public List<String> bannedUnitIds() { return bannedUnitIds; }
        public List<AiOption> ais() { return ais; }
    }

    final class Options {
        private final String baseModeId;
        private final float gameSpeed;
        private final List<String> bannedUnitIds;

        public Options(String baseModeId, float gameSpeed, List<String> bannedUnitIds) {
            this.baseModeId = baseModeId;
            this.gameSpeed = gameSpeed;
            this.bannedUnitIds = immutable(bannedUnitIds);
        }

        /** Null keeps the selected map's native rules. */
        public String baseModeId() { return baseModeId; }
        public float gameSpeed() { return gameSpeed; }
        public List<String> bannedUnitIds() { return bannedUnitIds; }
    }

    final class AiOptions {
        private final Integer allyTeam;
        private final Integer difficulty;
        private final String name;

        public AiOptions(Integer allyTeam, Integer difficulty, String name) {
            this.allyTeam = allyTeam;
            this.difficulty = difficulty;
            this.name = name;
        }

        public Integer allyTeam() { return allyTeam; }
        public Integer difficulty() { return difficulty; }
        public String name() { return name; }

        public static AiOptions defaults() { return new AiOptions(null, null, null); }
    }

    final class ModeOption {
        private final String id;
        private final String displayName;

        public ModeOption(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() { return id; }
        public String displayName() { return displayName; }
    }

    final class UnitOption {
        private final String id;
        private final String displayName;

        public UnitOption(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() { return id; }
        public String displayName() { return displayName; }
    }

    final class AiOption {
        private final int teamId;
        private final String name;
        private final int allyTeam;
        private final Integer difficulty;
        private final boolean paused;

        public AiOption(int teamId, String name, int allyTeam, Integer difficulty, boolean paused) {
            this.teamId = teamId;
            this.name = name;
            this.allyTeam = allyTeam;
            this.difficulty = difficulty;
            this.paused = paused;
        }

        public int teamId() { return teamId; }
        public String name() { return name; }
        public int allyTeam() { return allyTeam; }
        /** Null means inherit the room's AI difficulty. */
        public Integer difficulty() { return difficulty; }
        public boolean paused() { return paused; }
    }

    static <T> List<T> immutable(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values == null
            ? Collections.emptyList() : values));
    }
}
