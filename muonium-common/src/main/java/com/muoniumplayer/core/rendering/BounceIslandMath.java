package com.muoniumplayer.core.rendering;

/**
 * 「回弹胶囊」样式的纯数学：弹簧、语义底色、像素吸附、行淡入淡出、进度平滑。
 *
 * <p>刻意与 {@link DownloadDynamicIsland} 分开：那文件已经 2600 行且全程要碰 GL，
 * 而这一套样式的全部数值行为（弹簧会不会过冲、圆角会不会跟着抖、吸附会不会把
 * 半像素留成灰边）都能在离线的 JUnit 里断言。参照实现是同作者 Hypixel 端那份
 * Dynamic Island 脚本，几何常量按它逐项抄齐，方便日后两边对照。</p>
 */
final class BounceIslandMath {

    /** 一行的静息高度：参考稿是 {@code 40 * 0.80}。多行通知就是它的整数倍叠高。 */
    static final double ROW_HEIGHT = 40.0 * .80;
    /** 胶囊距屏幕顶的距离。其他样式用 {@code TOP_MARGIN=6}，这一款是 7。 */
    static final double TOP = 7.0;
    /** 左侧图标托盘的可见直径。 */
    static final double ICON_SIZE = 30.0;
    /** 左侧留给图标托盘的整块宽度，比直径宽一点，托盘两侧各有呼吸位。 */
    static final double LOGO_AREA_WIDTH = 28.0;
    /** 竖分隔符左右各留的空隙。 */
    static final double DIVIDER_GAP = 5.0;
    /** 胶囊两端的内缩。 */
    static final double SIDE_PADDING = 7.0;
    /** 通知行的字号系数：这一款的正文天生比其他样式小一档。 */
    static final double NOTICE_TEXT_SCALE = .80;
    /** 一次最多堆几行，再多就只留最新的几条。参考稿同样是 4。 */
    static final int MAX_ROWS = 4;

    static final long ENTER_MILLIS = 180L;
    static final long EXIT_MILLIS = 260L;

    /** 圆角上限：行高的另一半。超过两行之后圆角不再跟着高度走，胶囊就变成大圆角方块。 */
    static final double RADIUS_CAP = ROW_HEIGHT * .5;
    /** 窄屏上的宽度地板：低于这个值弹簧会在文字还没装下时就开始抖。 */
    static final double MIN_WIDTH = 120.0;

    /** 弹簧：{@code v *= exp(-DAMPING*dt); v += (target-x)*STIFFNESS*dt; x += v*dt}。 */
    static final double SPRING_DAMPING = 20.0;
    static final double SPRING_STIFFNESS = 170.0;
    /** 收敛判据：位移与速度同时足够小才吸附到目标值，否则会永远留一丝抖动。 */
    static final double SPRING_SNAP_DISTANCE = .02;
    static final double SPRING_SNAP_VELOCITY = .1;

    /** 帧间隔钳位区间。参考稿 240fps 上限、20fps 下限，防卡顿一帧把弹簧炸开。 */
    static final double MIN_FRAME_SECONDS = 1.0 / 240.0;
    static final double MAX_FRAME_SECONDS = 1.0 / 20.0;

    /** 进度条的平滑时长，秒。 */
    static final double PROGRESS_SMOOTH_TIME = .14;

    /** 底色分类：中性、成功、失败、进行中。整块表面跟着当前分类走。 */
    static final int TINT_CATEGORY_NEUTRAL = 0;
    static final int TINT_CATEGORY_SUCCESS = 1;
    static final int TINT_CATEGORY_DANGER = 2;
    static final int TINT_CATEGORY_PROGRESS = 3;

    static final int TINT_NEUTRAL = 0x08080A;
    static final int TINT_SUCCESS = 0x0A2014;
    static final int TINT_DANGER = 0x240A0C;
    static final int TINT_PROGRESS = 0x0A1428;
    /** 表面不透明度。参考稿是 240/255，不是全黑，能透出一点背景。 */
    static final int SURFACE_ALPHA = 240;
    static final double TINT_RATE = 12.0;

    /** 参考稿的文本色板。这一款不用主题强调色，颜色是写死的一整套。 */
    static final int COL_TEXT = 0xFFF7F7F9;
    static final int COL_SECONDARY = 0xFFB9C1C8;
    static final int COL_BRAND = 0xFFD56BFF;
    static final int COL_IDLE = 0xFFE2E6EA;
    static final int COL_FAINT = 0xFFCFD6DC;
    static final int COL_DIVIDER = 0xDCB7BFC6;
    static final int COL_TRACK_ON = 0xFF4BA367;
    static final int COL_TRACK_OFF = 0xFF874E54;
    static final int COL_KNOB = 0xFFEEEEF2;
    static final int COL_BAR_TRACK = 0x692C4860;
    static final int COL_BAR_FILL = 0xFF66A2D5;

    private BounceIslandMath() {
    }

    /** 把一帧的纳秒差换算成钳位后的秒。首帧（{@code lastNanos<=0}）按上限的中间值给。 */
    static double frameSeconds(long nowNanos, long lastNanos) {
        if (lastNanos <= 0L) {
            return 1.0 / 60.0;
        }
        double dt = (nowNanos - lastNanos) / 1_000_000_000.0;
        if (dt < MIN_FRAME_SECONDS) return MIN_FRAME_SECONDS;
        if (dt > MAX_FRAME_SECONDS) return MAX_FRAME_SECONDS;
        return dt;
    }

    /** 圆角：单行是标准胶囊，两行以上固定成 {@code ROW_HEIGHT/2} 的大圆角。 */
    static double pillRadius(double height) {
        return Math.min(RADIUS_CAP, height * .5);
    }

    /** 把 GUI 坐标向下吸附到物理像素网格，消除缩放 2/3 档时边缘那根半透明灰线。 */
    static double alignFloor(double value, double pixelScale) {
        if (pixelScale < 1.0) return value;
        return Math.floor(value * pixelScale) / pixelScale;
    }

    /** 把宽高向上吸附到物理像素网格，不补额外宽度。给高度用。 */
    static double alignUp(double size, double pixelScale) {
        if (pixelScale < 1.0) return size;
        return Math.ceil(size * pixelScale) / pixelScale;
    }

    /** 把宽高向上吸附，并且至少补一个物理像素，避免吸附后左右边缘各吃掉半像素导致整体变窄。 */
    static double alignSize(double size, double pixelScale) {
        if (pixelScale < 1.0) return size;
        return alignUp(size, pixelScale) + 1.0 / pixelScale;
    }

    static double easeOutCubic(double t) {
        double inverse = 1.0 - clamp01(t);
        return 1.0 - inverse * inverse * inverse;
    }

    static double clamp01(double value) {
        if (value < 0.0) return 0.0;
        return value > 1.0 ? 1.0 : value;
    }

    /**
     * 单行的淡入淡出系数：进入 180ms、退出 260ms，取两者的较小值，所以一条通知
     * 在队列里被提前顶掉时也会先淡出而不是凭空消失。
     */
    static double rowAlpha(long ageMillis, long lifetimeMillis) {
        double enter = clamp01(ageMillis / (double) ENTER_MILLIS);
        double exit = clamp01((lifetimeMillis - ageMillis) / (double) EXIT_MILLIS);
        return easeOutCubic(Math.min(enter, exit));
    }

    static int argb(int alpha, int red, int green, int blue) {
        return (clamp255(alpha) << 24) | (clamp255(red) << 16) | (clamp255(green) << 8) | clamp255(blue);
    }

    static int alphaOf(int color, double alpha) {
        return argb((int) Math.round(clamp01(alpha) * 255.0),
                (color >> 16) & 255, (color >> 8) & 255, color & 255);
    }

    private static int clamp255(int value) {
        if (value < 0) return 0;
        return value > 255 ? 255 : value;
    }

    /** 百分比文字自身的颜色梯度：0% 近白，100% 转青蓝。参考稿同一条三次曲线。 */
    static int percentColor(double progress) {
        double t = clamp01(progress);
        return argb(255, (int) (247.0 - 162.0 * t), (int) (247.0 + 8.0 * t), (int) (249.0 - 164.0 * t));
    }

    /** 底色分类 -> 目标 RGB。分类判定留在渲染层，因为那要读通知类型。 */
    static int tintForCategory(int category) {
        switch (category) {
            case TINT_CATEGORY_SUCCESS:
                return TINT_SUCCESS;
            case TINT_CATEGORY_DANGER:
                return TINT_DANGER;
            case TINT_CATEGORY_PROGRESS:
                return TINT_PROGRESS;
            default:
                return TINT_NEUTRAL;
        }
    }

    /**
     * 一维弹簧。带速度积分，所以尺寸目标突变时会先冲过头再回弹——这正是这一款
     * 和其他七款唯一的观感差别，其他样式走的都是不过冲的指数低通。
     */
    static final class Spring {
        private double value;
        private double velocity;

        Spring(double initial) {
            this.value = initial;
        }

        double value() {
            return value;
        }

        double velocity() {
            return velocity;
        }

        /** 直接落到目标上，不播动画。用于第一次显示和被门控归零的场合。 */
        void snapTo(double target) {
            value = target;
            velocity = 0.0;
        }

        double stepTo(double target, double dt) {
            double damping = Math.exp(-SPRING_DAMPING * dt);
            velocity = velocity * damping + (target - value) * SPRING_STIFFNESS * dt;
            value += velocity * dt;
            if (Math.abs(value - target) < SPRING_SNAP_DISTANCE && Math.abs(velocity) < SPRING_SNAP_VELOCITY) {
                value = target;
                velocity = 0.0;
            }
            return value;
        }
    }

    /**
     * 临界阻尼的标量平滑，用于进度条。
     *
     * <p>参考稿这一步写的是 {@code current + (change + temp) * decay}。在
     * {@code dt→0} 时它等于 {@code 2*current - target}，误差每帧翻倍，只是靠
     * {@code decay<1} 才没炸开。这里按标准 SmoothDamp 的 {@code target + (...)}
     * 形式写，观感一致但帧率无关。</p>
     */
    static final class DampedValue {
        private double value;
        private double velocity;

        double value() {
            return value;
        }

        void snapTo(double target) {
            value = target;
            velocity = 0.0;
        }

        double stepTo(double target, double dt) {
            double omega = 2.0 / PROGRESS_SMOOTH_TIME;
            double step = omega * dt;
            double decay = 1.0 / (1.0 + step + .48 * step * step + .235 * step * step * step);
            double change = value - target;
            double temp = (velocity + omega * change) * dt;
            velocity = (velocity - omega * temp) * decay;
            double next = target + (change + temp) * decay;
            // 不允许越过目标值再继续：越过去就会看到进度条"顶一下再退回来"。
            if ((target - value > 0.0) == (next > target)) {
                next = target;
                velocity = 0.0;
            }
            value = next;
            return value;
        }
    }

    /** 表面底色。逐通道指数逼近目标色，所以换分类时是一次渐变而不是跳色。 */
    static final class Tint {
        private double red = (TINT_NEUTRAL >> 16) & 255;
        private double green = (TINT_NEUTRAL >> 8) & 255;
        private double blue = TINT_NEUTRAL & 255;

        void stepTo(int category, double dt) {
            int target = tintForCategory(category);
            double t = clamp01(1.0 - Math.exp(-TINT_RATE * Math.max(0.0, dt)));
            red += (((target >> 16) & 255) - red) * t;
            green += (((target >> 8) & 255) - green) * t;
            blue += ((target & 255) - blue) * t;
        }

        void reset() {
            red = (TINT_NEUTRAL >> 16) & 255;
            green = (TINT_NEUTRAL >> 8) & 255;
            blue = TINT_NEUTRAL & 255;
        }

        int redChannel() {
            return (int) Math.round(clamp255((int) Math.floor(red + .5)));
        }

        int greenChannel() {
            return (int) Math.round(clamp255((int) Math.floor(green + .5)));
        }

        int blueChannel() {
            return (int) Math.round(clamp255((int) Math.floor(blue + .5)));
        }
    }
}
