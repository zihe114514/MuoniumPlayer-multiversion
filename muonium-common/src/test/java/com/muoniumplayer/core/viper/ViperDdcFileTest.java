package com.muoniumplayer.core.viper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ViperDdcFile}：.vdc 文本系数解析（端口自官方 App {@code ViperService.parseVdc}）。
 */
class ViperDdcFileTest {

    private static File write(String content) throws Exception {
        File file = Files.createTempFile("viper-ddc-", ".vdc").toFile();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    @DisplayName("正常两行系数：按 5 个一段解析，两套采样率长度一致")
    void parseValid() throws Exception {
        File file = write("SR_44100: 1,2,3,4,5, 6,7,8,9,10\nSR_48000: 11,12,13,14,15, 16,17,18,19,20\n");
        ViperDdcFile.Vdc vdc = ViperDdcFile.parse(file);
        assertNotNull(vdc);
        assertEquals(2, vdc.sectionCount());
        assertEquals(10, vdc.sections44100.length, "每行 10 个 float = 2 段");
        assertEquals(1.0f, vdc.sections44100[0], 1e-9f);
        assertEquals(10.0f, vdc.sections44100[9], 1e-9f);
        assertEquals(11.0f, vdc.sections48000[0], 1e-9f);
        assertEquals(20.0f, vdc.sections48000[9], 1e-9f);
    }

    @Test
    @DisplayName("长度不一致 / 非 5 倍数 / 缺一行 → 拒绝（返回 null，不抛异常）")
    void rejectsInvalid() throws Exception {
        assertNull(ViperDdcFile.parse(write("SR_44100: 1,2,3,4,5\nSR_48000: 1,2,3,4,5,6,7,8,9,10\n")));
        assertNull(ViperDdcFile.parse(write("SR_44100: 1,2,3\nSR_48000: 1,2,3\n")));
        assertNull(ViperDdcFile.parse(write("SR_44100: 1,2,3,4,5\n")));
        assertNull(ViperDdcFile.parse(new File("Z:/definitely/not/exist.vdc")));
    }
}
