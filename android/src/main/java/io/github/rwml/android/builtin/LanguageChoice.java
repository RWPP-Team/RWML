/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.builtin;

import android.app.Activity;
import android.app.AlertDialog;

import io.github.rwml.api.i18n.Messages;

import java.io.IOException;
import java.util.Arrays;

/** Language selection works before the game or its language file is loaded. */
final class LanguageChoice {
    private LanguageChoice() { }

    static void show(Activity activity, Messages messages, Runnable changed) {
        String[] tags = {"system", "en", "zh"};
        String[] names = {messages.text("language.system"), messages.text("language.en"),
            messages.text("language.zh")};
        int selected = Arrays.asList(tags).indexOf(messages.language());
        new AlertDialog.Builder(activity).setTitle(messages.text("language.title"))
            .setSingleChoiceItems(names, selected, (dialog, which) -> {
                try {
                    messages.language(tags[which]);
                    changed.run();
                    dialog.dismiss();
                } catch (IOException failure) {
                    new AlertDialog.Builder(activity).setTitle(messages.text("common.error"))
                        .setMessage(failure.getMessage()).setPositiveButton(messages.text("common.close"), null).show();
                }
            }).setNegativeButton(messages.text("common.cancel"), null).show();
    }
}
