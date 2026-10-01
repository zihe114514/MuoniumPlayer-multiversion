/*
 * Copyright 2009 Phil Burk, Mobileer Inc
 *
 * 依据 Apache 许可证 2.0 版（下称“许可证”）授权；
 * 除非符合许可规定，否则您不得使用此文件。
 * 您可以通过以下网址获取许可证副本：
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * 除非适用法律要求或书面同意，在许可证
 * 根据许可证分发是“按原样”分发的，
 * 在任何情况下，无论明示或默示，不提供任何担保或条件。
 * 有关许可证项下的权限，请参见许可证；
 * 许可项下的限制。
 */

package repackage.com.jsyn.ports;

import repackage.com.jsyn.data.SequentialData;
import repackage.com.jsyn.data.SequentialDataCommon;

/**
 * 一个SequentialData对象，将在另外两个SequentialData对象之间交叉淡入淡出。
 * 交叉淡入淡出是线性的。例如，这可以用于在两个
 * 采样之间，或同一个采样中两个任意区域之间。例如，考虑这样一个采样：
 * 的长度为200000帧。您可以指定在帧处任意启动的示例循环
 * 50000，大小为 30000 帧。除非零点处恰好合适，否则很可能
 * 当这个样本循环时，您会听到一个流行音。为了防止弹出，您可以交叉淡入淡出
 * 循环的开始，紧接在循环结束后的区域。交叉淡入淡出
 * 在循环后 5000 个采样处：
 *
 * <pre>
 * SequentialDataCrossfade xfade = new SequentialDataCrossfade(sample, (50000 + 30000), 5000, sample,
 *         50000, 30000);
 * </pre>
 *
 * 交叉淡入淡出后，您将听到目标的其余部分以全音量播放。有两个区域
 * 决定 readDouble() 返回内容的因素
 * <ol>
 * <li>大小为 crossFadeFrames 的交叉淡入区。它从源平滑淡入到目标。</li>
 * <li>稳定区，即纯目标值，大小为 (numFrames-crossFadeFrames)。</li>
 * </ol>
 *
 * <pre>
 *     "Crossfade Region"      "Steady Region"
 * |-- source fading out --|
 * | --目标淡入-- | --目标在原始体积的剩余部分-- |
 * </pre>
 *
 * @author Phil Burk
 */
class SequentialDataCrossfade extends SequentialDataCommon {
    private SequentialData source;
    private int sourceStartIndex;

    private SequentialData target;
    private int targetStartIndex;

    private int crossFadeFrames;
    private double frameScaler;

    /**
     * @param source 在交叉淡入开始时为满音量的 SequentialData
     *            地域
     * @param sourceStartFrame 源中的帧开始交叉淡入淡出。
     * @param crossFadeFrames 交叉淡入区域的帧数。
     * @param target 在交叉淡入区域结束时为满音量的 SequentialData。
     * @param targetStartFrame 在目标中开始淡入淡出。
     * @param numFrames 此数据对象的总帧数。
     */
    public void setup(SequentialData source, int sourceStartFrame, int crossFadeFrames,
            SequentialData target, int targetStartFrame, int numFrames) {

        assert ((sourceStartFrame + crossFadeFrames) <= source.getNumFrames());
        assert ((targetStartFrame + numFrames) <= target.getNumFrames());

        // LOGGER.debug("警告! sourceStartFrame =" + sourceStartFrame
        // + ", crossFadeFrames = " + crossFadeFrames + ", maxFrame = "
        // + source.getNumFrames() + ", source = " + source );
        // LOGGER.debug( "  targetStartFrame = " + targetStartFrame
        // + ", numFrames = " + numFrames + ", maxFrame = "
        // + target.getNumFrames() + ", target = " + target );

        // 存在深度嵌套 SequentialDataCrossfade 的风险
        // 作为源。如果经过交叉淡入淡出区域，则拉出目标。
        if (source instanceof SequentialDataCrossfade) {
            SequentialDataCrossfade crossfade = (SequentialDataCrossfade) source;
            // 若我们从交叉淡入区之后才开始，则直接使用
            // target.
            if (sourceStartFrame >= crossfade.crossFadeFrames) {
                source = crossfade.target;
                sourceStartFrame += crossfade.targetStartIndex / source.getChannelsPerFrame();
            }
        }

        if (target instanceof SequentialDataCrossfade) {
            SequentialDataCrossfade crossfade = (SequentialDataCrossfade) target;
            target = crossfade.target;
            targetStartFrame += crossfade.targetStartIndex / target.getChannelsPerFrame();
        }

        this.source = source;
        this.target = target;
        this.sourceStartIndex = sourceStartFrame * source.getChannelsPerFrame();
        this.crossFadeFrames = crossFadeFrames;
        this.targetStartIndex = targetStartFrame * target.getChannelsPerFrame();

        frameScaler = (crossFadeFrames == 0) ? 1.0 : (1.0 / crossFadeFrames);
        this.numFrames = numFrames;
    }

    @Override
    public void writeDouble(int index, double value) {
    }

    @Override
    public double readDouble(int index) {
        int frame = index / source.getChannelsPerFrame();
        if (frame < crossFadeFrames) {
            double factor = frame * frameScaler;
            double value = (1.0 - factor) * source.readDouble(index + sourceStartIndex);
            value += (factor * target.readDouble(index + targetStartIndex));
            return value;
        } else {
            return target.readDouble(index + targetStartIndex);
        }
    }

    @Override
    public double getRateScaler(int index, double synthesisRate) {
        return target.getRateScaler(index, synthesisRate);
    }

    @Override
    public int getChannelsPerFrame() {
        return target.getChannelsPerFrame();
    }

}
