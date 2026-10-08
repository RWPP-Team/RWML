/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.corrodinggames.rts.game.units.custom.CustomUnitMetadataLoader;
import com.corrodinggames.rts.gameFramework.GameEngine;
import com.corrodinggames.rts.gameFramework.SettingsEngine;
import com.corrodinggames.rts.gameFramework.files.FileLoader;
import com.corrodinggames.rts.gameFramework.mod.Mod;
import com.corrodinggames.rts.gameFramework.mod.ModManager;

import java.util.ArrayList;
import java.util.List;

import io.github.rwml.android.R;

/**
 * The mods screen: every installed mod as a checkbox, in the game's own mods layout.
 *
 * <p>A tick means the mod is enabled. The list is the engine's own, read through
 * {@link ModManager}, and it is read the way the game's screen reads it: a refresh scans the mod
 * folders, which is also what the Reload button does. Saving writes each mod's ticked state back
 * through the manager, which records the selection in the engine's settings, and then saves them.</p>
 *
 * <p>Importing a mod is the platform's file picker: the chosen archive is copied into the mods
 * folder and the list is refreshed. Creating the folder makes the folder a mod would be copied
 * into. Linking a folder through the storage-access framework belongs to the Android build's file
 * browser, which this host does not carry, so that button stays hidden.</p>
 */
public final class ModsActivity extends Activity {

    private static final String TAG = "RWML";

    /** The request code the platform's file picker answers with. */
    private static final int REQUEST_IMPORT = 5;

    private SettingsEngine settings;
    private ModManager mods;
    private LinearLayout container;

    /** One row of the list: the mod it stands for, and the box that shows its state. */
    private final List<Row> rows = new ArrayList<>();

    private static final class Row {
        final Mod mod;
        final CheckBox box;

        Row(Mod mod, CheckBox box) {
            this.mod = mod;
            this.box = box;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Mods");
        AndroidApp.prepare(this, true);
        setContentView(R.layout.mods);

        GameEngine engine = GameEngine.getInstance();
        if (engine == null) {
            Log.w(TAG, "The engine is not up; the mods screen cannot bind.");
            Toast.makeText(this, "The game is not loaded yet.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        settings = engine.settings;
        mods = engine.modManager;
        if (mods == null) {
            Log.w(TAG, "The engine carries no mod manager.");
            Toast.makeText(this, "The game is not loaded yet.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        container = findViewById(R.id.modsContainer);
        hide(R.id.modsLinkModFolder);
        hide(R.id.messageInfo);

        listMods();
        onclick(R.id.modsSave, this::save);
        onclick(R.id.modsReload, this::reload);
        onclick(R.id.modsCreateFolder, this::createFolder);
        onclick(R.id.modsImportMod, this::chooseArchive);
        onclick(R.id.modsClose, this::finish);
        LocalizedText.applyTo(findViewById(android.R.id.content));
        Log.i(TAG, "Mods open.");
    }

    // ------------------------------------------------------------------
    // The list
    // ------------------------------------------------------------------

    /** Rebuilds the list from the engine's own mod list. */
    private void listMods() {
        if (container == null) {
            return;
        }
        container.removeAllViews();
        rows.clear();

        List<Mod> installed = new ArrayList<>();
        try {
            Object listed = mods.refreshModList();
            if (listed instanceof List) {
                for (Object entry : (List<?>) listed) {
                    if (entry instanceof Mod) {
                        installed.add((Mod) entry);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "The mod list could not be read.", t);
        }

        fillInfo();
        for (Mod mod : installed) {
            container.addView(rowFor(mod));
        }
        if (installed.isEmpty()) {
            container.addView(message("No mods found."));
        }
    }

    /** The storage note above the list, plus whatever the manager last reported as a load problem. */
    private void fillInfo() {
        View view = findViewById(R.id.modExtraTextInfo);
        if (!(view instanceof TextView)) {
            return;
        }
        StringBuilder text = new StringBuilder();
        if (settings != null && settings.storageType == 0) {
            text.append("Note: Using internal game storage only")
                .append(" (Switch to external storage in game settings)");
        }
        if (ModManager.a != null) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(ModManager.a);
        }
        ((TextView) view).setText(text.toString());
    }

    /** One mod: its title in a checkbox, and whatever the engine says about its state. */
    private View rowFor(Mod mod) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(android.view.Gravity.CENTER);
        row.setBackgroundColor(Color.argb(30, 0, 0, 0));
        int padding = dip(7);
        row.setPadding(dip(2), padding, dip(2), padding);

        CheckBox box = new CheckBox(this);
        box.setChecked(!mod.disabled);
        box.setText(mod.getTitle());
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20.0F);
        box.setTextColor(Color.WHITE);
        row.addView(box);

        String description = safe(mod.getDescriptionWithStorage());
        if (description != null) {
            row.addView(line("  " + description, Color.rgb(0, 255, 0), 14.0F));
        }
        String status = safe(mod.getStatusMessage());
        if (status != null) {
            row.addView(line(status, Color.rgb(255, 255, 255), 14.0F));
        }
        if (mod.errorMessage != null) {
            TextView error = line("  " + mod.errorMessage, Color.rgb(255, 120, 120), 14.0F);
            error.setBackgroundColor(Color.argb(108, 0, 0, 0));
            row.addView(error);
        }

        rows.add(new Row(mod, box));
        return row;
    }

    private TextView line(String text, int color, float sizeSp) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTypeface(Typeface.DEFAULT);
        return view;
    }

    private TextView message(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setGravity(android.view.Gravity.CENTER);
        view.setPadding(0, dip(10), 0, dip(10));
        return view;
    }

    // ------------------------------------------------------------------
    // The buttons
    // ------------------------------------------------------------------

    /** Writes each ticked state back through the manager, and saves the selection. */
    private void save() {
        for (Row row : rows) {
            row.mod.disabled = !row.box.isChecked();
        }
        try {
            mods.saveSelection();
            if (settings != null) {
                settings.save();
            }
        } catch (Throwable t) {
            Log.w(TAG, "The mod selection could not be saved.", t);
            Toast.makeText(this, "The mod selection could not be saved.", Toast.LENGTH_LONG).show();
            return;
        }

        int errored = 0;
        try {
            errored = mods.countErroredEnabledMods();
        } catch (Throwable t) {
            Log.w(TAG, "The mod errors could not be counted.", t);
        }
        String message = errored == 0
            ? "Mod changes saved. They are used the next time a game is started."
            : "Mod changes saved. " + errored + " enabled mods have errors.";
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }

    /** Re-reads the mod folders, which is what the game's Reload button does. */
    private void reload() {
        listMods();
        LocalizedText.applyTo(findViewById(android.R.id.content));
        Toast.makeText(this, "Mod list reloaded.", Toast.LENGTH_SHORT).show();
    }

    /** Creates the folder a mod is installed into, when it is not there yet. */
    private void createFolder() {
        String directory;
        boolean ready;
        try {
            directory = CustomUnitMetadataLoader.getModsUnitsDirectory();
            // A folder that already exists is as good an outcome as one that was just made.
            ready = FileLoader.createDirectory(directory) || FileLoader.isDirectory(directory);
        } catch (Throwable t) {
            Log.w(TAG, "The mods folder could not be created.", t);
            Toast.makeText(this, "The mods folder could not be created.", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this,
            ready ? "Mods folder ready: " + directory : "Could not create: " + directory,
            Toast.LENGTH_LONG).show();
    }

    /** Asks the platform for an archive to install as a mod. */
    private void chooseArchive() {
        if (!FileImport.choose(this, REQUEST_IMPORT)) {
            Toast.makeText(this, "No file picker is available on this device.", Toast.LENGTH_LONG).show();
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
        importMod(data.getData());
    }

    /** Copies the picked archive into the mods folder, then re-reads the list. */
    private void importMod(Uri source) {
        String name = FileImport.nameOf(this, source);
        if (TextUtils.isEmpty(name)) {
            Toast.makeText(this, "Cannot import: the file has no name.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!name.endsWith(".zip") && !name.endsWith(".rwmod")) {
            new AlertDialog.Builder(this)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle("Cannot import")
                .setMessage("Expected a .zip or .rwmod file, but got: " + name)
                .setPositiveButton("Ok", null)
                .show();
            return;
        }

        String directory;
        try {
            directory = CustomUnitMetadataLoader.getModsUnitsDirectory();
            FileLoader.createDirectory(directory);
        } catch (Throwable t) {
            Log.w(TAG, "The mods folder could not be reached.", t);
            Toast.makeText(this, "The mods folder could not be reached.", Toast.LENGTH_LONG).show();
            return;
        }

        String failure = FileImport.copyInto(this, source, FileImport.join(directory, name));
        if (failure != null) {
            Toast.makeText(this, "Failed to import " + name + ": " + failure,
                Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(this, "Imported " + name + ".", Toast.LENGTH_SHORT).show();
        listMods();
        LocalizedText.applyTo(findViewById(android.R.id.content));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void onclick(int id, Runnable action) {
        View view = findViewById(id);
        if (view == null) {
            Log.w(TAG, "The mods layout has no view for " + id + ".");
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

    private static String safe(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
