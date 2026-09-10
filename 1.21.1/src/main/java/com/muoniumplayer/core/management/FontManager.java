package com.muoniumplayer.core.management;

import lombok.SneakyThrows;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.font.FontKerning;

import java.awt.*;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * @author MuoniumPlayer Contributors
 * @since 4/8/2023 11:09 AM
 */
public class FontManager extends AbstractManager {

    /**
     * Java 8u51 的 Java2D CFF/OpenType 光栅化会让 SF Pro 的半角字形使用
     * 与实际绘制不一致的尺寸。中文由 PingFang TrueType 回退字体绘制，
     * 因而只有英文、数字和半角标点出现比例异常。
     *
     * 仅在受影响的早期 Java 8 运行时切换半角主字体到已内置的 Arial
     * TrueType；中文回退字体、UI 逻辑及其它字体系统均保持不变。
     */
    private static final boolean USE_LEGACY_JAVA8_TTF_FONTS = isLegacyJava8CffRuntime();

    public FontManager() {
        super("FontManager");
    }

    public static CFontRenderer pf10bold, pf12bold, pf14bold, pf16bold, pf18bold, pf20bold, pf25bold, pf28bold, pf34bold, pf40bold, pf65bold, pf50bold;
    public static CFontRenderer pf12, pf14, pf18, pf20, pf25, pf32;
    public static CFontRenderer icon30;
    public static CFontRenderer music14, music18, music40;
    /** Fontello icon font bundled with the player UI. */
    public static CFontRenderer fontello14, fontello16, fontello18, fontello22;
    /** 与 {@link #musicBrand52} 同一档视觉尺寸的 fontello 图标（在线聚合源卡片上的链条）。 */
    public static CFontRenderer fontello40;
    /** Provider-brand Fontello glyphs use a separate font to avoid private-use code collisions. */
    public static CFontRenderer musicBrand16, musicBrand18;
    /**
     * 音乐来源二级菜单卡片上的品牌标记。
     *
     * <p>这一档的字号是按「墨迹高度等于 18 逻辑像素」反算出来的，而不是和别处取同一个字号：三个
     * 图标字形在自己的 em 方格里占的比例差很多（网易云 0.69em、QQ 音乐 0.53em、fontello 的链条
     * 0.91em），统一字号会让它们看起来一大一小。反算后 create(52)/create(68)/create(40) 分别得到
     * 17.94 / 17.88 / 18.22 像素的墨迹，肉眼一致。</p>
     *
     * <p>刻意不靠绘制时缩放去放大小字号的渲染器：字形图集是 2 倍超采样 + 精确 0.5 倍缩小，任何
     * 额外的非整数倍率都会重采样发虚。代价是每个 CFontRenderer 各自持有一张 2048² 的 GL_ALPHA
     * 图集（约 4MB 显存），所以只加了这三档，其它地方要用大号品牌图标时请复用它们。</p>
     */
    public static CFontRenderer musicBrand52;
    /**
     * 「下一首播放」图标字体（player-queue-icons，fontello U+E309）的两档字号。
     *
     * <p>两档而不是一档：这枚图标出现在两种大小的按钮里（歌曲行 20 像素、播放条 18 像素），而
     * 字形是按字号栅格化的，同一个渲染器画进不同大小的按钮就会一大一小。{@code PlayerQueueIcons}
     * 会按按钮边长挑墨迹最接近目标比例的那一档，把两处的观感对齐到相邻贴图图标的水平。</p>
     */
    public static CFontRenderer queueIcon34, queueIcon38;
    /**
     * 播放器动作图标字体（player-action-icons，fontello U+E113 添加到歌单 / U+E144 下一首播放列表）的两档字号。
     *
     * <p>和 {@code queueIcon*} 同样的理由要两档：同一枚字形出现在 20 像素与 18 像素的按钮里。
     * 单独一份字体文件而不是并入 player-queue-icons：两份是不同批次的 fontello 导出，各自独立
     * 才不会在私有区码位上互相覆盖。</p>
     */
    public static CFontRenderer actionIcon34, actionIcon38;
    /** YouTube play-button brand mark; generated separately to avoid private-use code collisions. */
    public static CFontRenderer youtubeIcon24, youtubeIcon34;
    /** QQ Music brand icon font; deliberately separate from Fontello login glyphs. */
    public static CFontRenderer qqMusicIcon16, qqMusicIcon20;
    /** 与 {@link #musicBrand52} 配套：QQ 音乐的品牌字形在另一份字体里（music-brand-icons 没有 U+E888）。 */
    public static CFontRenderer qqMusicIcon68;
    /**
     * 灵动岛状态/结果图标字体（island-status-icons，fontello U+E901~E904 与 U+E803/E807/E808）。
     *
     * <p>再开一份字体而不是并进 {@code fontello}：这是另一批 fontello 导出，私用区码位和旧包
     * （U+E800~E802）会互相覆盖。两档字号分别给常驻状态栏的小图标（12）和左侧通知托盘（16）用，
     * 字形是按字号栅格化的，共用一档会在其中一处明显发虚。</p>
     */
    public static CFontRenderer islandIcon12, islandIcon16;
    /**
     * 灵动岛音量/仪表图标字体（island-volume-icons，重映射后的 fontello U+E902~U+E906）。
     *
     * <p>不能并进 {@link #islandIcon12}：这两批字体在 U+E902~U+E904 上码位撞车而字形完全不同
     * （对勾/链条/心跳 对 仪表盘/喇叭三档），合并任何一方都会被覆盖。</p>
     *
     * <p>字号按「落屏边长 × 2」选，和 {@link #settingsIcon24} 一条规矩：图集是 2 倍超采样、
     * 落屏时固定再乘 0.5，只有额外倍率恰好为 1 时每个图集像素才正对一个屏幕像素。常驻条目的
     * 图标方格是 9px（{@code 18}），通知托盘与常驻左侧标记是 12px（{@code 24}）。之前只注册
     * 12/16 两档、再在绘制时放大 1.4~1.9 倍，正是图标发虚的原因。</p>
     */
    public static CFontRenderer islandVolumeIcon18, islandVolumeIcon24;
    /** {@link #islandVolumeIcon18} 的名义字号；图标几何要按它换算 em 方格，不能写死。 */
    public static final int ISLAND_VOLUME_ICON_SMALL = 18;
    /** {@link #islandVolumeIcon24} 的名义字号。 */
    public static final int ISLAND_VOLUME_ICON_LARGE = 24;
    /**
     * HUD 设置界面的图标字体（hud-settings-icons，Material Design Icons round 一档，U+E200~E21F）。
     *
     * <p>三档字号对应 12 / 16 / 20 逻辑像素的图标方格：这份字体的每个字形都占满 1000 upem 的
     * em 方格（{@code HudSettingsIcons.LOCATOR} 就是用来量这个方格的），而 {@code create(N)} 对
     * TrueType 的 AWT 字号正好是 N、落到屏幕上是 N×0.5 像素，所以 24/32/40 直接读成 12/16/20。</p>
     *
     * <p>不做「取一档再缩放」：字形图集是 2 倍超采样 + 精确 0.5 倍缩小，任何额外的非整数倍率都会
     * 重采样发虚。代价是每档各自持有一张 2048² 的 GL_ALPHA 图集（约 4MB 显存），因此只开三档，
     * 新的图标位置请就近复用而不是再加字号。</p>
     */
    public static CFontRenderer settingsIcon24, settingsIcon32, settingsIcon40;

    /**
     * 一起听界面的图标字体（party-icons，Material Icons round 一档，U+E400~E430 与 U+E4FF）。
     *
     * <p>四档字号对应 12 / 16 / 20 / 28 逻辑像素的图标方格，规矩与 {@link #settingsIcon24} 完全
     * 相同（{@code create(N)} 的 em 方格落屏是 N×0.5 像素），行内小标记用 12，按钮与列表用 16，
     * 页签与状态用 20，空状态那枚大图标用 28。</p>
     *
     * <p>又是一份独立字体而不是并进 {@code hud-settings-icons}：两份都是 Material round 导出，
     * 但私用区码位各自从 U+E200 / U+E400 起，合并任何一方都要重排码位。另外这份刻意保留了
     * Material 24 网格里的原始 x 坐标（hud-settings 那份把字形推到了 x=0），所以窄字形按
     * {@code PartyIcons.FRAME} 居中时是真的居中，chevron 之类不会偏。</p>
     *
     * <p>不做「取一档再缩放」：字形按字号栅格化，额外的非整数倍率会让图标发虚。</p>
     */
    public static CFontRenderer partyIcon24, partyIcon32, partyIcon40, partyIcon56;

    /**
     * 可以被用户换掉字体的<b>正文</b>渲染器。
     *
     * <p>白名单式登记，不靠字段名前缀去猜：十几份图标字体也是 {@link CFontRenderer}，一旦被
     * 一起换掉，界面上所有图标会变成方块或者别的字形，而这种错误只有肉眼能发现。往
     * {@link #loadFonts()} 里新加一档正文字号时记得走 {@link #createText}，加图标字号走
     * {@link #create}。</p>
     */
    private static final List<TextSlot> TEXT_SLOTS = new ArrayList<>();

    /** 一档正文字号：渲染器本身 + 它的内置字体，换回内置时按这套还原。 */
    private static final class TextSlot {
        private final CFontRenderer renderer;
        private final boolean bold;
        private final Font builtinMain;
        private final Font builtinFallback;
        private final FontKerning builtinKerning;
        /**
         * 内置字体下的行盒，锁定用。
         *
         * <p>懒取而不是构造时取：{@link CFontRenderer#lineBox()} 要等 Skia 把字体解析出来才有值，
         * 而首次换字体一定发生在那之后。</p>
         */
        private int lineBox = -1;

        private TextSlot(CFontRenderer renderer, boolean bold, Font builtinMain,
                         Font builtinFallback, FontKerning builtinKerning) {
            this.renderer = renderer;
            this.bold = bold;
            this.builtinMain = builtinMain;
            this.builtinFallback = builtinFallback;
            this.builtinKerning = builtinKerning;
        }

        private int lockedLineBox() {
            if (this.lineBox <= 0) {
                this.lineBox = this.renderer.lineBox();
            }
            return this.lineBox;
        }
    }

    /**
     * 把界面正文字体换成给定的一套。回退链固定留着内置字体，缺字才不会画成豆腐块。
     *
     * @param regular          正文字号用的主字体
     * @param bold             粗体字号用的主字体（可以和 {@code regular} 是同一款）
     * @param fakeBoldRegular  正文是否描边加粗（一般是 false）
     * @param fakeBoldBold     粗体位置是否描边加粗（字体族没有粗体文件时为 true）
     */
    public static void retargetTextFonts(Font regular, Font bold,
                                         boolean fakeBoldRegular, boolean fakeBoldBold) {
        for (TextSlot slot : TEXT_SLOTS) {
            Font main = slot.bold ? bold : regular;
            if (main == null) {
                continue;
            }
            slot.renderer.retarget(main,
                    new Font[]{slot.builtinMain, slot.builtinFallback},
                    slot.lockedLineBox(),
                    slot.bold ? fakeBoldBold : fakeBoldRegular,
                    null);
        }
    }

    /** 换回随 mod 打包的字体：连字间距表一起还原，度量口径也不再锁。 */
    public static void restoreBuiltinTextFonts() {
        for (TextSlot slot : TEXT_SLOTS) {
            slot.renderer.retarget(slot.builtinMain, new Font[]{slot.builtinFallback},
                    -1, false, slot.builtinKerning);
        }
    }

    /** 内置正文字体的主字体实例。用户挑的字体解析不出 AWT 句柄时拿它兜底量墨迹。 */
    public static Font builtinTextMain(boolean bold) {
        return readFont(builtinMainPath(bold));
    }

    public static List<CFontRenderer> getAllFontRenderers() {

        return Arrays.stream(FontManager.class.getDeclaredFields())
                .filter(field -> field.getType() == CFontRenderer.class)
                .map(method -> {
            try {
                return (CFontRenderer) method.get(null);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toList());

    }

    public static void deleteLoadedTextures() {
        getAllFontRenderers().forEach(c -> {
            if (c != null) {
                c.close();
            }
        });
    }

    public static void loadFonts() {
        if (USE_LEGACY_JAVA8_TTF_FONTS) {
            System.out.println("[MuoniumPlayer] Detected Java 8u51-compatible runtime; using TrueType UI fonts for stable half-width glyph sizing.");
        }

        String normalName = "pf_normal";
        String boldName = "pf_middleblack";

        // 重新加载字体时白名单要跟着重建，否则里面留着一批已经 close() 的渲染器。
        TEXT_SLOTS.clear();

        pf12 = createText(12, normalName);
        pf14 = createText(14, normalName);
        pf20 = createText(20, normalName);
        pf25 = createText(25, normalName);
        pf32 = createText(32, normalName);
        pf18 = createText(18, normalName);

        pf10bold = createText(10, boldName);
        pf12bold = createText(12, boldName);
        pf14bold = createText(14, boldName);
        pf16bold = createText(16, boldName);
        pf18bold = createText(18, boldName);
        pf20bold = createText(20, boldName);
        pf25bold = createText(25, boldName);
        pf28bold = createText(28, boldName);
        pf34bold = createText(34, boldName);
        pf40bold = createText(40, boldName);
        pf50bold = createText(50, boldName);
        pf65bold = createText(65, boldName);

        icon30 = create(30, "icomoon");

        music14 = create(14, "music");
        music18 = create(18, "music");
        music40 = create(40, "music");

        fontello14 = create(14, "fontello");
        fontello16 = create(16, "fontello");
        fontello18 = create(18, "fontello");
        fontello22 = create(22, "fontello");
        fontello40 = create(40, "fontello");

        musicBrand16 = create(16, "music-brand-icons");
        musicBrand18 = create(18, "music-brand-icons");
        musicBrand52 = create(52, "music-brand-icons");
        youtubeIcon24 = create(24, "youtube-icons");
        youtubeIcon34 = create(34, "youtube-icons");
        qqMusicIcon16 = create(16, "qq-music-icons");
        // The QQ Music brand glyph has more internal whitespace than the NetEase glyph.
        // Use a dedicated larger renderer in the source switcher so both providers read at the same visual size.
        qqMusicIcon20 = create(20, "qq-music-icons");
        qqMusicIcon68 = create(68, "qq-music-icons");
        queueIcon34 = create(34, "player-queue-icons");
        queueIcon38 = create(38, "player-queue-icons");
        actionIcon34 = create(34, "player-action-icons");
        actionIcon38 = create(38, "player-action-icons");
        islandIcon12 = create(12, "island-status-icons");
        islandIcon16 = create(16, "island-status-icons");
        islandVolumeIcon18 = create(ISLAND_VOLUME_ICON_SMALL, "island-volume-icons");
        islandVolumeIcon24 = create(ISLAND_VOLUME_ICON_LARGE, "island-volume-icons");

        settingsIcon24 = create(24, "hud-settings-icons");
        settingsIcon32 = create(32, "hud-settings-icons");
        settingsIcon40 = create(40, "hud-settings-icons");

        partyIcon24 = create(24, "party-icons");
        partyIcon32 = create(32, "party-icons");
        partyIcon40 = create(40, "party-icons");
        partyIcon56 = create(56, "party-icons");
    }

    /**
     * Font renderers are constructed synchronously in {@link #loadFonts()}; none
     * are initialized by a later worker. The former endless polling loop therefore
     * could never repair a missing assignment/resource and froze Forge's loading
     * screen while spamming the log. Validate once and continue startup instead.
     */
    public static void waitUntilAllLoaded() {
        List<String> missing = Arrays.stream(FontManager.class.getDeclaredFields())
                .filter(field -> field.getType() == CFontRenderer.class)
                .filter(field -> {
                    try {
                        return field.get(null) == null;
                    } catch (IllegalAccessException ignored) {
                        return true;
                    }
                })
                .map(field -> field.getName())
                .collect(Collectors.toList());
        if (!missing.isEmpty()) {
            System.err.println("[MuoniumPlayer] Font initialization incomplete: " + missing
                    + ". Continuing without a blocking retry loop.");
        }
    }

    @Override
    public void init() {
        loadFonts();
        waitUntilAllLoaded();
        // 用户上次选的界面字体。同步装：这时界面还没起来，几十毫秒没人看得见，而异步会让
        // 开头几帧先用内置字体画一遍再突然换掉。
        UiFontLibrary.bootstrap();
    }
    
    private static final HashMap<String, Font> fonts = new HashMap<>();
    private static final HashMap<String, FontKerning> fontKernings = new HashMap<>();
    
    private static Font readFont(String path) {
        return fonts.computeIfAbsent(path, p -> {
            try {
                InputStream resourceAsStream = FontManager.class.getResourceAsStream(p);
                byte[] data = readAll(resourceAsStream);
                resourceAsStream.close();
                Font font = Font.createFont(Font.TRUETYPE_FONT, new java.io.ByteArrayInputStream(data));
                // 同一份字节既给 AWT（墨迹度量还在用它）也给 Skia（真正的光栅化）。
                // 必须在这里登记：AWT 的 Font 对象拿不回字体文件的字节，
                // 而按字体名字查会撞车——多份 fontello 导出的内部名字都叫 fontello。
                com.muoniumplayer.render.skija.SkijaText.register(font, data);
                return font;
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        });
    }

    /**
     * 把字体流一次读干净。
     *
     * <p>不能直接把流交给 {@code Font.createFont}：那样字节就被 AWT 吃掉了，
     * Skia 侧再也拿不到，而两边必须用同一份字节，才能保证「量出来的宽度」和「画出来的字」
     * 出自同一个字体文件。
     */
    private static byte[] readAll(InputStream in) throws java.io.IOException {
        if (in == null) {
            return new byte[0];
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(1 << 16);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** 从任意字体流建 AWT Font，并同时把字节登记给 Skia。 */
    private static Font createAndRegister(InputStream stream) throws Exception {
        byte[] data = readAll(stream);
        Font font = Font.createFont(Font.TRUETYPE_FONT, new java.io.ByteArrayInputStream(data));
        com.muoniumplayer.render.skija.SkijaText.register(font, data);
        return font;
    }
    
    private static FontKerning readFontKerning(String path) {

        return fontKernings.computeIfAbsent(path, p -> {
            try {
                return new FontKerning(path);
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        });
    }

    @SneakyThrows
    public static CFontRenderer create(float size, InputStream fontStream) {
        Font font = createAndRegister(fontStream);

        Font fallback = readFont("/muonium/fonts/pf_normal.ttf");
        FontKerning fallbackKerning = readFontKerning("/muonium/fonts/pf_normal.ttf");
        return new CFontRenderer(font, size * 0.5f, fallbackKerning, fallback);
    }

    @SneakyThrows
    public static CFontRenderer create(float size, InputStream fontStream, InputStream fallBackStream) {
        Font font = createAndRegister(fontStream);
        Font fallBack = createAndRegister(fallBackStream);

        FontKerning fontKerning = readFontKerning("/muonium/fonts/pf_normal.ttf");
        return new CFontRenderer(font, size * 0.5f, fontKerning, fallBack);
    }

    @SneakyThrows
    public static CFontRenderer create(float size, String name) {

        Font font = readFont("/muonium/fonts/" + name + ".ttf");
        FontKerning kerning = readFontKerning("/muonium/fonts/" + name + ".ttf");

        // 中文字体默认使用 SF Pro 作为主字体
        // 因为它们的英文字母太他妈难看了
        // 丑陋不堪，，
        if ("googlesans".equals(name) || "product".equals(name) || "tahoma".equals(name)) {
            Font fallback = readFont("/muonium/fonts/pf_normal.ttf");
            FontKerning fallbackKerning = readFontKerning("/muonium/fonts/pf_normal.ttf");
            return new CFontRenderer(font, size * 0.5f, kerning, fallback);
        } else if ("googlesansbold".equals(name)) {
            Font fallback = readFont("/muonium/fonts/pf_middleblack.ttf");
            FontKerning fallbackKerning = readFontKerning("/muonium/fonts/pf_middleblack.ttf");
            return new CFontRenderer(font, size * 0.5f, kerning, fallback);
        } else if ("pf_normal".equals(name) || "pf_middleblack".equals(name)) {
            String mainPath = builtinMainPath("pf_middleblack".equals(name));
            Font main = readFont(mainPath);
            FontKerning mainKerning = readFontKerning(mainPath);
            return new CFontRenderer(main, size * 0.5f, mainKerning, font);
        }
        return new CFontRenderer(font, size * 0.5f, kerning, font);

    }

    /**
     * 建一档<b>正文</b>渲染器并登记进 {@link #TEXT_SLOTS}，于是它能被用户换字体。
     *
     * <p>图标字号一律走 {@link #create}：换掉图标字体等于把界面上所有图标画成别的字形。</p>
     */
    private static CFontRenderer createText(float size, String name) {
        CFontRenderer renderer = create(size, name);
        boolean bold = "pf_middleblack".equals(name);
        String mainPath = builtinMainPath(bold);
        TEXT_SLOTS.add(new TextSlot(renderer, bold, readFont(mainPath),
                readFont(builtinFallbackPath(bold)), readFontKerning(mainPath)));
        return renderer;
    }

    /** 内置正文字体的半角主字体：中文由回退字体画，所以这一份只管拉丁字母、数字与半角标点。 */
    private static String builtinMainPath(boolean bold) {
        if (bold) {
            return USE_LEGACY_JAVA8_TTF_FONTS
                    ? "/muonium/fonts/arialBold.ttf"
                    : "/muonium/fonts/sfbold.otf";
        }
        return USE_LEGACY_JAVA8_TTF_FONTS
                ? "/muonium/fonts/arial.ttf"
                : "/muonium/fonts/sfregular.otf";
    }

    /** 内置正文字体的中文回退（苹方）。 */
    private static String builtinFallbackPath(boolean bold) {
        return bold ? "/muonium/fonts/pf_middleblack.ttf" : "/muonium/fonts/pf_normal.ttf";
    }

    private static boolean isLegacyJava8CffRuntime() {
        int update = parseJava8Update(System.getProperty("java.version", ""));
        if (update < 0) {
            update = parseJava8Update(System.getProperty("java.runtime.version", ""));
        }

        // Java 8u51 is the affected target environment.  Keep newer Java 8
        // runtimes on the original SF Pro appearance.
        return update >= 0 && update <= 60;
    }

    static int parseJava8Update(String version) {
        if (version == null) {
            return -1;
        }

        String normalized = version.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()) {
            return -1;
        }

        int updateStart;
        if (normalized.startsWith("1.8.0_")) {
            updateStart = "1.8.0_".length();
        } else if (normalized.startsWith("8u")) {
            updateStart = 2;
        } else if (normalized.startsWith("8.0.")) {
            updateStart = "8.0.".length();
        } else {
            return -1;
        }

        int updateEnd = updateStart;
        while (updateEnd < normalized.length() && Character.isDigit(normalized.charAt(updateEnd))) {
            updateEnd++;
        }
        if (updateEnd == updateStart) {
            return -1;
        }

        try {
            return Integer.parseInt(normalized.substring(updateStart, updateEnd));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    @Override
    public void stop() {

    }
}
