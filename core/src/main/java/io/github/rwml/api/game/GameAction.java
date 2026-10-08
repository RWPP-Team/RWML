/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.game;

/**
 * A stage written as a lambda.
 *
 * <p>Declaring a class for every step of a boot would put more ceremony in the way than the steps
 * deserve, and most of them are a handful of calls. An action is registered through
 * {@link GameBoot#add(String, GameAction)}, which gives it the name a {@link GameStage} would have
 * had.</p>
 */
public interface GameAction {

    /**
     * Runs the step.
     *
     * @param context the running boot, never null
     * @throws Exception when the step could not be completed
     */
    void run(GameContext context) throws Exception;
}
