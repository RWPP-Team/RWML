/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.game;

/**
 * One named step of bringing the game up.
 *
 * <p>The game itself already has this shape: its engine reports each step it takes by setting a
 * loading message, and its desktop launcher does the same around the engine. A stage is that idea
 * lifted out of the game, so that a host and an extension can contribute steps to one sequence and
 * a loading screen can name them.</p>
 *
 * <p>A stage that throws does not stop the boot: the failure is recorded against the stage's name
 * and the remaining stages run. Starting a game with one broken feature beats not starting.</p>
 */
public interface GameStage {

    /**
     * The label reported while this stage runs, and the key its failure is recorded under.
     *
     * @return a short human-readable name, never null
     */
    String name();

    /**
     * Runs the step.
     *
     * @param context the running boot, never null
     * @throws Exception when the step could not be completed
     */
    void run(GameContext context) throws Exception;
}
