package com.muoniumplayer.render.skija;

/**
 * 预糊那一层的<b>分档策略</b>：一帧里可能出现好几种模糊强度（四处界面各一档），
 * 而每档都是「把整张采下来的背景重糊一遍」。这个类只管算术——哪一档命中、往哪个槽里重建、
 * 预算花完了找谁借——GL 对象与 Skia 一律不认识，所以它能直接在 JVM 里钉死
 * （见 {@code GlassBlurLevelsTest}）。
 *
 * <h2>为什么不是一格缓存</h2>
 * {@link GlassBackdrop} 以前只留一格预糊，key 一变就原地重糊。全局只有一个 sigma 时那正好；
 * 一旦四处界面能各调自己的模糊（{@code HudConfig.glassBlur*}），一格缓存就变成了
 * <b>逐次调用交替重建</b>：面板要 12、岛要 8，两者同屏时每帧互相把对方顶掉，
 * 一帧付出两遍全屏高斯，而下一帧又是两遍。多槽位把这个互顶消掉。
 *
 * <h2>为什么要有预算</h2>
 * 槽位多不等于免费：实时采集档下每一档都是每帧一次全屏两趟高斯。所以
 * {@link #REBUILDS_PER_CAPTURE} 给一次采集发了固定的重建次数，花完之后新出现的强度
 * <b>不再重建</b>，而是借本代数里已经糊好的那一档（{@link #nearest}）——
 * 症状是「这一面的糊差半档」，比「这一帧卡一下」便宜得多，而且下一格采集就有预算了。
 * 这也是为什么不设成 1：那样等于回到一格缓存的互顶，只是换了个死法。
 *
 * <h2>代数</h2>
 * 判「这一档还新不新」用的是 {@link GlassBackdrop#captures()} 那个采集计数，所以调用方
 * 每次进 {@link GlassBackdrop#blurred(float)} 都要先 {@link #beginCapture(long)}。
 * 忘了调用的后果是：老档被当成永远命中，玻璃透的是十秒前的画面，而这条在糊着的底上
 * 肉眼判不出来——只有「动起来的画面不跟着动」这种反馈能抓到它。
 */
final class GlassBlurLevels {

    /** 槽位数 = 生效面的数量：播放器、岛、桌面歌词、编辑器，最多同时四档。 */
    static final int SLOTS = 4;

    /**
     * 一次采集允许重糊几档。2 = 「两块不同的玻璃同屏」这种常见形状全都能精确糊上，
     * 而三块、四块时第三、第四块退到借最近的档——因为实时档下每多一档就是每帧多一遍全屏高斯。
     */
    static final int REBUILDS_PER_CAPTURE = 2;

    /** 空槽的 key；真实 key 是 {@code round(sigma * 2)}，永远 >= 0。 */
    static final float NO_KEY = -1f;

    /** 没命中 / 没有可借的档。 */
    static final int NO_SLOT = -1;

    private static final long NO_CAPTURE = -1L;

    private final float[] keys = new float[SLOTS];
    private final long[] builtAt = new long[SLOTS];
    /** 伪时钟：只用大小关系判「谁最久没被用」，不掺系统时间，这样测试里可复现。 */
    private final long[] usedAt = new long[SLOTS];

    private long generation = NO_CAPTURE;
    private int rebuilds;
    private long clock;

    GlassBlurLevels() {
        reset();
    }

    /** 回到「什么都没糊过」。尺寸变了、层停用时调用，槽位随之作废。 */
    void reset() {
        for (int i = 0; i < SLOTS; i++) {
            keys[i] = NO_KEY;
            builtAt[i] = NO_CAPTURE;
            usedAt[i] = NO_CAPTURE;
        }
        generation = NO_CAPTURE;
        rebuilds = 0;
        clock = 0;
    }

    /**
     * 告诉策略「现在是这一次采集了」。同一代里重复调用什么都不做，
     * 所以调用方可以在每次 {@link #find} 之前无脑调它。
     */
    void beginCapture(long captures) {
        if (generation == captures) {
            return;
        }
        generation = captures;
        rebuilds = 0;
    }

    /** 这一档在本代数里糊好过没有。只读，不改 LRU。 */
    int find(float key, long captures) {
        for (int i = 0; i < SLOTS; i++) {
            if (keys[i] == key && builtAt[i] == captures) {
                return i;
            }
        }
        return NO_SLOT;
    }

    /** 命中之后记一次「刚用过」，供 {@link #allocate} 挑牺牲槽。 */
    void touch(int slot) {
        if (slot >= 0 && slot < SLOTS) {
            usedAt[slot] = ++clock;
        }
    }

    /** 本代数的重建预算还有没有。 */
    boolean overBudget() {
        return rebuilds >= REBUILDS_PER_CAPTURE;
    }

    /** 本代数已经糊好了几档（探针读数）。 */
    int builtThisCapture(long captures) {
        int n = 0;
        for (int i = 0; i < SLOTS; i++) {
            if (builtAt[i] == captures && keys[i] != NO_KEY) {
                n++;
            }
        }
        return n;
    }

    /**
     * 预算花完了借哪一档：本代数已建的档里 key 与要的那个最接近的；并列取最近用过的。
     *
     * @return 槽位，或 {@link #NO_SLOT}（本代数一档都没建过——那就得建，没有可借的）
     */
    int nearest(float key, long captures) {
        int best = NO_SLOT;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < SLOTS; i++) {
            if (keys[i] == NO_KEY || builtAt[i] != captures) {
                continue;
            }
            float distance = Math.abs(keys[i] - key);
            if (distance < bestDistance || (distance == bestDistance && best >= 0
                    && usedAt[i] > usedAt[best])) {
                best = i;
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * 给一次重建挑槽位：空槽优先，其次本代数没建过的（过期也算，它反正要重糊），
     * 四槽全在本代数上时挑最久没用的那个。
     *
     * <p>调用方必须先看 {@link #overBudget()}：预算还在就总要有个槽被覆盖，
     * 覆盖掉一档本代数刚糊好的意味着那一档下一次请求要重建——这件事只有当同屏强度数
     * 超过槽位数时才会发生，而那正是「四档同时不同强度」的形状，届时宁可轮换也不能不画。</p>
     */
    int allocate(float key, long captures) {
        for (int i = 0; i < SLOTS; i++) {
            if (keys[i] == NO_KEY) {
                return i;
            }
        }
        for (int i = 0; i < SLOTS; i++) {
            if (builtAt[i] != captures) {
                return i;
            }
        }
        int victim = 0;
        for (int i = 1; i < SLOTS; i++) {
            if (usedAt[i] < usedAt[victim]) {
                victim = i;
            }
        }
        return victim;
    }

    /** 记下一档糊好了，并花掉一次预算。 */
    void markBuilt(int slot, float key, long captures) {
        keys[slot] = key;
        builtAt[slot] = captures;
        usedAt[slot] = ++clock;
        rebuilds++;
    }

    /** 一次采集之后重糊了几次（探针读数）。 */
    int rebuildsThisCapture() {
        return rebuilds;
    }

    float keyAt(int slot) {
        return keys[slot];
    }

    long builtAtCapture(int slot) {
        return builtAt[slot];
    }
}
