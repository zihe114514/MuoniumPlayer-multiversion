package com.muoniumplayer.compat.mc;

import com.muoniumplayer.core.lyrics3d.GlowBloom;
import com.muoniumplayer.core.lyrics3d.MaskQuadCollector;

import net.minecraft.client.Minecraft;

import org.joml.Matrix4f;

/**
 * 辉光后处理调度（HUD 直画与世界阶段共用）：一次 renderInner 同时产出清晰
 * 本体（原样进缓冲、endBatch 上屏）与 mask（字形 quad 双写进
 * {@link MaskQuadCollector}），随后 bloom 加色垫到最终画面 —— 复合是加法，
 * 与本体绘制先后无关，观感即「晕垫在字下、字芯被能量推白」。mask 空 /
 * 参数未配置 / 管线熔断时整段零成本跳过，行为与无辉光一致。
 *
 * <p>两个调用场景：HUD 直画（光影 + 更激进的适配，画在 Iris 收工后的最终
 * 画面）与世界阶段（世界渲染结束事件，无光影或关掉更激进的适配 —— 此时
 * LAST 事件之后只剩云/天气，晕和字层序自洽）。两处用的都是「画完本体后
 * 加色补能量」，本体的可见性不受影响。</p>
 */
final class HudBloomPass {

    private final MaskQuadCollector mask = new MaskQuadCollector();
    private BloomComposite bloom;
    private boolean collecting;

    /** renderInner 前调：装上 mask 采集。 */
    void beginPass() {
        collecting = true;
        mask.beginFrame();
        WorldBillboard.INSTANCE.beginGlowMask(mask);
    }

    /** renderInner 后调：跑 bloom 并复合到当前帧缓冲（调用方处于直画最终画面窗口内）。 */
    void endPass(Minecraft mc) {
        endPass(mc, null);
    }

    /**
     * 投影显式传入版：世界阶段（LAST 事件）的 bob 防抖已把 RenderSystem 投影
     * 换成纯 fov 透视，与本体 endBatch 同源 —— 直接读即可；但 1.21.11 这类
     * 投影走 GpuBufferSlice 的版本拿不到 Matrix4f，由调用方给。
     */
    void endPass(Minecraft mc, org.joml.Matrix4f explicitProjection) {
        WorldBillboard.INSTANCE.endGlowMask();
        collecting = false;
        if (bloom == null) bloom = new BloomComposite();
        GlowBloom.Params p = GlowBloom.take();
        if (p == null || mask.quadCount() == 0 || bloom.failed()) return;
        try {
            Minecraft m = mc;
            if (m == null || m.getWindow() == null) return;
            int w = m.getWindow().getWidth();
            int h = m.getWindow().getHeight();
            if (w <= 0 || h <= 0) return;
            // mask 顶点已含 basePose×billboard；模型视图栈此刻是单位矩阵，
            // MVP = 当前投影（与本体 endBatch 用的同一矩阵，逐字节对齐）
            Matrix4f mvp = new Matrix4f(explicitProjection != null
                    ? explicitProjection
                    : com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix());
            bloom.run(mask.floats(), mask.quadCount(), mvp, w, h,
                    ((p.tintRgb >>> 16) & 0xFF) / 255f,
                    ((p.tintRgb >>> 8) & 0xFF) / 255f,
                    (p.tintRgb & 0xFF) / 255f,
                    p.exposure, p.spread,
                    // 覆盖率归一参考点：满句 avg≈0.056 → boost≈2.1，配合曝光
                    // 量程 100 让满句晕外圈到 ~30% 亮度增量（对齐上游默认观感）；
                    // 稀疏帧（逐字出现期）封顶 4× 不糊。
                    0.12f);
        } catch (Throwable t) {
            WorldLyricsBridge.traceOnce("辉光后处理异常（本帧跳过）: " + t);
        }
    }

    boolean active() {
        return collecting;
    }
}
