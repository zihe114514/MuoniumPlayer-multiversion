package com.muoniumplayer.automix.playback;

import repackage.processing.sound.Engine;

/** 将过渡执行器接到现有 JSyn 音频任务，不改变端口写入和平滑处理。 */
public enum AutomixAudioTasks implements AutomixTransitionEngine.AudioTasks {
    INSTANCE;

    @Override
    public void add(Runnable task) {
        Engine.addAudioTask(task);
    }

    @Override
    public void remove(Runnable task) {
        Engine.removeAudioTask(task);
    }
}
