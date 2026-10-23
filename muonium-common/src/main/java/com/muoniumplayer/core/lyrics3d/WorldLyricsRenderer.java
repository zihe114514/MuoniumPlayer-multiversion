package com.muoniumplayer.core.lyrics3d;

import com.muoniumplayer.core.screens.ncm.LyricLine;

import java.util.List;

/**
 *
 * <p>与宿主的契约（树侧 WorldBillboard 实现）：</p>
 * <ul>
 *       {@code ModernWorld.push} 一比一：translate → rotateY(-yaw) → rotateX(pitch)
 *       → scale(-s,-s,s)。之后宿主按「MC 字体坐标」画字（x 右、y 下、原点是平面中心）；</li>
 *   <li>{@link Host#drawPixelText(text, x, y, argb, shadow)} —— 原版字体一批
 *   <li>{@link Host#drawSmoothGlyph(c, x, y, baselinePx, sizePx, rgb, alpha, rot, dx, dy, sc)}
 *       —— 平滑字体单字（纹理烘焙档）；</li>
 *   <li>{@link Host#popBillboard()}。</li>
 * </ul>
 *
 * 字的<b>顶部</b>（MC 字体口径），平面中心是原点、行高 {@code WorldLyricsState.LINE_H}。</p>
 */
public final class WorldLyricsRenderer {

    /** 诊断开关（与树侧同一个 -D 属性）。trace 每帧最多一行，避免刷爆日志。 */
    static final boolean TRACE = Boolean.getBoolean("muonium.debug.worldLyrics");
    private static long lastTraceNanos;

    private WorldLyricsRenderer() {
    }

    /** trace 节流：同一秒内不重复打。 */
    private static boolean traceThrottled() {
        long now = System.nanoTime();
        if (now - lastTraceNanos < 1_000_000_000L) return true;
        lastTraceNanos = now;
        return false;
    }

    /** 宿主能力。树侧实现（1.21.1 的 WorldBillboard）。 */
    public interface Host {
        /**
         * 绕 Y 转 -yaw、绕 X 转 pitch，再 scale(-s,-s,s) 翻成文字坐标。
         */
        void pushBillboard(double rx, double ry, double rz,
                           double yawDeg, double pitchDeg, float scale);

        void popBillboard();

        /** 原版像素字一批（y 是字顶；阴影按原版规则拆开单独画）。 */
        void drawPixelText(String text, float x, float y, int argb, boolean shadow);

        /** 原版像素字宽。 */
        float pixelWidth(String text);

        /** 原版单字符宽。 */
        float pixelCharWidth(char c);

        /**
         * 平滑字体单字（纹理烘焙档）。(x, y) 是字的顶部；rot/dx/dy/sc 是姿态
         * 四量（0/0/0/1 = 不动），宿主在纹理四边形上实现旋转/位移/缩放；
         * glowMul 是发光强度乘数（简洁模式恒 1；心象模式下正在唱的块 1、
         * 其余块 0.35 —— 宿主据此选发光烘焙档），alpha 是顶点透明度。
         */
        void drawSmoothGlyph(char c, float x, float y, float sizePx,
                             int rgb, float alpha, float rot, float dx, float dy, float sc,
                             float glowMul);

        /**
         * 绘光拖尾（径迹）：把一条折线画成圆头描边（folia trackLayer 的
         * stroke(width 1.2, round, round) 等价物）。xs/ys 是折线顶点（文字像素
         * 坐标、公告牌平面系），count 为点数；argb 已含整条径迹的透明度；
         * widthPx 是描边宽（设计像素）。
         *
         * <p>宿主可用圆头三角带或预烘彗尾纹理实现；不允许时留空实现
         * （只丢拖尾，不丢字）。宿主收到时仍处于当前 pushBillboard 的矩阵内。</p>
         */
        default void drawTrail(float[] xs, float[] ys, int count,
                               int argb, float widthPx) {
        }

        /**
         * 绘光逐字光晕（folia halos：每字一枚径向渐变柔光点垫在字下）。
         * (cx, cy) 是字心（文字像素坐标、公告牌平面系），sizePx 是光晕正方形
         * 边长（设计像素，folia ≈ fontPx×2.2~3.0），argb 已含整体透明度。
         * 宿主用径向渐变柔光贴图实现；不允许时留空实现（只丢光晕，不丢字）。
         * 调用时仍处于当前 pushBillboard 的矩阵内。
         */
        default void drawHalo(float cx, float cy, float sizePx, int argb) {
        }

        /**
         * 绘光四芒闪点（folia stars）：点亮瞬间的星芒爆闪精灵（四长芒+四短芒），
         * 画在字的<b>上面</b>一层。(cx, cy) 字心附近错落位（文字像素坐标）、
         * sizePx 星芒边长、rotDeg 贴图旋转角（随包络自转，folia star.rotation）、
         * argb 已含整体透明度。宿主用星芒贴图实现；
         * 不允许时留空实现（只丢星芒，不丢字）。调用时仍处于当前 pushBillboard 内。
         */
        default void drawStar(float cx, float cy, float sizePx, float rotDeg, int argb) {
        }

        /**
         * 绘光十字爆闪的一层（folia crossBurst 的竖/横光柱 glow+core、白闪、
         * 冲击环都由宿主用「条纹贴图旋转 0°/90°」+「径向点贴图」组合实现；
         * 本契约按层调用：cx/cy 层中心（文字像素坐标）、width/height 层尺寸
         * （width 沿条纹方向）、rotDeg 条纹旋转角（0=水平条纹、90=竖直条纹）、
         * argb 已含该层的 alpha 与颜色。宿主在 streak 纹理上按 (width, height)
         * 拉伸绘制；不允许时留空实现（只丢爆闪，不丢字）。
         * 调用时仍处于当前 pushBillboard 内。
         */
        default void drawBurstLayer(float cx, float cy, float width, float height,
                                    float rotDeg, int argb) {
        }

        /**
         * 歌词公告牌所在环境的感知亮度 0..1（1 = 明亮白天/有光源，0 = 漆黑深夜）。
         * 绘光模式用它调制未唱字的可见性：白天冷暗档靠暗度对比，夜晚切自发光
         * 冰蓝靠亮度对比（folia 里未唱字被光场照亮的 3D 等价物）。
         * 宿主按各自版本的 API 采样（天空暗度 + 公告牌处方块光）；默认 1 = 白天。
         */
        default float worldLightLevel() {
            return 1f;
        }

        /** 平滑字体的单字宽（同字号）。 */
        float smoothCharWidth(char c, float sizePx);

        /**
         * 同页字形合批（性能）：flush 前的 drawSmoothGlyph 只入缓冲不收批，
         * flush 时把同一图集页的字形一次 endBatch —— 一句 40 字从 ~120 次
         * flush 降到每页 1 次。宿主不实现时 drawSmoothGlyph 仍逐字收批
         * （行为不变，只是慢）。pageKey 由宿主定义（页句柄即可）。
         */
        default void flushGlyphBatch(Object pageKey) {
        }
    }

    /** 连续绘制失败熔断阈值：超过即整体停用（与 Bridge 的 broken 同一口径）。 */
    private static final int MAX_CONSECUTIVE_FAILURES = 60;
    private static int consecutiveFailures;

    /**
     * 持续失败（连续 {@value #MAX_CONSECUTIVE_FAILURES} 帧）时置停用旗标并停止
     * 打日志 —— 以前只 println 不熔断，渲染线程每帧刷一条还永远不桥接
     * Bridge 的 broken 熔断。
     */
    public static void render(WorldLyricsState state, WorldLyricsState.CameraView camera,
                              WorldLyricsSettings settings, Host host, long nowNanos) {
        try {
            renderGuarded(state, camera, settings, host, nowNanos);
            consecutiveFailures = 0;
        } catch (Throwable t) {
            if (consecutiveFailures == 0) {
                System.out.println("[MuoniumPlayer/WorldLyrics] 渲染失败，本帧跳过: " + t);
            }
            consecutiveFailures++;
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                System.out.println("[MuoniumPlayer/WorldLyrics] 连续 " + consecutiveFailures
                        + " 帧渲染失败，整体停用（重启游戏恢复）");
                consecutiveFailures = 0;
                broken = true;
            }
        }
    }

    /** 渲染层整体停用旗标（Bridge 一并在入口短路）。 */
    private static volatile boolean broken;

    private static void renderGuarded(WorldLyricsState state, WorldLyricsState.CameraView camera,
                                      WorldLyricsSettings settings, Host host, long nowNanos) {
        if (broken || !settings.enabled || !WorldLyricsState.hasLyrics() || camera == null || host == null) {
            return;
        }
        // 一次取整份快照：分开调 getSnapshot()/positionMsNow() 可能跨切歌边界，
        // 「上一首的 playing」配上「下一首的 position」会把换行判定搅乱。
        com.muoniumplayer.core.ncm.music.CloudMusic.PlaybackSnapshot snap =
                com.muoniumplayer.core.ncm.music.CloudMusic.getSnapshot();
        WorldLyricsState.Frame frame = state.update(camera, settings, nowNanos,
                snap.playing, snap.positionMs);
        if (frame == null || frame.rows.isEmpty()) {
            if (TRACE && !traceThrottled()) {
                System.out.println("[WorldLyrics/trace] hasLyrics=true 但本帧无行可画"
                        + "（playing=" + snap.playing + " posMs=" + snap.positionMs + "）");
            }
            return;
        }

        long songNowMs = snap.positionMs;

        for (int i = 0; i < frame.rows.size(); i++) {
            WorldLyricsState.Row row = frame.rows.get(i);
            if (row.alpha <= 0.01f) continue;
            drawRow(row, settings, host, songNowMs);
        }
    }

    private static void drawRow(WorldLyricsState.Row row, WorldLyricsSettings settings,
                                Host host, long songNowMs) {
        // 视图矩阵已被 Bridge 换成单位矩阵、相机旋转在 basePose 里，所以顶点必须
        // 用「相对眼睛、未旋转」的坐标 —— 传世界坐标就是几万格外的视锥外。
        host.pushBillboard(
                row.x - row.camX, row.y - row.camY, row.z - row.camZ,
                row.yawDeg, row.pitchDeg, row.scale);
        try {
            // 渲染风格：现代化（Skija 字形图集 + 烘焙发光）或原版像素字。
            // 只换绘制通道 —— billboard 矩阵、逐字时序、特效数学完全共享。
            boolean smooth = settings.styleModern;
            WorldLyricsState.Floating f = row.floating;
            // 量宽必须与绘制通道同源：现代档的字形 advance 是烘焙字体的（一个汉字约
            // 10 设计像素），原版档是 MC 像素字体的（一个汉字 9px）。布局若固定用
            // MC 字体量、字却按烘焙 advance 画，相邻字形会叠在一起 —— 量宽跟着风格走。
            WorldLyricsState.PixelWidthOf widthOf = smooth
                    ? s -> {
                        float total = 0f;
                        for (int i = 0; i < s.length(); i++) {
                            float w = host.smoothCharWidth(s.charAt(i), WorldLyricsState.LYRIC_SIZE);
                            total += w > 0f ? w : host.pixelWidth(String.valueOf(s.charAt(i)));
                        }
                        return total;
                    }
                    : host::pixelWidth;
            // 两个动效都开时绘光优先（与设置界面的「此处优先」文案一致）——
            // 绘光是用户点名要的模式，心象作为兜底回退。
            boolean huiguang = settings.huiguang && smooth;
            boolean cadenza = settings.cadenza && smooth && !huiguang;
            // 关键字表解析（按设置字符串缓存）+ 本句匹配（folia
            // resolveGlyphKeywordColors 的字符区间精确命中）
            parseKeywordTable(settings.keywordColors);
            int[] keywordMatch = huiguang ? matchKeywords(f) : null;
            WorldLyricsState.LyricGlyphs glyphs = WorldLyricsState.buildGlyphs(
                    f, songNowMs, row.alpha, settings.wordPop, !row.active, widthOf,
                    cadenza, huiguang, keywordMatch);
            // 心象模式的分块配色基准色：取色器自定义过的色原样用（选白就是纯白），
            // 没动过时用心象的「暖白当主色」—— 块的 hero/柔色/白三层关系在
            // CadenzaMotion.tokenColor 里按基准色派生。
            int baseRgb = settings.colorCustom
                    ? settings.colorRgb
                    : WorldLyricsState.TEXT_RGB;

                // 文字颜色：取色器自定义过的色原样显示（选白色就是纯白 0xFFFFFF）；
                // 「是否自定义」必须是独立旗标 —— 0xFFFFFF 本身是合法的自定义色，
                // 以前拿值当旗标，选白色被回落成暖白，看起来就是「偏肉色」。
                int inkRgb = baseRgb;
            // 夜色系数 0..1（4 档量化）：宿主采样世界亮度，调制未唱字的
            // 冷暗档（白天暗墨 / 夜晚自发光冰蓝）与发光档
            float huiguangNight = 1f - Math.max(0f, Math.min(1f, host.worldLightLevel()));
            huiguangNight = Math.round(huiguangNight * 4f) / 4f;
            if (huiguang) {
                // 十字爆闪提亮的预计算（folia burstLightBoost，叠加封顶 0.45）：
                // 光斑/光晕 alpha 都会吃到它
                float burstBoost = 0f;
                float[] bursts = glyphs.huiguangBursts;
                if (bursts != null && bursts.length > 0) {
                    for (int bi = 0; bi < bursts.length / 4; bi++) {
                        float agePre = (glyphs.huiguangElapsedMs - bursts[bi * 4 + 1]) / 1000f;
                        if (agePre < 0f || agePre > HuiguangMotion.BURST_DURATION_S) continue;
                        burstBoost += HuiguangMotion.burstLightBoost(agePre);
                    }
                    burstBoost = Math.min(0.45f, burstBoost);
                }
                // ---- 追字光斑（folia spots：每句一枚、最底层，仅 active 句）：
                //      位置取排版路径（layout + 整句绕行，不含逐字飞行/呼吸 ——
                //      folia 用 flow spots，光斑始终沿行心线走）+ 对过去 ~0.3s 的
                //      指数平滑（folia 8 样本平均）；尺寸 5×字号、沿行进方向拉长
                //      ×1.6（两枚错位圆近似椭圆）、alpha 0.07、唱后 0.6s 淡出。
                long spotElapsed = glyphs.huiguangElapsedMs;
                int spotCount = glyphs.glyphs.size();
                if (row.active && spotCount > 0 && glyphs.huiguangLayout != null) {
                    float firstA = glyphs.appearAts.get(0);
                    float lastA = glyphs.appearAts.get(spotCount - 1);
                    if (spotElapsed >= firstA - 250L && spotElapsed <= lastA + 600L) {
                        int gi = 0;
                        while (gi + 1 < spotCount && glyphs.appearAts.get(gi + 1) <= spotElapsed) gi++;
                        WorldLyricsState.LyricGlyph a = glyphs.glyphs.get(gi);
                        WorldLyricsState.LyricGlyph b = glyphs.glyphs.get(Math.min(gi + 1, spotCount - 1));
                        float aAt = glyphs.appearAts.get(gi);
                        float bAt = glyphs.appearAts.get(Math.min(gi + 1, spotCount - 1));
                        float t = bAt > aAt ? (spotElapsed - aAt) / (bAt - aAt) : 0f;
                        t = Math.max(0f, Math.min(1f, t));
                        // 排版路径坐标（layout 中心 + 整句绕行；不含逐字微动）
                        float wA = host.smoothCharWidth(a.c, WorldLyricsState.LYRIC_SIZE) * layoutScaleOf(glyphs, gi);
                        float wB = host.smoothCharWidth(b.c, WorldLyricsState.LYRIC_SIZE) * layoutScaleOf(glyphs, Math.min(gi + 1, spotCount - 1));
                        float tx = (a.x + wA * 0.5f) + ((b.x + wB * 0.5f) - (a.x + wA * 0.5f)) * t
                                + glyphs.huiguangDriftX;
                        float ty = (a.y + WorldLyricsState.LYRIC_SIZE * 0.5f)
                                + ((b.y + WorldLyricsState.LYRIC_SIZE * 0.5f)
                                        - (a.y + WorldLyricsState.LYRIC_SIZE * 0.5f)) * t
                                + glyphs.huiguangDriftY;
                        // 0.3s 指数平滑（folia resolveSpotX 的 8 样本平均等价）；
                        // 歌曲时间驱动（暂停冻结），seek 大跳直接贴合
                        float dt = Math.max(0f, Math.min(200f, spotElapsed - spotLastElapsedMs));
                        if (spotRow != row.floating || spotElapsed < spotLastElapsedMs - 400L
                                || spotLastElapsedMs == Long.MIN_VALUE) {
                            spotSmoothX = tx;
                            spotSmoothY = ty;
                        } else {
                            float k = 1f - (float) Math.exp(-dt / 120f);
                            spotSmoothX += (tx - spotSmoothX) * k;
                            spotSmoothY += (ty - spotSmoothY) * k;
                        }
                        spotRow = row.floating;
                        spotLastElapsedMs = spotElapsed;
                        // 进出渐变：唱前 0.3s 淡入、唱后 0.6s 淡出（folia 同款）
                        float fadeIn = Math.max(0f, Math.min(1f, (spotElapsed - firstA + 250f) / 300f));
                        float fadeOut = 1f - Math.max(0f, Math.min(1f, (spotElapsed - lastA - 150f) / 600f));
                        float spotA = row.alpha * (0.10f + 0.5f * burstBoost) * fadeIn * fadeOut;
                        if (spotA > 0.008f) {
                            int glowColor = settings.glowEnabled ? settings.glowColorRgb
                                    : (inkRgb & 0xFFFFFF);
                            int argb = ((int) (Math.min(1f, spotA) * 255f) << 24) | (glowColor & 0xFFFFFF);
                            // 椭圆 = 两枚错位圆（沿 x 各偏 0.3×半径），等效 ×1.6 拉长
                            float r = WorldLyricsState.LYRIC_SIZE * 2.5f;
                            host.drawHalo(spotSmoothX - r * 0.3f, spotSmoothY, r * 1.15f, argb);
                            host.drawHalo(spotSmoothX + r * 0.3f, spotSmoothY, r * 1.15f, argb);
                        }
                    }
                }
                // ---- 十字爆闪（folia crossBurst 完整版）：句身触发表 + 包络求值，
                //      白闪/竖光柱/横光柱/冲击环五层全画；同时累计光场提亮（folia
                //      burstLightBoost，叠加封顶 0.45），加到追字光斑与逐字光晕的
                //      alpha 上 —— 一串十字连炸时整句呼吸式增亮。
                if (bursts != null && bursts.length > 0) {
                    BURST_POSE[0] = 0f;
                    for (int bi = 0; bi < bursts.length / 4; bi++) {
                        int charIdx = (int) bursts[bi * 4];
                        float ageS = (glyphs.huiguangElapsedMs - bursts[bi * 4 + 1]) / 1000f;
                        if (ageS < 0f || ageS > HuiguangMotion.BURST_DURATION_S) continue;
                        if (charIdx >= glyphs.glyphs.size()) continue;
                        WorldLyricsState.LyricGlyph anchor = glyphs.glyphs.get(charIdx);
                        float anchorW = host.smoothCharWidth(anchor.c, WorldLyricsState.LYRIC_SIZE);
                        float length = WorldLyricsState.LYRIC_SIZE * bursts[bi * 4 + 2]
                                * layoutScaleOf(glyphs, charIdx);
                        // anchor.x/y 已含 orbit+breath+崩解（buildGlyphs 加过）——
                        // 这里不能再加 huiguangDrift（否则 orbit 双加，十字绕字漂移）
                        float bx = anchor.x + anchorW * 0.5f;
                        float by = anchor.y + WorldLyricsState.LYRIC_SIZE * 0.5f
                                + bursts[bi * 4 + 3] * WorldLyricsState.LYRIC_SIZE;
                        HuiguangMotion.burstPose(ageS, length, BURST_POSE);
                        float env = BURST_POSE[0];
                        if (env < 0.01f) continue;
                        int glowColor = settings.glowEnabled ? settings.glowColorRgb
                                : (inkRgb & 0xFFFFFF);
                        // 关键字字的爆闪染色（folia keywordBurstColor 0.7 权重）
                        int burstBase = glowColor & 0xFFFFFF;
                        if (anchor.keywordRgb != 0) {
                            burstBase = keywordLight(burstBase, anchor.keywordRgb, 0.7f);
                        }
                        int glowTint = HuiguangMotion.dimRgb(burstBase, 1.1f);
                        int coreTint = mixRgb(glowTint, 0xFFFFFF, 0.75f);
                        int layerA = (int) (255f * Math.min(1f, row.alpha));
                        // 竖光柱（glow + core）：交点系原点在光柱 0.42L 上段（拉丁十字）
                        float colX = bx;
                        float colY = by - length * 0.2f;
                        if (BURST_POSE[1] > 0.5f) {
                            host.drawBurstLayer(colX, colY, BURST_POSE[1],
                                    Math.max(1f, length * 0.10f * BURST_POSE[6]),
                                    90f, (int) (layerA * 0.6f * env) << 24 | (glowTint & 0xFFFFFF));
                            host.drawBurstLayer(colX, colY, BURST_POSE[1],
                                    Math.max(1f, length * 0.02f * BURST_POSE[6]),
                                    90f, (int) (layerA * 1f * env) << 24 | (coreTint & 0xFFFFFF));
                        }
                        // 横光条（glow + core）：交点在光柱上段
                        float rowY = by - length * 0.42f;
                        if (BURST_POSE[2] > 0.5f) {
                            // folia 横条还有 30ms 的二段淡入（crossBurst.ts:164）
                            float hFade = Math.max(0f, Math.min(1f, (ageS - 0.02f) / 0.03f));
                            host.drawBurstLayer(bx, rowY, BURST_POSE[2],
                                    Math.max(1f, length * 0.09f * BURST_POSE[6]),
                                    0f, (int) (layerA * 0.55f * env * hFade) << 24 | (glowTint & 0xFFFFFF));
                            host.drawBurstLayer(bx, rowY, BURST_POSE[2],
                                    Math.max(1f, length * 0.018f * BURST_POSE[6]),
                                    0f, (int) (layerA * 0.95f * env * hFade) << 24 | (coreTint & 0xFFFFFF));
                        }
                        // 白闪（点贴图）
                        float flashA = (float) Math.exp(-ageS / 0.07f);
                        if (flashA > 0.03f) {
                            host.drawHalo(bx, rowY, BURST_POSE[3],
                                    (int) (layerA * flashA) << 24 | (coreTint & 0xFFFFFF));
                        }
                        // 冲击环（无 bokeh 环贴图：以大号低 alpha 柔光圆近似「扩散
                        // 的冲击波前」—— 实心盘但随扩散 alpha 反比消失，观感近似）
                        if (BURST_POSE[5] > 0.02f) {
                            host.drawHalo(bx, rowY, BURST_POSE[4],
                                    (int) (layerA * BURST_POSE[5]) << 24 | (glowTint & 0xFFFFFF));
                        }
                    }
                }
                // ---- 绘光预通道：径迹 + 逐字光晕（folia 层序 spots<track<halos<glyphs：
                //      两层都垫在所有字的下面，跨字重叠时光晕不会盖住相邻字形）----
                for (int gi = 0; gi < glyphs.glyphs.size(); gi++) {
                    WorldLyricsState.LyricGlyph g = glyphs.glyphs.get(gi);
                    HuiguangMotion.Flight fl = !glyphs.huiguangFlights.isEmpty()
                            ? glyphs.huiguangFlights.get(gi) : null;
                    // 径迹只在飞行窗口内 + 收拢等待期（TRACK_TIME）内存在 ——
                    // folia drawTracks 的「彻底消失」条款：超出后即使 wobble 还在
                    // 微振也整条不画（否则折线底噪让 strength 恒 >0.05，径迹永不清除）。
                    boolean trailAlive = glyphs.huiguangElapsedMs
                            <= HuiguangMotion.ENTRY_WINDOW_MS
                                    + HuiguangMotion.TRACK_TIME_S * 1000f;
                        if (fl != null && fl.valid && trailAlive) {
                            int n = HuiguangMotion.trailPoints(fl, glyphs.huiguangElapsedMs,
                                    HuiguangMotion.ENTRY_WINDOW_MS, WorldLyricsState.LYRIC_SIZE,
                                    TRAIL_XS, TRAIL_YS);
                            // 径迹点补上本句恒定绕行 + 本字呼吸（与字形同一套微动）——
                            // 拖尾才能一直连着正在漂移的字
                            float[] br = HuiguangMotion.glyphBreath(
                                    glyphs.huiguangElapsedMs / 1000f,
                                    WorldLyricsState.LYRIC_SIZE, fl.wobblePhase);
                            for (int ti = 0; ti < n; ti++) {
                                TRAIL_XS[ti] += glyphs.huiguangDriftX + br[0];
                                TRAIL_YS[ti] += glyphs.huiguangDriftY + br[1];
                            }
                            if (n > 1) {
                            // 强度按无 wobble 的 pose 折线判定（静止字恒 0、收尾自然
                            // 衰减归零）；带 wobble 的 TRAIL_XS/YS 只用于绘制顶点。
                            float strength = HuiguangMotion.trailPoseStrength(fl,
                                    glyphs.huiguangElapsedMs, HuiguangMotion.ENTRY_WINDOW_MS,
                                    WorldLyricsState.LYRIC_SIZE);
                            if (strength > HuiguangMotion.TRAIL_MIN_STRENGTH) {
                                int trailRgb = HuiguangMotion.dimRgb(inkRgb & 0xFFFFFF, 1.15f);
                                int argb = ((int) (Math.min(1f,
                                        row.alpha * HuiguangMotion.TRAIL_ALPHA * strength) * 255f) << 24)
                                        | (trailRgb & 0xFFFFFF);
                                host.drawTrail(TRAIL_XS, TRAIL_YS, n, argb,
                                        HuiguangMotion.TRAIL_WIDTH_PX);
                            }
                        }
                    }
                    // 逐字光晕（folia halos：每字一枚径向渐变柔光点垫在字下）。
                    // folia haloAlpha = glyphAlpha × lit × (0.03 + 0.14illum + 0.08flash)、
                    // size = fontPx × (2.2 + 0.8illum)；本仓库无光场，illumination 以
                    // 点亮进度 lit、闪点以 flash 代入 —— 未唱字留底晕，唱到时晕随点亮
                    // 涨大变亮、闪点瞬间再增亮一拍。
                    // folia haloAlpha = glyphAlpha × lit × (0.03+0.14illum+0.08flash)：
                    // 满打满算 ~0.25 且通常远低，未唱字（lit=0）无光晕。本仓库贴图
                    // 无 bloom 加持取同档下限：lit 满时 ~0.14、闪点 +0.12。尺寸收紧
                    // 到 1.7~2.4 字号 —— 早期 0.22 常驻底晕 + 3.5 字号大圆盘，相邻
                    // 字的光晕叠成一层奶雾盖住字形（用户反馈的观感问题）。
                    float haloA = Math.min(1f, g.alpha)
                            * g.huiguangLit * (0.10f + 0.12f * g.huiguangFlash)
                            + Math.min(1f, g.alpha) * burstBoost * 0.3f;
                    if (haloA > 0.012f) {
                        float w = host.smoothCharWidth(g.c, WorldLyricsState.LYRIC_SIZE);
                        float haloSize = WorldLyricsState.LYRIC_SIZE
                                * (1.7f + 0.5f * g.huiguangLit + 0.4f * g.huiguangFlash);
                        int glowColor = settings.glowEnabled ? settings.glowColorRgb
                                : (inkRgb & 0xFFFFFF);
                        if (g.keywordRgb != 0) {
                            glowColor = keywordLight(glowColor & 0xFFFFFF, g.keywordRgb, 0.7f);
                        }
                        int argb = ((int) (Math.min(1f, haloA) * 255f) << 24) | (glowColor & 0xFFFFFF);
                        host.drawHalo(g.x + w * 0.5f, g.y + WorldLyricsState.LYRIC_SIZE * 0.5f,
                                haloSize, argb);
                    }
                }
            }
            for (int gi = 0; gi < glyphs.glyphs.size(); gi++) {
                WorldLyricsState.LyricGlyph g = glyphs.glyphs.get(gi);
                // g.pop 恒生效的弹性缩放（入场飞入回弹 / word pop 回弹 / 出场收拢），
                if (smooth) {
                    int rgb = inkRgb;
                    float glowMul = 1f;
                    if (g.tokenRgb != 0) {
                        // 心象：块墨色 + 正在唱的块提亮（心象 ACTIVE_BRIGHTEN 一比一）。
                        rgb = g.tokenActive
                                ? CadenzaMotion.brighten(g.tokenRgb & 0xFFFFFF, CadenzaMotion.ACTIVE_BRIGHTEN)
                                : g.tokenRgb & 0xFFFFFF;
                    }
                    if (g.cadenzaGlow > 0f) {
                        // 心象：唱到的块用满档发光（块脉冲走 alpha 顶点色通道，
                        // 发光烘焙按两档强度分格 —— 见 drawSmoothGlyph 宿主侧）。
                        glowMul = 1f;
                    } else if (cadenza && g.tokenRgb != 0) {
                        // 心象下不在唱的块压低发光（与简洁模式「整行恒定发光」区分）。
                        glowMul = 0.35f;
                    }
                    if (huiguang) {
                        // ---- 卡拉OK双色：冷暗档 / 暖亮档两张烘焙格，顶点 alpha 交叉淡化
                        //      （folia tint 混色的 alpha 通道等价物）。每字恰好两格 ——
                        //      早期按点亮进度量化 9 档墨色，一次换句要烘几十格、烘焙
                        //      预算撑不住时肉眼可见地退回原版像素字，已废弃。
                        //      冷暗档随环境光调制：白天暗墨（亮背景靠暗度对比），
                        //      夜晚自发光冰蓝（暗背景靠亮度对比）—— 夜色系数 4 档量化，
                        //      黄昏/黎明平滑过渡且图集缓存键不抖动。
                        float lit = g.huiguangLit;
                        // 关键字着色（folia keywordLight）：点亮后字身按 0.6 混入
                        // 关键字色相（先拉满亮度只取色相，再归一回光色亮度），保证
                        // 「仍是发光体」
                        int full = inkRgb & 0xFFFFFF;
                        if (g.keywordRgb != 0) {
                            full = keywordLight(full, g.keywordRgb, 0.6f);
                        }
                        // folia「未点亮的字保持冷色」：暗档从未混色 inkRgb 派生，
                        // 只有亮档走关键字混色 —— 未唱关键字字不带淡色相
                        int dim = huiguangUnlitInk(inkRgb & 0xFFFFFF, huiguangNight);
                        if (lit < 0.999f) {
                            float da = Math.min(1f, g.alpha * (1f + 0.45f * huiguangNight)) * (1f - lit);
                            if (da > 0.02f) {
                                host.drawSmoothGlyph(g.c, g.x, g.y, WorldLyricsState.LYRIC_SIZE,
                                        dim, da, g.rot, g.dx, g.dy, g.pop, 0.35f + 0.25f * huiguangNight);
                            }
                        }
                        if (lit > 0.001f) {
                            float fa = g.alpha * lit;
                            if (fa > 0.02f) {
                                // 飞行白化（folia hot 色 += flying×0.25 的等价物）：
                                // 飞行中的字提高发光烘焙乘数，像带电粒子
                                float glow = 1f + (g.flyingWhite ? 0.25f : 0f);
                                host.drawSmoothGlyph(g.c, g.x, g.y, WorldLyricsState.LYRIC_SIZE,
                                        full, fa, g.rot, g.dx, g.dy, g.pop, glow);
                            }
                        }
                        // ---- 四芒闪点（folia stars 一比一校准）：逐字大小差
                        //      0.65~1.35 字号 ×(0.8+0.5flash)、静态转角 ±0.25rad +
                        //      随包络旋转（熄得越快转得越多）、位置上偏（多在字上半）。
                        if (g.huiguangFlash > 0.02f) {
                            float u = WorldLyricsState.hashRand(g.k, 0L, 71, glyphs.seed) * 0.5f + 0.5f;
                            float starBase = 0.65f + u * 0.7f;
                            float starSize = WorldLyricsState.LYRIC_SIZE * starBase
                                    * (0.8f + 0.5f * g.huiguangFlash);
                            float rotDeg = (float) Math.toDegrees(
                                    WorldLyricsState.hashRand(g.k, 0L, 85, glyphs.seed) * 0.25f)
                                    * (1f + (1f - g.huiguangFlash) * 0.6f);
                            float cx = g.x + WorldLyricsState.LYRIC_SIZE * 0.5f
                                    + WorldLyricsState.hashRand(g.k, 0L, 71, glyphs.seed)
                                            * 0.22f * WorldLyricsState.LYRIC_SIZE;
                            float u2 = WorldLyricsState.hashRand(g.k, 0L, 71, glyphs.seed + 1) * 0.5f + 0.5f;
                            float cy = g.y + WorldLyricsState.LYRIC_SIZE * 0.5f
                                    + (-0.62f + (float) Math.pow(u2, 1.4f) * 0.9f) * WorldLyricsState.LYRIC_SIZE;
                            int glowColor = settings.glowEnabled ? settings.glowColorRgb
                                    : (inkRgb & 0xFFFFFF);
                            int argb = ((int) (Math.min(1f, row.alpha * g.huiguangFlash * 0.55f) * 255f) << 24)
                                    | (glowColor & 0xFFFFFF);
                            host.drawStar(cx, cy, starSize, rotDeg, argb);
                        }
                        continue;
                    }
                    host.drawSmoothGlyph(g.c, g.x, g.y, WorldLyricsState.LYRIC_SIZE,
                            rgb, g.alpha, g.rot, g.dx, g.dy, g.pop, glowMul);
                } else {
                    int argb = ((int) (g.alpha * 255f) << 24) | (inkRgb & 0xFFFFFF);
                    host.drawPixelText(String.valueOf(g.c), g.x, g.y, argb, true);
                }
            }
        } finally {
            host.popBillboard();
        }
    }

    public static List<LyricLine> split(LyricLine line, boolean enabled) {
        return SplitLines.split(line, enabled);
    }

    // ==================== 绘光（Huiguang）渲染辅助 ====================

    /** 径迹折线的采样缓冲（TRACK_SAMPLES+1 点；渲染线程单线程使用）。 */
    private static final float[] TRAIL_XS = new float[HuiguangMotion.TRACK_SAMPLES + 1];
    private static final float[] TRAIL_YS = new float[HuiguangMotion.TRACK_SAMPLES + 1];

    /** 未唱字夜晚档的冰蓝色（月光霜感，暗背景上自发光可读）。 */
    private static final int HUIGUANG_ICE_RGB = 0xA9D6F2;

    /** 十字爆闪包络缓冲（burstPose 输出 7 分量；渲染线程单线程）。 */
    private static final float[] BURST_POSE = new float[7];

    // 追字光斑的指数平滑状态（渲染线程单线程；按句身份切换、暂停冻结）
    private static WorldLyricsState.Floating spotRow;
    private static long spotLastElapsedMs = Long.MIN_VALUE;
    private static float spotSmoothX, spotSmoothY;

    /** 绘光排版第 gi 个非空白字符的词级字号倍率（layout 数组 4 步长的第 4 通道）。 */
    private static float layoutScaleOf(WorldLyricsState.LyricGlyphs glyphs, int gi) {
        return glyphs.huiguangLayoutScales != null && gi < glyphs.huiguangLayoutScales.length
                ? glyphs.huiguangLayoutScales[gi] : 1f;
    }

    /**
     * 未唱字的冷暗档墨色（随环境光调制）：白天 = 原暗墨（亮背景靠暗度对比，
     * 观感与旧版一致）；夜晚 = 自发光冰蓝（暗背景靠亮度对比，folia 里未唱字
     * 被光场照亮的等价物）。night 已 4 档量化 —— 图集缓存键含颜色，量化保证
     * 黄昏/黎明平滑过渡时缓存键不抖动（每字 ≤5 档冷暗格 + 1 档暖亮格）。
     */
    private static int huiguangUnlitInk(int ink, float night) {
        int dim = HuiguangMotion.dimRgb(ink, HuiguangMotion.UNLIT_DIM);
        if (night <= 0.01f) return dim;
        // 夜色冰蓝：冷色为主、混入少量墨色保色调（亮度 ≈0.76，暗夜里清晰可读）
        int ice = mixRgb(ink, HUIGUANG_ICE_RGB, 0.55f);
        return mixRgb(dim, ice, night);
    }

    /** 按通道线性混色（t=0 → a，t=1 → b）。 */
    private static int mixRgb(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int g = (int) (((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int b2 = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return (r << 16) | (g << 8) | b2;
    }
    // ==================== 绘光关键字着色（folia keywordColors 完整版） ====================

    /** 关键字表解析缓存（按设置字符串；渲染线程单线程）。 */
    private static String cachedKeywordSpec = "";
    private static String[] cachedKeywordWords = new String[0];
    private static int[] cachedKeywordColors = new int[0];

    /** 解析「词:RRGGBB,词:RRGGBB,…」设置串；非法项跳过，最多 12 项（烘焙预算护栏）。 */
    private static Object parseKeywordTable(String spec) {
        if (spec == null) spec = "";
        if (!spec.equals(cachedKeywordSpec)) {
            java.util.ArrayList<String> words = new java.util.ArrayList<>();
            java.util.ArrayList<Integer> colors = new java.util.ArrayList<>();
            for (String item : spec.split(",")) {
                String part = item.trim();
                int colon = part.lastIndexOf(':');
                if (colon <= 0 || colon + 1 >= part.length() || words.size() >= 12) continue;
                try {
                    String hex = part.substring(colon + 1).trim();
                    if (hex.startsWith("0x") || hex.startsWith("0X")) hex = hex.substring(2);
                    int rgb = Integer.parseInt(hex, 16) & 0xFFFFFF;
                    String word = part.substring(0, colon).trim();
                    if (!word.isEmpty()) {
                        words.add(word);
                        colors.add(rgb);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            cachedKeywordSpec = spec;
            cachedKeywordWords = words.toArray(new String[0]);
            cachedKeywordColors = new int[colors.size()];
            for (int i = 0; i < colors.size(); i++) cachedKeywordColors[i] = colors.get(i);
        }
        return new Object[] {cachedKeywordWords, cachedKeywordColors};
    }

    /**
     * 本句的关键字逐字匹配（folia resolveGlyphKeywordColors 的字符区间版）：
     * 整句非空白字符流上找关键字的完整命中（前后不能再与任何关键字续成更长
     * 命中——「花火」不染「火车」的火），命中区间按关键字色标记。
     */
    private static int[] matchKeywords(WorldLyricsState.Floating f) {
        String[] words = cachedKeywordWords;
        int[] colors = cachedKeywordColors;
        if (words.length == 0) return null;
        // 整句非空白字符流
        StringBuilder text = new StringBuilder();
        for (String line : f.lines) {
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (!Character.isWhitespace(c)) text.append(c);
            }
        }
        String flat = text.toString();
        int[] out = new int[flat.length()];
        // 区间语义（folia wordColoring）：先收集每个词的全部出现区间，命中区间
        // 被拒仅当存在「另一词」的出现与它相交（长词优先覆盖短词）—— 命中自身
        // 不参与相交判定（旧版的自我匹配让 ≥2 字词句中永不染色）。
        // words/colors 按「长度降序 + 原序稳定」排列调用（解析层已保证）。
        java.util.ArrayList<int[]> spans = new java.util.ArrayList<>();   // {start, end, colorIdx}
        for (int w = 0; w < words.length; w++) {
            String word = words[w];
            int from = 0;
            while (from + word.length() <= flat.length()) {
                int at = flat.indexOf(word, from);
                if (at < 0) break;
                spans.add(new int[] {at, at + word.length(), w});
                from = at + 1;
            }
        }
        // 长词优先（稳定）：相交冲突时长词吃掉短词
        spans.sort((a, b) -> {
            int la = a[1] - a[0], lb = b[1] - b[0];
            return la != lb ? Integer.compare(lb, la) : Integer.compare(a[0], b[0]);
        });
        boolean[] taken = new boolean[flat.length()];
        for (int[] span : spans) {
            boolean free = true;
            for (int i = span[0]; i < span[1] && free; i++) {
                if (taken[i]) free = false;
            }
            if (!free) continue;
            for (int i = span[0]; i < span[1]; i++) {
                taken[i] = true;
                out[i] = colors[span[2]];
            }
        }
        return out;
    }

    /**
     * folia keywordLight 一比一：关键字色先拉到满亮度只取色相，与光色按 amount
     * 混，再把混合结果归一回光色亮度——「关键字染色后仍是发光体」。
     */
    private static int keywordLight(int light, int keyword, float amount) {
        int kr = (keyword >> 16) & 0xFF, kg = (keyword >> 8) & 0xFF, kb = keyword & 0xFF;
        int peak = Math.max(kr, Math.max(kg, kb));
        float hr = peak > 0 ? kr / (float) peak : 1f;
        float hg = peak > 0 ? kg / (float) peak : 1f;
        float hb = peak > 0 ? kb / (float) peak : 1f;
        int lr = (light >> 16) & 0xFF, lg = (light >> 8) & 0xFF, lb = light & 0xFF;
        float mr = lr + (hr * 255f - lr) * amount;
        float mg = lg + (hg * 255f - lg) * amount;
        float mb = lb + (hb * 255f - lb) * amount;
        float mixedPeak = Math.max(mr, Math.max(mg, mb));
        float target = Math.max(lr, Math.max(lg, lb));
        if (mixedPeak > 1e-3f) {
            float s = target / mixedPeak;
            mr *= s;
            mg *= s;
            mb *= s;
        }
        return (((int) mr) << 16) | (((int) mg) << 8) | ((int) mb);
    }
}
