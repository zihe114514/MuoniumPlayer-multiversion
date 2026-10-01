package com.muoniumplayer.core.management;

import java.io.File;

/**
 * 一款可以选来当界面字体的字体。
 *
 * <p>只描述「有哪些可选」，不碰加载与生效——那些在 {@link UiFontLibrary} 里。分成三种来源：</p>
 * <ul>
 *   <li>{@link Origin#BUILTIN}：随 mod 打包的 SF Pro + 苹方，永远可用，是所有回退的终点；</li>
 *   <li>{@link Origin#SYSTEM}：系统里装着的字体族。<b>不落到文件</b>——Windows 上 Skia 的默认
 *       {@code FontMgr} 就是 DirectWrite，按族名要 typeface 即可，用不着知道字体文件在哪；</li>
 *   <li>{@link Origin#EXTERNAL}：{@code config/muonium/fonts/} 里的字体文件，用户自己丢进去的
 *       或者通过「选择字体文件」导入的。</li>
 * </ul>
 *
 * <p>{@link #getId()} 是持久化用的键，带来源前缀。系统字体记族名而不是文件路径：同一款字体在
 * 不同机器上的文件名不一样（甚至同一台机器升级系统后也会变），族名才是稳定的。</p>
 */
public final class UiFont {

    public enum Origin {
        BUILTIN,
        SYSTEM,
        EXTERNAL
    }

    /** 内置字体这一项是常量：它不需要枚举、不会失效，也是「恢复默认」的目标。 */
    public static final UiFont BUILTIN =
            new UiFont(Origin.BUILTIN, "builtin", "内置字体", null, null).mark(true, true, true);

    private final Origin origin;
    private final String id;
    private final String displayName;
    /** 系统字体族名（Skia / DirectWrite 认的那个，一般是英文）。其它来源为 null。 */
    private final String family;
    /** 外部字体文件。其它来源为 null。 */
    private final File file;

    /** 有拉丁字形。没有的话界面上的数字和英文会整段落到内置回退字体。 */
    private boolean latin = true;
    /**
     * 有汉字字形。
     *
     * <p>列表里必须标出来：中文用户挑到一款纯拉丁字体时，界面上的汉字会全部落到内置回退字体，
     * 于是「换了字体但中文没变」，看着像功能坏了。标一下就变成一句可以理解的说明。</p>
     */
    private boolean cjk;
    /** 这一族里有真的粗体字重。没有的话粗体位置由 Skia 描边假粗顶上。 */
    private boolean bold;

    private UiFont(Origin origin, String id, String displayName, String family, File file) {
        this.origin = origin;
        this.id = id;
        this.displayName = displayName;
        this.family = family;
        this.file = file;
    }

    /** 系统里装着的一款字体族。{@code displayName} 用本地化名字（如「微软雅黑」），族名留英文。 */
    static UiFont system(String family, String displayName) {
        return new UiFont(Origin.SYSTEM, "system:" + family,
                displayName == null || displayName.isEmpty() ? family : displayName, family, null);
    }

    /** 一个字体文件。id 只记文件名：目录是固定的，记全路径会让配置跟着游戏目录搬家而失效。 */
    static UiFont external(File file, String displayName) {
        String name = file.getName();
        return new UiFont(Origin.EXTERNAL, "file:" + name,
                displayName == null || displayName.isEmpty() ? name : displayName, null, file);
    }

    UiFont mark(boolean latin, boolean cjk, boolean bold) {
        this.latin = latin;
        this.cjk = cjk;
        this.bold = bold;
        return this;
    }

    public Origin getOrigin() {
        return origin;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getFamily() {
        return family;
    }

    public File getFile() {
        return file;
    }

    public boolean isBuiltin() {
        return origin == Origin.BUILTIN;
    }

    public boolean hasLatin() {
        return latin;
    }

    public boolean hasCjk() {
        return cjk;
    }

    public boolean hasBold() {
        return bold;
    }

    /** 列表行第二行的说明：字重与字符覆盖，都是「选了会不会出乎意料」的信息。 */
    public String describe() {
        if (origin == Origin.BUILTIN) {
            return "SF Pro + 苹方，随 mod 打包";
        }
        StringBuilder text = new StringBuilder();
        text.append(cjk ? "含中文" : "无中文（中文走内置字体）");
        text.append(bold ? " · 有粗体" : " · 粗体由描边模拟");
        if (origin == Origin.EXTERNAL && file != null) {
            text.append(" · ").append(file.getName());
        }
        return text.toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof UiFont && id.equals(((UiFont) other).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return id;
    }
}