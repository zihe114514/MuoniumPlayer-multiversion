/*
 * Copyright 2009 Phil Burk, Mobileer Inc
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

package repackage.com.jsyn.devices.javasound;

import repackage.com.jsyn.devices.AudioDeviceInputStream;
import repackage.com.jsyn.devices.AudioDeviceManager;
import repackage.com.jsyn.devices.AudioDeviceOutputStream;

import javax.sound.sampled.*;
import java.util.ArrayList;

/**
 * 使用JavaSound访问音频硬件。
 *
 * @author Phil Burk (C) 2009 Mobileer Inc
 */
public class JavaSoundAudioDevice implements AudioDeviceManager {

    private static final int BYTES_PER_SAMPLE = 2;
    private static final boolean USE_BIG_ENDIAN = false;

    ArrayList<DeviceInfo> deviceRecords;
    private double suggestedOutputLatency = 0.040;
    private double suggestedInputLatency = 0.100;
    private int defaultInputDeviceID = -1;
    private int defaultOutputDeviceID = -1;

    public JavaSoundAudioDevice() {
        String osName = System.getProperty("os.name");
        if (osName.contains("Windows")) {
            suggestedOutputLatency = 0.08;
        }
        deviceRecords = new ArrayList<>();
        sniffAvailableMixers();
    }

    private void dumpAvailableMixers() {
        for (DeviceInfo deviceInfo : deviceRecords) {
            System.out.println("" + deviceInfo);
        }
    }

    /**
     * 构建设备信息并确定默认设备。
     */
    private void sniffAvailableMixers() {
        Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        for (int i = 0; i < mixers.length; i++) {
            DeviceInfo deviceInfo = new DeviceInfo();

            deviceInfo.name = mixers[i].getName();
            Mixer mixer = AudioSystem.getMixer(mixers[i]);

            Line.Info[] lines = mixer.getTargetLineInfo();
            deviceInfo.maxInputs = scanMaxChannels(lines);
            // 记住第一个支持输入的设备。
            if ((defaultInputDeviceID < 0) && (deviceInfo.maxInputs > 0)) {
                defaultInputDeviceID = i;
            }

            lines = mixer.getSourceLineInfo();
            deviceInfo.maxOutputs = scanMaxChannels(lines);
            // 记住第一个支持输出的设备。
            if ((defaultOutputDeviceID < 0) && (deviceInfo.maxOutputs > 0)) {
                defaultOutputDeviceID = i;
            }

            deviceRecords.add(deviceInfo);
        }
    }

    private int scanMaxChannels(Line.Info[] lines) {
        int maxChannels = 0;
        for (Line.Info line : lines) {
            if (line instanceof DataLine.Info) {
                int numChannels = scanMaxChannels(((DataLine.Info) line));
                if (numChannels > maxChannels) {
                    maxChannels = numChannels;
                }
            }
        }
        return maxChannels;
    }

    private int scanMaxChannels(DataLine.Info info) {
        int maxChannels = 0;
        for (AudioFormat format : info.getFormats()) {
            int numChannels = format.getChannels();
            if (numChannels > maxChannels) {
                maxChannels = numChannels;
            }
        }
        return maxChannels;
    }

    static class DeviceInfo {
        String name;
        int maxInputs;
        int maxOutputs;

        @Override
        public String toString() {
            return "AudioDevice: " + name + ", max in = " + maxInputs + ", max out = " + maxOutputs;
        }
    }

    private static class JavaSoundStream {
        AudioFormat format;
        byte[] bytes;
        int frameRate;
        int deviceID;
        int samplesPerFrame;

        public JavaSoundStream(int deviceID, int frameRate, int samplesPerFrame) {
            this.deviceID = deviceID;
            this.frameRate = frameRate;
            this.samplesPerFrame = samplesPerFrame;
            format = new AudioFormat(frameRate, 16, samplesPerFrame, true, USE_BIG_ENDIAN);
        }

        Line getDataLine(DataLine.Info info) throws LineUnavailableException {
            Line dataLine;
            if (deviceID >= 0) {
                Mixer.Info[] mixers = AudioSystem.getMixerInfo();
                Mixer mixer = AudioSystem.getMixer(mixers[deviceID]);
                dataLine = mixer.getLine(info);
            } else {
                dataLine = AudioSystem.getLine(info);
            }
            return dataLine;
        }

        int calculateBufferSize(double suggestedOutputLatency) {
            int numFrames = (int) (suggestedOutputLatency * frameRate);
            return numFrames * samplesPerFrame * BYTES_PER_SAMPLE;
        }

    }

    private class JavaSoundOutputStream extends JavaSoundStream implements AudioDeviceOutputStream {
        SourceDataLine line;

        public JavaSoundOutputStream(int deviceID, int frameRate, int samplesPerFrame) {
            super(deviceID, frameRate, samplesPerFrame);
        }

        @Override
        public void start() {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) {
                // TODO 处理该错误。
            } else {
                try {
                    line = (SourceDataLine) getDataLine(info);
                    int bufferSize = calculateBufferSize(suggestedOutputLatency);
                    line.open(format, bufferSize);
                    line.start();

                } catch (Exception e) {
                    e.printStackTrace();
                    line = null;
                }
            }
        }

        /** 效率极低。请改为调用数组版本。*/
        @Override
        public void write(double value) {
            double[] buffer = new double[1];
            buffer[0] = value;
            write(buffer, 0, 1);
        }

        @Override
        public void write(double[] buffer) {
            write(buffer, 0, buffer.length);
        }

        @Override
        public void write(double[] buffer, int start, int count) {
            // 如果需要，分配字节缓冲区。
            if ((bytes == null) || ((bytes.length * 2) < count)) {
                bytes = new byte[count * 2];
            }

            // 将float示例转换为LittleEndian字节。
            int byteIndex = 0;
            for (int i = 0; i < count; i++) {
                // 在强制类型转换前先做偏移，以避免使用 floor()。
                // 同时加上 0.5 进行四舍五入，使极小的信号归零。
                double temp = (32767.0 * buffer[i + start]) + 32768.5;
                int sample = ((int) temp) - 32768;
                if (sample > Short.MAX_VALUE) {
                    sample = Short.MAX_VALUE;
                } else if (sample < Short.MIN_VALUE) {
                    sample = Short.MIN_VALUE;
                }
                bytes[byteIndex++] = (byte) sample; // 小端
                bytes[byteIndex++] = (byte) (sample >> 8); // 大端
            }

            line.write(bytes, 0, byteIndex);
        }

        @Override
        public void stop() {
            if (line != null) {
                line.stop();
                line.flush();
                line.close();
                line = null;
            } else {
                new RuntimeException("AudioOutput stop attempted when no line created.")
                        .printStackTrace();
            }
        }

        @Override
        public double getLatency() {
            if (line == null) {
                return 0.0;
            }
            int numBytes = line.getBufferSize();
            int numFrames = numBytes / (BYTES_PER_SAMPLE * samplesPerFrame);
            return ((double) numFrames) / frameRate;
        }

        @Override
        public void close() {
        }

    }

    private class JavaSoundInputStream extends JavaSoundStream implements AudioDeviceInputStream {
        TargetDataLine line;

        public JavaSoundInputStream(int deviceID, int frameRate, int samplesPerFrame) {
            super(deviceID, frameRate, samplesPerFrame);
        }

        @Override
        public void start() {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) {
                // TODO 处理该错误。
            } else {
                try {
                    line = (TargetDataLine) getDataLine(info);
                    int bufferSize = calculateBufferSize(suggestedInputLatency);
                    line.open(format, bufferSize);
                    line.start();
                } catch (Exception e) {
                    e.printStackTrace();
                    line = null;
                }
            }
        }

        @Override
        public double read() {
            double[] buffer = new double[1];
            read(buffer, 0, 1);
            return buffer[0];
        }

        @Override
        public int read(double[] buffer) {
            return read(buffer, 0, buffer.length);
        }

        @Override
        public int read(double[] buffer, int start, int count) {
            // 如果需要，分配字节缓冲区。
            if ((bytes == null) || ((bytes.length * 2) < count)) {
                bytes = new byte[count * 2];
            }
            int bytesRead = line.read(bytes, 0, bytes.length);

            // 将BigEndian字节转换为浮点样本
            int bi = 0;
            for (int i = 0; i < count; i++) {
                int sample = bytes[bi++] & 0x00FF; // 小端
                sample = sample + (bytes[bi++] << 8); // 大端
                buffer[i + start] = sample * (1.0 / 32767.0);
            }
            return bytesRead / 4;
        }

        @Override
        public void stop() {
            if (line != null) {
                line.drain();
                line.close();
            } else {
                new RuntimeException("AudioInput stop attempted when no line created.")
                        .printStackTrace();
            }
        }

        @Override
        public double getLatency() {
            if (line == null) {
                return 0.0;
            }
            int numBytes = line.getBufferSize();
            int numFrames = numBytes / (BYTES_PER_SAMPLE * samplesPerFrame);
            return ((double) numFrames) / frameRate;
        }

        @Override
        public int available() {
            return line.available() / BYTES_PER_SAMPLE;
        }

        @Override
        public void close() {
        }

    }

    @Override
    public AudioDeviceOutputStream createOutputStream(int deviceID, int frameRate,
            int samplesPerFrame) {
        return new JavaSoundOutputStream(deviceID, frameRate, samplesPerFrame);
    }

    @Override
    public AudioDeviceInputStream createInputStream(int deviceID, int frameRate, int samplesPerFrame) {
        return new JavaSoundInputStream(deviceID, frameRate, samplesPerFrame);
    }

    @Override
    public double getDefaultHighInputLatency(int deviceID) {
        return 0.300;
    }

    @Override
    public double getDefaultHighOutputLatency(int deviceID) {
        return 0.300;
    }

    @Override
    public int getDefaultInputDeviceID() {
        return defaultInputDeviceID;
    }

    @Override
    public int getDefaultOutputDeviceID() {
        return defaultOutputDeviceID;
    }

    @Override
    public double getDefaultLowInputLatency(int deviceID) {
        return 0.100;
    }

    @Override
    public double getDefaultLowOutputLatency(int deviceID) {
        return 0.100;
    }

    @Override
    public int getDeviceCount() {
        return deviceRecords.size();
    }

    @Override
    public String getDeviceName(int deviceID) {
        return deviceRecords.get(deviceID).name;
    }

    @Override
    public int getMaxInputChannels(int deviceID) {
        return deviceRecords.get(deviceID).maxInputs;
    }

    @Override
    public int getMaxOutputChannels(int deviceID) {
        return deviceRecords.get(deviceID).maxOutputs;
    }

    @Override
    public int setSuggestedOutputLatency(double latency) {
        suggestedOutputLatency = latency;
        return 0;
    }

    @Override
    public int setSuggestedInputLatency(double latency) {
        suggestedInputLatency = latency;
        return 0;
    }

    @Override
    public String getName() {
        return "JavaSound";
    }

}
