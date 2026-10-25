package com.muoniumplayer.core.settings;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.settings.JsonConfigStorage;

import java.io.File;

/**
 * 触屏手势层的第一批旋钮，落在共享的 {@code config/muonium/touch.json}。
 *
 * <p>为什么独立成文件而不是进 {@code hud.json}：HudConfig / HudSetting 是五棵版本树
 * 各自一份的 FORK，加一项要五处同步（见 SHARE-MANIFEST）；而手势层的全部实现都在
 * muonium-common，设置跟着实现走。{@link ClientSettings} 已有「共享独立小配置」的先例，
 * 这里沿用。第一期没有 GUI——数值经过真机调参后再考虑进设置页，目前改文件即生效
 * （类加载时读一次）。</p>
 *
 * <p>{@link #ENABLED} 是整个手势层的总闸，也是回滚开关：关掉它，NCM 界面立即回到
 * 「按下即分发点击、无拖动滚动」的旧行为（旧路径完整保留，见 NCMScreen 的延迟分发）。</p>
 */
public final class TouchSettings {

    /** 触屏手势层总开关（拖动滚动 + 松手触发点击）。false = 完整回退旧行为。 */
    public static boolean ENABLED = true;

    /**
     * 按下后累计位移超过该值才锁定为拖动（GUI 像素）。
     *
     * <p>低于它的一切移动都算「手抖」，仍然是一次点按。8 是「鼠标稳、手指抖」的中间值：
     * 再小会把手指标定偏差当拖动，再大点按就要挪很远才触发。</p>
     */
    public static double DRAG_THRESHOLD_PX = 8.0;

    /**
     * 松手甩动的减速度（GUI 像素 / ms²）。惯性距离 = v²/(2·decel)。
     *
     * <p>1.8 对应「轻扫半屏、重甩两三屏」的手感；越小滑得越远。</p>
     */
    public static double FLING_DECEL = 1.8;

    /** 单次甩动最远距离（GUI 像素），防一次甩动直接飞到底。 */
    public static double FLING_MAX_DISTANCE = 3000.0;

    /**
     * 长按判定时长（毫秒）。目前唯一的消费者是播放队列的「按住一行抓取排序」。
     *
     * <p>必须小于用户「按下想滚动却先停顿」的常见时长，否则滚动会频繁被误判成抓取；
     * 350ms 是安卓系统长按的量级。</p>
     */
    public static long LONG_PRESS_MS = 350;

    static {
        load();
    }

    private TouchSettings() {
    }

    private static void load() {
        File file = ConfigPaths.TOUCH;
        if (!file.isFile()) {
            return;
        }
        try {
            JsonObject o = JsonConfigStorage.readObject(file, new Gson());
            if (o == null) {
                return;
            }
            ENABLED = booleanOf(o, "enabled", ENABLED);
            DRAG_THRESHOLD_PX = clamp(doubleOf(o, "dragThresholdPx", DRAG_THRESHOLD_PX), 1.0, 64.0);
            FLING_DECEL = clamp(doubleOf(o, "flingDecel", FLING_DECEL), 0.4, 12.0);
            FLING_MAX_DISTANCE = clamp(doubleOf(o, "flingMaxDistance", FLING_MAX_DISTANCE), 200.0, 20000.0);
            LONG_PRESS_MS = (long) clamp(doubleOf(o, "longPressMs", LONG_PRESS_MS), 120.0, 1500.0);
        } catch (Throwable unreadable) {
            // 读不了就用默认值继续跑：手势层宁可参数朴素也不能因为配置坏了而不工作。
        }
    }

    /** 以后进设置页时调用；现在留给手改 json 之外的程序化修改。 */
    public static void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("enabled", ENABLED);
            o.addProperty("dragThresholdPx", DRAG_THRESHOLD_PX);
            o.addProperty("flingDecel", FLING_DECEL);
            o.addProperty("flingMaxDistance", FLING_MAX_DISTANCE);
            o.addProperty("longPressMs", LONG_PRESS_MS);
            JsonConfigStorage.writeObject(ConfigPaths.TOUCH, new Gson(), o);
        } catch (Throwable unwritable) {
            // 保存失败不影响本次会话。
        }
    }

    private static boolean booleanOf(JsonObject o, String key, boolean fallback) {
        var element = o.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsBoolean() : fallback;
    }

    private static double doubleOf(JsonObject o, String key, double fallback) {
        var element = o.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsDouble() : fallback;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
