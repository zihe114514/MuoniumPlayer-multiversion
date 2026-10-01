package com.muoniumplayer.core.screens.ncm;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 洛雪音乐（LX Music）写在 LRC 末尾的 {@code [awlrc:lrc:B64,tlrc:B64,rlrc:B64,awlrc:B64]} 容器解包。
 *
 * <p>四个键对应洛雪内部的 {@code lyric} / {@code tlyric} / {@code rlyric} / {@code lxlyric}，
 * 其中 {@code awlrc} 是<b>逐字</b>时轴轨。洛雪导出时会把原文、翻译、罗马音按空行依次拼成正文
 * （每块时间戳都从头重来）再附上这个容器 —— 正文只是给不认识容器的播放器看的降级视图，
 * 容器才是权威数据，所以命中容器时直接取容器、跳过正文。</p>
 *
 * <p>容器也是本项目能拿到的唯一一种「本地文件里的逐字歌词」：用户从洛雪导出的
 * {@code .lrc} 落到磁盘后，嵌入标签 / 同目录歌词文件读到的就是这种文本。</p>
 *
 * <p>参考实现：lx-music-desktop {@code src/renderer/worker/download/lrcTool.ts}。</p>
 */
final class AwlrcContainer {

    /**
     * 与洛雪自己的探测表达式一致（{@code /\[awlrc:(.+)\]/}）：不做行首锚定，写到哪儿都能认出来。
     *
     * <p>base64 的字母表里没有 {@code ]}，所以贪婪分组不会跑出这个标签；反过来说正文里出现
     * 别的方括号也不会打断匹配。</p>
     */
    private static final Pattern CONTAINER = Pattern.compile("\\[awlrc:(.+)]");

    private AwlrcContainer() {
    }

    /** 一次解包的结果。任何一轨都可能为空串（原文、翻译、罗马音、逐字）。 */
    static final class Tracks {
        /** 洛雪 {@code lyric}：逐行原文，无逐字时轴。 */
        String lrc = "";
        /** 洛雪 {@code tlyric}：翻译轨。 */
        String tlrc = "";
        /** 洛雪 {@code rlyric}：罗马音轨；汉字音译会在解包阶段被丢弃。 */
        String rlrc = "";
        /** 洛雪 {@code lxlyric}：逐字时轴轨，{@code [mm:ss.ms]<相对行首偏移ms,时长ms>text} 形式。 */
        String awlrc = "";

        boolean isEmpty() {
            return lrc.isEmpty() && tlrc.isEmpty() && rlrc.isEmpty() && awlrc.isEmpty();
        }
    }

    /**
     * 从歌词正文里取出容器并解码各轨。
     *
     * @return 未命中容器、或所有轨都解不出来时返回 {@code null}，调用方应回退到常规解析
     */
    static Tracks extract(String content) {
        if (content == null || content.isEmpty()) return null;
        Matcher matcher = CONTAINER.matcher(content);
        if (!matcher.find()) return null;

        Tracks tracks = new Tracks();
        boolean decodedAny = false;
        // base64 正文里可能有 ',',但它只会出现在分隔键值对的位置之外,所以按 ',' 切分是安全的。
        for (String segment : matcher.group(1).split(",")) {
            int separator = segment.indexOf(':');
            if (separator <= 0) continue;

            String key = segment.substring(0, separator);
            if (!key.equals("lrc") && !key.equals("tlrc") && !key.equals("rlrc") && !key.equals("awlrc")) {
                continue;
            }

            String decoded = decodeBase64(segment.substring(separator + 1));
            if (decoded.trim().isEmpty()) continue;

            if (key.equals("lrc")) tracks.lrc = decoded;
            else if (key.equals("tlrc")) tracks.tlrc = decoded;
            else if (key.equals("rlrc")) tracks.rlrc = decoded;
            else tracks.awlrc = decoded;
            decodedAny = true;
        }

        if (!tracks.rlrc.isEmpty() && isHanTransliteration(tracks.rlrc)) {
            // rlrc 的语义在酷狗侧不固定：日文歌放拉丁罗马音，韩文歌放汉字谐音音译
            // （例：슬퍼하지마 -> 丝跑哈几吗）。本项目副字幕只有 translation/romanization 两档，
            // 音译挂上去会让「罗马音」名不副实，所以整轨丢弃。逐行判定会被原文夹带的英文单词
            // 干扰，因此按整轨字符构成统计。
            tracks.rlrc = "";
        }

        return decodedAny ? tracks : null;
    }

    private static String decodeBase64(String value) {
        try {
            String padded = value.trim();
            StringBuilder builder = new StringBuilder(padded);
            while (builder.length() % 4 != 0) builder.append('=');
            return new String(Base64.getDecoder().decode(builder.toString()), StandardCharsets.UTF_8);
        } catch (Throwable failure) {
            // 解不出来就当这一轨不存在,绝不让容器解包失败影响正文解析。
            return "";
        }
    }

    /** 汉字/假名/谚文的总数是否超过拉丁字母数——超过就认定这是汉字音译轨。 */
    private static boolean isHanTransliteration(String track) {
        int cjk = 0;
        int latin = 0;
        for (int index = 0; index < track.length(); ) {
            int codePoint = track.codePointAt(index);
            index += Character.charCount(codePoint);
            if (codePoint >= 0x3400 && codePoint <= 0x9FFF) cjk++;          // 中日韩统一表意文字
            else if (codePoint >= 0xF900 && codePoint <= 0xFAFF) cjk++;     // 兼容表意文字
            else if (codePoint >= 0x3040 && codePoint <= 0x30FF) cjk++;     // 平假名/片假名
            else if (codePoint >= 0xAC00 && codePoint <= 0xD7AF) cjk++;     // 谚文音节
            else if ((codePoint >= 'A' && codePoint <= 'Z') || (codePoint >= 'a' && codePoint <= 'z')) latin++;
        }
        return cjk > latin;
    }
}
