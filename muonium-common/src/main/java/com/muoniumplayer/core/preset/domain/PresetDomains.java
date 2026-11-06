package com.muoniumplayer.core.preset.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 全部配置域，<b>顺序就是套用顺序</b>：HUD 先落（显隐、歌词、颜色字段），主题随后（配色来源与主题切换
 * 都要走过渡动画，起点得是 HUD 已经就位后的画面），音效与运镜最后。
 *
 * <p>新加一个与外观 / 音效有关的设置文件时：写一个 {@link PresetDomain} 并登记在这里，守卫测试会检查它的
 * 键策略是否覆盖了快照里的每一个键。账号、Cookie、音源、缓存这类文件<b>永远不许</b>登记成配置域。</p>
 */
public final class PresetDomains {

    private PresetDomains() {
    }

    public static List<PresetDomain> all() {
        List<PresetDomain> domains = new ArrayList<PresetDomain>();
        domains.add(new HudPresetDomain());
        domains.add(new ThemePresetDomain());
        domains.add(new FoliaBackgroundPresetDomain());
        domains.add(new FontPresetDomain());
        domains.add(new TransparentPresetDomain());
        domains.add(new CinePresetDomain());
        domains.add(new AcousticsPresetDomain());
        domains.add(new ViperPresetDomain());
        return Collections.unmodifiableList(domains);
    }
}
