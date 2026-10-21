package com.muoniumplayer.core.screens.ncm.foliabg;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「背景」这一页的六档模型：切换、参数行、以及三个必须同步的状态。
 *
 * <p>这里钉的是菜单融合后最容易坏的两件事：</p>
 * <ol>
 *   <li><b>切档位要真的换画面</b>。{@code backgroundKind}（渲染分派）、{@code mode}（Folia
 *       画哪一个）、{@code HudConfig} 那个 QPlayer 开关三者不同步时，界面会显示「隐现」而画面
 *       还是模糊封面 —— 不会编译报错，只会让人觉得「点了没反应」。</li>
 *   <li><b>切档位要真的换配置项</b>。每档的参数行必须不同，且覆盖该档的全部字段；两档共用一组
 *       行的话，用户在「漫游」下面能改到「通用」的参数。</li>
 * </ol>
 *
 * <p>另有一组迁移测试：老配置存的是下标，删掉「空」「莫奈」两档后必须还能落到对的样式上。</p>
 */
class FoliaBackgroundKindTest {

    @Test
    void sixKindsInTheExpectedOrder() {
        assertEquals(6, FoliaBackgroundOptions.kindCount());
        assertEquals("QPlayer 流体", FoliaBackgroundOptions.KIND_LABELS[0]);
        assertEquals("模糊封面", FoliaBackgroundOptions.KIND_LABELS[1]);
        assertEquals(4, FoliaBackgroundMode.values().length);
        assertEquals("通用", FoliaBackgroundOptions.KIND_LABELS[2]);
        assertEquals(FoliaBackgroundMode.COMMON.getDisplayName(), FoliaBackgroundOptions.KIND_LABELS[2]);
        assertEquals(FoliaBackgroundMode.EMBED.getDisplayName(),
                FoliaBackgroundOptions.KIND_LABELS[5]);
        // 「空」与「莫奈」已移除
        for (String label : FoliaBackgroundOptions.KIND_LABELS) {
            assertFalse("空".equals(label), "「空」应该已经移除");
            assertFalse("莫奈".equals(label), "「莫奈」应该已经移除");
        }
    }

    @Test
    void qplayerKindsAndFoliaKindsAreDistinguished() {
        assertTrue(FoliaBackgroundOptions.isQPlayer(0));
        assertTrue(FoliaBackgroundOptions.isQPlayer(1));
        assertFalse(FoliaBackgroundOptions.isQPlayer(2));
        assertNull(FoliaBackgroundOptions.foliaModeOf(0));
        assertNull(FoliaBackgroundOptions.foliaModeOf(1));
        assertEquals(FoliaBackgroundMode.COMMON, FoliaBackgroundOptions.foliaModeOf(2));
        assertEquals(FoliaBackgroundMode.LATENT, FoliaBackgroundOptions.foliaModeOf(3));
        assertEquals(FoliaBackgroundMode.NOMAND, FoliaBackgroundOptions.foliaModeOf(4));
        assertEquals(FoliaBackgroundMode.EMBED, FoliaBackgroundOptions.foliaModeOf(5));
    }

    @Test
    void applyKindWritesBothTheSwitchAndTheStyle() {
        FoliaBackgroundConfig.resetAll();
        // 切到「漫游」：kind 与 mode 必须同时落到同一档
        FoliaBackgroundOptions.applyKind(4);
        assertEquals(4, FoliaBackgroundConfig.backgroundKind);
        assertEquals(FoliaBackgroundMode.NOMAND, FoliaBackgroundMode.current());
        // 再切回「模糊封面」：kind 回到 1，但 Folia 的 mode 不该被改写
        FoliaBackgroundOptions.applyKind(1);
        assertEquals(1, FoliaBackgroundConfig.backgroundKind);
        assertEquals(FoliaBackgroundMode.NOMAND, FoliaBackgroundMode.current());
        // 再切回漫游：还是原来的样式，参数也没丢
        FoliaBackgroundOptions.applyKind(4);
        assertEquals(FoliaBackgroundMode.NOMAND, FoliaBackgroundMode.current());
        FoliaBackgroundConfig.resetAll();
    }

    @Test
    void wrapAroundInBothDirections() {
        assertEquals(5, FoliaBackgroundOptions.clampKind(-1));
        assertEquals(0, FoliaBackgroundOptions.clampKind(6));
        assertEquals(5, FoliaBackgroundOptions.clampKind(5));
        FoliaBackgroundConfig.resetAll();
        FoliaBackgroundOptions.applyKind(-1);
        assertEquals(5, FoliaBackgroundConfig.backgroundKind);
        FoliaBackgroundConfig.resetAll();
    }

    @Test
    void everyKindShowsItsOwnRows() {
        for (int kind = 2; kind < FoliaBackgroundOptions.kindCount(); kind++) {
            List<FoliaBackgroundOptions.Spec> rows = FoliaBackgroundOptions.rowsFor(kind);
            assertNotNull(rows);
            assertFalse(rows.isEmpty(), "档位 " + FoliaBackgroundOptions.KIND_LABELS[kind]
                    + " 下面必须至少有一项参数");
        }
        assertTrue(FoliaBackgroundOptions.rowsFor(0).isEmpty());
        assertTrue(FoliaBackgroundOptions.rowsFor(1).isEmpty());
    }

    @Test
    void neighbouringKindsDoNotShareTheSameRows() {
        int latent = FoliaBackgroundOptions.rowsFor(3).size();
        int common = FoliaBackgroundOptions.rowsFor(2).size();
        int embed = FoliaBackgroundOptions.rowsFor(5).size();
        assertTrue(latent > common, "隐现的行数应多于通用: " + latent + " vs " + common);
        assertTrue(embed >= 5, "嵌入要有列表管理那几行: " + embed);
    }

    @Test
    void rowsFollowSubOptions() {
        FoliaBackgroundConfig.resetAll();
        // 漫游：换效果 → 参数行整组换掉
        FoliaBackgroundConfig.nomandEffect = 0;
        int dithering = FoliaBackgroundOptions.rowsFor(4).size();
        FoliaBackgroundConfig.nomandEffect = 4;
        int lens = FoliaBackgroundOptions.rowsFor(4).size();
        assertTrue(dithering != lens, "不同效果的行数/内容必须不同: "
                + dithering + " vs " + lens);
        FoliaBackgroundConfig.resetAll();
    }

    @Test
    void everyRowIdIsReadableAndWritable() {
        for (int kind = 2; kind < FoliaBackgroundOptions.kindCount(); kind++) {
            for (FoliaBackgroundOptions.Spec spec : FoliaBackgroundOptions.rowsFor(kind)) {
                if (spec.kind == FoliaBackgroundOptions.KIND_SECTION) continue;
                if (spec.kind == FoliaBackgroundOptions.KIND_SLIDER) {
                    float value = FoliaBackgroundOptions.readFloat(spec.id);
                    assertTrue(value >= spec.min - 1e-4f && value <= spec.max + 1e-4f,
                            spec.id + " 的读回值越界: " + value);
                }
            }
        }
        FoliaBackgroundConfig.resetAll();
    }

    @Test
    void rowHeightMatchesTheControlKind() {
        assertEquals(32.0, FoliaBackgroundOptions.rowHeight(
                FoliaBackgroundOptions.slider("x", "x", 0f, 1f, 0.1f)), 1e-6);
        assertEquals(26.0, FoliaBackgroundOptions.rowHeight(
                FoliaBackgroundOptions.toggle("x", "x")), 1e-6);
        assertEquals(20.0, FoliaBackgroundOptions.rowHeight(new FoliaBackgroundOptions.Spec(
                FoliaBackgroundOptions.KIND_SECTION, "", "", 0, 0, 0, null, null)), 1e-6);
    }

    // ──────────────────────────── 老配置迁移 ────────────────────────────

    @Test
    void legacyIndexesMapToTheRemainingStyles() {
        // 旧顺序：common / latent / sora / nomand / monet / embed
        assertEquals(FoliaBackgroundMode.COMMON, FoliaBackgroundMode.fromLegacyIndex(0));
        assertEquals(FoliaBackgroundMode.LATENT, FoliaBackgroundMode.fromLegacyIndex(1));
        assertEquals(FoliaBackgroundMode.NOMAND, FoliaBackgroundMode.fromLegacyIndex(3));
        assertEquals(FoliaBackgroundMode.EMBED, FoliaBackgroundMode.fromLegacyIndex(5));
        // 被删掉的两档回落到默认，而不是串到别的样式
        assertEquals(FoliaBackgroundMode.LATENT, FoliaBackgroundMode.fromLegacyIndex(2));
        assertEquals(FoliaBackgroundMode.LATENT, FoliaBackgroundMode.fromLegacyIndex(4));
    }

    @Test
    void unknownStoredIdsFallBackToTheDefault() {
        assertEquals(FoliaBackgroundMode.LATENT, FoliaBackgroundMode.byStoredId("sora"));
        assertEquals(FoliaBackgroundMode.LATENT, FoliaBackgroundMode.byStoredId("monet"));
        assertEquals(FoliaBackgroundMode.LATENT, FoliaBackgroundMode.byStoredId(null));
        assertEquals(FoliaBackgroundMode.LATENT, FoliaBackgroundMode.byStoredId(""));
        assertEquals(FoliaBackgroundMode.EMBED, FoliaBackgroundMode.byStoredId("embed"));
        assertEquals(FoliaBackgroundMode.NOMAND, FoliaBackgroundMode.byStoredId("NOMAND"));
    }

    @Test
    void everyRemainingStyleHasAStableId() {
        for (FoliaBackgroundMode mode : FoliaBackgroundMode.values()) {
            assertEquals(mode, FoliaBackgroundMode.byStoredId(mode.storedId()));
            assertNotNull(mode.storedId());
            assertFalse(mode.storedId().isEmpty());
        }
        // 配置文件里存的是 id 而不是下标：切换后 mode 字段必须是字符串
        FoliaBackgroundConfig.resetAll();
        FoliaBackgroundOptions.applyKind(3);
        assertEquals("latent", FoliaBackgroundConfig.mode);
    }
}
