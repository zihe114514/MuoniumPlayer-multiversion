/*
 * Copyright 2011 Phil Burk, Mobileer Inc
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

package repackage.com.jsyn.util;

import lombok.SneakyThrows;
import repackage.com.jsyn.data.FloatSample;
import repackage.com.jsyn.util.soundfile.CustomSampleLoader;
import repackage.com.jsyn.util.soundfile.streamed.buffered.BufferedSampleLoader;
import repackage.com.jsyn.util.soundfile.streamed.raf.RafSampleLoader;
import repackage.javazoom.jl.converter.Converter;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;

/**
 * 从各种来源加载FloatSample。默认加载器使用自定义代码加载WAV或AIF
 * 文件。支持的数据格式为16位、24位和32位PCM ，以及32位浮点数。压缩格式
 * 如无符号 8 位、uLaw、A-Law 和 MP3 等不受支持。若你需要加载其中之一
 * files try setJavaSoundPreferred(true).或使用Audacity或SOX将其转换为受支持的格式
 * 或其他示例文件工具。以下是从文件加载示例的示例。
 *
 * <pre>
 * <code>
 *     File sampleFile = new File("guitar.wav");
 *     FloatSample sample = SampleLoader.loadFloatSample(sampleFile);
 * </code>
 * </pre>
 *
 * @author Phil Burk (C) 2011 Mobileer Inc
 */
public class SampleLoader {
    private static boolean javaSoundPreferred = false;
    private static final String JS_LOADER_NAME = "com.jsyn.util.JavaSoundSampleLoader";

    /**
     * 尝试创建 AudioSampleLoader 的实现。
     *
     * 此平台支持@return A设备。
     */
    private static AudioSampleLoader createLoader() {
        return new CustomSampleLoader();
    }

    /**
     * 从 File 对象加载 FloatSample。
     */
    public static FloatSample loadFloatSample(File fileIn) throws IOException {
        AudioSampleLoader loader = SampleLoader.createLoader();
        return loader.loadFloatSample(fileIn);
    }

    /**
     * 从 InputStream 加载 FloatSample。从 JAR 文件加载资源时很方便。
     */
    public static FloatSample loadFloatSample(InputStream inputStream) throws IOException {
        AudioSampleLoader loader = SampleLoader.createLoader();
        return loader.loadFloatSample(inputStream);
    }

    @SneakyThrows
    public static FloatSample loadStreamedFloatSample(File fileIn) {

        String name = fileIn.getName();
        if (name.endsWith(".flac")) {
            return new BufferedSampleLoader().loadFromFlacStream(new BufferedInputStream(Files.newInputStream(fileIn.toPath())));
        } else if (name.endsWith(".wav")) {
            return new RafSampleLoader().loadFloatSample(new RandomAccessFile(fileIn, "r"));
        } else if (name.endsWith(".mp3")) {
            Converter converter = new Converter();
            return loadFloatSample(new ByteArrayInputStream(converter.convert(Files.newInputStream(fileIn.toPath()), null, null)));
        } else {
            throw new RuntimeException("Extension not supported: " + name.substring(name.lastIndexOf(".")));
        }

    }

    /**
     * 从URL加载FloatSample。这在从网站加载资源时很方便。
     */
    public static FloatSample loadFloatSample(URL url) throws IOException {
        AudioSampleLoader loader = SampleLoader.createLoader();
        return loader.loadFloatSample(url);
    }

    public static boolean isJavaSoundPreferred() {
        return javaSoundPreferred;
    }

    /**
     * 如果设置为true ，则将使用来自JavaSound的音频文件解析器。请注意， JavaSound
     * 无法加载包含浮点数据的音频文件。但它也许能加载一些
     * 如 uLaw 之类的压缩数据格式。
     *
     * 注意： Android不支持JavaSound。
     *
     * @param javaSoundPreferred
     */
    public static void setJavaSoundPreferred(boolean javaSoundPreferred) {
        SampleLoader.javaSoundPreferred = javaSoundPreferred;
    }

    /**
     * 从大端字节数组解码 24 位采样到 float 数组。采样将被
     * 归一化到 -1.0 到 +1.0 范围。
     *
     * @param audioBytes 来自音频文件的原始数据
     * @param offset 字节数组的第一个元素
     * @param numBytes 要处理的字节数
     * @param data 用于填充 float 的数组
     * @param outputOffset 要填充的浮点数组的第一个元素
     */
    public static void decodeBigI24ToF32(byte[] audioBytes, int offset, int numBytes, float[] data,
            int outputOffset) {
        int lastByte = offset + numBytes;
        int byteCursor = offset;
        int floatCursor = outputOffset;
        while (byteCursor < lastByte) {
            int hi = ((audioBytes[byteCursor++]) & 0x00FF);
            int mid = ((audioBytes[byteCursor++]) & 0x00FF);
            int lo = ((audioBytes[byteCursor++]) & 0x00FF);
            int value = (hi << 24) | (mid << 16) | (lo << 8);
            data[floatCursor++] = value * (1.0f / Integer.MAX_VALUE);
        }
    }

    public static void decodeBigI16ToF32(byte[] audioBytes, int offset, int numBytes, float[] data,
            int outputOffset) {
        int lastByte = offset + numBytes;
        int byteCursor = offset;
        int floatCursor = outputOffset;
        while (byteCursor < lastByte) {
            int hi = ((audioBytes[byteCursor++]) & 0x00FF);
            int lo = ((audioBytes[byteCursor++]) & 0x00FF);
            short value = (short) ((hi << 8) | lo);
            data[floatCursor++] = value * (1.0f / 32768);
        }
    }

    public static void decodeBigF32ToF32(byte[] audioBytes, int offset, int numBytes, float[] data,
            int outputOffset) {
        int lastByte = offset + numBytes;
        int byteCursor = offset;
        int floatCursor = outputOffset;
        while (byteCursor < lastByte) {
            int bits = audioBytes[byteCursor++];
            bits = (bits << 8) | ((audioBytes[byteCursor++]) & 0x00FF);
            bits = (bits << 8) | ((audioBytes[byteCursor++]) & 0x00FF);
            bits = (bits << 8) | ((audioBytes[byteCursor++]) & 0x00FF);
            data[floatCursor++] = Float.intBitsToFloat(bits);
        }
    }

    public static void decodeBigI32ToF32(byte[] audioBytes, int offset, int numBytes, float[] data,
            int outputOffset) {
        int lastByte = offset + numBytes;
        int byteCursor = offset;
        int floatCursor = outputOffset;
        while (byteCursor < lastByte) {
            int value = audioBytes[byteCursor++]; // MSB
            value = (value << 8) | ((audioBytes[byteCursor++]) & 0x00FF);
            value = (value << 8) | ((audioBytes[byteCursor++]) & 0x00FF);
            value = (value << 8) | ((audioBytes[byteCursor++]) & 0x00FF);
            data[floatCursor++] = value * (1.0f / Integer.MAX_VALUE);
        }
    }

    public static void decodeLittleF32ToF32(byte[] audioBytes, int offset, int numBytes,
            float[] data, int outputOffset) {
        int lastByte = offset + numBytes;
        int byteCursor = offset;
        int floatCursor = outputOffset;
        while (byteCursor < lastByte) {
            int bits = ((audioBytes[byteCursor++]) & 0x00FF); // LSB
            bits += ((audioBytes[byteCursor++]) & 0x00FF) << 8;
            bits += ((audioBytes[byteCursor++]) & 0x00FF) << 16;
            bits += (audioBytes[byteCursor++]) << 24;
            data[floatCursor++] = Float.intBitsToFloat(bits);
        }
    }

    public static void decodeLittleI32ToF32(byte[] audioBytes, int offset, int numBytes,
            float[] data, int outputOffset) {
        int lastByte = offset + numBytes;
        int byteCursor = offset;
        int floatCursor = outputOffset;
        while (byteCursor < lastByte) {
            int value = ((audioBytes[byteCursor++]) & 0x00FF);
            value += ((audioBytes[byteCursor++]) & 0x00FF) << 8;
            value += ((audioBytes[byteCursor++]) & 0x00FF) << 16;
            value += (audioBytes[byteCursor++]) << 24;
            data[floatCursor++] = value * (1.0f / Integer.MAX_VALUE);
        }
    }

    public static void decodeLittleI24ToF32(byte[] audioBytes, int offset, int numBytes,
            float[] data, int outputOffset) {
        int lastByte = offset + numBytes;
        int byteCursor = offset;
        int floatCursor = outputOffset;
        while (byteCursor < lastByte) {
            int lo = ((audioBytes[byteCursor++]) & 0x00FF);
            int mid = ((audioBytes[byteCursor++]) & 0x00FF);
            int hi = ((audioBytes[byteCursor++]) & 0x00FF);
            int value = (hi << 24) | (mid << 16) | (lo << 8);
            data[floatCursor++] = value * (1.0f / Integer.MAX_VALUE);
        }
    }

    public static void decodeLittleI16ToF32(byte[] audioBytes, int offset, int numBytes,
            float[] data, int outputOffset) {
        int lastByte = offset + numBytes;
        int byteCursor = offset;
        int floatCursor = outputOffset;
        while (byteCursor < lastByte) {
            int lo = ((audioBytes[byteCursor++]) & 0x00FF);
            int hi = ((audioBytes[byteCursor++]) & 0x00FF);
            short value = (short) ((hi << 8) | lo);
            float sample = value * (1.0f / 32768);
            data[floatCursor++] = sample;
        }
    }

}
