package com.muoniumplayer.core.ncm.music;

import repackage.com.jsyn.engine.SynthesisEngine;
import repackage.processing.sound.Engine;

/**
 * JSyn 引擎最终混音输出的<b>多路分发观察者</b>。
 *
 * <p>{@link SynthesisEngine} 的 {@code OutputObserver} 是<b>单槽位</b>：后挂的会把先挂的顶掉，
 * 而且引擎端只要观察者抛过一次异常就把它永久摘除（{@code observeOutput} 里的 {@code outputObserver = null}）。
 * 频谱可视化（{@link AudioSpectrumAnalyzer}）一直占着这个槽；语音广播（{@link VoicechatBroadcast}）
 * 也要同一条 PCM —— 两者只能共存于一个分发器后面。</p>
 *
 * <p>分发语义：</p>
 * <ul>
 *   <li><b>挂接时机就是既有约定的那个</b>：调用方（频谱的 {@code requestFrame}、广播的
 *       {@link VoicechatBroadcast#ensureInstalled()}）都只在引擎已经该存在的时候来挂，这里不负责
 *       创建引擎的时序 —— 提前建引擎会破坏 {@code installAudioDeviceFallbackIfMissing} 的设备兜底顺序。</li>
 *   <li><b>逐监听隔离</b>：任何一个监听抛 Throwable 都被吞掉且不影响其余监听、更不影响引擎
 *       （分发器本身永不抛，槽位就不会被引擎摘掉）。</li>
 *   <li><b>监听仍须遵守引擎契约</b>：音频线程上运行 —— 不许阻塞、不许改传入缓冲、尽量零分配。</li>
 * </ul>
 */
public final class EngineOutputTap {

    /** 与引擎端契约一致的只读回调：交错 PCM（-1..1），channels 声道，sampleRate 采样率。 */
    public interface Listener {
        void onOutput(double[] interleaved, int channels, int sampleRate);
    }

    private static final Listener[] EMPTY = new Listener[0];

    /** 只增不改的监听表：加监听时整体换新数组（音频线程无锁迭代）。 */
    private static volatile Listener[] listeners = EMPTY;

    private EngineOutputTap() {
    }

    /**
     * 把分发器挂上引擎输出观察者槽。幂等：重复挂是同一个分发器，不产生叠层。
     * 注意<b>引擎会同时被此调用创建</b>——只许在 {@code loadUsableSoundFile} 这类
     * 已经约定好「此刻引擎该在」的时机调用。
     */
    public static void install() {
        Engine.getEngine().setOutputObserver(EngineOutputTap::dispatch);
    }

    /** 注册一个监听。重复注册同一实例会收到重复回调，由调用方自己用标志位防（同频谱的 {@code attached}）。 */
    public static void addListener(Listener listener) {
        if (listener == null) return;
        synchronized (EngineOutputTap.class) {
            Listener[] current = listeners;
            Listener[] next = new Listener[current.length + 1];
            System.arraycopy(current, 0, next, 0, current.length);
            next[current.length] = listener;
            listeners = next;
        }
    }

    /** 音频线程入口：逐监听隔离分发。这里任何一行都不许抛、不许阻塞、不许分配。 */
    private static void dispatch(double[] interleaved, int channels, int sampleRate) {
        Listener[] current = listeners;
        for (int i = 0; i < current.length; i++) {
            try {
                current[i].onOutput(interleaved, channels, sampleRate);
            } catch (Throwable ignored) {
                // 单个监听炸了不许波及别的监听，更不许把引擎端的观察者槽炸掉。
            }
        }
    }
}
