/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.app.Activity;
import android.database.DataSetObserver;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListAdapter;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.TextView;

import io.github.rwml.api.Rwml;
import io.github.rwml.api.i18n.Messages;
import io.github.rwml.api.ui.GameUi;

/**
 * The game's own words, read from the game's language file.
 *
 * <p>A layout cannot call into the engine, so a label that has to be translated writes the engine's
 * locale key behind an {@code i:} marker, which is how the game's own layouts name their text. This
 * is where that marker is read: {@link #applyTo(View)} walks a view tree and replaces every marked
 * label with the engine's word, {@link #get} resolves one key, and {@link #setName} names one view
 * of an activity, which is what a screen needs for a button it wired in code.</p>
 *
 * <p>A key the language file does not answer leaves the label as the layout wrote it, and a language
 * file that cannot be read at all leaves every label alone. That is what the game's own screens do,
 * and it is not worth failing a screen over.</p>
 */
public final class LocalizedText {

    private static final String TAG = "RWML";

    /** What the game's layouts put in front of a locale key. */
    private static final String MARKER = "i:";

    private LocalizedText() {
    }

    /** RWML-owned labels use the shared bundle, which is also available before engine startup. */
    public static String ui(String key, Object... arguments) {
        return Rwml.get() == null ? Messages.fallback(key, arguments)
            : Rwml.get().ui().messages().text(key, arguments);
    }

    /** Called while a layout is attached, before a screen binds player-provided text into it. */
    static void applyLabels(View root) {
        if (root instanceof TextView && !(root instanceof EditText) && Rwml.get() != null) {
            TextView label = (TextView) root;
            label.setText(Rwml.get().ui().messages().label(label.getText().toString()));
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) { applyLabels(group.getChildAt(index)); }
        }
        applyTo(root);
    }

    /**
     * The engine's word for a locale key.
     *
     * @return the text, or {@code null} when the language file does not answer for the key
     */
    public static String get(String key) {
        if (key == null) {
            return null;
        }
        try {
            GameUi game = Rwml.get() == null ? null : Rwml.get().ui().find(GameUi.class);
            String value = game == null ? null : game.text(key);
            return value == null || value.isEmpty() ? null : value;
        } catch (Throwable notInTheLanguageFile) {
            // A key the language file does not carry is an ordinary answer rather than a fault: the
            // layout's own text stays, which is what the game's own screens do. It is a debug line
            // and not a warning, and the failure is not printed: the engine reports a miss by
            // throwing, so a stack trace here would say nothing about what went wrong.
            Log.d(TAG, "The language file has no word for '" + key + "'.");
            return null;
        }
    }

    /** The engine's word for a marked label, or {@code null} when the label carries no marker. */
    public static String resolve(CharSequence marked) {
        if (!isMarked(marked)) {
            return null;
        }
        return get(marked.toString().substring(MARKER.length()));
    }

    /**
     * Replaces every label under {@code root} that carries the marker with the engine's word.
     *
     * <p>The hint of an input field is resolved as well, since the layouts use it for the same
     * purpose.</p>
     */
    public static void applyTo(View root) {
        if (root == null) {
            return;
        }
        if (root instanceof Spinner) {
            Spinner spinner = (Spinner) root;
            if (spinner.getPrompt() != null && Rwml.get() != null) {
                spinner.setPrompt(Rwml.get().ui().messages().label(spinner.getPrompt().toString()));
            }
            SpinnerAdapter adapter = spinner.getAdapter();
            if (adapter != null && !(adapter instanceof TranslatedChoices)) {
                int selected = spinner.getSelectedItemPosition();
                spinner.setAdapter(new TranslatedChoices(adapter));
                if (selected >= 0 && selected < adapter.getCount()) { spinner.setSelection(selected); }
            }
        }
        if (root instanceof TextView) {
            TextView label = (TextView) root;
            String resolved = resolve(label.getText());
            if (resolved != null) {
                label.setText(resolved);
            }
            String hint = resolve(label.getHint());
            if (hint != null) {
                label.setHint(hint);
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyTo(group.getChildAt(i));
            }
        }
    }

    /**
     * The engine's word for a locale key, or a word to fall back on when the language file has none.
     *
     * <p>A label that is not part of a layout has no text of its own to keep, so a key that cannot
     * be read has to leave something readable behind. The fallback is the word the game's own
     * English language file carries for the key.</p>
     */
    public static String word(String key, String fallback) {
        String value = get(key);
        return value == null ? fallback : value;
    }

    /** Names one layout view in the engine's words, leaving the layout's own text when it cannot be read. */
    public static void setName(Activity owner, int id, String key) {
        View view = owner.findViewById(id);
        if (!(view instanceof TextView)) {
            return;
        }
        String value = get(key);
        if (value != null) {
            ((TextView) view).setText(value);
        }
    }

    private static boolean isMarked(CharSequence text) {
        return text != null
            && text.length() > MARKER.length()
            && text.charAt(0) == 'i'
            && text.charAt(1) == ':';
    }

    /** Translates rendered choices; getItem still returns the original value used by game commands. */
    private static final class TranslatedChoices extends BaseAdapter {
        private final SpinnerAdapter original;

        TranslatedChoices(SpinnerAdapter original) {
            this.original = original;
            original.registerDataSetObserver(new DataSetObserver() {
                @Override public void onChanged() { notifyDataSetChanged(); }
                @Override public void onInvalidated() { notifyDataSetInvalidated(); }
            });
        }

        @Override public int getCount() { return original.getCount(); }
        @Override public Object getItem(int position) { return original.getItem(position); }
        @Override public long getItemId(int position) { return original.getItemId(position); }
        @Override public boolean hasStableIds() { return original.hasStableIds(); }
        @Override public int getItemViewType(int position) { return original.getItemViewType(position); }
        @Override public int getViewTypeCount() { return original.getViewTypeCount(); }
        @Override public boolean isEnabled(int position) {
            return !(original instanceof ListAdapter) || ((ListAdapter) original).isEnabled(position);
        }
        @Override public boolean areAllItemsEnabled() {
            return !(original instanceof ListAdapter) || ((ListAdapter) original).areAllItemsEnabled();
        }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            View view = original.getView(position, recycled, parent);
            applyLabels(view);
            return view;
        }
        @Override public View getDropDownView(int position, View recycled, ViewGroup parent) {
            View view = original.getDropDownView(position, recycled, parent);
            applyLabels(view);
            return view;
        }
    }
}
