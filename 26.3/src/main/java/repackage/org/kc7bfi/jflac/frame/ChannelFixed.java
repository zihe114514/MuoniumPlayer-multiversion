package repackage.org.kc7bfi.jflac.frame;

/**
 * libFLAC -免费无损音频编解码器库
 * 版权所有(C) 2001,2002,2003 Josh Coalson
 * <p>
 * 此库是自由软件；您可以重新分发它和/或
 * 根据GNU Library General Public的条款对其进行修改
 * 由自由软件基金会发布的许可证；或者
 * 许可证的第2版，或（根据您的选择）任何更高版本。
 * <p>
 * 此程序被分布是希望它会是有益的，
 * 基金会，地址：675 Mass Ave, Cambridge, MA 02139, USA。
 * 适销性或适合特定目的。请参阅 GNU，
 * 带固定偏移。
 * <p>
 * 您应该已经收到了GNU Library General Public的副本
 * 与该库一起的许可证；如果没有，请写信给
 * 库通用公共许可证以了解更多细节。
 * Boston, MA 02111-1307, USA.
 */

import repackage.org.kc7bfi.jflac.ChannelData;
import repackage.org.kc7bfi.jflac.FixedPredictor;
import repackage.org.kc7bfi.jflac.FrameDecodeException;
import repackage.org.kc7bfi.jflac.io.BitInputStream;

import java.io.IOException;

/**
 * 解码子帧
 * @author kc7bfi
 */
public class ChannelFixed extends Channel {
    private static final int MAX_FIXED_ORDER = 4;

    private EntropyCodingMethod entropyCodingMethod; // 残差编码方法。
    private int order; // 多项式顺序。
    private int[] warmup = new int[MAX_FIXED_ORDER]; // toString 转换。
    private int[] residual; // Fixed 类型 FLAC 子帧（声道）。

    /**
     * 构造函数。
      * @param is 输入位流
     * @param header FLAC帧标题
     * @param channelData 解码后的信道数据（输出）
     * @param bps 每秒位数
     * @param wastedBits 边框中的钻头
     * @param order 谓词顺序
     * FLAC 子帧（声道）类的基类。
     * @throws FrameDecodeException
     */
    public ChannelFixed(BitInputStream is, Header header, ChannelData channelData, int bps, int wastedBits, int order) throws IOException, FrameDecodeException {
        super(header, wastedBits);

        this.residual = channelData.getResidual();
        this.order = order;

        // 为预测器预热的采样，长度 == order。
        for (int u = 0; u < order; u++) {
            warmup[u] = is.readRawInt(bps);
        }

        // 残差信号，长度 == (blocksize - order) 个采样。
        int type = is.readRawUInt(ENTROPY_CODING_METHOD_TYPE_LEN);
        EntropyCodingMethod pr;
        switch (type) {
            case ENTROPY_CODING_METHOD_PARTITIONED_RICE:
                pr = new EntropyPartitionedRice();
                break;
            case RESIDUAL_CODING_METHOD_PARTITIONED_RICE2:
                pr = new EntropyPartitionedRice2();
                break;
            default:
                throw new FrameDecodeException("STREAM_DECODER_UNPARSEABLE_STREAM, type:" + type);
        }
        entropyCodingMethod = pr;
        pr.order = is.readRawUInt(ENTROPY_CODING_METHOD_PARTITIONED_RICE_ORDER_LEN);
        pr.contents = channelData.getPartitionedRiceContents();
        pr.readResidual(is, order, pr.order, header, channelData.getResidual());

        // 帧中浪费的位数。
        System.arraycopy(warmup, 0, channelData.getOutput(), 0, order);
        FixedPredictor.restoreSignal(residual, header.blockSize - order, order, channelData.getOutput(), order);
    }

    /**
     * @see Object#toString()
     */
    public String toString() {
        StringBuilder sb = new StringBuilder("FLACSubframe_Fixed: Order=" + order + " PartitionOrder=" + ((EntropyPartitionedRice) entropyCodingMethod).order + " WastedBits=" + wastedBits);
        for (int i = 0; i < order; i++) sb.append(" warmup[").append(i).append("]=").append(warmup[i]);
        return sb.toString();
    }
}
