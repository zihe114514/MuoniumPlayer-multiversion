package today.opai.api.features;

import com.muoniumplayer.compat.mc.GuiScreen;

/**
 * 对应原项目 today.opai.api.features.ExtensionScreen。
 *
 * <p>1.8.9 时直接以原版 {@code net.minecraft.client.gui.GuiScreen} 为基类；1.20.1 换成
 * 移植层的 {@link GuiScreen}（它把 1.8.9 的 initGui/onGuiClosed/keyTyped/mouseClicked/
 * drawScreen 生命周期桥接到 1.20.1 的 Screen 上），因此 NCMScreen / CoverflowOverlay
 * 的覆写签名一行都不用改。
 */
public abstract class ExtensionScreen extends GuiScreen {

    /**
     * 原项目 NCMScreen / CoverflowOverlay 均覆写两参 drawScreen；此处加一层桥接，
     * 使上层代码无需改动即可保持原签名。
     *
     * 故这里不再向上调用。
     */
    @Override
    public final void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawScreen(mouseX, mouseY);
    }

    public void drawScreen(int mouseX, int mouseY) {
    }
}
