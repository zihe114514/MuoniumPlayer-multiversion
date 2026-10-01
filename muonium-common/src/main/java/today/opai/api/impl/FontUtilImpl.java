package today.opai.api.impl;

import com.muoniumplayer.compat.mc.FontRenderer;
import today.opai.api.interfaces.render.Font;
import today.opai.api.interfaces.render.FontUtil;

public class FontUtilImpl implements FontUtil {

    private Font vanillaFont;

    @Override
    public Font getVanillaFont() {
        if (vanillaFont == null) {
            // 1.8.9: new VanillaFont(Minecraft.getMinecraft().fontRendererObj)
            vanillaFont = new VanillaFont(FontRenderer.vanilla());
        }
        return vanillaFont;
    }
}
