package com.muoniumplayer.core.management;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.render.skija.SkijaNatives;
import com.muoniumplayer.render.skija.SkijaText;

import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.FontStyle;
import io.github.humbleui.skija.Typeface;

import java.awt.Font;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 界面字体的目录与切换。
 *
 * <h2>为什么可以只换字形不动排版</h2>
 * 整个界面的行高与垂直居中都读 {@code CFontRenderer.getFontHeight()}，而它是由字体的
 * ascent/descent 算出来的。任意一款系统字体的这两个量都和内置的 SF Pro 不同，直接换字体
 * 会让所有列表行、按钮内文、歌词行距在同一帧里全部改变。所以换字体走
 * {@link CFontRenderer#retarget} 并锁住原来的行盒：新字体自己的 ascent/descent 盒被居中
 * 放进旧行盒，度量口径一字不改，只有字形换了。
 *
 * <h2>系统字体为什么不需要找字体文件</h2>
 * Windows 上 Skia 的默认 {@code FontMgr} 就是 DirectWrite：{@code getFamilyName(i)} 能枚举
 * 系统装了哪些族，{@code matchFamilyStyle(族名, 字重)} 直接给回 Typeface。于是「电脑内字体」
 * 这条路完全不碰磁盘 —— 否则光是把 {@code msyh.ttc}（19MB，里面还装着两款字体）读进内存
 * 就要几十毫秒，还得自己挑 ttc 索引。外部字体文件才走「读字节 → makeFromData」。
 *
 * <h2>缺字怎么办</h2>
 * 换字体时回退链永远保留内置的 SF Pro + 苹方。用户挑的字体十有八九缺字（拉丁字体没有汉字、
 * 中文字体没有假名），缺的那一格由回退链接住，画出来是内置字形而不是豆腐块。列表里也会
 * 标出「无中文」，免得用户以为功能坏了。
 */
public final class UiFontLibrary {

    /** 扫描状态。字体目录不在启动时枚举 —— 打开选择界面才扫，省掉每次启动 300ms 的 AWT 字体管理器初始化。 */
    public enum State {
        IDLE,
        SCANNING,
        READY
    }

    /**
     * 排除的符号字体（族名小写）。
     *
     * <p>这些字体把拉丁码位映射成了图形，光靠「有没有 'A' 的字形」判断不出来 —— 它们都有，
     * 只是画出来是个方块或者箭头。选中任何一款，整个界面会变成一堆符号，而用户很难反推
     * 出「刚才点的那一行是什么」再点回来。所以直接不列。</p>
     */
    private static final Set<String> SYMBOL_FAMILIES = new HashSet<String>(Arrays.asList(
            "wingdings", "wingdings 2", "wingdings 3", "webdings", "symbol", "marlett",
            "mt extra", "bookshelf symbol 7", "ms outlook", "ms reference specialty",
            "segoe mdl2 assets", "segoe fluent icons", "hololens mdl2 assets",
            "segoe ui symbol", "segoe ui emoji", "opensymbol", "zwadobef"));

    private static final String[] EXTENSIONS = {".ttf", ".otf", ".ttc", ".otc"};

    private static final Gson GSON = new Gson();

    /** Preferred first-run system family; falls back to the bundled font when unavailable. */
    private static final String DEFAULT_FONT_ID = "system:思源黑体";

    private static volatile State state = State.IDLE;
    /** 系统字体 + 外部字体，已按显示名排好序。内置那一项不在里面，界面单独列在最前。 */
    private static volatile List<UiFont> catalog = Collections.emptyList();
    private static volatile UiFont active = UiFont.BUILTIN;
    /** 正在装配的那一项的 id：切换要读文件、解析字体，慢的话界面上得看得出来在忙。 */
    private static volatile String pendingId;
    private static volatile String status = "";
    private static volatile boolean statusBad;

    private UiFontLibrary() {
    }

    /** 装配好的一套字体：正文与粗体各一个 AWT 句柄 + 一个 Skia typeface。 */
    private static final class Prepared {
        Font regularAwt;
        Font boldAwt;
        Typeface regular;
        Typeface bold;
        boolean fakeBoldRegular;
        boolean fakeBoldBold;
    }
    // ════════════════════════════ 对外状态 ════════════════════════════

    public static State getState() {
        return state;
    }

    public static List<UiFont> getCatalog() {
        return catalog;
    }

    public static UiFont getActive() {
        return active;
    }

    public static boolean isActive(UiFont font) {
        return font != null && active != null && font.getId().equals(active.getId());
    }

    public static boolean isPending(UiFont font) {
        return font != null && font.getId().equals(pendingId);
    }

    public static boolean isBusy() {
        return pendingId != null;
    }

    public static String getStatus() {
        return status;
    }

    public static boolean isStatusBad() {
        return statusBad;
    }

    public static File getExternalDir() {
        return ConfigPaths.FONT_DIR;
    }

    // ════════════════════════════ 启动装配 ════════════════════════════

    /**
     * 把上次选的字体装回去。由 {@code FontManager.init()} 在 {@code loadFonts()} 之后同步调用。
     *
     * <p>同步而不是异步：这时候界面还没起来，几十毫秒的解析没人看得见；异步反而会让开头几帧
     * 用内置字体画、然后突然换掉。任何一步失败都静默回到内置字体并留一句状态说明，绝不
     * 因为一款字体读不出来就拦住 mod 启动。</p>
     */
    public static void bootstrap() {
        String savedId;
        try {
            savedId = readSavedId();
        } catch (Throwable failure) {
            return;
        }
        // No persisted choice means first run: try the preferred system family, but never fail
        // startup if this machine does not have it. An explicit built-in choice stays built-in.
        if (savedId == null || savedId.isEmpty()) {
            savedId = DEFAULT_FONT_ID;
        }
        if (UiFont.BUILTIN.getId().equals(savedId)) {
            return;
        }

        UiFont font = resolveSaved(savedId);
        if (font == null) {
            status = "上次选的字体已经找不到了，先用内置字体";
            statusBad = true;
            return;
        }
        if (!install(font)) {
            status = "上次选的字体读不出来，先用内置字体";
            statusBad = true;
        }
    }

    /** 按持久化的 id 反查候选项。这一步刻意不扫描整个字体目录：只验证这一款还在不在。 */
    private static UiFont resolveSaved(String savedId) {
        if (savedId.startsWith("system:")) {
            String family = savedId.substring("system:".length());
            if (family.isEmpty()) {
                return null;
            }
            Typeface probe = matchFamily(family, FontStyle.NORMAL);
            if (probe == null) {
                return null;
            }
            probe.close();
            return UiFont.system(family, localizedFamilyName(family));
        }
        if (savedId.startsWith("file:")) {
            File file = new File(ConfigPaths.FONT_DIR, savedId.substring("file:".length()));
            if (!file.isFile()) {
                return null;
            }
            return UiFont.external(file, stripExtension(file.getName()));
        }
        return null;
    }

    // ════════════════════════════ 切换 ════════════════════════════

    /**
     * 换成这一款字体。
     *
     * <p>解析放后台、装配放主线程：{@code Font.createFont} 对一个大字体文件要几百毫秒（实测
     * 19MB 的 msyh.ttc 是 556ms），在渲染线程上做就是一次明显的卡顿；而 {@code SkijaText} 的
     * 登记表是普通 IdentityHashMap，只能在主线程写。</p>
     */
    public static void select(UiFont font) {
        if (font == null || font.isBuiltin()) {
            restoreBuiltin();
            return;
        }
        if (isActive(font) || isPending(font)) {
            return;
        }
        pendingId = font.getId();
        status = "正在加载 " + font.getDisplayName() + "…";
        statusBad = false;
        MultiThreadingUtil.runAsync(() -> {
            // 后台只做「把字节变成 AWT Font」这一件慢事，结果丢给主线程装。
            final Prepared prepared;
            try {
                prepared = prepare(font);
            } catch (Throwable failure) {
                finish(font, null, String.valueOf(failure.getMessage()));
                return;
            }
            MultiThreadingUtil.runOnMainThread(() -> finish(font, prepared, null));
        });
    }

    /** 回到随 mod 打包的字体。 */
    public static void restoreBuiltin() {
        pendingId = null;
        FontManager.restoreBuiltinTextFonts();
        active = UiFont.BUILTIN;
        status = "已回到内置字体";
        statusBad = false;
        save(UiFont.BUILTIN);
    }
    private static void finish(UiFont font, Prepared prepared, String error) {
        // 解析期间用户又点了别的字体、或者点了「恢复内置」，这一次的结果就不该再装上去 ——
        // 否则先点的那款会在几百毫秒后把后点的那款顶掉。
        if (!font.getId().equals(pendingId)) {
            return;
        }
        pendingId = null;
        if (prepared == null) {
            status = font.getDisplayName() + " 读不出来" + (error == null ? "" : "：" + error);
            statusBad = true;
            return;
        }
        wire(prepared);
        active = font;
        status = "已换成 " + font.getDisplayName()
                + (font.hasCjk() ? "" : "（这款字体没有汉字，中文仍由内置字体绘制）");
        statusBad = false;
        save(font);
    }

    /** 主线程：登记 typeface 并让所有正文渲染器重建。 */
    private static void wire(Prepared prepared) {
        SkijaText.registerTypeface(prepared.regularAwt, prepared.regular);
        SkijaText.registerTypeface(prepared.boldAwt, prepared.bold);
        FontManager.retargetTextFonts(prepared.regularAwt, prepared.boldAwt,
                prepared.fakeBoldRegular, prepared.fakeBoldBold);
    }

    /** 同步装配（启动时用；这时还没有渲染线程要让）。 */
    private static boolean install(UiFont font) {
        try {
            Prepared prepared = prepare(font);
            if (prepared == null) {
                return false;
            }
            wire(prepared);
            active = font;
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    // ════════════════════════════ 解析 ════════════════════════════

    private static Prepared prepare(UiFont font) throws Exception {
        return font.getOrigin() == UiFont.Origin.SYSTEM
                ? prepareSystem(font)
                : prepareExternal(font);
    }

    private static Prepared prepareSystem(UiFont font) {
        Typeface regular = matchFamily(font.getFamily(), FontStyle.NORMAL);
        if (regular == null) {
            return null;
        }
        Typeface bold = matchFamily(font.getFamily(), FontStyle.BOLD);
        boolean realBold = bold != null && bold.getFontStyle().getWeight() >= 600;
        if (bold == null) {
            bold = regular;
        }

        Prepared prepared = new Prepared();
        prepared.regular = regular;
        prepared.bold = bold;
        prepared.regularAwt = awtHandle(font.getFamily(), Font.PLAIN, false);
        prepared.boldAwt = awtHandle(font.getFamily(), Font.BOLD, true);
        // 没有真粗体文件时用描边假粗顶上：界面上「标题比正文重」这层信息不能丢。
        prepared.fakeBoldBold = !realBold;
        return prepared;
    }

    private static Prepared prepareExternal(UiFont font) throws Exception {
        File file = font.getFile();
        if (file == null || !file.isFile()) {
            return null;
        }
        byte[] bytes = readAll(file);
        Typeface regular = typefaceFromBytes(bytes);
        if (regular == null) {
            return null;
        }

        Prepared prepared = new Prepared();
        prepared.regular = regular;
        prepared.regularAwt = awtFromBytes(bytes, false);

        // 同目录下的粗体伴生文件（MyFont-Regular.ttf 旁边的 MyFont-Bold.ttf）。找得到就用真粗体，
        // 找不到才假粗 —— 用户把整套字重丢进目录时，界面理应用上他给的粗体。
        File boldFile = findBoldCompanion(file);
        Typeface bold = null;
        if (boldFile != null) {
            byte[] boldBytes = readAll(boldFile);
            Typeface candidate = typefaceFromBytes(boldBytes);
            if (candidate != null && candidate.getFontStyle().getWeight() >= 600) {
                bold = candidate;
                prepared.boldAwt = awtFromBytes(boldBytes, true);
            } else if (candidate != null) {
                candidate.close();
            }
        }
        if (bold == null) {
            bold = regular;
            prepared.boldAwt = distinct(prepared.regularAwt);
            prepared.fakeBoldBold = true;
        }
        prepared.bold = bold;
        return prepared;
    }

    private static Typeface matchFamily(String family, FontStyle style) {
        // 显式确认原生库就绪。启动时 FontManager.loadFonts() 已经强制加载过一遍，但这个类
        // 也会被后台扫描线程和界面按钮直接调到，不该依赖调用顺序。
        if (!SkijaNatives.ensureLoaded()) {
            return null;
        }
        try {
            return FontMgr.getDefault().matchFamilyStyle(family, style);
        } catch (Throwable failure) {
            return null;
        }
    }

    private static Typeface typefaceFromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || !SkijaNatives.ensureLoaded()) {
            return null;
        }
        try (Data data = Data.makeFromBytes(bytes)) {
            // 索引固定 0：.ttc 里装着好几款字体，AWT 的 createFont 也只取第一款，
            // 两边必须挑同一款，否则量出来的墨迹和画出来的字不是一回事。
            return FontMgr.getDefault().makeFromData(data, 0);
        } catch (Throwable failure) {
            return null;
        }
    }
    /**
     * 系统字体的 AWT 句柄。
     *
     * <p>这个实例有两个身份：{@link SkijaText} 登记表的键（按对象身份查），以及
     * {@code GlyphInkMetrics} 量墨迹用的字体 —— 文字的垂直居中读的是它。所以 AWT 必须
     * 认得出这个族名，否则它会静默返回 Dialog，于是「量的是一款字、画的是另一款」，
     * 一行字会整体偏几像素。认不出就退回内置字体的度量：字形换了、对齐还是原来那套。</p>
     */
    private static Font awtHandle(String family, int style, boolean bold) {
        try {
            Font handle = new Font(family, style, 1);
            if (!"Dialog".equalsIgnoreCase(handle.getFamily(Locale.ROOT))) {
                return handle;
            }
        } catch (Throwable ignored) {
        }
        return FontManager.builtinTextMain(bold).deriveFont(1f);
    }

    /** 外部字体文件的 AWT 句柄。解析不了就退回内置度量，理由同 {@link #awtHandle}。 */
    private static Font awtFromBytes(byte[] bytes, boolean bold) {
        try {
            return Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(bytes));
        } catch (Throwable ignored) {
        }
        return FontManager.builtinTextMain(bold).deriveFont(1f);
    }

    /**
     * 找同目录下的粗体伴生文件。
     *
     * <p>只按文件名猜，不去逐个解析目录里所有字体：目录里可能有几十个文件，为了找一个粗体
     * 把每个都读一遍字节太贵。猜错了也没事 —— 调用方会验字重，不到 600 就当没找到。</p>
     */
    private static File findBoldCompanion(File regular) {
        File dir = regular.getParentFile();
        if (dir == null) {
            return null;
        }
        String name = regular.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot);
        String ext = dot < 0 ? "" : name.substring(dot);

        List<String> tries = new ArrayList<String>();
        String lower = stem.toLowerCase(Locale.ROOT);
        for (String tag : new String[]{"-regular", " regular", "regular"}) {
            if (lower.endsWith(tag)) {
                String head = stem.substring(0, stem.length() - tag.length());
                String joint = tag.startsWith("-") ? "-" : (tag.startsWith(" ") ? " " : "");
                tries.add(head + joint + "Bold");
                tries.add(head + joint + "bold");
                break;
            }
        }
        tries.add(stem + "-Bold");
        tries.add(stem + " Bold");
        tries.add(stem + "Bold");
        tries.add(stem + "bd");
        tries.add(stem + "b");

        for (String candidate : tries) {
            File file = new File(dir, candidate + ext);
            if (file.isFile() && !file.equals(regular)) {
                return file;
            }
        }
        return null;
    }

    private static byte[] readAll(File file) throws Exception {
        long size = file.length();
        if (size <= 0 || size > 96L * 1024 * 1024) {
            throw new IllegalArgumentException("字体文件大小不合理：" + size);
        }
        byte[] buffer = new byte[(int) size];
        int filled = 0;
        try (InputStream in = new FileInputStream(file)) {
            while (filled < buffer.length) {
                int read = in.read(buffer, filled, buffer.length - filled);
                if (read < 0) {
                    break;
                }
                filled += read;
            }
        }
        if (filled != buffer.length) {
            return Arrays.copyOf(buffer, filled);
        }
        return buffer;
    }

    /** 族名的本地化写法（「Microsoft YaHei」→「微软雅黑」）。列表里读英文名对中文用户不友好。 */
    private static String localizedFamilyName(String family) {
        try {
            Font probe = new Font(family, Font.PLAIN, 1);
            String local = probe.getFamily();
            if (local != null && !local.isEmpty() && !"Dialog".equalsIgnoreCase(local)) {
                return local;
            }
        } catch (Throwable ignored) {
        }
        return family;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    // ════════════════════════════ 持久化 ════════════════════════════

    private static String readSavedId() {
        File file = ConfigPaths.UI_FONT;
        if (!file.isFile()) {
            return null;
        }
        try {
            JsonObject json = JsonConfigStorage.readObject(file, GSON);
            if (json == null || !json.has("id") || json.get("id").isJsonNull()) {
                return null;
            }
            return json.get("id").getAsString();
        } catch (Throwable failure) {
            return null;
        }
    }

    private static void save(UiFont font) {
        try {
            File file = ConfigPaths.UI_FONT;
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            JsonObject json = new JsonObject();
            json.addProperty("id", font.getId());
            // name 只是给人看的备注，反查一律按 id：族名的本地化写法会跟着系统语言变。
            json.addProperty("name", font.getDisplayName());
            JsonConfigStorage.writeObject(file, GSON, json);
        } catch (Throwable ignored) {
        }
    }
    // ════════════════════════════ 枚举 ════════════════════════════

    /**
     * 重新列一遍可选字体。打开选择界面时调，界面靠 {@link #getState()} 显示「正在扫描」。
     *
     * <p>放后台：枚举系统字体族要初始化 AWT 的字体管理器（实测 300ms），外部字体还要逐个读
     * 字节验字形覆盖。这两件事都不能在渲染线程上做。</p>
     */
    /**
     * 需要字体目录时调。已经扫过就直接用上次的结果。
     *
     * <p>不每次打开界面都重扫：扫一遍要几百毫秒，而结果在一次游戏里几乎不会变；每次重扫
     * 都会让列表在界面开着的时候重建一次，看起来就是打开后"闪"一下。</p>
     */
    public static void ensureScanned() {
        if (state == State.IDLE) {
            refresh();
        }
    }

    public static void refresh() {
        if (state == State.SCANNING) {
            return;
        }
        state = State.SCANNING;
        MultiThreadingUtil.runAsync(() -> {
            List<UiFont> found = new ArrayList<UiFont>();
            try {
                found.addAll(scanExternal());
            } catch (Throwable ignored) {
            }
            try {
                found.addAll(scanSystem());
            } catch (Throwable failure) {
                status = "枚举系统字体失败：" + failure;
                statusBad = true;
            }
            catalog = found;
            state = State.READY;
        });
    }

    /**
     * 系统装了哪些字体族。
     *
     * <p>问 Skia 而不是 AWT：Windows 上 Skia 的默认 {@code FontMgr} 就是 DirectWrite，枚举出来
     * 的族名可以直接 {@code matchFamilyStyle} 换成 typeface；AWT 那份族名列表还得再想办法
     * 找到对应的字体文件。顺手用一个 16px 的探针字体量「有没有 'A'、有没有汉字」，
     * 这两条是用户最容易踩坑的信息。</p>
     */
    private static List<UiFont> scanSystem() {
        if (!SkijaNatives.ensureLoaded()) {
            return Collections.emptyList();
        }
        FontMgr manager = FontMgr.getDefault();
        int count = manager.getFamiliesCount();
        List<UiFont> out = new ArrayList<UiFont>(Math.max(16, count));
        for (int index = 0; index < count; index++) {
            String family = manager.getFamilyName(index);
            if (family == null || family.isEmpty()) {
                continue;
            }
            // Windows 会为每个中日韩字体额外注册一个 @ 前缀的竖排别名族，选中之后整行字会躺倒。
            if (family.charAt(0) == '@') {
                continue;
            }
            if (SYMBOL_FAMILIES.contains(family.toLowerCase(Locale.ROOT))) {
                continue;
            }
            Typeface regular = matchFamily(family, FontStyle.NORMAL);
            if (regular == null) {
                continue;
            }
            boolean latin = false;
            boolean cjk = false;
            try (io.github.humbleui.skija.Font probe = new io.github.humbleui.skija.Font(regular, 16f)) {
                short[] glyphs = probe.getStringGlyphs("A\u4e00");
                latin = glyphs.length > 0 && glyphs[0] != 0;
                cjk = glyphs.length > 1 && glyphs[1] != 0;
            } catch (Throwable ignored) {
            }
            Typeface bold = matchFamily(family, FontStyle.BOLD);
            boolean realBold = bold != null && bold.getFontStyle().getWeight() >= 600;
            if (bold != null) {
                bold.close();
            }
            regular.close();
            if (!latin && !cjk) {
                continue;
            }
            out.add(UiFont.system(family, localizedFamilyName(family)).mark(latin, cjk, realBold));
        }
        sortByName(out);
        return out;
    }

    /** {@code config/muonium/fonts/} 里的字体文件。 */
    private static List<UiFont> scanExternal() {
        File dir = ConfigPaths.FONT_DIR;
        if (!dir.isDirectory()) {
            return Collections.emptyList();
        }
        File[] entries = dir.listFiles();
        if (entries == null) {
            return Collections.emptyList();
        }
        List<File> files = new ArrayList<File>();
        for (File entry : entries) {
            if (entry.isFile() && hasFontExtension(entry.getName())) {
                files.add(entry);
            }
        }
        Collections.sort(files, (left, right) -> left.getName().compareToIgnoreCase(right.getName()));

        // 粗体伴生文件不单列：它是别人的一个字重，单列会让同一款字体在列表里出现两行，
        // 而选中那一行的效果和选中正常那一行几乎一样，只会让人以为列表重复了。
        Set<String> companions = new HashSet<String>();
        for (File file : files) {
            File bold = findBoldCompanion(file);
            if (bold != null) {
                companions.add(bold.getName().toLowerCase(Locale.ROOT));
            }
        }

        List<UiFont> out = new ArrayList<UiFont>();
        for (File file : files) {
            if (companions.contains(file.getName().toLowerCase(Locale.ROOT))) {
                continue;
            }
            UiFont font = probeExternal(file);
            if (font != null) {
                out.add(font);
            }
        }
        return out;
    }

    /** 读一个字体文件，量出显示名与字形覆盖。读不出来返回 null（坏文件不该出现在列表里）。 */
    private static UiFont probeExternal(File file) {
        try {
            byte[] bytes = readAll(file);
            Typeface typeface = typefaceFromBytes(bytes);
            if (typeface == null) {
                return null;
            }
            boolean latin = false;
            boolean cjk = false;
            try (io.github.humbleui.skija.Font probe = new io.github.humbleui.skija.Font(typeface, 16f)) {
                short[] glyphs = probe.getStringGlyphs("A\u4e00");
                latin = glyphs.length > 0 && glyphs[0] != 0;
                cjk = glyphs.length > 1 && glyphs[1] != 0;
            }
            String name = typeface.getFamilyName();
            boolean bold = typeface.getFontStyle().getWeight() >= 600
                    || findBoldCompanion(file) != null;
            typeface.close();
            if (name == null || name.isEmpty()) {
                name = stripExtension(file.getName());
            }
            return UiFont.external(file, name).mark(latin, cjk, bold);
        } catch (Throwable failure) {
            return null;
        }
    }

    private static void sortByName(List<UiFont> fonts) {
        try {
            final java.text.Collator collator = java.text.Collator.getInstance(Locale.CHINA);
            Collections.sort(fonts, (left, right) ->
                    collator.compare(left.getDisplayName(), right.getDisplayName()));
        } catch (Throwable failure) {
            Collections.sort(fonts, (left, right) ->
                    left.getDisplayName().compareToIgnoreCase(right.getDisplayName()));
        }
    }

    private static boolean hasFontExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }
    // ════════════════════════════ 导入外部字体 ════════════════════════════

    /**
     * 打开系统文件选择框挑一个字体文件。
     *
     * <p>反射调 LWJGL 的 tinyfd：它随 Minecraft 一起分发（{@code lwjgl-tinyfd}），但版本之间
     * 未必都在编译期可见，而这个功能不该成为跨版本编译的风险点。拿不到就退回「把文件丢进
     * 目录」的路子，并把目录路径写在状态行里。</p>
     *
     * <p>单开一个线程：原生对话框是模态的，在渲染线程上弹会把整个游戏冻在那一帧。</p>
     */
    public static void browseForFile() {
        status = "已打开系统文件选择框，请在窗口里挑一个字体文件";
        statusBad = false;
        Thread picker = new Thread(() -> {
            String picked = nativeOpenDialog();
            if (picked == null || picked.trim().isEmpty()) {
                if (!statusBad) {
                    status = "没有选择字体文件";
                }
                return;
            }
            importFile(new File(picked.trim()));
        }, "MuoniumPlayer-FontPicker");
        picker.setDaemon(true);
        picker.start();
    }

    private static String nativeOpenDialog() {
        try {
            Class<?> dialogs = Class.forName("org.lwjgl.util.tinyfd.TinyFileDialogs");
            Class<?> pointerBuffer = Class.forName("org.lwjgl.PointerBuffer");
            java.lang.reflect.Method open = dialogs.getMethod("tinyfd_openFileDialog",
                    CharSequence.class, CharSequence.class, pointerBuffer,
                    CharSequence.class, boolean.class);
            Object result = open.invoke(null, "\u9009\u62e9\u5b57\u4f53\u6587\u4ef6",
                    defaultBrowseDir(), null, null, Boolean.FALSE);
            return result == null ? null : result.toString();
        } catch (Throwable failure) {
            status = "这个版本打不开系统文件选择框，请把 ttf/otf 文件放进 "
                    + ConfigPaths.FONT_DIR.getPath() + " 再刷新";
            statusBad = true;
            return null;
        }
    }

    /** 对话框的起始目录。Windows 上直接落在系统字体目录，省掉用户自己找路径。 */
    private static String defaultBrowseDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String root = System.getenv("WINDIR");
            File fonts = new File(root == null || root.isEmpty() ? "C:\\Windows" : root, "Fonts");
            if (fonts.isDirectory()) {
                return fonts.getAbsolutePath() + File.separator;
            }
        }
        File dir = ConfigPaths.FONT_DIR;
        return dir.getAbsolutePath() + File.separator;
    }

    /**
     * 把字体文件复制进外部字体目录并立刻选用。
     *
     * <p>复制而不是记住原路径：原文件可能在 U 盘上、可能被改名，而字体是每次启动都要读的
     * 东西，指向一个会消失的路径等于下次启动静默回退成内置字体。</p>
     */
    public static void importFile(File source) {
        MultiThreadingUtil.runAsync(() -> {
            final File copy;
            try {
                copy = copyIntoLibrary(source);
            } catch (Throwable failure) {
                status = "导入失败：" + failure.getMessage();
                statusBad = true;
                return;
            }
            final UiFont font = probeExternal(copy);
            if (font == null) {
                status = copy.getName() + " 不是能用的字体文件";
                statusBad = true;
                return;
            }
            refresh();
            MultiThreadingUtil.runOnMainThread(() -> select(font));
        });
    }

    private static File copyIntoLibrary(File source) throws Exception {
        if (source == null || !source.isFile()) {
            throw new IllegalArgumentException("文件不存在");
        }
        if (!hasFontExtension(source.getName())) {
            throw new IllegalArgumentException("只支持 ttf / otf / ttc / otc");
        }
        File dir = ConfigPaths.FONT_DIR;
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("建不了目录 " + dir.getPath());
        }
        // 已经在目录里的文件直接用，不再复制一份（用户可能是从这个目录里挑的）。
        if (dir.getCanonicalFile().equals(source.getParentFile() == null
                ? null : source.getParentFile().getCanonicalFile())) {
            return source;
        }
        File target = new File(dir, source.getName());
        if (target.isFile() && target.length() == source.length()) {
            return target;
        }
        int suffix = 1;
        while (target.exists()) {
            String name = source.getName();
            int dot = name.lastIndexOf('.');
            target = new File(dir, (dot <= 0 ? name : name.substring(0, dot))
                    + "-" + suffix + (dot <= 0 ? "" : name.substring(dot)));
            suffix++;
        }
        byte[] bytes = readAll(source);
        try (OutputStream out = new FileOutputStream(target)) {
            out.write(bytes);
        }
        return target;
    }

    /**
     * 在系统文件管理器里打开外部字体目录。
     *
     * <p>单开线程的理由和文件对话框一样：{@code Desktop.open} 在某些环境下会阻塞很久。</p>
     */
    public static void openExternalDir() {
        final File dir = ConfigPaths.FONT_DIR;
        if (!dir.isDirectory() && !dir.mkdirs()) {
            status = "建不了目录 " + dir.getPath();
            statusBad = true;
            return;
        }
        status = "字体目录：" + dir.getAbsolutePath();
        statusBad = false;
        Thread opener = new Thread(() -> {
            try {
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().open(dir);
                }
            } catch (Throwable failure) {
                status = "打不开目录，路径是 " + dir.getAbsolutePath();
                statusBad = true;
            }
        }, "MuoniumPlayer-FontDir");
        opener.setDaemon(true);
        opener.start();
    }

    /** 同一款字体的第二个 AWT 句柄：登记表按对象身份查，一个实例只能绑一个 typeface。 */
    private static Font distinct(Font font) {
        return font.deriveFont(font.getSize2D());
    }

    /** 界面上给字体做效果预览的示例文本。 */
    public static String sampleText() {
        return "\u6c38\u8fdc\u76f8\u4fe1\u7f8e\u597d\u7684\u4e8b\u60c5\u5373\u5c06\u53d1\u751f  AaBbGg 0123";
    }
}