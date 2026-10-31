/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.game;

/**
 * 启动游戏过程中的一个命名步骤。
 *
 * <p>游戏本身已有类似流程：引擎通过设置加载消息报告各步骤，桌面启动器也会在引擎启动前后报告步骤。阶段将此概念抽离，
 * 使宿主和扩展都能向同一流程添加步骤，并让加载界面显示步骤名称。</p>
 *
 * <p>阶段抛出异常不会中断启动：系统会按阶段名称记录故障，并继续运行后续阶段。带有单个故障功能的游戏仍比完全无法启动好。</p>
 */
public interface GameStage {

    /**
     * 阶段运行期间显示的标签，也是记录阶段故障时使用的键。
     *
     * @return 简短易读的名称，不为 null
     */
    String name();

    /**
     * 执行此步骤。
     *
     * @param context 正在运行的启动流程上下文，不为 null
     * @throws Exception 步骤无法完成时抛出
     */
    void run(GameContext context) throws Exception;
}
