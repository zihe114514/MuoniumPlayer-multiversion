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
import repackage.org.kc7bfi.jflac.FrameDecodeException;
import repackage.org.kc7bfi.jflac.LPCPredictor;
import repackage.org.kc7bfi.jflac.io.BitInputStream;
import repackage.org.kc7bfi.jflac.util.BitMath;

import java.io.IOException;

/**
 * LPC FLAC子帧（通道）。
 * @author kc7bfi
 */
public class ChannelLPC extends Channel {
    private static final int SUBFRAME_LPC_QLP_COEFF_PRECISION_LEN = 4; /* 读取预热采样 */
    private static final int SUBFRAME_LPC_QLP_SHIFT_LEN = 5; /* 读取预热采样 */
    private static final int MAX_LPC_ORDER = 32;

    private EntropyCodingMethod entropyCodingMethod; // 残差编码方法。
    private int order; // FIR顺序。
    private int qlpCoeffPrecision; // 读取熵编码方法信息
    private int quantizationLevel; // 位
    private int[] qlpCoeff = new int[MAX_LPC_ORDER]; // FIR滤波器系数。
    private int[] warmup = new int[MAX_LPC_ORDER]; // toString 转换。
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
    public ChannelLPC(BitInputStream is, Header header, ChannelData channelData, int bps, int wastedBits, int order) throws IOException, FrameDecodeException {
        super(header, wastedBits);

        this.residual = channelData.getResidual();
        this.order = order;

        // 为预测器预热的采样，长度 == order。
        //System.out.println("订单=" +订单);
        for (int u = 0; u < order; u++) {
            warmup[u] = is.readRawInt(bps);
        }
        //量化 FIR 滤波器系数精度，单位位。

        // 读取qlp coeff precision
        int u32 = is.readRawUInt(SUBFRAME_LPC_QLP_COEFF_PRECISION_LEN);
        if (u32 == (1 << SUBFRAME_LPC_QLP_COEFF_PRECISION_LEN) - 1) {
            throw new FrameDecodeException("STREAM_DECODER_ERROR_STATUS_LOST_SYNC");
        }
        qlpCoeffPrecision = u32 + 1;
        //System.out.println("qlpCoeffPrecision="+qlpCoeffPrecision);

        // 读取qlp shift
        quantizationLevel = is.readRawInt(SUBFRAME_LPC_QLP_SHIFT_LEN);
        //所需的 qlp 系数移位。

        // 读取量化后的 lp 系数
        for (int u = 0; u < order; u++) {
            qlpCoeff[u] = is.readRawInt(qlpCoeffPrecision);
        }

        // 残差信号，长度 == (blocksize - order) 个采样。
        int codingType = is.readRawUInt(ENTROPY_CODING_METHOD_TYPE_LEN);
        //System.out.println("codingType="+codingType);
        switch (codingType) {
            case ENTROPY_CODING_METHOD_PARTITIONED_RICE:
                entropyCodingMethod = new EntropyPartitionedRice();
                break;
            case RESIDUAL_CODING_METHOD_PARTITIONED_RICE2:
                entropyCodingMethod = new EntropyPartitionedRice2();
                break;
            default:
                throw new FrameDecodeException("STREAM_DECODER_UNPARSEABLE_STREAM, " + codingType);
        }
        entropyCodingMethod.order = is.readRawUInt(ENTROPY_CODING_METHOD_PARTITIONED_RICE_ORDER_LEN);
        entropyCodingMethod.contents = channelData.getPartitionedRiceContents();

        // 读取残差
        entropyCodingMethod.readResidual(is, order, entropyCodingMethod.order, header,
                channelData.getResidual());

        //System.out.println();
        //for (int i = 0; i < header.blockSize; i++) {System.out.print(channelData.residual[i]+" ");
        //if (i%200==0)System.out.println();
        //}
        //System.out.println();

        // 帧中浪费的位数。
        System.arraycopy(warmup, 0, channelData.getOutput(), 0, order);
        if (bps + qlpCoeffPrecision + BitMath.ilog2(order) <= 32) {
            LPCPredictor.restoreSignal(channelData.getResidual(), header.blockSize - order, qlpCoeff, order, quantizationLevel, channelData.getOutput(), order);
        } else {
            LPCPredictor.restoreSignalWide(channelData.getResidual(), header.blockSize - order, qlpCoeff, order, quantizationLevel, channelData.getOutput(), order);
        }
    }

    /**
     * @see Object#toString()
     */
    public String toString() {
        StringBuilder sb = new StringBuilder("ChannelLPC: Order=" + order + " WastedBits=" + wastedBits);
        sb.append(" qlpCoeffPrecision=").append(qlpCoeffPrecision).append(" quantizationLevel=").append(quantizationLevel);
        sb.append("\n\t\tqlpCoeff: ");
        for (int i = 0; i < order; i++) sb.append(qlpCoeff[i]).append(" ");
        sb.append("\n\t\tWarmup: ");
        for (int i = 0; i < order; i++) sb.append(warmup[i]).append(" ");
        sb.append("\n\t\tParameter: ");
        for (int i = 0; i < (1 << entropyCodingMethod.order); i++)
            sb.append(((EntropyPartitionedRice) entropyCodingMethod).contents.parameters[i]).append(" ");
        //sb.append("\n\t\tResidual: ");
        //for (int i = 0; i < header.blockSize; i++) sb.append(residual[i] + " ");
        return sb.toString();
    }

}
