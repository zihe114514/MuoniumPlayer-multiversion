/*
 * Copyright 2010 Phil Burk, Mobileer Inc
 *
 * 依据 Apache 许可证 2.0 版（下称“许可证”）授权；
 * 除非符合许可规定，否则您不得使用此文件。
 * 您可以通过以下网址获取许可证副本：
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * 除非适用法律要求或书面同意，在许可证
 * 根据许可证分发是“按原样”分发的，
 * 在任何情况下，无论明示或默示，不提供任何担保或条件。
 * 有关许可证项下的权限，请参见许可证；
 * 许可项下的限制。
 */

package repackage.com.jsyn.data;

import java.util.ArrayList;

/**
 * FloatSample和ShortSample的基类。
 *
 * @author Phil Burk (C) 2010 Mobileer Inc
 */
public abstract class AudioSample extends SequentialDataCommon {
    protected int numFrames;
    protected int channelsPerFrame = 1;
    private double frameRate = 44100.0;
    private double pitch;
    private ArrayList<SampleMarker> markers;

    public abstract void allocate(int numFrames, int channelsPerFrame);

    @Override
    public double getRateScaler(int index, double synthesisRate) {
        return 1.0;
    }

    public double getFrameRate() {
        return frameRate;
    }

    public void setFrameRate(double f) {
        this.frameRate = f;
    }

    @Override
    public int getNumFrames() {
        return numFrames;
    }

    @Override
    public int getChannelsPerFrame() {
        return channelsPerFrame;
    }

    public void setChannelsPerFrame(int channelsPerFrame) {
        this.channelsPerFrame = channelsPerFrame;
    }

    /**
     * 将记录的音高设为小数 MIDI 半音值，其中 60 为中央 C。
     *
     * @param pitch
     */
    public void setPitch(double pitch) {
        this.pitch = pitch;
    }

    public double getPitch() {
        return pitch;
    }

    public int getMarkerCount() {
        if (markers == null)
            return 0;
        else
            return markers.size();
    }

    public SampleMarker getMarker(int index) {
        if (markers == null)
            return null;
        else
            return markers.get(index);
    }

    /**
     * 添加将按位置排序存储的标记。这通常由内部使用
     * SampleLoader.
     *
     * @param marker
     */
    public void addMarker(SampleMarker marker) {
        if (markers == null)
            markers = new ArrayList<>();
        int idx = markers.size();
        for (int k = 0; k < markers.size(); k++) {
            SampleMarker cue = markers.get(k);
            if (cue.position > marker.position) {
                idx = k;
                break;
            }
        }
        markers.add(idx, marker);
    }
}
