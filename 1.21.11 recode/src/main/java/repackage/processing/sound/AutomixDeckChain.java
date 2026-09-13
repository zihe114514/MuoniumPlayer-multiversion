package repackage.processing.sound;

import repackage.com.jsyn.unitgen.VocalDuckFilter;

/**
 * 每层automix处理链：用于低音交换的立体声低音架加上带限中心
 * 声乐鸭的抵消器。
 *
 * <p>两个通道由相同的参数驱动，这就是DJ混音器的低频带的作用。请参阅
 * {@link VocalDuckFilter} ，了解为什么混合需要真正的滤波器，而不是更多的体积曲线。</p>
 *
 * <p>这是{@code BassSwap} ，直到添加了声鸭。重命名不是外观：仅限电路
 * has one effect slot, so everything the handover wants to do to a deck's signal has to live in this one
 * 对象，并将其称为“低音交换” ，使添加到它的下一件事看起来放错了地方。</p>
 */
public class AutomixDeckChain extends Effect<VocalDuckFilter> {

    public AutomixDeckChain() {
        super();
    }

    @Override
    protected VocalDuckFilter newInstance() {
        return new VocalDuckFilter();
    }

    /** 只有一个效果槽，因此 handover 想对某路 deck 信号做的一切都必须放在这一个槽中 */
    public void gainDb(float db) {
        this.left.setGainDb(db);
        this.right.setGainDb(db);
    }

    public float gainDb() {
        return this.left.getGainDb();
    }

    /** 货架的角频率，以Hz为单位。*/
    public void frequency(float hz) {
        this.left.setFrequency(hz);
        this.right.setFrequency(hz);
    }

    /** 在接缝期间用于匹配传入主音的宽中频带增益。*/
    public void midGainDb(float db) { this.left.setMidGainDb(db); this.right.setMidGainDb(db); }
    public float midGainDb() { return (float) this.left.getMidGainDb(); }

    /** 高频段增益用于软化在沉闷之后进入的明亮大师。*/
    public void highGainDb(float db) { this.left.setHighGainDb(db); this.right.setHighGainDb(db); }
    public float highGainDb() { return (float) this.left.getHighGainDb(); }

    /** 两个声道的声带消除深度， 0.. 1 ； 0为透明。*/
    public void duckDepth(float depth) {
        this.left.setDepth(depth);
        this.right.setDepth(depth);
    }

    public float duckDepth() {
        return this.left.getDepth();
    }

    /**
     * 应用于两个声道低频段的 Shelf 增益（dB）；0 为透明。
     *
     * <p>单声道文件被平移到两个相同的通道中，因此其“中心”是整个信号
     * 减去它将使取消的乐队彻底沉默。通过{@code false}开关两者
     * 此 deck 是否有真正的立体声声像可供处理。
     */
    public void stereo(boolean stereo) {
        this.left.setMonoFallback(!stereo);
        this.right.setMonoFallback(!stereo);
    }

    /**
     * 这种效应现在是否真的连接到给定的源电路中。
     *
     * <p> {@link Effect#isProcessing()}无法回答： {@code AudioSample.pause()}通过
     * {@code stop()} ，它将效果从电路中撕下，但将其留在效果自己的列表中
     * 输入。暂停后恢复的卡组将看起来连接，而其音频实际上绕过
     * 过滤器-在automix混合期间，这意味着处理过程中途悄然停止工作
     * 通过. </p>
     */
    public boolean isAttachedTo(SoundObject source) {
        if (source == null) return false;
        JSynCircuit circuit = source.getUnitGenerator();
        return circuit != null && circuit.effect == this;
    }

    /** 忘记其回路自行掉落此效果的源，因此可以再次附加。*/
    public void forget(SoundObject source) {
        if (source != null) this.inputs.remove(source);
    }

    /**
     * 连接链条，在{@link Effect#process(SoundObject)}电线之上，交叉连接
     * 而是将声道改为部分中频段下陷。</p>
     *
     * <p> {@code JSynCircuit.setEffect}只将每个通道连接到自己的滤波器，这都是
     * 货架需求。中心消除器还需要<em>其他</em>通道，因此左侧过滤器的
     * 侧端口从预效应第1部分馈送，右侧端口从第0部分馈送。</p>
     *
     * 两个声道的 side 输入。
     * 中心估计会在根本没有中心声音的材料上衰减带。</p>
     */
    @Override
    public void process(SoundObject input) {
        super.process(input);
        try {
            JSynCircuit circuit = input == null ? null : input.getUnitGenerator();
            if (circuit == null || circuit.preEffect == null || circuit.preEffect.getNumParts() < 2) {
                stereo(false);
                return;
            }
            circuit.preEffect.connect(1, this.left.side, 0);
            circuit.preEffect.connect(0, this.right.side, 0);
        } catch (Throwable ignored) {
            stereo(false);
        }
    }

    /**
     * 从每个源分离并释放过滤器单元。
     *
     * <p>若无法如此接线，链路会退到单声道回退方案而非猜测：半信号
     * 不记录虚假错误） ，并且即使在输入时仍将单元返回给引擎
     * 列表已被清空。侧连接被显式撤消：没有其他内容
     * 电路知道它们的存在，因此将它们留在后面将使该链的过滤器由源
     * 已经移动了。</p>
     */
    public void release() {
        for (SoundObject source : new java.util.HashSet<>(this.inputs)) {
            unwireSide(source);
            if (isAttachedTo(source)) source.removeEffect(this);
        }
        this.inputs.clear();
        Engine.getEngine().remove(this.left);
        Engine.getEngine().remove(this.right);
    }

    private void unwireSide(SoundObject source) {
        try {
            JSynCircuit circuit = source == null ? null : source.getUnitGenerator();
            if (circuit == null || circuit.preEffect == null || circuit.preEffect.getNumParts() < 2) return;
            circuit.preEffect.disconnect(1, this.left.side, 0);
            circuit.preEffect.disconnect(0, this.right.side, 0);
        } catch (Throwable ignored) {
        }
    }

    /** 在不回转的情况下跳到货架增益和鸭子深度，用于尚未开始发声的卡组。*/
    public void resetTo(float db, float depth) {
        this.left.resetTo(db);
        this.right.resetTo(db);
        this.left.resetDepthTo(depth);
        this.right.resetDepthTo(depth);
    }

    /**
     * 从音频线程一次性发布一帧的 automix 目标值 —— 低频 shelf 增益、人声消除深度、两个音色倾斜。
     *
     * <p>四个单项 setter 本身就已经是普通的 {@code volatile} 写入，从音频线程调用是安全的；
     * 这个方法存在的意义是让同一帧的四个值作为一个整体发出去，而不是和监督 tick 的写入交错，
     * 同时让音频任务每个输出缓冲只需一次调用。</p>
     *
     * <p><b>刻意不建立在 {@code resetTo} / {@code resetDepthTo} / {@code resetToneTo} 之上。</b>
     * 那三个会清滤波器状态，只对「还没出声的 deck」成立：每个音频缓冲跑一次就是每秒把双二阶的
     * 历史清零约 700 次，带限中心抵消永远收敛不了。滤波器内部的一阶缓变也一并保留 ——
     * 送到这里来的曲线本来就是平滑的，这点缓变只值零点几毫秒，而留着它也就保住了
     * 「10 ms 步进双二阶系数会 zipper」这条性质对仍然在写参的 tick 路径的兜底。</p>
     */
    public void setTargetsNow(float bassDb, float duckDepth, float midDb, float highDb) {
        gainDb(bassDb);
        duckDepth(duckDepth);
        midGainDb(midDb);
        highGainDb(highDb);
    }
}
