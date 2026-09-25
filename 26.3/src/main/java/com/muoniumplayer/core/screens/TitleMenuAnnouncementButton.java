package com.muoniumplayer.core.screens;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

/**
 * 主菜单上那颗「公告」按钮。
 *
 * <p>只有构建这一颗按钮是共享的，<b>挂上去的动作留在各加载器外壳里</b>：Fabric 用
 * {@code Screens.getButtons(screen).add(...)}（26.2 起那个方法改名 {@code getWidgets}），
 * Forge / NeoForge 用 {@code ScreenEvent.Init.Post#addListener(...)}。两条路都在 Screen
 * 自己 {@code init()} 完之后派发，所以窗口 resize、原版重排按钮之后我们会被再叫一次 ——
 * 原版重建时已经把旧组件清空了，不会越加越多。</p>
 *
 * <p>不用 mixin：两侧加载器本来就给了这个入口，而 {@code muonium.mixins.json} 里目前一条
 * 客户端 mixin 都没有，为一颗按钮开这个头不值得（{@code injectors.defaultRequire = 1} 之下，
 * 一个写错的目标方法名会让四个版本的玩家一起进不了游戏）。</p>
 */
public final class TitleMenuAnnouncementButton {

    /** 按钮文案。四个版本、两侧加载器共用一份，改名只改这里。 */
    public static final String LABEL = "公告";

    private static final int WIDTH = 100;
    private static final int HEIGHT = 20;
    /** 距屏幕底边的距离。 */
    private static final int BOTTOM_GAP = 6;

    private TitleMenuAnnouncementButton() {
    }

    /**
     * 这颗按钮该不该出现在这一屏上。
     *
     * <p>只认主菜单：暂停菜单、设置页、服务器列表上都已经有各自的出口，再摆一颗只会挡原版的东西。</p>
     */
    public static boolean appliesTo(Screen screen) {
        return screen instanceof TitleScreen;
    }

    /**
     * 建那颗按钮。位置取<b>底部正中</b>。
     *
     * <p>为什么是这里：原版那一列按钮（单人 / 多人 / 选项 / 退出）占的是从 {@code height / 4 + 32}
     * 往下的几行，它的下半段与中间列之间是空的；左下角那几行字是版本与加载器署名，右下角是语言与
     * 无障碍两颗图标按钮。四个 MC 版本里只有底部正中谁都不占，而且它不依赖原版那一列具体排到第几行
     * —— 那套偏移量每个版本都在变，跟着它算才是真会撞。</p>
     *
     * <p>尺寸按 GUI 缩放后的坐标算，所以读的是窗口自己的 {@code getGuiScaledWidth/Height}，
     * 不是 {@code Screen#width}：后者在部分版本上是受保护字段，而这两个取值方法四个版本都有。</p>
     */
    public static Button create() {
        Window window = Minecraft.getInstance().getWindow();
        int left = window.getGuiScaledWidth() / 2 - WIDTH / 2;
        int top = window.getGuiScaledHeight() - HEIGHT - BOTTOM_GAP;
        return Button.builder(Component.literal(LABEL),
                button -> GuiAnnouncements.open(Minecraft.getInstance().gui.screen()))
                .bounds(left, top, WIDTH, HEIGHT)
                .build();
    }
}
