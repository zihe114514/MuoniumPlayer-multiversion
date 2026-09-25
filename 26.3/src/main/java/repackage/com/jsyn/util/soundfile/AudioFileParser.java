/*
 * 版权所有2001 Phil Burk ， Mobileer Inc
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

package repackage.com.jsyn.util.soundfile;

import repackage.com.jsyn.data.FloatSample;
import repackage.com.jsyn.data.SampleMarker;

import java.io.IOException;
import java.util.HashMap;

/**
 * 用于各种类型的音频特定文件解析器的基类。
 * 
 * @author (C) 2001 Phil Burk, SoftSynth.com
 */

abstract class AudioFileParser implements ChunkHandler {
    public IFFParser parser;
    public byte[] byteData;
    boolean ifLoadData = true; /* 若为 true，将声音数据加载到内存。 */
    public long dataPosition; /*
                        * 声音数据在文件中起始位置的字节数。
                        */
    public int bitsPerSample;
    public int bytesPerFrame; // 在文件中
    public int bytesPerSample; // 在文件中
    public HashMap<Integer, SampleMarker> cueMap = new HashMap<>();
    public short samplesPerFrame;
    public double frameRate;
    public int numFrames;
    public double originalPitch = 60.0;
    public int sustainBegin = -1;
    public int sustainEnd = -1;

    public AudioFileParser() {
    }

    /**
     * @return 声音数据所在的流开头的字节数。
     */
    public long getDataPosition() {
        return dataPosition;
    }

    /**
     * 当加载样本以确定有多少字节时，可以由另一个线程读取
     * 目前已读取。
     */
    public synchronized long getNumBytesRead() {
        IFFParser p = parser; // 防止竞态
        if (p != null)
            return p.getOffset();
        else
            return 0;
    }

    /**
     * 当加载示例以确定需要多少字节时，这可以由另一个线程读取
     * 待读取。
     */
    public synchronized long getFileSize() {
        IFFParser p = parser; // 防止竞态
        if (p != null)
            return p.getFileSize();
        else
            return 0;
    }

    public SampleMarker findOrCreateCuePoint(int uniqueID) {
        SampleMarker cuePoint = cueMap.computeIfAbsent(uniqueID, k -> new SampleMarker());
        return cuePoint;
    }

    public FloatSample load(IFFParser parser) throws IOException {
        this.parser = parser;
        parser.parseAfterHead(this);
        return finish();
    }

    abstract FloatSample finish() throws IOException;

    FloatSample makeSample(float[] floatData) {
        FloatSample floatSample = new FloatSample(floatData, samplesPerFrame);

        floatSample.setChannelsPerFrame(samplesPerFrame);
        floatSample.setFrameRate(frameRate);
        floatSample.setPitch(originalPitch);

        if (sustainBegin >= 0) {
            floatSample.setSustainBegin(sustainBegin);
            floatSample.setSustainEnd(sustainEnd);
        }

        for (SampleMarker marker : cueMap.values()) {
            floatSample.addMarker(marker);
        }

        /* 通过假设前两个标记是循环点来设置维持循环。*/
        if (floatSample.getMarkerCount() >= 2) {
            floatSample.setSustainBegin(floatSample.getMarker(0).position);
            floatSample.setSustainEnd(floatSample.getMarker(1).position);
        }
        return floatSample;
    }

    public String parseString(IFFParser parser, int textLength) throws IOException {
        byte[] bar = new byte[textLength];
        parser.read(bar);
        return new String(bar);
    }
}
