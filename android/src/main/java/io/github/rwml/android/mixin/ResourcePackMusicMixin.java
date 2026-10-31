/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android.mixin;

import android.media.MediaPlayer;

import io.github.rwml.android.HostLog;
import io.github.rwml.game.ResourcePackAssets;

import java.io.File;

import kim.der.asm.api.annotation.AsmInject;
import kim.der.asm.api.annotation.AsmMixin;
import kim.der.asm.api.annotation.CallbackInfo;
import kim.der.asm.api.annotation.InjectionPoint;

/**
 * 从资源包播放背景音乐。
 *
 * <p>音乐播放器按资源描述符打开归档中的音乐，而资源包的文件位于普通目录，平台无法用同一个入口读取。此处改为以文件路径
 * 设置数据源，并按播放器自身的流程准备：音量先归零，由控制器随后按设置调整，循环与准备回调保持原行为。</p>
 *
 * <p>资源包未提供该曲目时保持原方法执行，因此只有被替换的曲目才走这条路径。</p>
 */
@AsmMixin("com/corrodinggames/rts/gameFramework/MusicControllerAndroidMusicFactoryAndroidMusicSteam")
public final class ResourcePackMusicMixin {

    private static volatile boolean announced;

    private ResourcePackMusicMixin() {
    }

    @AsmInject(method = "playTrack(Z)V", target = InjectionPoint.HEAD, cancellable = true, require = 1)
    public static void playPackedTrack(CallbackInfo callbackInfo, Object self, boolean loop) {
        Object handle = MixinFields.read(self, "trackHandle");
        Object path = MixinFields.read(handle, "trackPath");
        if (!(path instanceof String)) {
            return;
        }
        String track = (String) path;
        if (!track.startsWith("music")) {
            return;
        }
        File file = ResourcePackAssets.file(track);
        if (file == null) {
            return;
        }
        MediaPlayer player = (MediaPlayer) MixinFields.read(self, "mediaPlayer");
        if (player == null) {
            return;
        }
        try {
            player.reset();
            player.setDataSource(file.getAbsolutePath());
            if (loop) {
                player.setLooping(true);
            }
            player.setVolume(0f, 0f);
            player.setOnInfoListener((mediaPlayer, what, extra) -> true);
            player.setOnPreparedListener(MediaPlayer::start);
            player.prepareAsync();
        } catch (Exception unavailable) {
            HostLog.warn("could not play " + file + " from the resource pack", unavailable);
            return;
        }
        if (!announced) {
            announced = true;
            HostLog.add("mixin      ResourcePackMusicMixin is in: music comes from the resource pack");
        }
        callbackInfo.cancel();
    }
}
