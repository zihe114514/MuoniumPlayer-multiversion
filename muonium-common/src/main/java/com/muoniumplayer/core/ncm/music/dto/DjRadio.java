package com.muoniumplayer.core.ncm.music.dto;

import lombok.Data;

/**
 * 电台 / 播客条目。网易云把播客并进电台体系（播客就是 djradio，播客里的声音就是节目），
 * 所以浏览、订阅、最近收听共用这一个模型；DIFM 频道与广播电台的结构和它足够接近，
 * 也借同一张卡展示，各自的特殊字段由 {@link DjRadioService} 解析时填进通用槽位。
 */
@Data
public class DjRadio {

    private long id;
    private String name = "";
    private String picUrl = "";
    private String djName = "";
    private String categoryName = "";
    private long subCount;
    private long programCount;
    private long playCount;
    private String desc = "";
    private boolean subscribed;
    /** 最后一次更新时间（毫秒），「最近收听」按它排到最前。 */
    private long lastUpdateTime;
    /**
     * 这张卡点击后去哪。电台/播客进 {@code RadioDetailPanel}；广播电台页与 DIFM 频道
     * 有自己的落点，解析侧直接填好，UI 不必再按类型分派。
     */
    private Kind kind = Kind.DJ_RADIO;

    public enum Kind {
        /** 网易云电台 / 播客。 */
        DJ_RADIO,
        /** DIFM 策展曲风频道，点开后取正在播的曲目列表播放。 */
        DIFM_CHANNEL,
        /** 真实广播电台（直播流），本期只浏览与收藏。 */
        BROADCAST_CHANNEL
    }

    public String coverUrl() {
        return picUrl == null ? "" : picUrl.replace("http://", "https://");
    }

    /** 卡片第二行：DJ · 分类 · N 期，缺哪段就跳过哪段。 */
    public String subtitle() {
        StringBuilder sb = new StringBuilder();
        if (djName != null && !djName.isEmpty()) sb.append(djName);
        if (categoryName != null && !categoryName.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(categoryName);
        }
        if (programCount > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(programCount).append(" 期");
        }
        return sb.toString();
    }

    /** 名称兜底：上游偶发空名时卡片至少有字可读。 */
    public String displayName() {
        return name == null || name.trim().isEmpty() ? "未命名电台" : name.trim();
    }
}
