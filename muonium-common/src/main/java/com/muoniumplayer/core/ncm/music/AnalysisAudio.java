package com.muoniumplayer.core.ncm.music;

import java.io.Closeable;

/**
 * 离线分析使用的有界单声道 PCM 窗口。打开与读取由后台所属线程完成。
 * 每个实现拥有独立解码器，不能读取正在播放的采样窗口；浮点采样保留解码精度与余量。
 * 无效请求、取消或解码失败返回空窗口；调用方必须关闭分析源。
 */
public interface AnalysisAudio extends Closeable {
    int sampleRate();
    float[] readMono(long startMillis, long lengthMillis);
    @Override void close();
}
