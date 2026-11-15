package com.muoniumplayer.core.settings;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.File;

/**
 * 桌面歌词（屏幕下方那条 OSD 逐字歌词）的横向对齐方式，落在共享的
 * {@code config/muonium/desktop_lyrics.json}。
 *
 * <h3>为什么单独成文件</h3>
 * <p>对齐的消费方全在共享层：{@code MusicLyricsWidget}（经典样式，三条绘制路径：KTV 逐字、
 * 整行文本、译文）与 {@code BlossomLyricStyle}（毛玻璃卡片，逐字行与行级歌词各算各的行宽）。
 * 而 {@code HudConfig} 是七棵版本树各自一份的 FORK——加一个字段要七处同步，
 * 为的却只是一个渲染时的 {@code switch}。{@link TouchSettings} 与
 * {@code TransparentMode} 已经踩过同一条路：共享层的旋钮跟着实现走，落自己的小文件，
 * 见 {@link ConfigPaths#DESKTOP_LYRIC}。</p>
 *
 * <h3>取值词汇</h3>
 * <p>落盘与内存里都用 {@code Left / Center / Right}：这正是
 * {@code MusicLyricsWidget.alignmentX} 与 {@code calculateTextX} 一直在比的字符串。
 * 设置页只负责把它们翻译成「左靠 / 居中 / 右靠」，渲染路径一个字都不用改。
 * 默认 {@code Center}，与加这个设置之前的行为完全一致（老用户升级后画面不变）。</p>
 */
public final class DesktopLyricSetting {

    /** 三种对齐方式。{@code mode} 是渲染层认的字符串，{@code label} 是设置页显示的名字。 */
    public enum Align {
        LEFT("Left", "左靠"),
        CENTER("Center", "居中"),
        RIGHT("Right", "右靠");

        private final String mode;
        private final String label;

        Align(String mode, String label) {
            this.mode = mode;
            this.label = label;
        }

        /** 渲染层使用的对齐字符串：{@code Left / Center / Right}。 */
        public String mode() {
            return mode;
        }

        /** 设置页显示的中文名。 */
        public String label() {
            return label;
        }

        /**
         * 按渲染字符串取枚举；认不出来按 {@code Center} 处理。
         *
         * <p>配置是用户可手改的文本，坏值只该退化成默认对齐，不该让桌面歌词整条消失。</p>
         */
        public static Align ofMode(String mode) {
            if (mode != null) {
                for (Align align : values()) {
                    if (align.mode.equalsIgnoreCase(mode.trim())) {
                        return align;
                    }
                }
            }
            return CENTER;
        }

        /** 按设置页的中文名取枚举；认不出来返回 {@code null}（调用方决定是否忽略）。 */
        public static Align ofLabel(String label) {
            if (label != null) {
                for (Align align : values()) {
                    if (align.label.equals(label.trim())) {
                        return align;
                    }
                }
            }
            return null;
        }
    }

    private static final Gson GSON = new Gson();

    /** 全部可选对齐方式显示名，顺序即设置页下拉顺序：左靠 / 居中 / 右靠。 */
    private static final String[] LABELS = {"左靠", "居中", "右靠"};

    /** 当前对齐方式。默认居中：与引入这个设置之前的渲染结果一致。 */
    private static Align align = Align.CENTER;

    static {
        load();
    }

    private DesktopLyricSetting() {
    }

    public static Align get() {
        return align;
    }

    /** 渲染层直接用的对齐字符串（{@code Left / Center / Right}）。 */
    public static String modeName() {
        return align.mode();
    }

    /** 设置页显示用的当前中文名。 */
    public static String label() {
        return align.label();
    }

    /** 下拉的全部选项。 */
    public static String[] labels() {
        return LABELS.clone();
    }

    /** 设置对齐并立即落盘。设置页的每一次点选都该走到这里。 */
    public static void set(Align next) {
        if (next == null || next == align) {
            return;
        }
        align = next;
        save();
    }

    /** 按设置页的中文名设置；不认识的名字直接忽略。 */
    public static void setLabel(String label) {
        set(Align.ofLabel(label));
    }

    /**
     * 保存到 {@link ConfigPaths#DESKTOP_LYRIC}。
     *
     * <p>写失败不影响本次会话：对齐只是个渲染选项，为它抛异常只会把设置页一起弄崩。</p>
     */
    public static void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("align", align.mode());
            JsonConfigStorage.writeObject(ConfigPaths.DESKTOP_LYRIC, GSON, o);
        } catch (Throwable unwritable) {
            // 保存失败就当这次改动只在本次会话生效。
        }
    }

    /** 严格解析：只认 {@code Left / Center / Right}，其余返回 {@code null}。读档用。 */
    private static Align parseStrict(String mode) {
        if (mode != null) {
            for (Align candidate : Align.values()) {
                if (candidate.mode().equalsIgnoreCase(mode.trim())) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static void load() {
        File file = ConfigPaths.DESKTOP_LYRIC;
        if (!file.isFile()) {
            return;
        }
        try {
            JsonObject o = JsonConfigStorage.readObject(file, GSON);
            if (o == null) {
                return;
            }
            var element = o.get("align");
            if (element != null && element.isJsonPrimitive()) {
                // 只有认得出的值才覆盖默认：坏值 / 空值都退回居中，而不是把「坏」写进内存。
                Align parsed = parseStrict(element.getAsString());
                if (parsed != null) {
                    align = parsed;
                }
            }
        } catch (Throwable unreadable) {
            // 读不了就用默认居中继续跑。
        }
    }
}
