/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.builtin;

import io.github.rwml.api.ui.GameRulesUi;
import io.github.rwml.api.ui.UiServices;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.KeyboardFocusManager;
import java.awt.KeyEventDispatcher;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Opens the portable room-rule capability from the running desktop game. */
public final class DesktopGameRulesShortcut {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private DesktopGameRulesShortcut() { }

    public static void install(UiServices services) {
        if (!INSTALLED.compareAndSet(false, true)) { return; }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(new KeyEventDispatcher() {
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getID() != KeyEvent.KEY_PRESSED || event.getKeyCode() != KeyEvent.VK_R
                    || !event.isControlDown() || !event.isShiftDown()) { return false; }
                GameRulesUi rules = services.find(GameRulesUi.class);
                if (rules == null) { return false; }
                showRules(services, rules);
                return true;
            }
        });
    }

    private static void showRules(UiServices services, GameRulesUi rules) {
        Window owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        rules.refresh(new GameRulesUi.Callback() {
            @Override public void onSuccess(GameRulesUi.Snapshot snapshot) {
                SwingUtilities.invokeLater(() -> createRulesWindow(services, rules, owner, snapshot));
            }
            @Override public void onFailure(String message) {
                SwingUtilities.invokeLater(() -> error(services, owner, message));
            }
        });
    }

    private static void createRulesWindow(UiServices services, GameRulesUi rules, Window owner,
                                          GameRulesUi.Snapshot snapshot) {
        if (!snapshot.available()) { error(services, owner, text(services, "ui.rulesUnavailable")); return; }
        if (!snapshot.canEditRules()) { error(services, owner, text(services, "ui.hostOnly")); return; }

        JDialog dialog = new JDialog(owner, text(services, "ui.rulesTitle"), Dialog.ModalityType.MODELESS);
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel settings = new JPanel();
        settings.setLayout(new javax.swing.BoxLayout(settings, javax.swing.BoxLayout.Y_AXIS));
        JPanel modeRow = new JPanel(new BorderLayout(8, 0));
        modeRow.add(new JLabel(text(services, "ui.rulesMode")), BorderLayout.WEST);
        JComboBox<ModeChoice> mode = new JComboBox<>();
        mode.addItem(new ModeChoice(null, text(services, "ui.rulesMapMode")));
        for (GameRulesUi.ModeOption item : snapshot.baseModes()) {
            mode.addItem(new ModeChoice(item.id(), item.displayName()));
        }
        for (int index = 0; index < mode.getItemCount(); index++) {
            ModeChoice choice = mode.getItemAt(index);
            if (same(choice.id, snapshot.activeBaseModeId())) { mode.setSelectedIndex(index); break; }
        }
        modeRow.add(mode, BorderLayout.CENTER);
        settings.add(modeRow);

        JLabel speedText = new JLabel();
        JSlider speed = new JSlider(0, 100, Math.max(0, Math.min(100, Math.round(snapshot.gameSpeed() * 20f))));
        speed.addChangeListener(event -> speedText.setText(text(services, "ui.rulesSpeed",
            String.format(Locale.ROOT, "%.2fx", speed.getValue() / 20f))));
        speedText.setText(text(services, "ui.rulesSpeed",
            String.format(Locale.ROOT, "%.2fx", speed.getValue() / 20f)));
        settings.add(speedText);
        settings.add(speed);

        Set<String> banned = new HashSet<>(snapshot.bannedUnitIds());
        JPanel unitChecks = new JPanel(new GridLayout(0, 2, 6, 2));
        List<JCheckBox> checkboxes = new ArrayList<>();
        List<String> unitIds = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (GameRulesUi.UnitOption unit : snapshot.units()) {
            addUnit(unitChecks, checkboxes, unitIds, seen, banned, unit.id(), unit.displayName());
        }
        for (String id : banned) {
            if (!seen.contains(id)) { addUnit(unitChecks, checkboxes, unitIds, seen, banned, id, id); }
        }
        settings.add(new JLabel(text(services, "ui.rulesBanUnits")));
        settings.add(new JScrollPane(unitChecks));
        content.add(settings, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton ai = new JButton(text(services, "ui.aiControls"));
        ai.setEnabled(snapshot.canAddAi() || !snapshot.ais().isEmpty());
        ai.addActionListener(event -> createAiWindow(services, rules, dialog, owner));
        JButton apply = new JButton(text(services, "ui.apply"));
        apply.addActionListener(event -> {
            List<String> selected = new ArrayList<>();
            for (int index = 0; index < checkboxes.size(); index++) {
                if (checkboxes.get(index).isSelected()) { selected.add(unitIds.get(index)); }
            }
            ModeChoice selectedMode = (ModeChoice) mode.getSelectedItem();
            float multiplier = speed.getValue() / 20f;
            rules.apply(new GameRulesUi.Options(selectedMode == null ? null : selectedMode.id, multiplier, selected),
                callback(services, owner, dialog, text(services, "ui.rulesApplied")));
        });
        JButton close = new JButton(text(services, "common.close"));
        close.addActionListener(event -> dialog.dispose());
        buttons.add(ai);
        buttons.add(apply);
        buttons.add(close);
        content.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.setSize(620, 680);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    private static void createAiWindow(UiServices services, GameRulesUi rules, Window parent, Window owner) {
        rules.refresh(new GameRulesUi.Callback() {
            @Override public void onSuccess(GameRulesUi.Snapshot snapshot) {
                SwingUtilities.invokeLater(() -> {
                    JDialog dialog = new JDialog(parent, text(services, "ui.aiControls"), Dialog.ModalityType.MODELESS);
                    JPanel rows = new JPanel();
                    rows.setLayout(new javax.swing.BoxLayout(rows, javax.swing.BoxLayout.Y_AXIS));
                    if (snapshot.canAddAi()) {
                        JButton add = new JButton(text(services, "ui.addAi"));
                        add.addActionListener(event -> rules.addAi(GameRulesUi.AiOptions.defaults(),
                            refreshCallback(services, rules, dialog, owner)));
                        rows.add(add);
                    }
                    if (snapshot.ais().isEmpty()) {
                        rows.add(new JLabel(text(services, "ui.noAi")));
                    }
                    for (GameRulesUi.AiOption ai : snapshot.ais()) {
                        rows.add(aiRow(services, rules, ai, snapshot.canManageAi(), dialog, owner));
                    }
                    dialog.setContentPane(new JScrollPane(rows));
                    dialog.setSize(560, Math.min(600, 100 + 74 * Math.max(1, snapshot.ais().size())));
                    dialog.setLocationRelativeTo(owner);
                    dialog.setVisible(true);
                });
            }
            @Override public void onFailure(String message) {
                SwingUtilities.invokeLater(() -> error(services, owner, message));
            }
        });
    }

    private static JPanel aiRow(UiServices services, GameRulesUi rules, GameRulesUi.AiOption ai,
                                boolean canManage, JDialog parent, Window owner) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row.add(new JLabel(ai.name() + " · " + text(services, "ui.aiTeam", ai.teamId() + 1)));
        String[] labels = {text(services, "ui.aiInherit"), text(services, "ui.choice.veryEasy"),
            text(services, "ui.choice.easy"), text(services, "ui.choice.medium"), text(services, "ui.choice.hard"),
            text(services, "ui.choice.veryHard"), text(services, "ui.choice.impossible")};
        JComboBox<String> difficulty = new JComboBox<>(labels);
        difficulty.setSelectedIndex(ai.difficulty() == null ? 0 : Math.max(0, Math.min(6, ai.difficulty() + 3)));
        difficulty.setEnabled(canManage);
        JButton setDifficulty = new JButton(text(services, "ui.apply"));
        setDifficulty.setEnabled(canManage);
        setDifficulty.addActionListener(event -> {
            Integer value = difficulty.getSelectedIndex() == 0 ? null : difficulty.getSelectedIndex() - 3;
            rules.setAiDifficulty(ai.teamId(), value, refreshCallback(services, rules, parent, owner));
        });
        JButton pause = new JButton(text(services, ai.paused() ? "ui.aiResume" : "ui.aiPause"));
        pause.setEnabled(canManage);
        pause.addActionListener(event -> rules.setAiPaused(ai.teamId(), !ai.paused(),
            refreshCallback(services, rules, parent, owner)));
        JButton remove = new JButton(text(services, "ui.kick"));
        remove.setEnabled(canManage);
        remove.addActionListener(event -> rules.removeAi(ai.teamId(),
            refreshCallback(services, rules, parent, owner)));
        row.add(difficulty);
        row.add(setDifficulty);
        row.add(pause);
        row.add(remove);
        return row;
    }

    private static GameRulesUi.Callback callback(UiServices services, Window owner, JDialog close,
                                                String successMessage) {
        return new GameRulesUi.Callback() {
            @Override public void onSuccess(GameRulesUi.Snapshot snapshot) {
                SwingUtilities.invokeLater(() -> {
                    close.dispose();
                    if (successMessage != null && !successMessage.isEmpty()) {
                        JOptionPane.showMessageDialog(owner, successMessage, text(services, "ui.rulesTitle"),
                            JOptionPane.INFORMATION_MESSAGE);
                    }
                });
            }
            @Override public void onFailure(String message) {
                SwingUtilities.invokeLater(() -> error(services, owner, message));
            }
        };
    }

    private static GameRulesUi.Callback refreshCallback(UiServices services, GameRulesUi rules,
                                                        JDialog dialog, Window owner) {
        return new GameRulesUi.Callback() {
            @Override public void onSuccess(GameRulesUi.Snapshot snapshot) {
                SwingUtilities.invokeLater(() -> {
                    dialog.dispose();
                    createAiWindow(services, rules, owner, owner);
                });
            }
            @Override public void onFailure(String message) {
                SwingUtilities.invokeLater(() -> error(services, owner, message));
            }
        };
    }

    private static void addUnit(JPanel parent, List<JCheckBox> boxes, List<String> ids, Set<String> seen,
                                Set<String> banned, String id, String label) {
        if (id == null || id.isEmpty() || !seen.add(id)) { return; }
        JCheckBox box = new JCheckBox(label, banned.contains(id));
        parent.add(box);
        boxes.add(box);
        ids.add(id);
    }

    private static GameRulesUi.Callback callback(UiServices services, Window owner, JDialog close) {
        return callback(services, owner, close, null);
    }

    private static boolean same(Object first, Object second) {
        return first == null ? second == null : first.equals(second);
    }

    private static String text(UiServices services, String key, Object... arguments) {
        return services.messages().text(key, arguments);
    }

    private static void error(UiServices services, Window owner, String message) {
        JOptionPane.showMessageDialog(owner, message, text(services, "common.error"), JOptionPane.ERROR_MESSAGE);
    }

    private static final class ModeChoice {
        final String id;
        final String label;
        ModeChoice(String id, String label) { this.id = id; this.label = label; }
        @Override public String toString() { return label; }
    }
}
