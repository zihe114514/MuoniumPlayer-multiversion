package com.muoniumplayer.core.lyrics3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 3D 世界歌词的「心象」动效模式 —— 全屏歌词页「律动词云 · 心象」
 * （{@code CadenzaLyricRenderer}）的文字动效<b>一比一移植</b>，词云布局与镜头不移植
 * （3D 歌词的本质是飘在世界里的公告牌，2D 词云的螺旋避让/弹簧镜头在公告牌上没有意义）。
 *
 * <p>纯函数模块：不碰 GL、不碰 MC 类，全部数学可离线测试。姿态输出走 3D 侧现成的
 * 每字四量通道（{@code rot/dx/dy/sc}，宿主绕字中心应用），与简洁模式的落位飞入
 * 完全同一条绘制路径。</p>
 *
 * <h2>与心象本体的口径对应</h2>
 * <ul>
 *   <li>心象的「块（token）」= 这里的 {@link Token}：整行按词分块（≤9 块、每块 ≤5 字、
 *       相邻单字合并），分块与动画全部是<b>行的纯函数</b>（种子 = 行文本 hash），同一行
 *       每帧画出来都一样；</li>
 *   <li>心象的 dy 单位是「屏显字高」—— 这里换算成 3D 侧设计像素：乘
 *       {@link WorldLyricsState#LYRIC_SIZE}（10 设计 px，一个汉字的量级与心象 65 号
 *       档的观感比例一致）；</li>
 *   <li>心象的进出场进度来自播放毫秒/退场来自墙上时钟 —— 3D 侧入场复用同一播放毫秒轴
 *       （{@code charTimes} 逐字时刻），退场复用整行 alphaMul 的淡出轴（出场级联同源），
 *       不再另起墙上时钟：行的淡出本来就是墙上时钟推进的。</li>
 * </ul>
 */
final class CadenzaMotion {

    private CadenzaMotion() {
    }

    /** 进场动画在唱到之前多久开始（心象 ENTER_LEAD_MS 一比一）。 */
    static final long ENTER_LEAD_MS = 260L;

    /** 进场动画时长（心象 ENTER_DURATION_MS 一比一，比简洁模式的 320ms 慢一倍，更「表演」）。 */
    static final long ENTER_DURATION_MS = 520L;

    /** 出场动画占整行淡出窗口（FADE_SECONDS）的比例（心象 EXIT_SECONDS=0.5s ≈ 行淡出 0.4s 的 1.25 倍；这里按窗口比例表达）。 */
    static final float EXIT_WINDOW_RATIO = 1.0f;

    /** 块的进出场动画种类（心象 Anim 枚举一比一 —— 顺序参与种子抽样，不得重排）。 */
    enum Anim {
        FADE, JUMP, DROP, SPIN, BOOMERANG, SLIDE, ZOOM, SWIRL
    }

    /** hero（主角）块的基础放大（心象 HERO_BASE_SCALE）。 */
    static final double HERO_BASE_SCALE = 1.10;
    /** 普通块的基础放大（心象 NORMAL_BASE_SCALE）。 */
    static final double NORMAL_BASE_SCALE = 1.02;
    /** 正在唱的块的呼吸幅度（心象 BREATH_STRENGTH）。 */
    static final double BREATH_STRENGTH = .03;
    /** 正在唱的块的提亮量（心象 ACTIVE_BRIGHTEN）。 */
    static final double ACTIVE_BRIGHTEN = .55;
    /** 一行最多拆成这么多块（心象 MAX_TOKENS）。 */
    static final int MAX_TOKENS = 9;
    /** 合并后每块最多字符数（心象 merge 的上限）。 */
    static final int MAX_TOKEN_CHARS = 5;

    // ==================== 块（Token） ====================

    /** 一个字词块：分块、时轴与派生的随机量，行的纯函数。 */
    static final class Token {
        final String text;
        /** 相对行开始的毫秒（心象用绝对时间轴，这里统一相对行始，方便与 charTimes 对齐）。 */
        final long startMs;
        final long endMs;
        final boolean hero;
        final Anim enter;
        final Anim exit;
        final double direction;
        final double angle;
        /** 块在整行非空白字符流里的起始下标（含空白计数的 k 轴）与字符数 —— 映射回 3D 的逐字流用。 */
        final int charOffset;
        final int charCount;
        /** 块的渲染尺寸（设计像素；布局时按整行统一字高算出）与词云落点（舞台坐标，中心原点）。 */
        double width;
        double height;
        double homeX;
        double homeY;

        Token(String text, long startMs, long endMs, boolean hero,
              Anim enter, Anim exit, double direction, double angle,
              int charOffset, int charCount) {
            this.text = text;
            this.startMs = startMs;
            this.endMs = endMs;
            this.hero = hero;
            this.enter = enter;
            this.exit = exit;
            this.direction = direction;
            this.angle = angle;
            this.charOffset = charOffset;
            this.charCount = charCount;
            this.width = 0;
            this.height = 0;
            this.homeX = 0;
            this.homeY = 0;
        }
    }

    /**
     * 把一行拆成块（心象 split + merge + pickHero + 随机派发的一比一，去掉了字号/落点）。
     *
     * <p>输入用 3D 侧现成的逐字时刻轴（{@code charTimes}：每个非空白字符一项、相对行始
     * 的毫秒，词内字符共享词的时刻）与整行的文本 —— 不需要 LyricLine 对象，与简洁模式
     * 的数据来源完全一致。时间轴缺失（全 0）时按心象的无时序路径：按空白切、时长均分。</p>
     *
     * @param lineText  整行文本（与 Floating.lines 拼接一致）
     * @param charTimes 逐字时刻（相对行始 ms；每个非空白字符一项）
     * @param lineChars 整行的全部字符（含空白；与逐字流同一顺序）
     */
    static List<Token> splitTokens(String lineText, long[] charTimes, String lineChars) {
        List<Token> raw = new ArrayList<>();
        boolean timed = hasTimedChars(charTimes);
        // 心象的 hasTimedWords 分支按词切；3D 侧的 charTimes 已把词拍平到字符，
        // 这里按「时刻变化」重组词边界 —— 同一时刻的连续非空白字符 = 一个词。
        // offset 语义 = 非空白字符序号（与 buildGlyphs 的 n 同轴），字符数同理。
        StringBuilder text = new StringBuilder();
        long startMs = 0L;
        long endMs = 0L;
        int offset = 0;
        int count = 0;
        boolean inToken = false;
        int n = 0;
        int len = lineChars.length();
        for (int i = 0; i < len; i++) {
            char c = lineChars.charAt(i);
            if (Character.isWhitespace(c)) {
                if (inToken) {
                    raw.add(makeToken(text.toString(), startMs, endMs, offset, count, timed));
                    inToken = false;
                }
                count = 0;
                continue;
            }
            long at = timed && n < charTimes.length ? charTimes[n] : 0L;
            // 时序路径按「时刻变化」封块（词内字符共享时刻 —— 与 charTimesOf 的
            // 拍平规则互逆）；无时序路径按空白切（上面处理）。
            boolean boundary = inToken && timed && at != startMs;
            if (boundary) {
                raw.add(makeToken(text.toString(), startMs, endMs, offset, count, timed));
                inToken = false;
            }
            if (!inToken) {
                text.setLength(0);
                startMs = at;
                endMs = timed ? at + 400L : 0L;
                offset = n;
                count = 0;
                inToken = true;
            }
            n++;
            text.append(c);
            endMs = timed ? Math.max(endMs, at + 400L) : 0L;
            count++;
        }
        if (inToken) {
            raw.add(makeToken(text.toString(), startMs, endMs, offset, count, timed));
        }
        List<Token> merged = merge(raw, lineChars, timed, charTimes);
        return decorate(merged, lineText);
    }

    private static Token makeToken(String text, long startMs, long endMs, int offset, int count, boolean timed) {
        if (!timed) {
            startMs = 0L;
            endMs = 0L;
        }
        return new Token(text, startMs, endMs, false, Anim.FADE, Anim.FADE, 1.0, 0.0, offset, count);
    }

    private static boolean hasTimedChars(long[] charTimes) {
        if (charTimes == null) return false;
        for (long t : charTimes) {
            if (t > 0L) return true;
        }
        return false;
    }

    /**
     * 相邻短块合并（心象 merge 一比一）：块数 ≤9 且不全是单字时原样返回；
     * 否则合并到 ≤9 块、每块 ≤5 字。合并块的时轴取首块 start、末块 end。
     *
     * <p>与心象的一点差异：心象的输入是 TTML 音节（天然 ≤5 字），这里重组出的「词」
     * 可能远超 5 字（长英文词 / 逐字轴拉长的词）—— 心象的 merge 只并不拆，直接照搬
     * 会产出 12 字的巨块。所以合并循环里对超长块按 {@link #MAX_TOKEN_CHARS} 硬切，
     * 时轴按字符均分。</p>
     */
    private static List<Token> merge(List<Token> raw, String lineChars, boolean timed, long[] charTimes) {
        List<Token> split = new ArrayList<>();
        for (Token t : raw) {
            if (t.text.length() <= MAX_TOKEN_CHARS) {
                split.add(t);
                continue;
            }
            // 超长块硬切：每片 MAX_TOKEN_CHARS 字，时轴在块内均分。
            int pieces = (int) Math.ceil(t.text.length() / (double) MAX_TOKEN_CHARS);
            long span = Math.max(1L, t.endMs - t.startMs);
            int consumed = 0;
            for (int p = 0; p < pieces; p++) {
                int take = Math.min(MAX_TOKEN_CHARS, t.text.length() - consumed);
                String piece = t.text.substring(consumed, consumed + take);
                long s0 = t.startMs + span * consumed / t.text.length();
                long s1 = t.startMs + span * (consumed + take) / t.text.length();
                split.add(new Token(piece, s0, Math.max(s1, s0 + 1L), false,
                        Anim.FADE, Anim.FADE, 1.0, 0.0,
                        t.charOffset + consumed, take));
                consumed += take;
            }
        }

        boolean allSingle = true;
        for (Token t : split) {
            if (t.text.length() > 1) allSingle = false;
        }
        if (split.size() <= MAX_TOKENS && !allSingle) return split;

        int groups = Math.min(MAX_TOKENS, Math.max(1, split.size()));
        int perGroup = (int) Math.ceil(split.size() / (double) groups);
        perGroup = Math.max(1, Math.min(MAX_TOKEN_CHARS, perGroup));

        List<Token> merged = new ArrayList<>();
        int index = 0;
        while (index < split.size()) {
            Token head = split.get(index);
            StringBuilder builder = new StringBuilder(head.text);
            long endMs = head.endMs;
            int count = 1;
            while (count < perGroup && index + count < split.size()
                    && builder.length() < MAX_TOKEN_CHARS) {
                Token next = split.get(index + count);
                builder.append(next.text);
                endMs = next.endMs;
                count++;
            }
            Token last = split.get(Math.min(index + count - 1, split.size() - 1));
            int charCount = last.charOffset + last.charCount - head.charOffset;
            merged.add(new Token(builder.toString(), head.startMs, Math.max(endMs, head.startMs + 1L),
                    false, Anim.FADE, Anim.FADE, 1.0, 0.0, head.charOffset, charCount));
            index += count;
        }
        return merged;
    }

    /**
     * hero 选择（心象 pickHero 一比一：最长且靠中间）+ 动画/方向/极角的种子派发
     * （心象 rebuild 尾部一比一：Random(seedOf) 逐块抽 enter/exit/direction/angle）。
     */
    private static List<Token> decorate(List<Token> tokens, String lineText) {
        int heroIndex = 0;
        double best = -1;
        int n = tokens.size();
        for (int i = 0; i < n; i++) {
            double centreBias = 1.0 - Math.abs(i - (n - 1) * .5) / Math.max(1.0, n);
            double score = tokens.get(i).text.length() * .6 + centreBias * 1.4;
            if (score > best) {
                best = score;
                heroIndex = i;
            }
        }

        long seed = lineText.hashCode();
        Random random = new Random(seed);
        Anim[] pool = Anim.values();
        List<Token> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Token t = tokens.get(i);
            boolean hero = i == heroIndex;
            out.add(new Token(t.text, t.startMs, t.endMs, hero,
                    pool[random.nextInt(pool.length)],
                    pool[random.nextInt(pool.length)],
                    random.nextBoolean() ? 1.0 : -1.0,
                    random.nextDouble() * Math.PI * 2,
                    t.charOffset, t.charCount));
        }
        return out;
    }

    // ==================== 姿态（心象 applyEnterPose/applyExitPose 一比一） ====================

    /** 一次姿态求值的结果。dx/dy 单位是设计像素（渲染层直接进公告牌坐标），rotation 度，scale 乘法项。 */
    static final class Pose {
        double dx;
        double dy;
        double rotation;
        double scale;
        double enterAlpha;
        double exitAlpha;

        void reset() {
            dx = 0;
            dy = 0;
            rotation = 0;
            scale = 1;
            enterAlpha = 1;
            exitAlpha = 0;
        }
    }

    /**
     * 块在一帧里的完整姿态：基础放大 + 呼吸 + 逐字连续缩放 + 入场 + 退场，
     * 全部按心象的公式与顺序叠加（心象 drawTokens 一比一，去掉了 wordScale 的
     * 2D 配置项 —— 3D 侧统一用呼吸 + 出入场表达「律动」）。
     *
     * @param token        块
     * @param positionMs   行内毫秒（相对行始，与 charTimes 同轴）
     * @param alphaMul     整行淡出系数（0..1；出场动画的进度 = 1 - alphaMul）
     * @param exiting      该行是否正在淡出（退场动画只在淡出时走）
     * @param pxPerHeight  「一个字高」对应的设计像素数（dy/dx 公式里的 h，取 LYRIC_SIZE）
     */
    static void pose(Token token, Pose pose, double positionMs, float alphaMul, boolean exiting,
                     double pxPerHeight) {
        pose.reset();
        pose.scale = token.hero ? HERO_BASE_SCALE : NORMAL_BASE_SCALE;
        boolean active = positionMs >= token.startMs - ENTER_LEAD_MS && positionMs <= token.endMs;
        if (active) {
            // 呼吸：与心象同一条公式（相位掺块 startMs，块间不齐步）。
            pose.scale *= 1.0 + Math.sin(positionMs * .01 + token.startMs * .005) * BREATH_STRENGTH;
        }

        double enter = clamp01((positionMs - (token.startMs - ENTER_LEAD_MS)) / (double) ENTER_DURATION_MS);
        applyEnterPose(token, pose, enter, pxPerHeight);
        if (exiting && alphaMul < 1f) {
            // 退场进度：整行淡出窗口即退场动画的全程（心象 EXIT_SECONDS=0.5s ≈ 行淡出 0.4s，
            // 这里取「淡出全程 = 动画全程」，观感等价且与出场级联同一条时间轴）。
            double exitProgress = clamp01(1.0 - alphaMul);
            applyExitPose(token, pose, exitProgress, pxPerHeight);
        }
    }

    private static void applyEnterPose(Token token, Pose pose, double progress, double h) {
        if (progress >= 1) return;
        double inverse = 1.0 - easeOutCubic(progress);
        double inverseBack = 1.0 - easeOutBack(progress);
        double direction = token.direction;
        switch (token.enter) {
            case FADE:
                pose.dy += inverse * h * .6;
                pose.enterAlpha = easeOutCubic(progress);
                break;
            case JUMP:
                pose.dy += inverseBack * h * 2.6;
                pose.enterAlpha = clamp01(progress * 2);
                pose.scale *= 1.0 + inverse * .15;
                break;
            case DROP:
                pose.dy -= (1.0 - easeOutBounce(progress)) * h * 3.2;
                pose.enterAlpha = clamp01(progress * 3);
                break;
            case SPIN:
                pose.rotation += inverse * -540;
                pose.enterAlpha = easeOutCubic(progress);
                pose.scale *= .6 + .4 * easeOutCubic(progress);
                break;
            case BOOMERANG:
                pose.dx += inverseBack * direction * h * 6;
                pose.dy -= Math.sin(progress * Math.PI) * h * 2;
                pose.rotation += inverse * direction * 360;
                pose.enterAlpha = clamp01(progress * 2);
                break;
            case SLIDE:
                pose.dx += inverse * direction * h * 4.5;
                pose.enterAlpha = easeOutCubic(progress);
                break;
            case ZOOM:
                pose.scale *= .15 + .85 * easeOutBack(progress);
                pose.enterAlpha = easeOutCubic(progress);
                break;
            case SWIRL:
                double swirl = token.angle + progress * Math.PI * 2 * direction;
                pose.dx += Math.cos(swirl) * inverse * h * 3.5;
                pose.dy += Math.sin(swirl) * inverse * h * 3.5;
                pose.rotation += inverse * direction * 300;
                pose.enterAlpha = clamp01(progress * 2);
                break;
            default:
                break;
        }
    }

    private static void applyExitPose(Token token, Pose pose, double progress, double h) {
        if (progress <= 0) return;
        double eased = easeInCubic(progress);
        double direction = token.direction;
        switch (token.exit) {
            case FADE:
                pose.dy -= eased * h * .5;
                break;
            case JUMP:
                pose.dy -= eased * h * 3;
                pose.scale *= 1.0 - .3 * progress;
                break;
            case DROP:
                pose.dy += progress * progress * h * 4;
                pose.rotation += progress * direction * 180;
                break;
            case SPIN:
                pose.rotation += eased * direction * 540;
                pose.scale *= 1.0 - .4 * progress;
                break;
            case BOOMERANG:
                pose.dx += easeInBack(progress) * direction * h * 7;
                pose.dy -= Math.sin(progress * Math.PI) * h * 1.8;
                pose.rotation += progress * direction * 360;
                break;
            case SLIDE:
                pose.dx += eased * direction * h * 5;
                break;
            case ZOOM:
                pose.scale *= 1.0 + .8 * progress;
                break;
            case SWIRL:
                double swirl = token.angle + progress * Math.PI * 2 * direction;
                pose.dx += Math.cos(swirl) * progress * h * 4;
                pose.dy += Math.sin(swirl) * progress * h * 4;
                pose.rotation += progress * direction * 320;
                break;
            default:
                break;
        }
        pose.exitAlpha = clamp01(progress);
    }

    // ==================== 配色（心象 assignColors/brighten/mixRgb 一比一） ====================

    /**
     * 块的墨色：hero = 基准色（3D 侧传用户自定义色或默认暖白），其余块按种子哈希在
     * 「基准色与中性白的 45% 混合」与纯中性白之间三选一 —— 心象的 accentSoft/neutral
     * 层次关系（主色/柔色/白）保留，3D 侧没有主题强调色，hero 用基准色充当。
     *
     * @param baseRgb 基准墨色（0xRRGGBB；用户自定义过就是自定义色，否则默认暖白）
     */
    static int tokenColor(Token token, int heroIndex, long seed, int baseRgb) {
        if (token.hero) return 0xFF000000 | (baseRgb & 0xFFFFFF);
        int neutral = 0xFFF3F0FA;
        int soft = mixRgb(baseRgb & 0xFFFFFF, neutral & 0xFFFFFF, .45);
        int hash = Math.abs((int) (seed * 31L + (long) heroIndex * 2654435761L + (long) tokenCharSeed(token)));
        return (hash % 3 == 0 ? soft : neutral) | 0xFF000000;
    }

    /** 心象的 hash 里 token 序号参与了混色 —— 这里用块的首字符偏移当稳定序号。 */
    private static int tokenCharSeed(Token token) {
        return token.charOffset * 31 + token.text.hashCode();
    }

    /** 正在唱的块提亮（心象 brighten 一比一：向白推进 amount，alpha 字节保留）。 */
    static int brighten(int argb, double amount) {
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        r = (int) Math.round(r + (255 - r) * amount);
        g = (int) Math.round(g + (255 - g) * amount);
        b = (int) Math.round(b + (255 - b) * amount);
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /** 两色按权重混合（心象 mixRgb 一比一：w=0 取 left、1 取 right，强制不透明）。 */
    static int mixRgb(int left, int right, double weight) {
        double w = clamp01(weight);
        int lr = (left >>> 16) & 0xFF, lg = (left >>> 8) & 0xFF, lb = left & 0xFF;
        int rr = (right >>> 16) & 0xFF, rg = (right >>> 8) & 0xFF, rb = right & 0xFF;
        int r = (int) Math.round(lr * (1 - w) + rr * w);
        int g = (int) Math.round(lg * (1 - w) + rg * w);
        int b = (int) Math.round(lb * (1 - w) + rb * w);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * 块的发光脉冲（心象 drawTokens 里的 glow 公式一比一）：正在唱的块、按音节进度，
     * 峰值在进度 35% 处。返回 0..1；0 = 无脉冲。
     */
    static double glowPulse(Token token, double positionMs) {
        if (positionMs < token.startMs || positionMs > token.endMs || token.endMs <= token.startMs) {
            return 0.0;
        }
        double own = clamp01((positionMs - token.startMs) / (double) (token.endMs - token.startMs));
        return Math.max(0.0, 1.0 - Math.abs(own - .35) * 1.6);
    }

    // ==================== ease 族（心象一比一，double 口径） ====================

    static double clamp01(double value) {
        return value < 0 ? 0 : (value > 1 ? 1 : value);
    }

    static double easeOutCubic(double t) {
        double clamped = clamp01(t);
        double u = 1.0 - clamped;
        return 1.0 - u * u * u;
    }

    static double easeInCubic(double t) {
        double clamped = clamp01(t);
        return clamped * clamped * clamped;
    }

    static double easeOutBack(double t) {
        double clamped = clamp01(t);
        double c1 = 1.70158;
        double c3 = c1 + 1.0;
        double u = clamped - 1.0;
        return 1.0 + c3 * u * u * u + c1 * u * u;
    }

    static double easeInBack(double t) {
        double clamped = clamp01(t);
        double c1 = 1.70158;
        double c3 = c1 + 1.0;
        return c3 * clamped * clamped * clamped - c1 * clamped * clamped;
    }

    static double easeOutBounce(double t) {
        double clamped = clamp01(t);
        double n1 = 7.5625;
        double d1 = 2.75;
        if (clamped < 1.0 / d1) return n1 * clamped * clamped;
        if (clamped < 2.0 / d1) {
            double u = clamped - 1.5 / d1;
            return n1 * u * u + .75;
        }
        if (clamped < 2.5 / d1) {
            double u = clamped - 2.25 / d1;
            return n1 * u * u + .9375;
        }
        double u = clamped - 2.625 / d1;
        return n1 * u * u + .984375;
    }

    // ==================== 词云布局（心象 assignAnchors/place 一比一，舞台=行文字像素平面） ====================

    /** 碰撞盒在文本外撑开的留白（心象 BOX_PAD_* 一比一：单侧量；绝对下限盖住光晕）。 */
    private static final double BOX_PAD_X_FACTOR = .30;
    private static final double BOX_PAD_Y_FACTOR = .22;
    private static final double BOX_PAD_MIN = 5.0;

    /** 排布尝试次数（心象 LAYOUT_ATTEMPTS）：每次换顺序/锚点密度，取重叠最小的那次。 */
    private static final int LAYOUT_ATTEMPTS = 3;

    /** 小于这个面积的重叠当作没有重叠（心象 OVERLAP_EPSILON）。 */
    private static final double OVERLAP_EPSILON = 1e-6;

    /** 一块布局完成后的舞台快照：宽高与全部块落点（舞台坐标，中心原点）。 */
    static final class Stage {
        final double width;
        final double height;
        /** 焦点块（正在唱/即将唱的块）下标 —— 镜头目标 = -home。 */
        final List<Token> tokens;

        Stage(double width, double height, List<Token> tokens) {
            this.width = width;
            this.height = height;
            this.tokens = tokens;
        }
    }

    private static double boxPadX(double height) {
        return Math.max(height * BOX_PAD_X_FACTOR, BOX_PAD_MIN);
    }

    private static double boxPadY(double height) {
        return Math.max(height * BOX_PAD_Y_FACTOR, BOX_PAD_MIN);
    }

    /**
     * 碰撞盒的放大余量：只覆盖真实会发生的缩放（hero 基础放大 × 呼吸峰值），
     * 不再为「逐字放大」预留 —— 3D 心象的律动走呼吸，不走心象 2D 的逐字放大滑条。
     * 余量写大会让碰撞盒比实际画出来的宽很多，锚点间距永远塞不下，
     * 螺旋把块一个个推远 —— 间隙巨大、布局涣散（首版踩过的坑）。
     */
    private static double boxReserve(boolean hero) {
        double base = hero ? HERO_BASE_SCALE : NORMAL_BASE_SCALE;
        return base * (1.0 + BREATH_STRENGTH);
    }

    /**
     * 词云布局：给每一块定渲染尺寸、把锚点场折行、再用螺旋避让找不重叠的落点。
     *
     * <p>与心象的差异（各自舞台语义不同）：心象的舞台是 2D 视口、字号走档位试探；
     * 3D 的舞台是「行的文字像素平面」—— 字号恒为烘焙档（LYRIC_SIZE），放不下时
     * <b>舞台横向扩张</b>（横排一遍 ≤ ROW_FILL 的约束反解最小舞台宽），纵向 =
     * 锚点折行行数 × 行高。玩家在 3D 里看的是整块公告牌，扩舞台就是让牌子更宽，
     * 没有 2D 视口裁剪的问题。</p>
     *
     * @param tokens        分好块的行（decorate 之后：hero/动画已派发）
     * @param charWidth     单字符渲染宽的度量（与绘制通道同源；逐字不同宽）
     * @param lineHeight    行高（设计像素，3D 侧 LINE_H）
     * @param minStageWidth 最小舞台宽（≈ 无词云时的整行宽；再窄没有意义）
     */
    static Stage layout(List<Token> tokens, PixelMetrics charWidth,
                        double lineHeight, double minStageWidth) {
        // 1. 尺寸与碰撞盒：块宽 = Σ 字符宽（逐字不同宽），块高 = 行高；
        //    碰撞盒 = 文本 + 发光留白，再乘真实动画余量（hero 放大 × 呼吸峰值）。
        int count = tokens.size();
        double[] boxW = new double[count];
        double[] boxH = new double[count];
        double rowWidth = 0;
        double widest = 0;
        for (int i = 0; i < count; i++) {
            Token t = tokens.get(i);
            double w = 0;
            for (int c = 0; c < t.text.length(); c++) {
                w += charWidth.widthOf(t.text.charAt(c));
            }
            t.width = w;
            t.height = lineHeight;
            double reserve = boxReserve(t.hero);
            boxW[i] = (w * .5 + boxPadX(t.height)) * reserve * 2.0;
            boxH[i] = (t.height * .5 + boxPadY(t.height)) * reserve * 2.0;
            rowWidth += w;
            widest = Math.max(widest, boxW[i]);
        }

        // 2. 舞台宽：调用方的最小宽与最宽块的舒展空间取大者 —— 不再按整行比例
        //    无条件扩张：块少时把舞台撑到 78% 只会让锚点稀稀拉拉（间隙过大的另一半根源）；
        //    放不下的块由锚点场自然折行，词云的散布由折行 + 纵向抖动表达。
        double stageWidth = Math.max(minStageWidth, widest * 1.6);
        // 锚点间距系数：相邻块文本间隙 = 两侧碰撞盒半宽均值的 18% ——
        // 手牵手排开而不是各留一大段真空（首版间隙过大的根源）。
        double gapFactor = .18;

        // 3. 锚点场（心象 assignAnchors 的精神 + 3D 收紧）：
        //    相邻锚点以盒宽节奏排布 → 天然不重叠，螺旋只做微调；
        //    每行带内再给纵向 ±0.22 行高的确定性抖动 ——
        //    词云的「灵动」来自块不站在一条绝对直线上。
        int[] rowOf = new int[count];
        List<Double> rowWidths = new ArrayList<>();
        double used = 0;
        int rows = 0;
        for (int i = 0; i < count; i++) {
            Token t = tokens.get(i);
            double advance = t.width + (i + 1 < count ? (boxW[i] + boxW[i + 1]) * .5 * gapFactor : 0.0);
            if (used > 0 && used + advance > stageWidth) {
                rows++;
                rowWidths.add(used);
                used = 0;
            }
            used += advance;
            rowOf[i] = rows;
        }
        rows++;
        rowWidths.add(used);

        List<Double> band = new ArrayList<>();
        double totalHeight = 0;
        for (int r = 0; r < rows; r++) {
            double b = lineHeight + boxPadY(lineHeight) * 2.0;
            band.add(b);
            totalHeight += b;
        }
        double stageHeight = Math.max(totalHeight, lineHeight * 1.4);

        double[] anchorX = new double[count];
        double[] anchorY = new double[count];
        double[] cursor = new double[rows];
        double y = -totalHeight * .5;
        java.util.Random scatter = new java.util.Random(0x5EEDC0DE);
        for (int r = 0; r < rows; r++) {
            double centreY = y + band.get(r) * .5;
            cursor[r] = -rowWidths.get(r) * .5;
            y += band.get(r);
            for (int i = 0; i < count; i++) {
                if (rowOf[i] != r) continue;
                anchorX[i] = cursor[r] + tokens.get(i).width * .5;
                anchorY[i] = centreY + (scatter.nextDouble() * 2.0 - 1.0)
                        * band.get(r) * .22;
                cursor[r] += tokens.get(i).width
                        + (i + 1 < count ? (boxW[i] + boxW[i + 1]) * .5 * gapFactor : 0.0);
            }
        }

        // 4. 放置：hero 最先，其余按盒子面积从大到小（心象 placementOrder 的面积序）；
        //    3 次尝试（第 2 次锚点向中心收紧 0.72），取重叠最小的那次（心象 placeAll）。
        Integer heroIndex = null;
        double[] area = new double[count];
        for (int i = 0; i < count; i++) {
            Token t = tokens.get(i);
            if (t.hero) heroIndex = i;
            double reserve = boxReserve(t.hero);
            area[i] = (t.width * .5 + boxPadX(t.height)) * reserve
                    * (t.height * .5 + boxPadY(t.height)) * reserve;
        }

        double[] bestX = new double[count];
        double[] bestY = new double[count];
        double bestOverlap = Double.MAX_VALUE;
        List<double[]> placed = new ArrayList<>();
        for (int attempt = 0; attempt < LAYOUT_ATTEMPTS; attempt++) {
            boolean byArea = attempt != 2;
            double squeeze = attempt == 1 ? .72 : 1.0;
            boolean[] taken = new boolean[count];
            placed.clear();

            int slot = 0;
            int[] order = new int[count];
            if (heroIndex != null) {
                order[slot++] = heroIndex;
                taken[heroIndex] = true;
            }
            if (byArea) {
                for (int s = slot; s < count; s++) {
                    int best = -1;
                    for (int i = 0; i < count; i++) {
                        if (taken[i]) continue;
                        if (best < 0 || area[i] > area[best]) best = i;
                    }
                    order[s] = best;
                    taken[best] = true;
                }
            } else {
                for (int i = 0; i < count; i++) {
                    if (!taken[i]) order[slot++] = i;
                }
            }

            for (int oi = 0; oi < count; oi++) {
                int i = order[oi];
                Token t = tokens.get(i);
                double ax = i == (heroIndex == null ? -1 : heroIndex.intValue())
                        ? anchorX[i] : anchorX[i] * squeeze;
                double ay = anchorY[i] * squeeze;
                place(t, ax, ay, placed, stageWidth, stageHeight);
            }

            double overlap = totalOverlap(placed);
            if (overlap < bestOverlap) {
                bestOverlap = overlap;
                for (int i = 0; i < count; i++) {
                    bestX[i] = tokens.get(i).homeX;
                    bestY[i] = tokens.get(i).homeY;
                }
            }
            if (bestOverlap <= OVERLAP_EPSILON) break;
        }
        for (int i = 0; i < count; i++) {
            tokens.get(i).homeX = bestX[i];
            tokens.get(i).homeY = bestY[i];
        }
        return new Stage(stageWidth, stageHeight, tokens);
    }

    /** 宿主量宽回调：单字符渲染宽（与逐字绘制通道同源）。 */
    interface PixelMetrics {
        double widthOf(char c);
    }

    /**
     * 螺旋避让（心象 place 一比一）：从锚点出发的同心采样环上找第一个零重叠点；
     * 越界候选跳过不夹取；螺旋失败再扫一遍全域网格；都失败接受重叠最小的位置。
     */
    private static void place(Token token, double anchorX, double anchorY, List<double[]> placed,
                              double stageWidth, double stageHeight) {
        double halfWidth = token.width * .5 + boxPadX(token.height);
        double halfHeight = token.height * .5 + boxPadY(token.height);
        double scaleUp = boxReserve(token.hero);
        double boxWidth = halfWidth * scaleUp;
        double boxHeight = halfHeight * scaleUp;

        double limitX = Math.max(0.0, stageWidth * .5 - boxWidth);
        double limitY = Math.max(0.0, stageHeight * .5 - boxHeight);

        double originX = clamp(anchorX, -limitX, limitX);
        double originY = clamp(anchorY, -limitY, limitY);

        double bestX = originX;
        double bestY = originY;
        double bestOverlap = overlapArea(originX, originY, boxWidth, boxHeight, placed);
        if (bestOverlap <= 0) {
            commit(token, originX, originY);
            return;
        }

        double step = Math.max(6.0, Math.min(boxWidth, boxHeight) * .5);
        double maxRadius = Math.max(limitX, limitY) * 2.0 + Math.max(boxWidth, boxHeight);
        for (double radius = step; radius <= maxRadius; radius += step) {
            int samples = Math.max(10, (int) (radius / step) * 6);
            for (int s = 0; s < samples; s++) {
                double angle = (Math.PI * 2 * s) / samples + radius * .35;
                double x = originX + Math.cos(angle) * radius;
                double yy = originY + Math.sin(angle) * radius * .85;
                if (x < -limitX || x > limitX || yy < -limitY || yy > limitY) continue;
                double overlap = overlapArea(x, yy, boxWidth, boxHeight, placed);
                if (overlap <= 0) {
                    commit(token, x, yy);
                    return;
                }
                if (overlap < bestOverlap) {
                    bestOverlap = overlap;
                    bestX = x;
                    bestY = yy;
                }
            }
        }

        int cells = 29;
        for (int ix = 0; ix < cells; ix++) {
            double x = -limitX + 2.0 * limitX * ix / (cells - 1);
            for (int iy = 0; iy < cells; iy++) {
                double yy = -limitY + 2.0 * limitY * iy / (cells - 1);
                double overlap = overlapArea(x, yy, boxWidth, boxHeight, placed);
                if (overlap <= 0) {
                    commit(token, x, yy);
                    return;
                }
                if (overlap < bestOverlap) {
                    bestOverlap = overlap;
                    bestX = x;
                    bestY = yy;
                }
            }
        }
        commit(token, bestX, bestY);
    }

    private static void commit(Token token, double x, double y) {
        token.homeX = x;
        token.homeY = y;
    }

    private static double overlapArea(double x, double y, double halfWidth, double halfHeight,
                                      List<double[]> placed) {
        double total = 0;
        for (int i = 0; i < placed.size(); i++) {
            double[] box = placed.get(i);
            double dx = Math.abs(x - box[0]) - (halfWidth + box[2]);
            double dy = Math.abs(y - box[1]) - (halfHeight + box[3]);
            if (dx < 0 && dy < 0) total += (-dx) * (-dy);
        }
        return total;
    }

    private static double totalOverlap(List<double[]> placed) {
        double total = 0;
        for (int i = 0; i < placed.size(); i++) {
            double[] left = placed.get(i);
            for (int j = i + 1; j < placed.size(); j++) {
                double[] right = placed.get(j);
                double dx = (left[2] + right[2]) - Math.abs(left[0] - right[0]);
                double dy = (left[3] + right[3]) - Math.abs(left[1] - right[1]);
                if (dx > 0 && dy > 0) total += dx * dy;
            }
        }
        return total;
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : (value > max ? max : value);
    }
}
