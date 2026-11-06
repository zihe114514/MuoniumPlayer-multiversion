package com.muoniumplayer.core.cine;

/**
 * 运镜输出的一帧相机位姿：树侧 mixin 拿到后 {@code setPosition + setRotation} 整体接管。
 *
 * <p>position 是眼睛世界坐标；yawDeg/pitchDeg 是朝向；rollDeg 是荷兰角（旧版
 * Camera 只有 2 参 setRotation，树侧 probe 报 capability=false 时共享层自动出 0）；
 * fovOffsetDeg 是 FOV 脉冲偏移（叠加在原版 FOV 上，正 = 广角）。</p>
 */
public record CinePose(double x, double y, double z,
                       float yawDeg, float pitchDeg, float rollDeg,
                       float fovOffsetDeg) {

    /** 不带 FOV 偏移的便捷构造。 */
    public static CinePose of(double x, double y, double z, float yawDeg, float pitchDeg, float rollDeg) {
        return new CinePose(x, y, z, yawDeg, pitchDeg, rollDeg, 0f);
    }
}
