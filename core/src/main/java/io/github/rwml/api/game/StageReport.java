/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The outcome of one {@link GameBoot#start()}.
 *
 * <p>Every stage that ran is listed, in order, and every stage that failed is listed against the
 * reason it failed. The two are separate on purpose: a boot in which the fonts could not be loaded
 * still has the engine in it, and the caller needs to tell the difference between "this never got
 * a chance to run" and "this failed".</p>
 */
public final class StageReport {

    private final List<String> ran;
    private final Map<String, String> failed;

    StageReport(List<String> ran, Map<String, String> failed) {
        this.ran = Collections.unmodifiableList(new ArrayList<>(ran));
        this.failed = Collections.unmodifiableMap(new LinkedHashMap<>(failed));
    }

    /** @return the names of the stages that ran to completion, in the order they ran */
    public List<String> ran() {
        return ran;
    }

    /** @return the stages that failed, keyed by name with the reason as the value */
    public Map<String, String> failed() {
        return failed;
    }

    /** @return whether every stage ran to completion */
    public boolean isClean() {
        return failed.isEmpty();
    }

    /**
     * @return whether a stage of that name both ran and completed, which is how a caller asks
     *     whether the engine came up
     */
    public boolean contains(String name) {
        return ran.contains(name);
    }

    @Override
    public String toString() {
        return "StageReport{ran=" + ran + ", failed=" + failed + "}";
    }
}
