package com.muoniumplayer.core.rendering.ui.widgets;

import lombok.Getter;
import lombok.Setter;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.texture.ITextureObject;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.utils.Location;

import java.util.function.Supplier;

/**
 * @author MuoniumPlayer Contributors
 * Date: 2025/9/30 15:11
 */
public class RoundedImageWidget extends AbstractWidget<RoundedImageWidget> {

    @Getter
    @Setter
    private Supplier<Location> locImg;

    @Getter
    private double radius = 0;

    boolean fadeIn = false;

    @Getter
    boolean linearFilter = false;

    public RoundedImageWidget(Supplier<Location> locImg, double x, double y, double width, double height) {
        this.setBounds(x, y, width, height);
        this.locImg = locImg;
    }

    public RoundedImageWidget(Location locImg, double x, double y, double width, double height) {
        this(() -> locImg, x, y, width, height);
    }

    public RoundedImageWidget(double x, double y, double width, double height) {
        this(() -> null, x, y, width, height);
    }

    public RoundedImageWidget fadeIn() {
        fadeIn = true;
        this.setAlpha(0);
        return this;
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        Location img = locImg.get();

        if (img == null)
            return;

        ITextureObject textureObject = TextureManager.getInstance().getTexture(img);

        if (textureObject == null)
            return;

        if (fadeIn && this.getWidgetAlpha() < 1.0f) {
            // 渐显只在自己还没满亮时推进：到位之后每帧一次 setAlpha（内部 new Color）
            // 是纯白花的分配。用 0.25f 的步长，纹理就绪后约 6 帧淡满，与同批封面几乎同时到齐。
            float next = Interpolations.interpolate(this.getWidgetAlpha(), 1.0f, 0.25f);
            if (next > 0.99f) next = 1.0f;
            this.setAlpha(next);
        }

        api.getGLStateManager().color(1, 1, 1, this.getAlpha());
        api.getGLStateManager().bindTexture(textureObject.getGlTextureId());

        if (this.isLinearFilter())
            textureObject.linearFilter();
        else
            textureObject.nearestFilter();

        this.roundedRectTextured(this.getX(), this.getY(), this.getWidth(), this.getHeight(), this.getRadius(), this.getAlpha());
    }

    public RoundedImageWidget setRadius(double radius) {
        this.radius = radius;
        return this;
    }

    public RoundedImageWidget setLinearFilter(boolean linearFilter) {
        this.linearFilter = linearFilter;
        return this;
    }
}
