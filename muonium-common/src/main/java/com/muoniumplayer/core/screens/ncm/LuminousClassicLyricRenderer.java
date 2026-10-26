package com.muoniumplayer.core.screens.ncm;

import java.util.ArrayList;
import java.util.List;

import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.settings.HudConfig;

/**
 * 「流光 · 原版」：对本项目 {@code classic} 可视化器的更忠实移植。
 *
 * <p>与项目里现有的 {@link LuminousLyricRenderer} 不同，这一套不是把每个字都画成独立进度条，
 * 而是完整保留参考实现的字词状态机：等待时字词在画面外保持低缩放和高旋转，临近演唱时飞入，
 * 演唱时整词点亮并带一次沿字序移动的光晕，唱完后落到主文字色并继续缓慢旋转。行本身也有
 * 独立的进场/退场缩放，整块随节拍呼吸。</p>
 *
 * <p>参考实现使用 React + framer-motion + DOM filter；这里用逐帧位置插值近似 spring 和
 * 5 秒 rotate 动画，不引入 Web 运行时。所有随机量仍以行为种子，换行前每帧布局稳定。</p>
 */
final class LuminousClassicLyricRenderer extends AbstractStageLyricRenderer {

    /** 当前词的演唱提前量：进入 active 状态并开始飞入。 */
    private static final double WORD_LOOKAHEAD_MS = 150;

    /** 等待姿态飞入 active 姿态的时长。 */
    private static final double WORD_ENTER_MS = 420;

    /** 唱完后从 active 缩放/颜色回到 passed 姿态的时长。 */
    private static final double WORD_SETTLE_MS = 500;

    /** 参考实现里 passed 旋转大约持续 5 秒。 */
    private static final double PASSED_ROTATE_MS = 5000;

    /** 整行进场时长，和参考实现的 normal lineTransition 对齐。 */
    private static final double LINE_ENTER_MS = 420;

    /** 整行退场时长，墙上时钟。 */
    private static final double EXIT_SECONDS = .30;

    private static final int MAX_ROWS = 4;

    /** normal 强度下的散布与静态旋转幅度。 */
    private static final double BASE_SPREAD = 20.0;
    private static final double BASE_ROTATE = 5.0;

    /** 参考实现的 active scale：配置 scale × 1.4。 */
    private static final double ACTIVE_SCALE = 1.4;

    /** 一个词从 active 到 passed 的余晖持续时间。 */
    private static final double RIPPLE_SECONDS = .5;

    private static final class Word {
        String text = "";
        List<String> chars;
        long startMs;
        long endMs;
        int index;

        double x;
        double y;
        double width;
        double slotWidth;
        double height;

        double baseScale = 1.0;
        double rotate;
        double passedRotate;
        double spreadX;
        double spreadY;
        double ripple;

        double durationMs() {
            return Math.max(1.0, endMs - startMs);
        }
    }

    private final List<Word> words = new ArrayList<Word>();
    private final List<Word> outgoing = new ArrayList<Word>();

    private LyricLine laidOutLine;
    private double laidOutWidth = -1;
    private double laidOutHeight = -1;
    private long firstStartMs = Long.MAX_VALUE;

    private CFontRenderer font = null;
    private double fontScale = 1;
    private double fontPx = 16;
    private double viewportScale = 1;

    private long exitStartNanos;

    protected void onReset() {
        words.clear();
        outgoing.clear();
        laidOutLine = null;
        laidOutWidth = -1;
        laidOutHeight = -1;
        firstStartMs = Long.MAX_VALUE;
        exitStartNanos = 0L;
    }

    protected void draw(double left, double top, double width, double height,
                        float alpha, double positionMs, LyricLine currentLine, double opened) {
        long now = System.nanoTime();
        boolean viewportChanged = tuningDirty
                || Math.abs(width - laidOutWidth) >= 2.0
                || Math.abs(height - laidOutHeight) >= 2.0;
        if (currentLine != laidOutLine || viewportChanged) {
            if (currentLine != laidOutLine) {
                outgoing.clear();
                outgoing.addAll(words);
                exitStartNanos = now;
            }
            rebuild(currentLine, width, height);
            laidOutLine = currentLine;
            laidOutWidth = width;
            laidOutHeight = height;
        }
        if (words.isEmpty() && outgoing.isEmpty()) return;

        double floatAmount = Math.max(0f, HudConfig.luminousFloatAmount);
        double bob = Math.sin(elapsedSeconds * .78) * 6.5 * floatAmount
                + beat * 2.8 * floatAmount;
        double centerX = left + width * .5;
        double centerY = top + height * .5 + bob + (1.0 - opened) * height * .28;

        double lineEnter = 1.0;
        if (currentLine != null && firstStartMs != Long.MAX_VALUE) {
            lineEnter = FullscreenLyricStage.easeOutCubic(FullscreenLyricStage.clamp01(
                    (positionMs - (firstStartMs - WORD_LOOKAHEAD_MS)) / LINE_ENTER_MS));
        }

        if (!outgoing.isEmpty()) {
            double exit = (now - exitStartNanos) / 1.0e9 / EXIT_SECONDS;
            if (exit >= 1.0) {
                outgoing.clear();
            } else {
                drawWords(outgoing, positionMs, centerX, centerY, alpha, exit, 1.0, true);
            }
        }
        if (!words.isEmpty()) {
            drawWords(words, positionMs, centerX, centerY, alpha, 0.0, lineEnter, false);
        }
    }

    // ==================== 布局 ====================

    private void rebuild(LyricLine line, double viewWidth, double viewHeight) {
        words.clear();
        firstStartMs = Long.MAX_VALUE;
        if (line == null) return;

        double stageWidth = Math.max(48.0, viewWidth - 20.0);
        double stageHeight = Math.max(40.0, viewHeight - 24.0);
        viewportScale = FullscreenLyricStage.clamp(viewWidth / 820.0, .42, 1.25);

        List<FullscreenLyricStage.Token> tokens = FullscreenLyricStage.tokensOf(line, 4);
        if (tokens.isEmpty()) return;

        // 参考实现的字号上限较宽松；这里按 Minecraft 播放器右半区的尺寸取一个稳定的基准。
        double targetPx = FullscreenLyricStage.baseFontPx(viewWidth, viewHeight) * 1.24;
        for (int attempt = 0; attempt < 8; attempt++) {
            font = FullscreenLyricStage.fontAtMost(targetPx);
            fontScale = FullscreenLyricStage.scaleFor(font, targetPx);
            fontPx = targetPx;
            if (layout(tokens, line, stageWidth, stageHeight)) return;
            targetPx *= .84;
            if (targetPx < 11.0) break;
        }
        layout(tokens, line, stageWidth, stageHeight);
    }

    private boolean layout(List<FullscreenLyricStage.Token> tokens, LyricLine line,
                           double stageWidth, double stageHeight) {
        words.clear();
        firstStartMs = Long.MAX_VALUE;
        if (font == null || tokens.isEmpty()) return false;

        long seed = FullscreenLyricStage.seedOf(line);
        double spacing = FullscreenLyricStage.clamp(HudConfig.luminousWordSpacing, .20, 1.60);
        double gap = fontPx * (.06 + .31 * spacing);
        double lineHeight = fontPx * 1.52 + 4.0 * viewportScale;
        boolean rotation = HudConfig.luminousWordRotation;

        List<List<Word>> rows = new ArrayList<List<Word>>();
        List<Word> row = new ArrayList<Word>();
        double rowWidth = 0;
        for (int i = 0; i < tokens.size(); i++) {
            FullscreenLyricStage.Token token = tokens.get(i);
            Word word = new Word();
            word.text = token.text;
            word.chars = FullscreenLyricStage.graphemes(token.text);
            word.startMs = token.startMs;
            word.endMs = token.endMs;
            word.index = i;
            word.width = font.getStringWidthD(token.text) * fontScale;
            if (word.width <= .01) word.width = Math.max(1.0, fontPx * .62 * word.chars.size());
            word.height = font.getFontHeight() * fontScale;

            double r1 = FullscreenLyricStage.random(seed, i * 13 + 2);
            double r2 = FullscreenLyricStage.random(seed, i * 13 + 3);
            double r3 = FullscreenLyricStage.random(seed, i * 13 + 4);
            double r4 = FullscreenLyricStage.random(seed, i * 13 + 5);
            double r5 = FullscreenLyricStage.random(seed, i * 13 + 6);

            // 参考实现的 normal 强度：scale 1.1..1.3、rotate ±5°、passedRotate ±22.5°。
            word.baseScale = 1.10 + r1 * .20;
            word.rotate = rotation ? (r2 - .5) * BASE_ROTATE * 2.0 : 0.0;
            word.passedRotate = rotation ? (r3 - .5) * 45.0 : 0.0;
            word.spreadX = (r4 - .5) * BASE_SPREAD * 2.0 * viewportScale;
            word.spreadY = (r5 - .5) * BASE_SPREAD * 2.0 * viewportScale;
            word.ripple = r5;

            // 预留给 active 时的 1.4 倍放大，避免相邻词在演唱瞬间互相压字。
            double activeScale = word.baseScale * ACTIVE_SCALE;
            word.slotWidth = word.width * (1.0 + (activeScale - 1.0) * .46);
            double advance = word.slotWidth + (row.isEmpty() ? 0.0 : gap);
            if (!row.isEmpty() && rowWidth + advance > stageWidth) {
                rows.add(row);
                row = new ArrayList<Word>();
                rowWidth = 0.0;
                advance = word.slotWidth;
            }
            rowWidth += advance;
            row.add(word);

            if (word.startMs < firstStartMs) firstStartMs = word.startMs;
        }
        if (!row.isEmpty()) rows.add(row);

        double blockHeight = rows.size() * lineHeight;
        double blockWidth = 0.0;
        double blockTop = -blockHeight * .5;
        for (int r = 0; r < rows.size(); r++) {
            List<Word> current = rows.get(r);
            double total = 0.0;
            for (int i = 0; i < current.size(); i++) {
                if (i > 0) total += gap;
                total += current.get(i).slotWidth;
            }
            blockWidth = Math.max(blockWidth, total);

            double cursor = -total * .5;
            double rowTop = blockTop + r * lineHeight
                    + (lineHeight - font.getFontHeight() * fontScale) * .5;
            for (int i = 0; i < current.size(); i++) {
                Word word = current.get(i);
                if (i > 0) cursor += gap;
                word.x = cursor;
                word.y = rowTop;
                cursor += word.slotWidth;
                words.add(word);
            }
        }

        return rows.size() <= MAX_ROWS
                && blockHeight <= stageHeight
                && blockWidth <= stageWidth + .5;
    }

    // ==================== 绘制 ====================

    private void drawWords(List<Word> list, double positionMs, double centerX, double centerY,
                           float alpha, double exitProgress, double lineEnter, boolean outgoing) {
        if (font == null || list.isEmpty()) return;

        double exit = FullscreenLyricStage.clamp01(exitProgress);
        double groupAlpha = outgoing
                ? alpha * (1.0 - FullscreenLyricStage.easeInCubic(exit))
                : alpha * FullscreenLyricStage.clamp01(lineEnter);
        if (groupAlpha <= .004) return;

        double groupScale = outgoing
                ? 1.0 + .08 * FullscreenLyricStage.easeInCubic(exit)
                : .90 + .10 * FullscreenLyricStage.clamp01(lineEnter);

        FullscreenLyricStage.pushMatrix();
        try {
            FullscreenLyricStage.scaleAt(centerX, centerY, groupScale);
            for (int i = 0; i < list.size(); i++) {
                drawWord(list.get(i), positionMs, centerX, centerY, groupAlpha, exit, outgoing);
            }
        } finally {
            FullscreenLyricStage.popMatrix();
        }
    }

    private void drawWord(Word word, double positionMs, double centerX, double centerY,
                          double alpha, double exitProgress, boolean outgoing) {
        if (font == null || word.chars == null || word.chars.isEmpty()) return;

        double revealStart = word.startMs - WORD_LOOKAHEAD_MS;
        double reveal = FullscreenLyricStage.clamp01(
                (positionMs - revealStart) / WORD_ENTER_MS);
        if (reveal <= .004) return;

        boolean passed = positionMs > word.endMs;
        boolean active = positionMs >= word.startMs && positionMs <= word.endMs;
        double enter = FullscreenLyricStage.easeOutBack(reveal);
        double settle = passed
                ? FullscreenLyricStage.easeOutCubic(FullscreenLyricStage.clamp01(
                        (positionMs - word.endMs) / WORD_SETTLE_MS))
                : 0.0;
        double rotateSettle = passed
                ? FullscreenLyricStage.easeOutCubic(FullscreenLyricStage.clamp01(
                        (positionMs - word.endMs) / PASSED_ROTATE_MS))
                : 0.0;

        // framer-motion 的 waiting pose：向画面外散开、缩小 0.5、再向前倾 20°。
        double waitingX = word.x + Math.sin(word.y * .11 + word.index * 1.7)
                * word.spreadX * 2.8;
        double waitingY = word.y + Math.cos(word.x * .11 + word.index * 1.3)
                * word.spreadY * 2.2;

        double travel = passed ? 1.0 : enter;
        double activeScale = word.baseScale * ACTIVE_SCALE;
        double drawX = centerX + FullscreenLyricStage.lerp(waitingX, word.x, travel);
        double drawY = centerY + FullscreenLyricStage.lerp(waitingY, word.y, travel);
        double scale = FullscreenLyricStage.lerp(.5, activeScale, travel);
        double rotate = FullscreenLyricStage.lerp(word.rotate + 20.0, word.rotate, travel);

        if (passed) {
            scale = FullscreenLyricStage.lerp(scale, word.baseScale, settle);
            rotate += word.passedRotate * rotateSettle;
        }

        if (outgoing) drawY -= FullscreenLyricStage.easeInCubic(
                FullscreenLyricStage.clamp01(exitProgress)) * fontPx * .72;

        double bodyAlpha = reveal * (passed ? FullscreenLyricStage.lerp(1.0, .82, settle) : 1.0);
        if (bodyAlpha <= .008) return;

        int baseColor = FullscreenLyricStage.primaryText() & 0xFFFFFF;
        int accent = FullscreenLyricStage.accent() & 0xFFFFFF;
        double litMix = passed ? 1.0 - settle : 1.0;
        int color = FullscreenLyricStage.mixRgb(baseColor, accent, litMix) & 0xFFFFFF;

        double pivotX = drawX + word.slotWidth * .5;
        double pivotY = drawY + word.height * .5;
        double startX = pivotX - font.getStringWidthD(word.text) * .5;
        double startY = pivotY - font.getFontHeight() * .5;

        FullscreenLyricStage.pushMatrix();
        try {
            if (Math.abs(rotate) > .05 || travel < 1.0) {
                FullscreenLyricStage.rotateAt(pivotX, pivotY, rotate);
            }
            double total = scale * fontScale;
            if (Math.abs(total - 1.0) > 1.0e-4) {
                FullscreenLyricStage.scaleAt(pivotX, pivotY, total);
            }

            if (active && word.durationMs() <= 850.0 && beat > .42) {
                double t = FullscreenLyricStage.clamp01(
                        (positionMs - word.startMs) / (RIPPLE_SECONDS * 1000.0));
                if (t < 1.0) {
                    double radius = font.getFontHeight()
                            * (.75 + word.ripple * 1.25) * FullscreenLyricStage.easeOutCubic(t);
                    FullscreenLyricStage.dot(pivotX, pivotY, radius, accent,
                            .11 * (1.0 - t) * beat * alpha);
                }
            }

            drawGlowLayer(word, startX, startY, positionMs, bodyAlpha, accent);
            font.drawString(word.text, startX, startY,
                    FullscreenLyricStage.withAlpha(color, bodyAlpha));
        } finally {
            FullscreenLyricStage.popMatrix();
        }
    }

    /**
     * 参考实现的 glow layer：文字本体保持可读，只有逐字文字阴影沿演唱顺序亮起。
     */
    private void drawGlowLayer(Word word, double x, double y, double positionMs,
                               double alpha, int accent) {
        if (font == null || positionMs < word.startMs || positionMs > word.endMs) return;
        double duration = word.durationMs();
        int count = Math.max(1, word.chars.size());
        double cursor = 0.0;

        // 整词的基础辉光，避免只有正在唱的那一个字有存在感。
        FullscreenLyricStage.drawGlow(font, word.text, x, y, accent,
                alpha * .22, .65 + .35 * beat);

        for (int i = 0; i < word.chars.size(); i++) {
            String glyph = word.chars.get(i);
            double glyphWidth = font.getStringWidthD(glyph);
            double charStart = word.startMs + duration * i / (double) count;
            double charDuration = Math.max(40.0, duration / (double) count);
            double p = FullscreenLyricStage.clamp01(
                    (positionMs - charStart) / Math.max(40.0, charDuration * 4.0));
            double pulse = Math.sin(Math.PI * p);
            if (pulse > .025) {
                FullscreenLyricStage.drawGlow(font, glyph, x + cursor, y, accent,
                        alpha * pulse * (.62 + .38 * beat), pulse);
            }
            cursor += glyphWidth;
        }
    }
}
