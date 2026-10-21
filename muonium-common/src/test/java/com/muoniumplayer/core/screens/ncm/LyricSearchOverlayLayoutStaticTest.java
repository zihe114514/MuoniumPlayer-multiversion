package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 歌词搜索弹窗的四条不变量：换轴、倍率接线、逐帧现取尺寸、封面取流。
 *
 * <p>这一页的排版数值是照 folia 的 CSS px 抄来的，于是它必须画在「1 单位 = 1 CSS px」那一档；
 * 而它又是播放器面板的子层，天然继承的是布局轴（那条轴是 CSS 的一半）。第一次真机就栽在这对矛盾
 * 上：弹窗从占屏 53% 变成占屏 94%，接近正方形被压成横幅。</p>
 *
 * <p>第二次真机栽在换成设计空间的<b>代价</b>上：字小了一半。原因不在几何，而在字体的量纲 ——
 * {@code FontManager.createText(N, …)} 造 {@code CFontRenderer} 时把 {@code N * 0.5f} 交给 Skia
 * 当 {@code sizePx}（旧的「2 倍栅格化 + 0.5 倍绘制」没了，构造函数里那个 0.5 留了下来），所以
 * {@code pf14} 实际画的是 7 格、{@code pf18} 是 9 格。folia 的 14 CSS px 落在浏览器里是 14 物理
 * 像素，落在播放器界面上只等于名字里那个数字的一半。</p>
 *
 * <p>第三轮真机反馈「拉到最大那一档还是小」，把这件事定性成了排版问题而不是尺寸取舍：一档用户
 * 旋钮救不回来一整页的字。于是这一页的<b>字号档位</b>整体脱离 folia 原值，抬到正文 pf18 / 次级
 * pf16 / 标题 pf20 / 徽标 pf12。按离屏量出的行盒折算，默认 1.5× 倍率下正文占 9.75 设计格
 * ≈ 7.3 个布局单位，与播放器正文 pf14 的 6.75 布局单位同一量级；装饰性的纵向高度一起压下来，
 * 换回候选卡的行数。</p>
 *
 * <p>最近一轮真机反馈「按钮里的字不居中、逐字/逐行标签的字不居中」，根因就是上一段那个数字：居中
 * 原先写成 {@code (行盒 - 18) * .5}，减掉的是<b>渲染器名字里的 18</b>，而字真正只占 9 格，于是
 * 每行字都被往上推了约 4.5 格 —— 30 高的页脚键里墨迹中心落在 10.7，键的中心是 15。现在减数只许
 * 是字体自己报的行盒（{@code CFontRenderer#getFontHeight()}），也就是 {@code textY(…)} 这一个口子；
 * 输入框 {@code TextField#getRenderOffsetY()} 本来用的就是同一个量，这一轮两处在同一个约定上对上了。
 * 钉在这里的是这四件事：<b>字号只许走那四档具名渲染器</b>、<b>不许再有字号常量</b>、
 * <b>文字居中只许走 {@code textY}</b>、<b>装饰高度跟着字号收</b>。
 * 见 {@link #fontRampIsAtPlayerSizeAndChromeShrank()}。</p>
 *
 * <p>第二条同样来自真机：组件树只在行数变化时搭一次，搭建时把宽高抄成数值快照，而新 {@code new}
 * 出来的裁剪容器还没被自己的回调定过尺寸 —— 量回来是 0，被 {@code Math.max(1, …)} 夹成 1，整块
 * 候选列表就只剩一条缝。</p>
 *
 * <p>第四条是封面那条取流链：它必须自己发请求头、按地址去重、失败有限次重试，而不是走那条
 * 「一个头都不发、一次失败就永远停在空贴上」的下载捷径。（记一笔：我最初把真机上「只有 QQ 那一页
 * 整列没封面」归因成图床挡 {@code Java/17} 这个 UA，实测推翻了 —— 同一个地址带不带 UA、用
 * {@code curl} 还是浏览器 UA 都是 200 image/jpeg，照旧路径的设置复现也解得出 300×300。所以这一条
 * 是加固加二分用的，真机那半边仍未定论。）</p>
 *
 * <p>这四件事全是渲染期行为，单元测不到（构造一个弹窗要 GL 与整条 MC 静态链），所以按本仓库的老
 * 办法钉源码：{@code UpdateCheckStaticTest} 同样是「断言源文件里不许出现某种写法」。</p>
 */
class LyricSearchOverlayLayoutStaticTest {

    private static final Path SHARED_JAVA = ProjectPaths.sharedJava();
    private static final Path SCREEN = source("NCMScreen.java");
    private static final Path OVERLAY = source("LyricSearchOverlay.java");

    /** 这一页允许出现的字号：加粗那一套。见 {@link #fontRampIsAtPlayerSizeAndChromeShrank()}。 */
    private static final Set<String> ALLOWED_BOLD =
            new HashSet<String>(Arrays.asList("12", "16", "18", "20"));
    /** 这一页允许出现的字号：常规那一套。 */
    private static final Set<String> ALLOWED_PLAIN =
            new HashSet<String>(Arrays.asList("16", "18"));

    @Test
    @DisplayName("设计空间永远是播放器布局轴的 2 倍")
    void designSpaceIsExactlyDoubleTheLayoutAxis() throws IOException {
        String scale = read(source("WindowAdaptiveScale.java"));
        assertTrue(scale.contains("public static final double DESIGN_PER_LAYOUT = 2.0;"),
                "换轴倍率必须只有一份常量，散在各处的 .5 迟早和 designWidth() 对不上");
        assertTrue(scale.contains("RenderSystem.getWidth() * DESIGN_PER_LAYOUT"),
                "designWidth() 必须由这条常量算出来");
        assertTrue(scale.contains("RenderSystem.getHeight() * DESIGN_PER_LAYOUT"),
                "designHeight() 同理");
    }

    @Test
    @DisplayName("弹窗画在可缩放的 CSS px 那一档，边界与鼠标一起换轴")
    void overlayRendersInDesignSpace() throws IOException {
        String block = overlayRenderBlock();
        assertTrue(block.contains("final double unit = WindowAdaptiveScale.lyricSearchUnitsPerLayout();"),
                "这一层的单位必须由用户那一档算出来；写死 DESIGN_PER_LAYOUT 就是「字太小」那一单的真凶");
        assertTrue(block.contains("api.getGLStateManager().scale(1.0 / unit, 1.0 / unit, 1)"),
                "这一层必须绕原点换轴，否则照抄的 CSS px 数值整体大一倍");
        assertTrue(block.contains("basePanel.getWidth() * unit")
                        && block.contains("basePanel.getHeight() * unit"),
                "边界要乘回那一档单位，不然弹窗只画到面板左上角一块");
        assertTrue(block.contains("renderWidget(mouseX * unit, mouseY * unit, dWheel)"),
                "绘制换了轴、命中没换，就是看得见点不着");
    }

    @Test
    @DisplayName("缩放倍率四步接线齐全：存、读、枚举、滑杆")
    void uiScaleKnobIsFullyWired() throws IOException {
        // 这一档只管三处「必须同时存在」：能存却不读（滑杆改了没反应）、读了却不存（下次开游戏又
        // 回去）、存读都有但界面上没有那一根滑杆（用户无从调）。少任何一处都是静默失效。
        String config = read(coreSource("settings", "HudConfig.java"));
        assertTrue(config.contains("public static float lyricSearchUiScale = 1.50f;"),
                "字段要在，默认 1.5×");
        assertTrue(config.contains("getFloat(o, \"lyricSearchUiScale\", lyricSearchUiScale)"),
                "读档要夹着读，不能直接信文件里的数");
        assertTrue(config.contains("addProperty(\"lyricSearchUiScale\""), "存档要写回同一个键");

        String scale = read(source("WindowAdaptiveScale.java"));
        assertTrue(scale.contains("DESIGN_PER_LAYOUT / clampUiScale(HudConfig.lyricSearchUiScale)"),
                "读数处必须由这两个量算出来：倍率是「除」而不是「乘」，写反了字只会更小");

        String setting = read(coreSource("settings", "HudSetting.java"));
        assertTrue(setting.contains("case LYRIC_SEARCH_UI_SCALE: return HudConfig.lyricSearchUiScale;"),
                "枚举取值那一支要在");
        assertTrue(setting.contains("case LYRIC_SEARCH_UI_SCALE: HudConfig.lyricSearchUiScale = clamped;"),
                "枚举写值那一支要在");

        String model = read(coreSource("screens", "hud", "HudSettingsModel.java"));
        assertTrue(model.contains("slider(HudSetting.LYRIC_SEARCH_UI_SCALE,"),
                "界面上要有那一根滑杆，不然这一档等于不存在");
    }

    @Test
    @DisplayName("封面按地址去重、带请求头、失败有限次重试")
    void coverFetchRetriesWithHeaders() throws IOException {
        String overlay = read(OVERLAY);
        // 走那条下载捷径等于一个字段的头都不发、且一次失败就再不回头。注意别把它当成 QQ 那页
        // 没封面的原因 —— 「图床挡 Java UA」这个猜测已经被实测推翻了，见类注释第四条。
        assertFalse(overlay.contains("Textures.downloadTextureAndLoadAsync("),
                "封面不许走那条「不发请求头、失败只打一条栈」的下载捷径（注释里提一句可以，调用不行）");
        assertTrue(overlay.contains("headers.put(\"User-Agent\", COVER_USER_AGENT)"),
                "取封面要带浏览器 UA");
        assertTrue(overlay.contains("\"image/png, image/jpeg, image/*;q=0.5\""),
                "Accept 只能声明 ImageIO 解得动的类型，报上 webp 只会换回一份解不了的字节");
        assertTrue(overlay.contains("attempts >= COVER_MAX_ATTEMPTS"),
                "要重试，但次数必须有顶");
        assertTrue(overlay.contains("inFlight || now < nextTryAt"),
                "重试要有冷却：这一档在渲染路径里，每帧问一次就是每帧一条 HTTP");
        assertTrue(overlay.contains("if (loaded) return;"),
                "到手之后必须彻底停手，不能每帧再查一次贴图表");
        // 卡片封面那个供应器必须每帧现问 —— 捕获成一次调用就把重试的门关上了。
        assertTrue(overlay.contains("new RoundedImageWidget(() -> coverLocation(hit.coverUrl),"),
                "候选卡片的封面供应器要逐帧推动取流状态");
    }

    @Test
    @DisplayName("裁剪在换轴之外录，命中坐标与绘制同一套")
    void clipStaysOutsideTheDesignMatrix() throws IOException {
        String block = overlayRenderBlock();
        assertTrue(block.indexOf("StencilClipManager.beginClip") < block.indexOf("pushMatrix()"),
                "遮罩裁剪录的是屏幕坐标，必须留在设计空间之外（与播放器其余各层同一条规矩）");
        int push = block.indexOf("pushMatrix()");
        int pop = block.indexOf("popMatrix()");
        assertTrue(push >= 0 && pop > push, "pop 必须在 push 之后，且两者都落在这一段之内");

        assertTrue(read(SCREEN).contains(
                        "this.lyricSearchOverlay.onMouseClickReceived(mouseX * unit, mouseY * unit, mouseButton)"),
                "点击链路也要换轴，和 drawScreen 用同一个倍率");
    }

    @Test
    @DisplayName("排版不许在搭建组件树时抄尺寸快照")
    void geometryIsReadLiveNotAtBuildTime() throws IOException {
        String overlay = read(OVERLAY);
        // 这些方法每帧从弹窗现取宽高，所以签名里不该再出现「搭建时抄来的数值」。
        Pattern signature = Pattern.compile(
                "private (?:void|Panel|double) (leftColumn|rightColumn|footer|tabRow|searchRow"
                        + "|listArea|candidateCard|emptyState|infoStrip)\\(([^)]*)\\)");
        Matcher matcher = signature.matcher(overlay);
        int seen = 0;
        while (matcher.find()) {
            seen++;
            assertFalse(matcher.group(2).contains("width") && matcher.group(2).contains("double"),
                    matcher.group(1) + " 还在搭建时抄宽度：" + matcher.group(2));
            assertFalse(matcher.group(2).contains("height") && matcher.group(2).contains("double"),
                    matcher.group(1) + " 还在搭建时抄高度：" + matcher.group(2));
            assertFalse(matcher.group(2).contains("available"),
                    matcher.group(1) + " 的可用高是别处算好的快照：" + matcher.group(2));
        }
        assertEquals(9, seen, "排版方法少了一部分，测试锚点要跟着改");

        // clip() 是「新建即尺寸为 0」的那类容器：它的 x 与宽高必须由供应器逐帧给。
        assertTrue(overlay.contains("private Panel clip(final AbstractWidget<?> host, final DoubleSupplier x, final double y,\n"
                        + "                       final DoubleSupplier width, final DoubleSupplier height)"),
                "clip() 的 x / 宽 / 高必须是供应器");
        // clip() 容器新 new 出来时边界是 0，只有它自己的回调会给它定型；所以量它必须发生在回调里，
        // 而不是发生在 rebuild 里 —— 真机上「候选列表只剩一条缝」就是这一条没守住。
        assertTrue(overlay.contains("panel.setBeforeRenderCallback("),
                "clip() 容器必须由自己的回调逐帧定边界");
        int measured = overlay.indexOf("list.setBounds(Math.max(1, viewport.getWidth())");
        assertTrue(measured > overlay.indexOf("list.setBeforeRenderCallback("),
                "候选列表的宽高要在回调里现量 clip 容器，写在 rebuild 里就是那条缝");
        assertTrue(overlay.contains("private double listW()") && overlay.contains("private double listH()"),
                "逐帧现取的候选区尺寸要有自己的入口");
        assertFalse(overlay.contains("column.getHeight() - cursor"),
                "可用高不能从刚建好的栏里量");
    }

    @Test
    @DisplayName("弹窗外壳按 folia 的视口规则夹，而不是按面板夹")
    void dialogUsesFoliaViewportRules() throws IOException {
        String overlay = read(OVERLAY);
        assertTrue(overlay.contains("private static final double DIALOG_MAX_W = 1024.0;"),
                "max-w-5xl");
        assertTrue(overlay.contains("private static final double DIALOG_MAX_RATIO = .85;"),
                "max-h-[85vh]");
        // 视口 = 这一层的边界，而边界已经是设计单位（见 overlayRendersInDesignSpace）。
        // 地板 240：手机比例视口下宿主可能只有 300 多宽，硬地板比宿主还宽就是关闭键出屏。
        assertTrue(overlay.contains("return Math.min(DIALOG_MAX_W, Math.max(240.0, getWidth() - BACKDROP_PAD * 2));"),
                "弹窗宽必须由自己的边界算，不能再去读 RenderSystem");
        assertFalse(overlay.contains("RenderSystem."), "这一层不碰全局分辨率：轴由宿主决定");
    }

    @Test
    @DisplayName("字号只有四档具名渲染器，文字居中只许走 textY")
    void fontRampIsAtPlayerSizeAndChromeShrank() throws IOException {
        String overlay = read(OVERLAY);

        // 行盒是一份常量：卡片、页眉、徽标那些「一条带多高」全从这里加起来。
        assertTrue(overlay.contains("LH_TITLE = 24.0, LH_BODY = 22.0, LH_SUB = 20.0, LH_BADGE = 18.0;"),
                "行盒同样要成一份常量");

        // 字号不再有常量。上一轮的 FS_* 就是「(行盒 - FS_BODY) * .5」这种写法的入口，而 FS_BODY 是
        // 渲染器名字里的 18、字实际只占 9 格 —— 减错数就是真机那句「按钮里的字贴着上沿」。
        assertFalse(overlay.contains("FS_"), "字号常量会诱导出「拿名字当高度」的居中写法，居中只许走 textY");
        assertEquals(0, count(overlay, "- FS"), "还有一处拿字号常量给文字居中");

        // 整页只许用这四档字形：播放器正文 pf14=6.75 布局单位，默认倍率下 pf18≈7.3 才是同一量级。
        Matcher boldFonts = Pattern.compile("FontManager\\.pf([0-9]+)bold").matcher(overlay);
        int bold = 0;
        while (boldFonts.find()) {
            bold++;
            assertTrue(ALLOWED_BOLD.contains(boldFonts.group(1)),
                    "这一页用了不在档位里的加粗字号 " + boldFonts.group(1) + "，档位只有 12/16/18/20");
        }
        Matcher plainFonts = Pattern.compile("FontManager\\.pf([0-9]+)(?![0-9])(?!bold)").matcher(overlay);
        int plain = 0;
        while (plainFonts.find()) {
            plain++;
            assertTrue(ALLOWED_PLAIN.contains(plainFonts.group(1)),
                    "这一页用了不在档位里的字号 " + plainFonts.group(1) + "，档位只有 16/18");
        }
        assertTrue(bold > 0 && plain > 0, "锚点失效：这一页一个字都没画？");

        // 文字居中的唯一入口，且减数必须是字体自己报的行盒。
        assertTrue(overlay.contains(
                        "private static double textY(CFontRenderer font, double rowTop, double rowHeight)"),
                "文字居中要有唯一入口 textY(字体, 带上沿, 带高)");
        assertTrue(overlay.contains("private static double textY(LabelWidget label"),
                "挂在标签上的字也要走同一个入口，别在回调里各算各的");
        assertTrue(methodBody(overlay, "private static double textY(CFontRenderer font")
                        .contains("font.getFontHeight()"),
                "减数必须是实际行盒；字体没解析出来时退回带上沿，不抛");

        // 真机点出来的那几处：页脚两颗键、搜索键、两处逐字/逐行角标。
        assertTrue(overlay.contains("textY(caption, 0, BUTTON_H)"), "页脚按钮里的字（取消 / 保存）");
        assertTrue(overlay.contains("textY(caption, 0, SEARCH_H)"), "搜索键里的字");
        assertTrue(overlay.contains("textY(badgeText, badge.getRelativeY(), LH_BADGE)"), "候选卡上的角标");
        assertTrue(overlay.contains("textY(font, top, LH_BADGE)"), "右栏信息区的逐字 / 逐行徽标");
        assertTrue(count(overlay, "textY(") >= 16,
                "这一页有十几处文字居中，textY 的调用数掉下来了：又有字自己算对齐了？");

        // 居中的一律不许再用裸数字：那正是「改了字号却忘了改对齐」的那扇门。
        Matcher stray = Pattern.compile("\\([A-Z][A-Z_0-9]* - [0-9]+(?:\\.[0-9]+)?\\)").matcher(overlay);
        assertFalse(stray.find(), "还有写法拿裸数字给文字居中，行盒与字号会对不上");

        // 装饰高度跟着字号一起压：页眉页脚的纵向留白、栏内边距与栏间距。
        assertTrue(overlay.contains("PAD_X = 16.0, PY_FOOTER = 12.0;"), "页眉页脚的 py-5 得收成 12");
        assertTrue(overlay.contains("COL_PAD = 16.0, COL_GAP = 14.0;"), "栏内边距与间距得收成 16/14");
        assertTrue(overlay.contains("HEADER_H = PY_FOOTER + LH_TITLE + HEAD_GAP + LH_BODY + PY_FOOTER + 1;"),
                "页眉高必须由行盒加起来，不能是照抄 folia 的那串裸数字");
        assertTrue(overlay.contains("FOOTER_H = 1 + PY_FOOTER + BUTTON_H + PY_FOOTER;"), "页脚同理");
        // 腾出来的地方给候选卡：这两格纯装饰，folia 的 160/128 在这一页只是把列表挤没。
        assertTrue(overlay.contains("PLACEHOLDER_H = 120.0;"), "空态那一格还得是压缩后的 120");
        assertTrue(overlay.contains("RIGHT_COVER = 96.0"), "右栏封面还得是压缩后的 96");

        // 每开一次都留一行量纲日志：下次真机说「小」，先看这行再改代码。日志里报的必须是行盒，
        // 不是字号名字 —— 上一版在这里写了「正文 18 ≈ 27 物理像素」，量纲整个是错的，白看两行。
        assertTrue(overlay.contains("logAxisOnce(width, shownHeight);"),
                "弹窗定型那一帧要把量纲打出来");
        assertTrue(overlay.contains("WindowAdaptiveScale.lyricSearchUnitsPerLayout()"),
                "日志里的单位必须和宿主换轴用的是同一个入口");
        assertTrue(overlay.contains("FontManager.pf18.getFontHeight() / unit"),
                "日志要报「行盒折算成布局单位」，才能和播放器正文那 6.75 直接比");
    }

    @Test
    @DisplayName("打开一次只报一行性能，封面取流有并发上限，热路径不再逐帧重算")
    void perfProbeCoversAndHotPathsAreBounded() throws IOException {
        String overlay = read(OVERLAY);

        // 一次打开攒一行：真机说「卡一下」，先看这行四个数哪个顶起来，别再来一轮盲改。
        assertTrue(overlay.contains("\"[Music/Perf] 歌词搜索页 打开一次:"),
                "打开这一页要留一行分段耗时（打开首帧 / 检索 / 建树 / 逐帧绘制 + 贴图）");
        assertTrue(overlay.contains("samplePerfFrame();"), "逐帧采样要真的接进渲染收尾");
        assertTrue(overlay.indexOf("protected void afterRenderChildren") > 0
                        && overlay.indexOf("samplePerfFrame();") > overlay.indexOf("protected void afterRenderChildren"),
                "本层绘制耗时必须在子组件画完之后才收，量到的才是整层");
        assertTrue(overlay.contains("perfWindowLeft = PERF_WINDOW_FRAMES;"),
                "窗口从结果落地之后才开始数：那一帧之后才是「冻」最可能出现的地方");
        assertTrue(overlay.contains("if (perfReported) return;"),
                "每次打开只许报一行，报完就不再量");

        // 封面并发有上限，且名额是在「算作一趟」之前要的。
        assertTrue(overlay.contains("COVER_MAX_IN_FLIGHT = 3;"),
                "同时在途的封面请求要有顶：一屏五六张候选一起发，就是五六次主线程贴图挤在两帧里");
        int acquire = overlay.indexOf("if (!tryAcquireCoverSlot()) return;");
        assertTrue(acquire > 0 && overlay.indexOf("attempts++;") > acquire,
                "没真发出去的趟数不许记进 attempts：名额排在计数之前");
        assertEquals(2, count(overlay, "releaseCoverSlot();"),
                "收尾还名额一处，线程池拒收还名额一处");

        // 每帧、每个标签都会走的两个热路径：折叠与行高，都不许再逐帧重新分配。
        String font = read(coreSource("rendering", "font", "CFontRenderer.java"));
        assertTrue(font.contains("private String trimLinear(String name, double width)"),
                "折叠要逐字累加，不能一步一个 substring");
        assertTrue(font.contains("? trimLinear(name, width)")
                        && font.contains(": trimByBackscan(name, width)"),
                "带控制码或换行的文本才退回老路径");
        assertFalse(font.contains("TRUNCATE_WITH_CODES"),
                "这条判定每帧每个标签跑一次，不许用正则");
        assertFalse(methodBody(font, "public double getStringHeight").contains("split("),
                "行高不许每帧做一次正则 split");

        // 贴图上传的耗时只在主线程那一段统计，工作线程排队那段不算。
        String textures = read(coreSource("rendering", "texture", "Textures.java"));
        int reschedule = textures.indexOf("if (!MuoniumPlayerExtension.isCallingFromMainThread())");
        int timing = textures.indexOf("perfUploadNanos +=");
        assertTrue(reschedule > 0 && timing > reschedule,
                "计时要落在真正上传的那一段，而不是排回主线程之前");
    }

    /** {@code marker} 起、到它自己那条单独的 {@code }} 为止。 */
    private static String methodBody(String haystack, String marker) {
        int start = haystack.indexOf(marker);
        assertTrue(start >= 0, "找不到 " + marker);
        int end = haystack.indexOf("\n    }", start);
        assertTrue(end > start, marker + " 这一段没找到收尾");
        return haystack.substring(start, end);
    }

    private static int count(String haystack, String needle) {
        int total = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            total++;
        }
        return total;
    }

    // ──────────────────────────── 取段小工具 ────────────────────────────

    /** {@code drawScreen} 里渲染弹窗的那一段（含它自己的 push/pop）。 */
    private static String overlayRenderBlock() throws IOException {
        String screen = read(SCREEN);
        int draw = screen.indexOf("public void drawScreen(int mX, int mY)");
        int click = screen.indexOf("public void mouseClicked(int mX, int mY, int mouseButton)");
        assertTrue(draw >= 0 && click > draw, "测试锚点失效：NCMScreen 的两个入口位置变了");
        return segment(screen.substring(draw, click), "if (this.lyricSearchOverlay != null) {");
    }

    /** 从 {@code marker} 起，到它自己那条 {@code StencilClipManager.endClip();} 为止。 */
    private static String segment(String haystack, String marker) {
        int start = haystack.indexOf(marker);
        assertTrue(start >= 0, "找不到 " + marker);
        int end = haystack.indexOf("StencilClipManager.endClip();", start);
        assertTrue(end > start, marker + " 这一段没有收尾的 endClip");
        return haystack.substring(start, end);
    }

    private static Path source(String fileName) {
        return ProjectPaths.sourceFile(Paths.get("com", "muoniumplayer", "core", "screens", "ncm", fileName));
    }

    /** 弹窗那一层之外、同在 {@code core} 下的接线文件（配置、枚举、设置面板）。 */
    private static Path coreSource(String... parts) {
        Path relative = Paths.get("com", "muoniumplayer", "core");
        for (String part : parts) {
            relative = relative.resolve(part);
        }
        return ProjectPaths.sourceFile(relative);
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
