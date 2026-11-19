package com.muoniumplayer.core.screens.ncm.effects;

import com.muoniumplayer.core.viper.ViperPreset;
import com.muoniumplayer.core.viper.ViperPresetParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 系统文件选择器的有界读取；只解析配置数据，不执行导入文件中的任何代码。 */
public final class EffectsPresetFiles {

    private static final int MAX_BYTES = 2 * 1024 * 1024;

    private EffectsPresetFiles() { }

    public static ViperPreset read(File source) throws IOException {
        if (source == null || !source.isFile()) throw new IOException("请选择可读取的预设文件");
        byte[] bytes;
        try (InputStream input = Files.newInputStream(source.toPath())) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) throw new IOException("预设文件超过 2 MiB，拒绝读取");
        String name = source.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return ViperPresetParser.parse(new String(bytes, StandardCharsets.UTF_8), name);
    }
}
