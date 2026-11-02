package com.muoniumplayer.core.utils;

/**
 * Swing 模态对话框在 Minecraft 进程里的可用性闸。
 *
 * <h2>为什么需要这一层</h2>
 * Minecraft 在 {@code net.minecraft.client.main.Main} 的<b>静态初始化器</b>里写死了
 * {@code System.setProperty("java.awt.headless", "true")}（26.3 的 Main.class 字节码可证，
 * 1.20.1～26.2 同款）。headless 模式下 {@code JFileChooser#showOpenDialog} 与一切
 * {@code Window} 的实时化都会抛 {@code HeadlessException}——「点按钮弹系统选择器」
 * 这类功能整条死掉，而且异常发生在我们自己的点击回调里，表现只是「点了没反应」。
 *
 * <p>Windows / macOS 的桌面上 AWT 其实完全能起窗口（LWJGL 与 AWT 各管各的顶层窗口，
 * 互不冲突）；这个属性只是原版为了无显示器服务器场景提前短路 AWT。所以做法是：
 * 弹对话框的<b>那一刻</b>把属性临时清掉，弹完立刻恢复原值。属性只在
 * {@code Toolkit} 首次初始化时被读取，而游戏进程里在弹第一个对话框之前 AWT 从未被
 * 触碰过（模组不建 AWT 窗口），所以这次切换对本次弹窗是决定性的，恢复只是为了
 * 不留全局副作用。</p>
 *
 * <p><b>只在主线程（渲染线程）调用</b>：模态对话框要在弹起期间泵 AWT 事件，
 * 与 OpenGL 帧循环并发泵事件是未定义行为；既有调用点（HUD 设置、建单页）本来
 * 就跑在主线程上。</p>
 *
 * <p>无桌面环境（真 headless，比如 SSH 无显示）下 {@code createHeadful} 仍会抛
 * {@code HeadlessException}——调用方按「用户没选」收场即可，那正是本来就该有的退化。</p>
 */
public final class SwingDialogs {

    private SwingDialogs() {
    }

    /**
     * 在「临时 headful」窗口里跑一次 Swing 对话框。
     *
     * @param dialog 把 {@code chooser.showOpenDialog(null)} 之类包进来的调用体
     * @return 对话框自己的返回值；AWT 起不来（真 headless）时返回 {@code fallback}
     */
    public static <T> T withHeadful(java.util.function.Supplier<T> dialog, T fallback) {
        final String previous = System.getProperty("java.awt.headless");
        try {
            System.setProperty("java.awt.headless", "false");
            return dialog.get();
        } catch (Throwable headless) {
            // 真 headless 环境（无显示器/无桌面）：没有系统选择器可弹。
            // 返回 fallback 让调用方走「用户没选」的同一条收尾，不打断游戏。
            System.err.println("[SwingDialogs] AWT dialog unavailable (headless?): " + headless);
            return fallback;
        } finally {
            if (previous == null) {
                System.clearProperty("java.awt.headless");
            } else {
                System.setProperty("java.awt.headless", previous);
            }
        }
    }
}
