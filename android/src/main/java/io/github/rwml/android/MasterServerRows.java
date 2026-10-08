/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Wire parser independent of Android and the mounted game classes. */
public final class MasterServerRows {
    public static List<String[]> parse(String response) throws IOException {
        String[] lines = response.split("\n");
        if (lines.length == 0 || !lines[0].contains("CORRODINGGAMES") || lines[0].contains("[FAILED]")) {
            throw new IOException("Invalid master server response");
        }
        List<String[]> rows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String[] row = lines[i].replace("\r", "").split(",", -1);
            if (row.length < 22) { continue; }
            try {
                int port = Integer.parseInt(row[5]);
                if (port < 1 || port > 65535) { continue; }
                if (!row[21].trim().isEmpty()) { Integer.parseInt(row[21]); }
            } catch (NumberFormatException invalid) { continue; }
            if (row[18].trim().isEmpty()) { row[18] = row[0]; }
            if (!row[18].trim().isEmpty()) { rows.add(row); }
        }
        return rows;
    }
}
