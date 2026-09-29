package com.muoniumplayer.core.screens.hud;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static com.muoniumplayer.testsupport.ProjectPaths.sharedJava;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 现代 HUD 设置界面（{@link GuiHudEditorPro}）的动效契约。
 *
 * <p>这一页以前是「帧率相关的指数逼近 + 干脆没有」的混合体：换分类是硬切，退场是直接消失，
 * 而悬停与滚动那条 {@code Interpolations.interpolate} 既没有时长概念，也完全不理玩家的
 * 「界面切换动画速度」那一档。下面钉的都是这类改动最容易悄悄退回去的地方：</p>
 * <ul>
 *   <li>时长一律过倍率 —— 少除一次，症状是「把动画速度拉满，只有这一页还在按自己的秒数走」；</li>
 *   <li>错峰窗口开在<b>原始</b> t 上、右端钉在 1.0 以内 —— 越界的那一块会永久停在半透明，
 *       而它只在长列表底部出现，日常滚动看不见；</li>
 *   <li>进场有，退场也得有，且退场播完才回收界面 —— 早一帧回收就是「啪一下消失」；</li>
 *   <li>动画只走矩阵与透明度，不改命中矩形 —— 这是这个类顶上第三条约束，也是它和经典界面
 *       「画一套坐标、点击再抄一套」那处老毛病的分界线。</li>
 * </ul>
 */
class HudEditorMotionStaticTest {

    private static final Path PANEL = sharedJava().resolve(Paths.get(
            "com", "muoniumplayer", "core", "screens", "hud", "GuiHudEditorPro.java"));

    private static String read() throws IOException {
        return new String(Files.readAllBytes(PANEL), StandardCharsets.UTF_8);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int at = haystack.indexOf(needle); at >= 0;
                at = haystack.indexOf(needle, at + needle.length())) {
            n++;
        }
        return n;
    }

    @Test
    @DisplayName("每一段时长都过「界面切换动画速度」那一档")
    void everyDurationPassesTheSpeedMultiplier() throws IOException {
        String body = read();
        assertTrue(body.contains("durationMs / SheetClock.speed()"),
                "定长补间没有除以倍率：这一页会脱离玩家唯一能调的那个动画旋钮");
        assertFalse(body.contains("float introProgress"), "入场又回到了那个只会单调逼近 1 的帧耦合进度");
        assertFalse(body.contains("float contentFade"), "切分类又回到了帧耦合的整块淡入");
        // 只剩滚动惯性这一条指数通道：它按定义没有「多长」，播放器里的滚动容器也是同一做法。
        assertEquals(1, count(body, "Interpolations.interpolate("),
                "除了滚动惯性，这一页不该再有第二条帧耦合通道");
    }

    @Test
    @DisplayName("进场与退场共用一条可逆的 t，各层在它上面开窗")
    void entranceAndExitShareOneReversibleClock() throws IOException {
        String body = read();
        assertTrue(body.contains("pageFade = page.tick()") && body.contains("listFade = list.tick()"),
                "两条时钟没有每帧推进，或者错峰窗口拿不到原始 t —— 开在缓动后的进度上，"
                        + "越靠后的块走得越慢，就不是等间距瀑布了");
        assertTrue(body.contains("page.requestDismiss()") && body.contains("list.requestDismiss()"),
                "关闭没有走退场：进场有动画、退场没有，是这类界面最常见的失衡");
        assertTrue(body.contains("if (page.isGone())"), "退场播完之前就把界面回收掉了");
        // 26.x 那两棵写的是 mc.gui.setScreen(...)，所以这里只数到 setScreen 那一段。
        assertEquals(1, count(body, "setScreen(null);"),
                "整份文件只该有退场末尾那一处回收界面；ESC 那里再出现一处就是绕过了退场");
    }

    @Test
    @DisplayName("退场途中这一层不再接受输入")
    void dismissingLayerStopsTakingInput() throws IOException {
        String body = read();
        assertTrue(body.contains("if (page.isDismissing()) return;"),
                "退场途中还在吃键盘或鼠标：会在已经淡出去的界面上改设置");
        assertTrue(body.contains("boolean retiring = page.isDismissing()"),
                "绘制路径没有退场标志：拖动、滚轮、滑块还会跟着动");
        assertTrue(body.contains("if (retiring || resetConfirmationVisible) wheel = 0;"),
                "滚轮要在这一帧读出来再丢掉。放着不读，事件会攒到下一帧、落到关掉之后的那一层上");
    }

    @Test
    @DisplayName("内容一块一块落位，卡与它自己的行同一档到场")
    void contentRevealsBlockByBlock() throws IOException {
        String body = read();
        assertEquals(2, count(body, "float reveal = revealOf("),
                "卡与行不再各自按序号取窗口（一处都没有、或者被合并成一处）");
        assertTrue(body.contains("revealOf(index)") && body.contains("revealOf(row.slot)"),
                "卡和它的行没有共用序号：会出现「字已经到了、底下的板还在路上」");
        assertTrue(body.contains("int slot = cards.size();"),
                "分组没有记下自己落在第几张卡上");
        assertTrue(body.contains("Math.min(first + ROW_TRAVEL, 1.0)")
                && body.contains("Math.min(first + NAV_TRAVEL, 1.0)"),
                "错峰窗口右端没有钉在 1.0 以内：排在后面的块会永久停在半透明、偏几个像素的位置");
        assertTrue(body.contains("Math.min(slot, ROW_STAGGER_LIMIT)")
                && body.contains("Math.min(index, NAV_STAGGER_LIMIT)"),
                "序号没有并档：长列表底部的卡永远排在窗口外面");
    }

    @Test
    @DisplayName("换分类有方向，首次到场不偏")
    void categorySwitchCarriesADirection() throws IOException {
        String body = read();
        assertTrue(body.contains("Integer.compare(owner, contentOwner)"),
                "换分类不再区分前进/后退，滑入方向就没了");
        assertTrue(body.contains("contentOwner == Integer.MIN_VALUE ? 0"),
                "第一次到场也按「上一个」算方向：进场那一刻内容会莫名偏一边");
        assertTrue(body.contains("listDirection * (1f - gate) * CONTENT_SLIDE"),
                "横向偏移没有乘方向：新的从哪一侧来都一样了");
    }

    @Test
    @DisplayName("动画只改视觉，不改命中矩形")
    void motionNeverMovesTheHitRects() throws IOException {
        String body = read();
        assertTrue(body.contains("private void layoutRows(int contentX, int listY, int contentW, "
                + "HudSettingsModel.Category active)"), "布局那一路开始认识动画量了");
        assertTrue(body.contains("SkijaGfx.translate(slideX(reveal), slideY(reveal))"),
                "内容的进场偏移不再走矩阵 —— 直接加到坐标上就会和命中矩形错开");
        assertTrue(body.contains("drawRow(fr, row, mouseX - Math.round(dx), mouseY - Math.round(dy))"),
                "鼠标没有跟着视觉补偿：滑进来的那几十毫秒里悬停高亮落在别处");
        assertFalse(body.contains("row.y + slide"), "行矩形的纵向被动画改写了");
    }

    @Test
    @DisplayName("侧栏高亮条走橡皮筋，开关旋钮停在它本来的状态上")
    void indicatorSpringsAndSwitchesStartSettled() throws IOException {
        String body = read();
        assertTrue(body.contains("INDICATOR_MS, TransitionCurveCache.SPRING_LAND)"),
                "高亮条回到了「漂过去停下」：和播放器侧栏那条不再是同一档手感");
        assertTrue(body.contains("Math.max(navY + 6f, Math.min(navY + navH - rowH + 2f, navIndicator))"),
                "过冲没有钳在侧栏里：选中最后一行时条子会弹到分隔线底下的页脚上");
        assertTrue(body.contains("= entry.isOn() ? 1f : 0f"),
                "开关通道没有落在当前状态：每次打开界面都会看到所有已开启的开关一起从左滑到右");
    }

    @Test
    @DisplayName("凭空出现的那两层（确认框、取色器）自己补到场动画")
    void overlaysPopIn() throws IOException {
        String body = read();
        assertTrue(body.contains("motion(\"dialog.in\"") && body.contains("motion(\"picker.in\""),
                "这两层的到场进度没有每帧推进：通道一创建就落在目标上，永远看不到那一段");
        assertTrue(body.contains("fade(0x6D000000, dialogGate)"),
                "确认框的压暗层走的是原版管线，吃不到 layerAlpha —— 不自己乘就是一片先黑、后亮");
    }

    @Test
    @DisplayName("分层淡入只从 layerAlpha 一处收口，画完必须还回去")
    void alphaIsStackedInOnePlace() throws IOException {
        String body = read();
        assertTrue(body.contains("private float pushAlpha(float gate)"),
                "分层淡入又开始各自传 alpha 了 —— 漏一处就是一个不跟着淡的破口");
        assertTrue(count(body, "layerAlpha = ") >= count(body, "pushAlpha("),
                "每压一层都要在 finally 里还回去：恢复的次数不该比压的次数少");
    }
}
