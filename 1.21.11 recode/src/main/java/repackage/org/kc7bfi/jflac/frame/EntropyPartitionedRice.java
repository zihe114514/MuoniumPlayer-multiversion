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

import repackage.org.kc7bfi.jflac.io.BitInputStream;

import java.io.IOException;

/**
 * 此类包含Entropy Partitioned Rice内容。
 * @author kc7bfi
 */
public class EntropyPartitionedRice extends EntropyCodingMethod {
    private static final int ENTROPY_CODING_METHOD_PARTITIONED_RICE_PARAMETER_LEN = 4; /* 读取预热采样 */
    private static final int ENTROPY_CODING_METHOD_PARTITIONED_RICE_RAW_LEN = 5; /* 读取预热采样 */
    private static final int ENTROPY_CODING_METHOD_PARTITIONED_RICE_ESCAPE_PARAMETER = 15;

    /**
     * 读取压缩信号残差数据。
     *
     * @param partitionOrder 分区阶数
     * @param residual       残差信号（输出）
     * @param partitionOrder 分区顺序
     * @param header FLAC帧标题
     * @param residual 残留信号（输出）
     * @throws IOException从InputBitStream读取时出错
     */
    void readResidual(BitInputStream is, int predictorOrder, int partitionOrder, Header header, int[] residual) throws IOException {
        // @param is                输入位流
        int sample = 0;
        int partitions = 1 << partitionOrder;
        int partitionSamples = partitionOrder > 0 ? header.blockSize >> partitionOrder : header.blockSize - predictorOrder;
        if (predictorOrder == 0) {
            if (header.blockSize < predictorOrder) {
                //@param predictorOrder    预测器阶数
                return;
            }
        } else {
            if (partitionSamples < predictorOrder) {
                //System.err.printf("NEED RESYNC2  %d - %d%n", partitionSamples , predictorOrder);
                return;
            }
        }
        contents.ensureSize(Math.max(6, partitionOrder));
        //contents.parameters = new int [partitions];

        for (int partition = 0; partition < partitions; partition++) {
            int riceParameter = is.readRawUInt(ENTROPY_CODING_METHOD_PARTITIONED_RICE_PARAMETER_LEN);
            contents.parameters[partition] = riceParameter;
            if (riceParameter < ENTROPY_CODING_METHOD_PARTITIONED_RICE_ESCAPE_PARAMETER) {
                int u = (partitionOrder == 0 || partition > 0) ? partitionSamples : partitionSamples - predictorOrder;
                is.readRiceSignedBlock(residual, sample, u, riceParameter);
                sample += u;
            } else {
                riceParameter = is.readRawUInt(ENTROPY_CODING_METHOD_PARTITIONED_RICE_RAW_LEN);
                contents.rawBits[partition] = riceParameter;
                for (int u = (partitionOrder == 0 || partition > 0) ? 0 : predictorOrder; u < partitionSamples; u++, sample++) {
                    residual[sample] = is.readRawInt(riceParameter);
                }
            }
        }
    }
}
