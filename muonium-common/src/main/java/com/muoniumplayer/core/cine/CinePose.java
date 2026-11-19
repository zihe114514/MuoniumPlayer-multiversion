package com.muoniumplayer.core.cine;

/** 不可变相机位姿：位置是世界眼位；角度和 FOV 偏移均为度。 */
public record CinePose(double x, double y, double z,
                       float yawDeg, float pitchDeg, float rollDeg,
                       float fovOffsetDeg) {

    public static CinePose of(double x, double y, double z, float yawDeg, float pitchDeg, float rollDeg) {
        return new CinePose(x, y, z, yawDeg, pitchDeg, rollDeg, 0f);
    }

    public boolean isFinite() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && Float.isFinite(yawDeg) && Float.isFinite(pitchDeg)
                && Float.isFinite(rollDeg) && Float.isFinite(fovOffsetDeg);
    }

    public static CinePose interpolate(CinePose previous, CinePose current, float partialTick) {
        if (previous == null || previous == current) return current;
        float t = Float.isFinite(partialTick) ? Math.max(0f, Math.min(1f, partialTick)) : 1f;
        return new CinePose(previous.x + (current.x - previous.x) * t,
                previous.y + (current.y - previous.y) * t,
                previous.z + (current.z - previous.z) * t,
                previous.yawDeg + CineShots.wrapDegrees(current.yawDeg - previous.yawDeg) * t,
                previous.pitchDeg + (current.pitchDeg - previous.pitchDeg) * t,
                previous.rollDeg + (current.rollDeg - previous.rollDeg) * t,
                previous.fovOffsetDeg + (current.fovOffsetDeg - previous.fovOffsetDeg) * t);
    }
}
