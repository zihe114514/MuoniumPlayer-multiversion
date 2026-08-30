package com.muoniumplayer.render.skija;

import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.Typeface;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 字体登记表：把 {@code java.awt.Font} 对象映射到 Skia 的 {@link Typeface}。
 *
 * <h2>为什么需要这张表</h2>
 * 文字渲染换到 Skia，但字体的<b>选择逻辑</b>（哪一档字号配哪个主字体和回退字体、
 * 十几份图标字体各自对应哪些码位）全在 {@code FontManager} 里，是业务决策而不是渲染实现，
 * 一行都不该动。{@code FontManager} 交给渲染器的是 {@code java.awt.Font}，
 * 而 AWT 的 Font 对象<b>拿不回字体文件的字节</b>——{@code Font.createFont} 是单向的。
 *
 * <p>所以在读字体文件的那一刻同时登记「这个 Font 对象是从这段字节来的」。
 * 键用<b>对象身份</b>而不是名字：多份 fontello 导出的内部名字可能都叫 "fontello"，
 * 按名字查会张冠李戴，把「下一首播放」的图标画成登录页的图标。
 * {@code FontManager.readFont} 按路径缓存，同一个文件永远是同一个 Font 实例，
 * 身份键因此是稳定的。
 */
public final class SkijaText {

    /** Font 实例 → 字体文件字节。IdentityHashMap：见类注释，绝不能按名字或 equals 查。 */
    private static final Map<java.awt.Font, byte[]> SOURCE_BYTES = new IdentityHashMap<>();
    /** Font 实例 → 已构造的 Typeface。 */
    private static final Map<java.awt.Font, Typeface> TYPEFACES = new IdentityHashMap<>();

    private SkijaText() {
    }

    /** {@code FontManager} 读入字体文件后调用。 */
    public static void register(java.awt.Font awtFont, byte[] data) {
        if (awtFont == null || data == null || data.length == 0) {
            return;
        }
        SOURCE_BYTES.put(awtFont, data);
    }

    /**
     * 取这个 AWT 字体对应的 Skia Typeface。
     *
     * @param awtFont 必须是 {@link #register} 登记过的那个实例本身，
     *                不能是 {@code deriveFont} 出来的派生对象
     * @return 没登记过或解析失败时返回 null
     */
    public static Typeface typefaceFor(java.awt.Font awtFont) {
        if (awtFont == null) {
            return null;
        }
        Typeface cached = TYPEFACES.get(awtFont);
        if (cached != null) {
            return cached;
        }
        byte[] bytes = SOURCE_BYTES.get(awtFont);
        if (bytes == null) {
            return null;
        }
        if (!SkijaNatives.ensureLoaded()) {
            return null;
        }
        try (Data data = Data.makeFromBytes(bytes)) {
            Typeface typeface = FontMgr.getDefault().makeFromData(data);
            if (typeface != null) {
                TYPEFACES.put(awtFont, typeface);
            }
            return typeface;
        } catch (Throwable t) {
            System.out.println("[MuoniumPlayer/SkijaText] 解析字体失败 "
                    + awtFont.getFontName() + ": " + t);
            return null;
        }
    }

    /** 仅供静态测试用。 */
    public static boolean isRegistered(java.awt.Font awtFont) {
        return SOURCE_BYTES.containsKey(awtFont);
    }

    static void dispose() {
        for (Typeface typeface : TYPEFACES.values()) {
            typeface.close();
        }
        TYPEFACES.clear();
    }
}
