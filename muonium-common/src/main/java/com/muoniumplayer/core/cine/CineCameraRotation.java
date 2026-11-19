package com.muoniumplayer.core.cine;

/**
 * 相机旋转调用的临时 roll 作用域，供加载器适配在原版 setter 内消费。
 *
 * <p>返回值单位为度；{@link Float#NaN} 表示当前不在本模组的
 * {@code setRotation} 调用中，适配端必须保留原参数。</p>
 */
public interface CineCameraRotation {

    float cineRollDegrees();

    /**
     * 需要独立世界视图适配的宿主返回本次相机更新已应用的位姿。
     * 默认无独立视图消费方；返回 null 表示本次没有接管，不能重新采样调度器代替它。
     */
    default CinePose cineAppliedPose() {
        return null;
    }
}
