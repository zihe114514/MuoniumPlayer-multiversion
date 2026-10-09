package com.muoniumplayer.core.viper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * ViPER DDC 系数文件（{@code .vdc}）解析器——端口自官方 App 的
 * {@code ViperService.parseVdc}：文本格式，逐行读取，两行有效数据：
 *
 * <pre>
 * SR_44100: f,f,f,...   （每 5 个 float 一个 biquad 段）
 * SR_48000: f,f,f,...
 * </pre>
 *
 * <p>两行都必须存在、长度一致且为 5 的倍数；否则视为无效（与 App 的判定一致）。</p>
 */
final class ViperDdcFile {

    /** 解析结果：两套采样率的原始系数（长度均为 5 的倍数）。 */
    static final class Vdc {
        final float[] sections44100;
        final float[] sections48000;

        Vdc(float[] sections44100, float[] sections48000) {
            this.sections44100 = sections44100;
            this.sections48000 = sections48000;
        }

        int sectionCount() {
            return sections44100.length / 5;
        }
    }

    private ViperDdcFile() {
    }

    /** 解析失败返回 {@code null}（不抛异常）。 */
    static Vdc parse(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            float[] a = null;
            float[] b = null;
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("SR_44100:")) {
                    a = parseFloats(trimmed.substring("SR_44100:".length()));
                } else if (trimmed.startsWith("SR_48000:")) {
                    b = parseFloats(trimmed.substring("SR_48000:".length()));
                }
            }
            if (a == null || b == null || a.length == 0
                    || a.length != b.length || a.length % 5 != 0) {
                return null;
            }
            return new Vdc(a, b);
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    private static float[] parseFloats(String text) {
        String[] parts = text.split(",");
        float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Float.parseFloat(parts[i].trim());
        }
        return out;
    }
}
