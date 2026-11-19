package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.management.UiFont;
import com.muoniumplayer.core.management.UiFontLibrary;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 让 MuoniumConfig 的设置界面跟随播放器当前选中的字体，并且**热切换**。
 *
 * <p>MUSIC 的字体体系支持三种来源（内置 / 系统字体族 / {@code config/muonium/fonts/} 里丢的文件），
 * 玩家可以在界面里随时换。MuoniumConfig 是 Compose 应用，它自己有 Poppins；如果不接这条线，
 * 玩家换完字体后设置页仍是另一套字，看着像没生效。</p>
 *
 * <p>热切换的关键在 MuoniumConfig 的 {@code UiFontOverride}：它把字体数据的缓存键做成
 * {@code "muonium-ui:<id>:<revision>:<weight>"}，换字体时递一个新的 id 或调
 * {@code bumpRevision()}，缓存键就变了，下一帧重新读字节。**不需要重启游戏，也不需要把字体
 * 打包进 jar** —— 每次都是现读宿主那边的字体文件。</p>
 *
 * <p>与 HUD 接入一样走纯反射：MuoniumConfig 是可选依赖，编译期挂上会让没装的玩家加载失败。</p>
 */
public final class MuoniumFontBridge {

    private static final String OVERRIDE_CLASS = "org.polyfrost.oneconfig.api.ui.v1.UiFontOverride";
    private static final String SOURCE_CLASS = OVERRIDE_CLASS + "$FontSource";

    private static volatile Boolean available;
    private static volatile Object installedSource;
    private static volatile FontSnapshot snapshot;
    private static String lastWarning;

    private static final class FontSnapshot {
        final String id;
        final byte[] regular;
        final byte[] bold;
        final byte[] fallback;

        FontSnapshot(String id, byte[] regular, byte[] bold, byte[] fallback) {
            this.id = id;
            this.regular = regular;
            this.bold = bold;
            this.fallback = fallback;
        }

        boolean matches(FontSnapshot other) {
            return id.equals(other.id)
                    && java.util.Arrays.equals(regular, other.regular)
                    && java.util.Arrays.equals(bold, other.bold)
                    && java.util.Arrays.equals(fallback, other.fallback);
        }
    }

    private MuoniumFontBridge() {
    }

    /** MuoniumConfig 的字体覆盖 API 是否存在。 */
    public static boolean isAvailable() {
        if (Boolean.TRUE.equals(available)) return true;
        try {
            Class.forName(SOURCE_CLASS, false, MuoniumFontBridge.class.getClassLoader());
            available = Boolean.TRUE;
        } catch (Throwable absent) {
            available = Boolean.FALSE;
        }
        return Boolean.TRUE.equals(available);
    }

    /**
     * Keeps one provider installed while resolving the currently selected font on demand.
     * The host calls this after selection changes so Compose can invalidate its font families.
     */
    public static boolean sync() {
        if (!isAvailable()) return false;
        try {
            UiFont active = UiFontLibrary.getActive();
            String id = describe(active);
            byte[] regular = FontManager.uiFontBytesForBridge(false);
            byte[] bold = active != null && !active.isBuiltin() && !active.hasBold()
                    ? null : FontManager.uiFontBytesForBridge(true);
            boolean needsCjkFallback = active == null || active.isBuiltin() || !active.hasCjk();
            byte[] fallback = needsCjkFallback ? FontManager.builtinUiFallbackBytes() : null;
            if (!validFont(regular) || (needsCjkFallback && !validFont(fallback))) {
                if (installedSource != null) {
                    clearOverride("当前字体无法提供可读字节；设置界面退回主题默认字体");
                    installedSource = null;
                    snapshot = null;
                } else {
                    warnOnce("当前字体无法提供可读字节或缺少 CJK fallback；保留 MuoniumConfig 默认字体", null);
                }
                return false;
            }
            FontSnapshot next = new FontSnapshot(id, regular, validFont(bold) ? bold : null, fallback);

            if (installedSource != null) {
                FontSnapshot current = snapshot;
                if (current == null || !current.matches(next)) {
                    snapshot = next;
                    bumpRevision();
                    lastWarning = null;
                }
                return true;
            }

            ClassLoader loader = MuoniumFontBridge.class.getClassLoader();
            Class<?> sourceType = Class.forName(SOURCE_CLASS, true, loader);
            Class<?> overrideType = Class.forName(OVERRIDE_CLASS, true, loader);
            snapshot = next;
            Object source = Proxy.newProxyInstance(sourceType.getClassLoader(), new Class<?>[]{sourceType},
                    (proxy, method, args) -> answer(proxy, method, args));
            overrideType.getMethod("set", sourceType).invoke(null, source);
            installedSource = source;
            lastWarning = null;
            return true;
        } catch (Throwable t) {
            warnOnce("MuoniumConfig 字体桥安装失败；保留设置界面默认字体", t);
            clearOverride(null);
            installedSource = null;
            snapshot = null;
            return false;
        }
    }

    private static Object answer(Object proxy, Method method, Object[] args) {
        FontSnapshot current = snapshot;
        switch (method.getName()) {
            case "id":
                return current == null ? "muonium:unavailable" : current.id;
            case "regular":
                return current == null ? null : current.regular;
            case "medium":
                return current == null ? null : current.regular;
            case "semibold":
                return current == null ? null : current.bold;
            case "bold":
                return current == null ? null : current.bold;
            case "fallback":
                return current == null ? null : current.fallback;
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return args != null && args.length == 1 && proxy == args[0];
            case "toString":
                return "MuoniumFontSource[dynamic]";
            default:
                return null;
        }
    }

    private static void bumpRevision() {
        try {
            Class<?> overrideType = Class.forName(OVERRIDE_CLASS, true, MuoniumFontBridge.class.getClassLoader());
            overrideType.getMethod("bumpRevision").invoke(null);
        } catch (Throwable t) {
            warnOnce("MuoniumConfig 字体 revision 刷新失败", t);
        }
    }

    private static void clearOverride(String reason) {
        snapshot = null;
        try {
            Class<?> overrideType = Class.forName(OVERRIDE_CLASS, true, MuoniumFontBridge.class.getClassLoader());
            overrideType.getMethod("clear").invoke(null);
        } catch (Throwable t) {
            if (reason != null) warnOnce(reason, t);
        }
        if (reason != null) warnOnce(reason, null);
    }

    private static void warnOnce(String message, Throwable cause) {
        if (message.equals(lastWarning)) return;
        lastWarning = message;
        if (cause == null) System.out.println("[MuoniumPlayer/OneConfigFont] " + message);
        else {
            System.out.println("[MuoniumPlayer/OneConfigFont] " + message + ": " + cause);
            cause.printStackTrace(System.out);
        }
    }

    private static boolean validFont(byte[] bytes) {
        if (bytes == null || bytes.length < 12) return false;
        return (bytes[0] == 0 && bytes[1] == 1 && bytes[2] == 0 && bytes[3] == 0)
                || (bytes[0] == 'O' && bytes[1] == 'T' && bytes[2] == 'T' && bytes[3] == 'O')
                || (bytes[0] == 't' && bytes[1] == 'r' && bytes[2] == 'u' && bytes[3] == 'e')
                || (bytes[0] == 't' && bytes[1] == 't' && bytes[2] == 'c' && bytes[3] == 'f');
    }

    private static String describe(UiFont font) {
        if (font == null) return "builtin";
        StringBuilder id = new StringBuilder(font.getId());
        File file = font.getFile();
        if (file != null) {
            id.append(':').append(file.length()).append(':').append(file.lastModified());
            File bold = boldCompanion(file);
            if (bold != null) id.append(':').append(bold.length()).append(':').append(bold.lastModified());
        }
        return id.toString();
    }

    private static File boldCompanion(File regular) {
        String name = regular.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot);
        String ext = dot < 0 ? "" : name.substring(dot);
        String lower = stem.toLowerCase(java.util.Locale.ROOT);
        String base = lower.endsWith("-regular") ? stem.substring(0, stem.length() - 8)
                : lower.endsWith(" regular") ? stem.substring(0, stem.length() - 8) : stem;
        File dir = regular.getParentFile();
        if (dir == null) return null;
        for (String candidate : new String[]{base + "-Bold" + ext, base + "-bold" + ext, base + " Bold" + ext, stem + "bd" + ext}) {
            File file = new File(dir, candidate);
            if (file.isFile() && !file.equals(regular)) return file;
        }
        return null;
    }

    public static void clear() {
        clearOverride(null);
        installedSource = null;
    }
}
