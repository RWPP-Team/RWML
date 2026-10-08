/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.hello;

import io.github.rwml.api.Extension;
import io.github.rwml.api.ExtensionContext;
import io.github.rwml.api.ExtensionInfo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Entry point of the sample extension.
 *
 * <p>It shows the three things an extension typically does right after it is loaded:</p>
 * <ol>
 *   <li>announce itself through the context logger;</li>
 *   <li>persist some state in its private data directory;</li>
 * </ol>
 */
@ExtensionInfo(
    id = "com.example.rwml.hello",
    name = "Hello Extension",
    version = "1.0.0",
    author = "RWPP Team",
    description = "Sample RWML extension: writes to its data directory, calls a mixin-patched "
        + "service, and declares an interface.",
    homepage = "https://github.com/RWPP-Team/RWML",
    tags = {"example", "hello"},
    // The same value the descriptor carries. The descriptor wins; this is what a build without one
    // falls back to.
    uiFramework = "io.github.rwml.hello.HelloUi"
)
public final class HelloExtension implements Extension {

    @Override
    public void onLoad(ExtensionContext context) throws Exception {
        context.log("Hello Extension starting up");

        Path marker = context.dataDirectory().resolve("last-load.txt");
        // Files.write rather than Files.writeString: the same extension also runs on Android, where
        // the writeString helper does not exist below API 33.
        Files.write(marker, Instant.now().toString().getBytes(StandardCharsets.UTF_8));
        context.log("Recorded this load in " + marker);
    }
}
