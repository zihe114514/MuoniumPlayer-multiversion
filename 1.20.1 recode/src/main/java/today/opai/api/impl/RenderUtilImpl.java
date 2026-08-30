package today.opai.api.impl;

import today.opai.api.interfaces.render.RenderUtil;

import java.awt.Color;
import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaShapes;

public class RenderUtilImpl implements RenderUtil {

    @Override
    public void drawRect(double x, double y, double width, double height, Color color) {
        SkijaShapes.rect(x, y, x + width, y + height, SkijaGfx.argb(color));
    }
}
