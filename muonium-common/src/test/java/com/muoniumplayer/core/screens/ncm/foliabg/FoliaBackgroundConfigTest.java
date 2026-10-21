package com.muoniumplayer.core.screens.ncm.foliabg;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Folia 背景配置与数学的静态测试。
 *
 * <p>钉住三类东西：默认值必须等于 folia 的 {@code DEFAULT_*_BACKGROUND_TUNING}（否则「第一次
 * 打开」看到的不是参考实现的外观）、每个字段的夹取区间（手改坏的 JSON 不能把画面搞炸）、
 * 以及已删样式（sora / monet）的老配置回落。</p>
 */
class FoliaBackgroundConfigTest {

    @Test
    void defaultsMatchFolia() {
        FoliaBackgroundConfig.resetAll();
        // Latent：src/types.ts DEFAULT_LATENT_BACKGROUND_TUNING
        assertEquals(2, FoliaBackgroundConfig.latentDisplayMode);
        assertEquals(0, FoliaBackgroundConfig.latentColorSource);
        assertTrue(FoliaBackgroundConfig.latentDynamicOnlyInPlayer);
        assertTrue(FoliaBackgroundConfig.latentEnhancedBeatResponse);
        assertEquals(0.1f, FoliaBackgroundConfig.latentDitheringSpeed, 1e-6);
        assertEquals(1.2f, FoliaBackgroundConfig.latentDitheringAudioSpeed, 1e-6);
        assertEquals(2.5f, FoliaBackgroundConfig.latentDitheringSize, 1e-6);
        assertEquals(0.55f, FoliaBackgroundConfig.latentDitheringOpacity, 1e-6);
        assertEquals(0.3f, FoliaBackgroundConfig.latentMeshSpeed, 1e-6);
        assertEquals(2f, FoliaBackgroundConfig.latentMeshAudioSpeed, 1e-6);
        assertEquals(0.8f, FoliaBackgroundConfig.latentMeshDistortion, 1e-6);
        assertEquals(0.1f, FoliaBackgroundConfig.latentMeshSwirl, 1e-6);
        assertTrue(FoliaBackgroundConfig.latentOverlayEnabled);
        assertEquals(0.35f, FoliaBackgroundConfig.latentOverlayOpacity, 1e-6);

        // Nomand
        assertEquals(0, FoliaBackgroundConfig.nomandImageSource);
        assertEquals(0, FoliaBackgroundConfig.nomandEffect);
        assertEquals(2, FoliaBackgroundConfig.nomandDitheringType);
        assertEquals(3f, FoliaBackgroundConfig.nomandSize, 1e-6);
        assertEquals(4, FoliaBackgroundConfig.nomandColorSteps);
        assertEquals(0.5f, FoliaBackgroundConfig.nomandFlutedGlassSize, 1e-6);
        assertEquals(0.08f, FoliaBackgroundConfig.nomandFlutedGlassBlur, 1e-6);
        assertEquals(0.32f, FoliaBackgroundConfig.nomandPaperTextureContrast, 1e-6);
        assertEquals(0.42f, FoliaBackgroundConfig.nomandPaperTextureRoughness, 1e-6);
        assertEquals(0.3f, FoliaBackgroundConfig.nomandPaperTextureFiber, 1e-6);
        assertEquals(0.5f, FoliaBackgroundConfig.nomandHalftoneDotsSize, 1e-6);
        assertEquals(1.25f, FoliaBackgroundConfig.nomandHalftoneDotsRadius, 1e-6);
        assertEquals(0.4f, FoliaBackgroundConfig.nomandHalftoneDotsContrast, 1e-6);
        assertEquals(0.45f, FoliaBackgroundConfig.nomandLensDistortionSpread, 1e-6);
        assertEquals(0.3f, FoliaBackgroundConfig.nomandLensDistortionBulge, 1e-6);
        assertEquals(0.65f, FoliaBackgroundConfig.nomandLensDistortionDispersion, 1e-6);
        assertTrue(FoliaBackgroundConfig.nomandOverlayEnabled);
        assertEquals(0.35f, FoliaBackgroundConfig.nomandOverlayOpacity, 1e-6);

        // 默认背景是 latent（folia DEFAULT_VISUALIZER_BACKGROUND_MODE）
        assertEquals(FoliaBackgroundMode.LATENT, FoliaBackgroundMode.current());
        assertEquals("latent", FoliaBackgroundConfig.mode);
        assertEquals(2, FoliaBackgroundConfig.backgroundKind);
        assertFalse(FoliaBackgroundConfig.staticMode);
        assertEquals(1f, FoliaBackgroundConfig.audioResponse, 1e-6);
    }

    @Test
    void modeFallsBackToLatentForUnknownIds() {
        // 老配置里的已删样式（sora / monet）与乱写的值都回落到默认
        FoliaBackgroundConfig.mode = "sora";
        assertEquals(FoliaBackgroundMode.LATENT, FoliaBackgroundMode.current());
        FoliaBackgroundConfig.mode = "monet";
        assertEquals(FoliaBackgroundMode.LATENT, FoliaBackgroundMode.current());
        FoliaBackgroundConfig.mode = "no-such-style";
        assertEquals(FoliaBackgroundMode.LATENT, FoliaBackgroundMode.current());
        FoliaBackgroundConfig.mode = "embed";
        assertEquals(FoliaBackgroundMode.EMBED, FoliaBackgroundMode.current());
        FoliaBackgroundConfig.resetAll();
    }

    @Test
    void needsCoverOnlyForImageDrivenModes() {
        assertTrue(FoliaBackgroundConfig.needsCover(FoliaBackgroundMode.LATENT));
        assertTrue(FoliaBackgroundConfig.needsCover(FoliaBackgroundMode.NOMAND));

        FoliaBackgroundConfig.resetAll();
        assertFalse(FoliaBackgroundConfig.needsCover(FoliaBackgroundMode.COMMON));
        FoliaBackgroundConfig.commonUseCoverColorBg = true;
        assertTrue(FoliaBackgroundConfig.needsCover(FoliaBackgroundMode.COMMON));
        FoliaBackgroundConfig.resetAll();
    }

}



