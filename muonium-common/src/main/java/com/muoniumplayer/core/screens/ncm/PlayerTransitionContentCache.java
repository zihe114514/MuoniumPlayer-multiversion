package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.texture.Textures;
import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaHost;
import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.RuntimeEffect;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.IRect;
import io.github.humbleui.types.Rect;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Short-lived snapshot of the already composited content viewport, not an isolated page layer.
 * Captures only stable frames; movement reuses those pixels without traversing the page again.
 * All native references belong to this cache and are created/released in the host render context.
 */
public final class PlayerTransitionContentCache {
    private static final PlayerTransitionContentCache INSTANCE = new PlayerTransitionContentCache();
    static final String MOTION_SHADER =
            "uniform shader source;\n"
            + "uniform float4 mapping;\n"
            + "uniform float4 viewport;\n"
            + "uniform float4 previous;\n"
            + "uniform float4 exposure;\n"
            + "half4 fetch(float2 p) {\n"
            + "  if (p.x < viewport.x || p.y < viewport.y || p.x > viewport.z || p.y > viewport.w) return half4(0);\n"
            + "  return source.eval(p * mapping.xy + mapping.zw);\n"
            + "}\n"
            + "half4 main(float2 p) {\n"
            + "  half4 color = fetch(p);\n"
            + "  if (exposure.y > 0.0) {\n"
            + "    float2 q = p * previous.x + previous.yz;\n"
            + "    color = color * 0.5 + fetch(mix(p, q, 0.5)) * 0.3 + fetch(q) * 0.2;\n"
            + "  }\n"
            + "  return color * half(exposure.x);\n"
            + "}\n";

    private final PlayerTransitionCachePolicy policy = new PlayerTransitionCachePolicy();
    private final ByteBuffer uniforms = ByteBuffer.allocate(64).order(ByteOrder.nativeOrder());
    private final Shader[] children = new Shader[1];
    private final PlayerIslandMotion.Sample current = new PlayerIslandMotion.Sample();
    private final PlayerIslandMotion.Sample previous = new PlayerIslandMotion.Sample();
    private Image image;
    private Surface snapshotSurface;
    private int snapshotWidth, snapshotHeight;
    private Shader source;
    private RuntimeEffect effect;
    private Object owner;
    private Object regularFont, boldFont;
    private Object context;
    private double x, y, width, height, captureScaleX, captureScaleY, captureTranslateX, captureTranslateY;
    private int left, top, themeColor, accentColor;
    private double guiScale;
    private long generation, uploadGeneration;
    private boolean armed, shaderUnavailable, cacheUnavailable;

    private PlayerTransitionContentCache() {
    }

    static PlayerTransitionContentCache get() {
        return INSTANCE;
    }

    /** Shutdown hook; call before the host releases its Surface/DirectContext. */
    public static void clearAll() {
        INSTANCE.clear();
        if (INSTANCE.effect != null) INSTANCE.effect.close();
        INSTANCE.effect = null;
        INSTANCE.context = null;
        INSTANCE.shaderUnavailable = false;
        INSTANCE.cacheUnavailable = false;
    }

    void clear() {
        releaseImage();
        if (this.snapshotSurface != null) this.snapshotSurface.close();
        this.snapshotSurface = null;
        this.snapshotWidth = this.snapshotHeight = 0;
        this.policy.reset();
    }

    private void releaseImage() {
        this.armed = false;
        if (this.source != null) this.source.close();
        this.source = null;
        if (this.image != null) this.image.close();
        this.image = null;
        this.children[0] = null;
        this.owner = null;
        this.regularFont = this.boldFont = null;
    }

    void arm(Object page, long contentGeneration) {
        this.armed = this.image != null && this.owner == page && this.generation == contentGeneration
                && this.policy.isFresh(System.nanoTime());
    }

    boolean isArmed() {
        return this.armed;
    }

    private boolean matches(Object page, double px, double py, double pw, double ph) {
        return this.image != null && this.owner == page && this.context == SkijaGfx.context()
                && this.guiScale == SkijaGfx.guiScale() && this.uploadGeneration == Textures.perfUploads
                && this.regularFont == FontManager.pf14 && this.boldFont == FontManager.pf14bold
                && Math.abs(this.x - px) < .01 && Math.abs(this.y - py) < .01
                && Math.abs(this.width - pw) < .01 && Math.abs(this.height - ph) < .01
                && this.themeColor == NCMTheme.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND)
                && this.accentColor == NCMTheme.getColor(NCMScreen.ColorType.ACCENT);
    }

    /** Called after the live page has drawn, before controls, queue and other overlays. */
    void capture(Object page, long contentGeneration, double px, double py, double pw, double ph) {
        if (this.cacheUnavailable) return;
        try {
            captureReadyContent(page, contentGeneration, px, py, pw, ph);
        } catch (RuntimeException failure) {
            this.cacheUnavailable = true;
            clear();
            System.err.println("[MuoniumPlayer/PlayerTransition] Content cache unavailable: " + failure);
        }
    }

    private void captureReadyContent(Object page, long contentGeneration, double px, double py, double pw, double ph) {
        if (!SkijaGfx.active() || SkijaGfx.context() == null || page == null || pw <= 1.0 || ph <= 1.0) return;
        long now = System.nanoTime();
        if (!this.policy.canCapture(now, 1, 1)) return;
        if (matches(page, px, py, pw, ph) && this.generation == contentGeneration
                && this.policy.isFresh(now)) return;
        Canvas canvas = SkijaGfx.canvas();
        float[] matrix = canvas.getLocalToDeviceAsMatrix33().getMat();
        if (!axisAligned(matrix)) return;
        int l = (int) Math.floor(px * matrix[0] + matrix[2]);
        int t = (int) Math.floor(py * matrix[4] + matrix[5]);
        int r = (int) Math.ceil((px + pw) * matrix[0] + matrix[2]);
        int b = (int) Math.ceil((py + ph) * matrix[4] + matrix[5]);
        if (!this.policy.canCapture(now, r - l, b - t)) return;
        int pixelWidth = r - l, pixelHeight = b - t;
        // Copy the short-lived host ROI into a bounded cache target, then release the Java
        // host snapshot. Skia's queued native references and actual COW costs still need profiling.
        this.releaseImage();
        if (this.snapshotSurface == null || this.snapshotWidth != pixelWidth
                || this.snapshotHeight != pixelHeight || this.context != SkijaGfx.context()) {
            if (this.snapshotSurface != null) this.snapshotSurface.close();
            this.snapshotSurface = Surface.makeRenderTarget(SkijaGfx.context(), true,
                    ImageInfo.makeN32Premul(pixelWidth, pixelHeight));
            this.snapshotWidth = pixelWidth;
            this.snapshotHeight = pixelHeight;
        }
        if (this.snapshotSurface == null) return;
        try (Image hostImage = SkijaHost.snapshotContent(IRect.makeLTRB(l, t, r, b));
             Paint copy = new Paint().setBlendMode(BlendMode.SRC)) {
            if (hostImage == null) return;
            this.snapshotSurface.getCanvas().clear(0);
            this.snapshotSurface.getCanvas().drawImageRect(hostImage,
                    Rect.makeXYWH(0, 0, pixelWidth, pixelHeight), copy);
        }
        Image next = this.snapshotSurface.makeImageSnapshot();
        if (next == null) return;
        this.image = next;
        this.source = next.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, null);
        this.children[0] = this.source;
        this.owner = page;
        this.regularFont = FontManager.pf14;
        this.boldFont = FontManager.pf14bold;
        this.context = SkijaGfx.context();
        this.guiScale = SkijaGfx.guiScale();
        this.generation = contentGeneration;
        this.uploadGeneration = Textures.perfUploads;
        this.x = px;
        this.y = py;
        this.width = pw;
        this.height = ph;
        this.captureScaleX = matrix[0];
        this.captureScaleY = matrix[4];
        this.captureTranslateX = matrix[2];
        this.captureTranslateY = matrix[5];
        this.left = l;
        this.top = t;
        this.themeColor = NCMTheme.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND);
        this.accentColor = NCMTheme.getColor(NCMScreen.ColorType.ACCENT);
        this.policy.captured(now);
    }

    boolean canDraw(Object page, long contentGeneration, double px, double py, double pw, double ph) {
        return this.armed && this.generation == contentGeneration && matches(page, px, py, pw, ph);
    }

    boolean draw(Object page, long contentGeneration, double px, double py, double pw, double ph, float opacity) {
        try {
            return drawReadyContent(page, contentGeneration, px, py, pw, ph, opacity);
        } catch (RuntimeException failure) {
            this.cacheUnavailable = true;
            clear();
            System.err.println("[MuoniumPlayer/PlayerTransition] Cached draw unavailable: " + failure);
            return false;
        }
    }

    private boolean drawReadyContent(Object page, long contentGeneration, double px, double py,
                                     double pw, double ph, float opacity) {
        if (!SkijaGfx.active() || !canDraw(page, contentGeneration, px, py, pw, ph)) {
            this.armed = false;
            return false;
        }
        long started = System.nanoTime();
        Canvas canvas = SkijaGfx.canvas();
        PlayerIslandTransition.sampleMotion(0.0, this.current);
        // The draw is already inside the current content transform. Sample the same texture
        // at a short previous exposure, including expansion/contraction, not just translation.
        double exposureMillis = Math.min(8.0, PlayerIslandTransition.lastFrameMillis() * .35);
        PlayerIslandTransition.sampleMotion(-exposureMillis, this.previous);
        double ratio = 1.0, dx = 0.0, dy = 0.0;
        if (this.previous.contentScale > .001) {
            ratio = this.current.contentScale / this.previous.contentScale;
            dx = (this.current.contentTranslateX - this.previous.contentTranslateX) / this.previous.contentScale;
            dy = (this.current.contentTranslateY - this.previous.contentTranslateY) / this.previous.contentScale;
        }
        double centerX = px + pw * .5, centerY = py + ph * .5;
        double shift = Math.hypot(centerX * (ratio - 1.0) + dx, centerY * (ratio - 1.0) + dy)
                + Math.abs(ratio - 1.0) * Math.max(pw, ph) * .5;
        double amount = Math.max(0.0, Math.min(1.0, (shift - .35) / 1.65));
        // Limit the exposure length rather than turning a missed frame into a long smear.
        if (shift > 6.0) {
            double cap = 6.0 / shift;
            ratio = 1.0 + (ratio - 1.0) * cap;
            dx *= cap;
            dy *= cap;
        }
        boolean soften = amount > .01 && this.policy.canSoften(
                (long) this.image.getWidth() * this.image.getHeight(), PlayerIslandTransition.lastFrameMillis());
        if (this.effect == null && !this.shaderUnavailable) {
            // Never compile a new effect in the motion frame. A missing prepared shader
            // uses the ordinary image path and preserves the animation.
            soften = false;
        }
        if (soften && this.effect != null) {
            this.children[0] = this.source;
            this.uniforms.clear();
            put(this.captureScaleX, this.captureScaleY,
                    this.captureTranslateX - this.left, this.captureTranslateY - this.top);
            put(px, py, px + pw, py + ph);
            put(1.0 + (ratio - 1.0) * amount, dx * amount, dy * amount, 0.0);
            put(Math.max(0f, Math.min(1f, opacity)), amount, 0.0, 0.0);
            try (Data data = Data.makeFromBytes(this.uniforms.array());
                 Shader shader = this.effect.makeShader(data, this.children)) {
                Paint paint = SkijaGfx.fillShader(shader);
                try {
                    paint.setBlendMode(BlendMode.SRC_OVER);
                    canvas.drawRect(Rect.makeXYWH((float) px, (float) py, (float) pw, (float) ph), paint);
                } finally {
                    paint.setShader(null);
                }
            }
        } else {
            Paint paint = SkijaGfx.fill(0xFFFFFFFF);
            paint.setAlpha(Math.max(0, Math.min(255, Math.round(opacity * 255f))));
            paint.setBlendMode(BlendMode.SRC_OVER);
            canvas.drawImageRect(this.image,
                    Rect.makeLTRB((float) (px * this.captureScaleX + this.captureTranslateX - this.left),
                            (float) (py * this.captureScaleY + this.captureTranslateY - this.top),
                            (float) ((px + pw) * this.captureScaleX + this.captureTranslateX - this.left),
                            (float) ((py + ph) * this.captureScaleY + this.captureTranslateY - this.top)),
                    Rect.makeXYWH((float) px, (float) py, (float) pw, (float) ph), SamplingMode.LINEAR, paint, true);
        }
        this.policy.observeComposite(System.nanoTime() - started);
        return true;
    }

    /** One-time preparation after content has settled, never in init or a motion frame. */
    void prepareEffect() {
        if (this.effect != null || this.shaderUnavailable || !SkijaGfx.active()) return;
        try {
            RuntimeEffect compiled = RuntimeEffect.makeForShader(MOTION_SHADER);
            if (compiled.getUniformSize() != this.uniforms.capacity()) {
                compiled.close();
                throw new IllegalStateException("Unexpected player motion uniform layout");
            }
            this.effect = compiled;
        } catch (RuntimeException failure) {
            this.shaderUnavailable = true;
            System.err.println("[MuoniumPlayer/PlayerTransition] Motion softening unavailable: " + failure);
        }
    }

    private void put(double a, double b, double c, double d) {
        this.uniforms.putFloat((float) a).putFloat((float) b).putFloat((float) c).putFloat((float) d);
    }

    static boolean axisAligned(float[] m) {
        if (m == null || m.length != 9) return false;
        for (float value : m) if (!Float.isFinite(value)) return false;
        return m[0] > 0f && m[4] > 0f && Math.abs(m[1]) < .0001f && Math.abs(m[3]) < .0001f
                && Math.abs(m[6]) < .0001f && Math.abs(m[7]) < .0001f && Math.abs(m[8] - 1f) < .0001f;
    }
}
