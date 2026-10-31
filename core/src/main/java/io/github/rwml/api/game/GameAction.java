/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.game;

/**
 * 以 lambda 表达的启动阶段。
 *
 * <p>为启动流程的每一步都声明一个类会带来不必要的样板代码，大多数步骤也只有少数几次调用。可通过
 * {@link GameBoot#add(String, GameAction)} 注册操作，并为其指定原本由 {@link GameStage} 提供的名称。</p>
 */
public interface GameAction {

    /**
     * 执行此步骤。
     *
     * @param context 正在运行的启动流程上下文，不为 null
     * @throws Exception 步骤无法完成时抛出
     */
    void run(GameContext context) throws Exception;
}
