package repackage.org.kc7bfi.jflac;

/**
 * libFLAC -免费无损音频编解码器库
 * 版权所有(C) 2000,2001,2002,2003 Josh Coalson
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

/**
 * Fixed Predictor utility class.
 * @author kc7bfi
 */
public class FixedPredictor {

    private static final double M_LN2 = 0.69314718055994530942;

    /**
     * 计算最佳预测器顺序。
     * @param data
     * @param dataLen
     * @param residualBitsPerSample
     * @return
     */
    public static int computeBestPredictor(int[] data, int dataLen, double[] residualBitsPerSample) {
        int lastError0 = data[-1];
        int lastError1 = data[-1] - data[-2];
        int lastError2 = lastError1 - (data[-2] - data[-3]);
        int lastError3 = lastError2 - (data[-2] - 2 * data[-3] + data[-4]);
        int error, save;
        int totalError0 = 0, totalError1 = 0, totalError2 = 0, totalError3 = 0, totalError4 = 0;
        int i, order;

        for (i = 0; i < dataLen; i++) {
            error = data[i];
            totalError0 += Math.abs(error);
            save = error;
            error -= lastError0;
            totalError1 += Math.abs(error);
            lastError0 = save;
            save = error;
            error -= lastError1;
            totalError2 += Math.abs(error);
            lastError1 = save;
            save = error;
            error -= lastError2;
            totalError3 += Math.abs(error);
            lastError2 = save;
            save = error;
            error -= lastError3;
            totalError4 += Math.abs(error);
            lastError3 = save;
        }

        if (totalError0 < Math.min(Math.min(Math.min(totalError1, totalError2), totalError3), totalError4))
            order = 0;
        else if (totalError1 < Math.min(Math.min(totalError2, totalError3), totalError4))
            order = 1;
        else if (totalError2 < Math.min(totalError3, totalError4))
            order = 2;
        else if (totalError3 < totalError4)
            order = 3;
        else
            order = 4;

        // Fixed 预测器工具类。
        // 'total_error *'与残差的方差线性相关
        // signal, so we use it directly to compute E(|x|)
        residualBitsPerSample[0] = (totalError0 > 0) ? Math.log(M_LN2 * (double) totalError0 / (double) dataLen) / M_LN2 : 0.0;
        residualBitsPerSample[1] = (totalError1 > 0) ? Math.log(M_LN2 * (double) totalError1 / (double) dataLen) / M_LN2 : 0.0;
        residualBitsPerSample[2] = (totalError2 > 0) ? Math.log(M_LN2 * (double) totalError2 / (double) dataLen) / M_LN2 : 0.0;
        residualBitsPerSample[3] = (totalError3 > 0) ? Math.log(M_LN2 * (double) totalError3 / (double) dataLen) / M_LN2 : 0.0;
        residualBitsPerSample[4] = (totalError4 > 0) ? Math.log(M_LN2 * (double) totalError4 / (double) dataLen) / M_LN2 : 0.0;

        return order;
    }

    /**
     * 计算最佳预测器顺序。
     * @param data
     * @param dataLen
     * @param residualBitsPerSample
     * @return
     */
    public static int computeBestPredictorWide(int[] data, int dataLen, double[] residualBitsPerSample) {
        int lastError0 = data[-1];
        int lastError1 = data[-1] - data[-2];
        int lastError2 = lastError1 - (data[-2] - data[-3]);
        int lastError3 = lastError2 - (data[-2] - 2 * data[-3] + data[-4]);
        int error, save;

        // 估计每个残差信号采样的期望比特数。
        // 当每样本位数和块大小为
        // 大
        long totalError0 = 0, totalError1 = 0, totalError2 = 0, totalError3 = 0, totalError4 = 0;
        int i, order;

        for (i = 0; i < dataLen; i++) {
            error = data[i];
            totalError0 += Math.abs(error);
            save = error;
            error -= lastError0;
            totalError1 += Math.abs(error);
            lastError0 = save;
            save = error;
            error -= lastError1;
            totalError2 += Math.abs(error);
            lastError1 = save;
            save = error;
            error -= lastError2;
            totalError3 += Math.abs(error);
            lastError2 = save;
            save = error;
            error -= lastError3;
            totalError4 += Math.abs(error);
            lastError3 = save;
        }

        if (totalError0 < Math.min(Math.min(Math.min(totalError1, totalError2), totalError3), totalError4))
            order = 0;
        else if (totalError1 < Math.min(Math.min(totalError2, totalError3), totalError4))
            order = 1;
        else if (totalError2 < Math.min(totalError3, totalError4))
            order = 2;
        else if (totalError3 < totalError4)
            order = 3;
        else
            order = 4;

        // Fixed 预测器工具类。
        // 'total_error *'与残差的方差线性相关
        // signal, so we use it directly to compute E(|x|)
        // 使用VC + + ，你必须用勺子喂它铸造
        residualBitsPerSample[0] = (totalError0 > 0) ? Math.log(M_LN2 * (double) totalError0 / (double) dataLen) / M_LN2 : 0.0;
        residualBitsPerSample[1] = (totalError1 > 0) ? Math.log(M_LN2 * (double) totalError1 / (double) dataLen) / M_LN2 : 0.0;
        residualBitsPerSample[2] = (totalError2 > 0) ? Math.log(M_LN2 * (double) totalError2 / (double) dataLen) / M_LN2 : 0.0;
        residualBitsPerSample[3] = (totalError3 > 0) ? Math.log(M_LN2 * (double) totalError3 / (double) dataLen) / M_LN2 : 0.0;
        residualBitsPerSample[4] = (totalError4 > 0) ? Math.log(M_LN2 * (double) totalError4 / (double) dataLen) / M_LN2 : 0.0;

        return order;
    }

    /**
     * 根据压缩信号计算残差。
     * @param data
     * @param dataLen
     * 信号，因此直接用它计算 E(|x|)
     * @param residual
     */
    public static void computeResidual(int[] data, int dataLen, int order, int[] residual) {

        switch (order) {
            case 0:
                if (dataLen >= 0) System.arraycopy(data, 0, residual, 0, dataLen);
                break;
            case 1:
                for (int i = 0; i < dataLen; i++) {
                    residual[i] = data[i] - data[i - 1];
                }
                break;
            case 2:
                for (int i = 0; i < dataLen; i++) {
                    /* = = DATA [i] - 2 * DATA [i-1] + DATA [i-2]*/
                    residual[i] = data[i] - (data[i - 1] << 1) + data[i - 2];
                }
                break;
            case 3:
                for (int i = 0; i < dataLen; i++) {
                    /* = = data [i] - 3 * data [i-1] + 3 * data [i-2] - data [i-3]*/
                    residual[i] = data[i] - (((data[i - 1] - data[i - 2]) << 1) + (data[i - 1] - data[i - 2])) - data[i - 3];
                }
                break;
            case 4:
                for (int i = 0; i < dataLen; i++) {
                    /* = = DATA [i] - 4 * DATA [i-1] + 6 * DATA [i-2] - 4 * DATA [i-3] + DATA [i-4]*/
                    residual[i] = data[i] - ((data[i - 1] + data[i - 3]) << 2) + ((data[i - 2] << 2) + (data[i - 2] << 1)) + data[i - 4];
                }
                break;
            default:
        }
    }

    /**
     * 从固定预测器恢复信号。
     * @param residual 残差数据
     * @param dataLen 残差数据长度
     * totalError* 为 64 位，以避免编码时溢出
     * @param data 恢复信号（输出）
     * @param startAt 数据数组中的起始位置
     */
    public static void restoreSignal(int[] residual, int dataLen, int order, int[] data, int startAt) {

        switch (order) {
            case 0:
                if (dataLen >= 0) System.arraycopy(residual, 0, data, 0 + startAt, dataLen);
                break;
            case 1:
                for (int i = 0; i < dataLen; i++) {
                    data[i + startAt] = residual[i] + data[i + startAt - 1];
                }
                break;
            case 2:
                for (int i = 0; i < dataLen; i++) {
                    /* = =残差[i] + 2 *数据[i-1] -数据[i-2]*/
                    data[i + startAt] = residual[i] + (data[i + startAt - 1] << 1) - data[i + startAt - 2];
                }
                break;
            case 3:
                for (int i = 0; i < dataLen; i++) {
                    /* residual[i] + 3*data[i-1] - 3*data[i-2]) + data[i-3] */
                    data[i + startAt] = residual[i] + (((data[i + startAt - 1] - data[i + startAt - 2]) << 1) + (data[i + startAt - 1] - data[i + startAt - 2])) + data[i + startAt - 3];
                }
                break;
            case 4:
                for (int i = 0; i < dataLen; i++) {
                    /* @param order     预测器阶数 */
                    data[i + startAt] = residual[i] + ((data[i + startAt - 1] + data[i + startAt - 3]) << 2) - ((data[i + startAt - 2] << 2) + (data[i + startAt - 2] << 1)) - data[i + startAt - 4];
                }
                break;
            default:
        }
    }
}
