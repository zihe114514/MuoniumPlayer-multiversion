package com.muoniumplayer.core.preset.codec;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 分享码：往返、聊天环境下的容错、以及对恶意 / 损坏输入的拒绝。 */
class ShareCodeTest {

    private static final String JSON = "{\"format\":\"muonium.preset\",\"formatVersion\":1,"
            + "\"meta\":{\"name\":\"流光玻璃\"},\"facets\":[\"lyrics\"],"
            + "\"domains\":{\"hud\":{\"v\":1,\"values\":{\"currentGlowStrength\":0.82,\"osdLyricStyle\":1}}}}";

    @Test
    void 往返一致() throws Exception {
        String code = ShareCode.encode(JSON);
        assertTrue(code.startsWith(ShareCode.PREFIX));
        assertEquals(JSON, ShareCode.decode(code));
    }

    @Test
    void 夹在聊天内容里也能取出来() throws Exception {
        String code = ShareCode.encode(JSON);
        String chat = "试试这个！\n" + ShareCode.withHeader(code, "流光玻璃", "Cherry") + " 很好看～ abc123";
        assertEquals(JSON, ShareCode.decode(chat));
    }

    @Test
    void 被聊天软件折行也能取出来() throws Exception {
        String code = ShareCode.encode(JSON);
        StringBuilder wrapped = new StringBuilder();
        for (int i = 0; i < code.length(); i += 17) {
            wrapped.append(code, i, Math.min(code.length(), i + 17)).append("\n  ");
        }
        assertEquals(JSON, ShareCode.decode(wrapped.toString()));
    }

    @Test
    void 截断的分享码给出明确原因() {
        String code = ShareCode.encode(JSON);
        String cut = code.substring(0, code.indexOf('.') - 6);
        PresetFormatException error = assertThrows(PresetFormatException.class, () -> ShareCode.decode(cut));
        assertTrue(error.getMessage().contains("不完整"), error.getMessage());
    }

    @Test
    void 被改动的分享码校验不过() {
        String code = ShareCode.encode(JSON);
        int at = ShareCode.PREFIX.length() + 10;
        char flipped = code.charAt(at) == 'A' ? 'B' : 'A';
        String tampered = code.substring(0, at) + flipped + code.substring(at + 1);
        assertThrows(PresetFormatException.class, () -> ShareCode.decode(tampered));
    }

    @Test
    void 校验码被改也拒绝() {
        String code = ShareCode.encode(JSON);
        String wrongCrc = code.substring(0, code.length() - 8) + "00000000";
        assertThrows(PresetFormatException.class, () -> ShareCode.decode(wrongCrc));
    }

    @Test
    void 压缩炸弹被拦在解压上限() {
        char[] spaces = new char[PresetLimits.MAX_INFLATED_BYTES + 1024];
        Arrays.fill(spaces, ' ');
        String bomb = ShareCode.encode("{" + new String(spaces) + "}");
        assertTrue(bomb.length() < 4096, "高度可压缩的输入编码后很短，这正是炸弹的样子");
        PresetFormatException error = assertThrows(PresetFormatException.class, () -> ShareCode.decode(bomb));
        assertTrue(error.getMessage().contains("过大"), error.getMessage());
    }

    @Test
    void 直接粘贴预设JSON也认() throws Exception {
        assertEquals(JSON, ShareCode.decode("  " + JSON + "\n"));
    }

    @Test
    void 没有分享码时说清楚() {
        PresetFormatException error = assertThrows(PresetFormatException.class,
                () -> ShareCode.decode("今天天气不错"));
        assertTrue(error.getMessage().contains("MPP2:"), "提示里给出当前的分享码前缀：" + error.getMessage());
        assertThrows(PresetFormatException.class, () -> ShareCode.decode("   "));
    }

    @Test
    void 在线短码提示需要更新() {
        PresetFormatException error = assertThrows(PresetFormatException.class,
                () -> ShareCode.decode("MPP1R:abcd1234"));
        assertTrue(error.getMessage().contains("短码"));
    }

    @Test
    void 粗判是否像预设() {
        assertTrue(ShareCode.looksLikePreset("xx MPP1:abc.12345678"));
        assertTrue(ShareCode.looksLikePreset(JSON));
        assertFalse(ShareCode.looksLikePreset("hello"));
        assertFalse(ShareCode.looksLikePreset(null));
    }
}
