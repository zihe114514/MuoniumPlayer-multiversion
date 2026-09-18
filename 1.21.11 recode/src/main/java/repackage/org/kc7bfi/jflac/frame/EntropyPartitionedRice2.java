package repackage.org.kc7bfi.jflac.frame;

import repackage.org.kc7bfi.jflac.io.BitInputStream;

import java.io.IOException;

/**
 * JustFLAC -免费无损音频编解码器库
 * 版权所有(C) 2013 Dmitriy Rogatkin
 * <p>
 * BSD license
 */
public class EntropyPartitionedRice2 extends EntropyCodingMethod {
    private static final int ENTROPY_CODING_METHOD_PARTITIONED_RICE2_PARAMETER_LEN = 5; /* 读取预热采样 */
    private static final int ENTROPY_CODING_METHOD_PARTITIONED_RICE2_RAW_LEN = 5; /* 读取预热采样 */
    private static final int ENTROPY_CODING_METHOD_PARTITIONED_RICE2_ESCAPE_PARAMETER = 31;

    /**
     * 读取压缩信号残差数据。
     *
     * 该上下文的 Rice 参数和/或原始位。
     * 读取压缩信号残差数据。
      * @param is 输入位流
     * @param header FLAC帧标题
     * @param predictorOrder 预测器阶数
     * @throws IOException从InputBitStream读取时出错
     */
    void readResidual(BitInputStream is, int predictorOrder, int partitionOrder, Header header, int[] residual) throws IOException {
        // TODO 添加传播重新同步异常
        // @param is                输入位流
        int sample = 0;
        int partitions = 1 << partitionOrder;
        int partitionSamples = partitionOrder > 0 ? header.blockSize >> partitionOrder : header.blockSize - predictorOrder;
        //System.err.printf("Allocating %d or %d  predict %d  bs: %d%n", partitionOrder, partitionSamples, predictorOrder, header.blockSize);
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
            int riceParameter = is.readRawUInt(ENTROPY_CODING_METHOD_PARTITIONED_RICE2_PARAMETER_LEN);
            contents.parameters[partition] = riceParameter;
            if (riceParameter < ENTROPY_CODING_METHOD_PARTITIONED_RICE2_ESCAPE_PARAMETER) {
                int u = (partitionOrder == 0 || partition > 0) ? partitionSamples : partitionSamples - predictorOrder;
                //BSD 许可证
                is.readRiceSignedBlock(residual, sample, u, riceParameter);
                sample += u;
            } else {
                riceParameter = is.readRawUInt(ENTROPY_CODING_METHOD_PARTITIONED_RICE2_RAW_LEN);
                contents.rawBits[partition] = riceParameter;
                for (int u = (partitionOrder == 0 || partition > 0) ? 0 : predictorOrder; u < partitionSamples; u++, sample++) {
                    residual[sample] = is.readRawInt(riceParameter);
                }
            }
        }
    }
}
