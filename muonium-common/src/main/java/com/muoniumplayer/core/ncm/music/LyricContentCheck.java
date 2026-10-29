package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.screens.ncm.LyricLine;

import java.util.ArrayList;
import java.util.List;

/**
 * 逐字歌词的内容一致性校验：比对候选逐字源的歌词正文与当前平台行级歌词的正文，
 * 判定两边是不是<b>同一首歌</b>。
 *
 * <p>动机：QQ/酷狗的跨源匹配靠「标题 - 歌手 - 专辑 + 时长」打分，同名不同曲（现场版、
 * 翻唱、同名单曲）在元数据高度相似时仍可能被放行。元数据再像也不如正文本身有说服力——
 * 同一首歌的两份歌词对应行几乎逐字相同，不同歌的歌词正文几乎不重叠。所以平台行级
 * 歌词（songId 精确取回，绝无匹配错）在这里当<b>身份指纹</b>用。</p>
 *
 * <h3>正文提取</h3>
 * <p>比对前先剔除三类非正文行，对应「排除歌词前面的商业化内容，只看正文」：</p>
 * <ul>
 *   <li><b>制作人员行</b>——行首「作词：/作曲：/编曲：/Lyricist: …」这类冒号锚定的职务表；
 *       模式与 {@code WorldLyricsState.CREDIT_LINE} 同源（渲染层的私有实现，此处独立持有）；</li>
 *   <li><b>商业声明行</b>——命中关键词表（独家/版权/未经许可/官方渠道/歌词组/www…）的短行；
 *       真实歌词整行都是这类词的情况极少，行长上限挡掉误伤；</li>
 *   <li><b>占位行</b>——「纯音乐，请欣赏」「instrumental」这类恒在的提示行。</li>
 * </ul>
 * <p>{@code [ti:]/[ar:]/[offset:]} 元数据行不需要处理：{@code LyricParser} 解析时就丢弃了。</p>
 *
 * <h3>比对算法</h3>
 * <p>两侧的行各自过 {@link LyricMatchText#normalize}（假名→罗马音、繁→简、小写、仅字母数字，
 * 中日文互译与异体写法在这一步归一），然后对参考侧每一行在候选侧找字符集 Jaccard 最高的一行，
 * 取全部参考行的<b>平均最佳匹配分</b>。同一首歌对应行归一化后几乎全等（≈1.0），不同歌 ≈0.2~0.4。</p>
 *
 * <h3>宁缺毋滥</h3>
 * <p>参考侧或候选侧有效行不足 {@link #MIN_LINES_FOR_VERDICT} 时<b>证据不足，放行</b>：
 * 平台参考歌词可能只是节选（试听版、电台剪辑），候选侧的音节折行可能把一行拆成多行，
 * 用不完整的证据毙掉一份逐字歌词，比放过一次错配代价更大。逐字歌词的坐标、翻译都在行上，
 *毙掉的代价是整首没有逐字，误放行的代价是错词——但错配本身有元数据打分那道 75 分门槛
 * 在前面挡着，这里只是第二道闸。</p>
 */
final class LyricContentCheck {

    /** 平均最佳匹配分低于这个值判非同一首歌。同曲实测 ≈0.95+，异曲 ≈0.2~0.4。 */
    static final double ACCEPT_THRESHOLD = 0.6;

    /** 任一侧有效行少于这个数时证据不足，放行。 */
    static final int MIN_LINES_FOR_VERDICT = 4;

    /** 商业声明行的行长上限：正常歌词行很少整行都是职务/平台声明。 */
    private static final int META_LINE_MAX_LENGTH = 30;

    /** 行首冒号锚定的制作人员表（作词：/作曲：/编曲：/Lyricist: …）。 */
    private static final java.util.regex.Pattern CREDIT_LINE = java.util.regex.Pattern.compile(
            "^\\s*(作词|作詞|作曲|编曲|編曲|填詞|填词|填曲|改编|改編|原曲|翻唱|原唱"
                    + "|制作人|製作人|混音|母带|母帶|監製|监制|和声|和聲|吉他|贝斯|貝斯"
                    + "|键盘|鍵盤|鼓|鼓手|弦乐|弦樂|配唱|录音|錄音|录混|錄混|录音棚|錄音棚"
                    + "|混音师|混音師|母带师|母帶師|企划|企劃|出品|发行|發行|版权|版權"
                    + "|独家|獨家|OP|SP|ED|Lyricist|Composer|Producer|Arranger"
                    + "|Mixing|Mastering|Vocal|Guitar|Bass|Piano|Drums)"
                    + "\\s*[:：]",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE
                    | java.util.regex.Pattern.UNICODE_CHARACTER_CLASS);

    /** 商业/平台声明关键词：短行命中即剔除。与歌词内容撞车的词（「独家记忆」这类歌名片段）靠行长上限挡。 */
    private static final String[] META_KEYWORDS = {
            "独家", "獨家", "版权", "版權", "未经许可", "未經許可", "禁止翻唱", "翻唱必究",
            "音乐平台", "音樂平台", "云音乐", "雲音樂", "官方渠道", "正版授权", "正版的音樂",
            "歌词组", "歌詞組", "歌词提供", "歌詞提供", "lyric provider", "lyrics by",
            "www.", "http://", "https://", " powered by",
    };

    private LyricContentCheck() {
    }

    /** 一次校验的结论与依据，供调用方打日志。 */
    static final class Result {
        /** {@code true} 放行（同一首歌，或证据不足）；{@code false} 判非同一首歌。 */
        final boolean accepted;
        /** 平均最佳匹配分；证据不足时为 {@code -1}。 */
        final double score;
        final int referenceLines;
        final int candidateLines;

        private Result(boolean accepted, double score, int referenceLines, int candidateLines) {
            this.accepted = accepted;
            this.score = score;
            this.referenceLines = referenceLines;
            this.candidateLines = candidateLines;
        }

        static Result insufficient(int referenceLines, int candidateLines) {
            return new Result(true, -1.0, referenceLines, candidateLines);
        }

        static Result of(boolean accepted, double score, int referenceLines, int candidateLines) {
            return new Result(accepted, score, referenceLines, candidateLines);
        }

        /** 一行日志用的描述。 */
        String describe() {
            return score < 0
                    ? "insufficient evidence (ref " + referenceLines + " / cand " + candidateLines + " lines)"
                    : "score=" + String.format(java.util.Locale.ROOT, "%.3f", score)
                    + " (ref " + referenceLines + " / cand " + candidateLines + " lines)";
        }
    }

    /**
     * 校验候选逐字歌词与平台参考歌词是否同一首歌。
     *
     * @param reference 平台行级歌词（songId 精确取回，作为身份基准）；{@code null} 按证据不足放行
     * @param candidate 候选逐字歌词（各逐字源解析出的行）
     */
    static Result check(List<LyricLine> reference, List<LyricLine> candidate) {
        if (reference == null || candidate == null) {
            return Result.insufficient(0, 0);
        }

        List<String> referenceBody = bodyLines(reference);
        List<String> candidateBody = bodyLines(candidate);
        if (referenceBody.size() < MIN_LINES_FOR_VERDICT || candidateBody.size() < MIN_LINES_FOR_VERDICT) {
            return Result.insufficient(referenceBody.size(), candidateBody.size());
        }

        double total = 0.0;
        for (String refLine : referenceBody) {
            double best = 0.0;
            for (String candLine : candidateBody) {
                double similarity = LyricMatchText.similarity(refLine, candLine);
                if (similarity > best) best = similarity;
                if (best >= 1.0) break;
            }
            total += best;
        }
        double average = total / referenceBody.size();
        return Result.of(average >= ACCEPT_THRESHOLD, average,
                referenceBody.size(), candidateBody.size());
    }

    /** 提取归一化后的正文行：过滤非正文行，归一化，去重去空。 */
    private static List<String> bodyLines(List<LyricLine> lines) {
        List<String> body = new ArrayList<>();
        for (LyricLine line : lines) {
            if (line == null || line.lyric == null) continue;
            String text = line.lyric.trim();
            if (text.isEmpty() || isCreditLine(text) || isMetaLine(text) || isPlaceholderLine(text)) continue;
            String normalized = LyricMatchText.normalize(text);
            if (normalized.isEmpty() || body.contains(normalized)) continue;
            body.add(normalized);
        }
        return body;
    }

    /** 行首冒号锚定的职务表。 */
    static boolean isCreditLine(String text) {
        if (text == null || text.isEmpty()) return false;
        return CREDIT_LINE.matcher(text).find();
    }

    /** 命中商业/平台声明关键词的短行。 */
    static boolean isMetaLine(String text) {
        if (text == null || text.isEmpty()) return false;
        if (text.length() > META_LINE_MAX_LENGTH) return false;
        String lowered = text.toLowerCase(java.util.Locale.ROOT);
        for (String keyword : META_KEYWORDS) {
            if (lowered.contains(keyword)) return true;
        }
        return false;
    }

    /** 「纯音乐，请欣赏」这类恒在占位行：去掉全部非字母数字后恰好等于固定口吻之一。 */
    static boolean isPlaceholderLine(String text) {
        if (text == null) return false;
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (Character.isLetterOrDigit(character)) builder.append(Character.toLowerCase(character));
        }
        String flat = builder.toString();
        if (flat.isEmpty()) return false;
        return flat.equals("纯音乐") || flat.equals("纯音乐请欣赏") || flat.equals("纯音乐请您欣赏")
                || flat.equals("没有填词的纯音乐") || flat.equals("instrumental");
    }
}
