package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.management.UiFont;
import com.muoniumplayer.core.management.UiFontLibrary;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;

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

    private static Boolean available;
    private static boolean failed;
    private static Object installedSource;
    private static String installedId;

    private MuoniumFontBridge() {
    }

    /** MuoniumConfig 的字体覆盖 API 是否存在。 */
    public static boolean isAvailable() {
        if (available != null) return available;
        try {
            Class.forName(SOURCE_CLASS, false, MuoniumFontBridge.class.getClassLoader());
            available = Boolean.TRUE;
        } catch (Throwable absent) {
            available = Boolean.FALSE;
        }
        return available;
    }

    /**
     * 按播放器当前字体刷新 MuoniumConfig 的界面字体。
     *
     * <p>在字体切换成功后调一次即可。</p>
     *
     * <p><b>只在启动时接管一次，之后不再跟随热切换</b>：MuoniumConfig 是个独立的 Compose 应用，
     * 它的字体缓存以 identity 为键，每次热切换都要把整屏文字重新栅格化；而玩家在设置界面里
     * 换字体时，屏幕正被那个界面占着 —— 换的那一帧字体表是空的，整屏变方块，且不一定会自己
     * 恢复（实测就是这个现象）。设置界面不跟随换字体，比换字体时界面短暂变空白要好得多。</p>
     *
     * @return 是否成功接上（MuoniumConfig 不存在时返回 false，属于正常路径）
     */
    public static boolean sync() {
        if (!isAvailable() || failed) return false;
        try {
            // 已经接管过就不再动：见上面的说明，热切换会让设置界面在切换那一帧失去字形。
            if (installedSource != null) return true;

            UiFont active = UiFontLibrary.getActive();
            // 没有汉字的字体不接管：设置界面里几乎全是中文，换上去就是整屏方块。
            // 让 MuoniumConfig 保持它自带的那份（同样含 CJK），界面照常可读。
            if (active != null && !active.hasCjk()) {
                return false;
            }
            install(active, describe(active));
            return true;
        } catch (Throwable t) {
            failed = true;
            return false;
        }
    }

    /** 把名字写进 id：换字体时 id 变化，MuoniumConfig 那边的缓存键随之失效。 */
    private static String describe(UiFont font) {
        if (font == null) return "builtin";
        String name = font.getDisplayName();
        if (name == null || name.isEmpty()) name = font.getId();
        // 文件型字体带上最后修改时间：玩家原地替换同名文件时也能换掉
        File file = font.getFile();
        if (file != null && file.isFile()) {
            return name + ":" + file.length() + ":" + file.lastModified();
        }
        return name;
    }

    private static void install(UiFont font, String id) throws Exception {
        ClassLoader loader = MuoniumFontBridge.class.getClassLoader();
        Class<?> sourceType = Class.forName(SOURCE_CLASS, true, loader);
        Class<?> overrideType = Class.forName(OVERRIDE_CLASS, true, loader);

        byte[] regular = readFontBytes(font, false);
        if (regular == null) {
            // 拿不到字节就别覆盖：保持 MuoniumConfig 自带字体，界面至少不会变豆腐块
            return;
        }
        byte[] bold = readFontBytes(font, true);

        Object source = Proxy.newProxyInstance(
                sourceType.getClassLoader(),
                new Class<?>[]{sourceType},
                (proxy, method, args) -> answer(method, args, id, regular, bold));

        Method set = overrideType.getMethod("set", sourceType);
        set.invoke(null, source);

        installedSource = source;
        installedId = id;
    }

    private static Object answer(Method method, Object[] args, String id, byte[] regular, byte[] bold) {
        switch (method.getName()) {
            case "id":
                return id;
            case "regular":
                return regular;
            case "medium":
                return regular;
            case "semibold":
                return bold != null ? bold : regular;
            case "bold":
                return bold != null ? bold : regular;
            case "hashCode":
                return System.identityHashCode(id);
            case "equals":
                return args != null && args.length == 1 && id.equals(String.valueOf(args[0]));
            case "toString":
                return "MuoniumFontSource[" + id + "]";
            default:
                // FontSource 上没有需要真跑的 default 方法：四个字重与 id() 都已显式返回
                return null;
        }
    }

    private static void bumpRevision() {
        try {
            Class<?> overrideType = Class.forName(OVERRIDE_CLASS, true, MuoniumFontBridge.class.getClassLoader());
            overrideType.getMethod("bumpRevision").invoke(null);
        } catch (Throwable ignored) {
        }
    }

    /** 回到 MuoniumConfig 自带字体（播放器切回「内置字体」时用）。 */
    public static void clear() {
        installedSource = null;
        installedId = null;
        try {
            Class<?> overrideType = Class.forName(OVERRIDE_CLASS, true, MuoniumFontBridge.class.getClassLoader());
            overrideType.getMethod("clear").invoke(null);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 读当前字体对应字重文件的字节。
     *
     * <p>文件型字体直接读；系统字体与内置字体用 AWT 定位能拿到的字体文件，拿不到就返回 null
     * （调用方据此退回 MuoniumConfig 自带字体）。</p>
     */
    private static byte[] readFontBytes(UiFont font, boolean wantBold) {
        try {
            File direct = font != null ? font.getFile() : null;
            if (direct != null && direct.isFile()) {
                if (!wantBold) return Files.readAllBytes(direct.toPath());
                // 单文件字体没有独立粗体，拿同一份让 UI 用合成粗体
                return null;
            }
            return FontManager.fontBytesForUi(font, wantBold);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }
}
