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
 * @param metadata 元数据块
 * @author kc7bfi
 */
public class LPCPredictor {
    //static private final double M_LN2 = 0.69314718055994530942;
    
    /**
 * @param metadata 元数据块
 * @author kc7bfi
 */
    
    /**
 * @param metadata 元数据块
 * @author kc7bfi
 */

    /**
 * @param metadata 元数据块
 * @author kc7bfi
 */
    public static void restoreSignal(int[] residual, int dataLen, int[] qlpCoeff, int order, int lpQuantization, int[] data, int startAt) {
        //@param residual  残差信号
        for (int i = 0; i < dataLen; i++) {
            int sum = 0;
            for (int j = 0; j < order; j++) {
                sum += qlpCoeff[j] * data[startAt + i - j - 1];
            }
            //System.out.print((sum >> lpQuantization)+" ");
            data[startAt + i] = residual[i] + (sum >> lpQuantization);
        }
        //System.out.println();
        //for (int i = 0; i < dataLen+startAt; i++) System.out.print(data[i]+" "); System.out.println();
        //for (int j = 0; j < order; j++) System.out.print(qlpCoeff[j]+" ");System.out.println();
        //System.exit(1);
    }

    /**
      * 从 LPC 压缩中恢复信号。
      * @param residual 残差信号
     * @param dataLen 残差数据长度
     * 从 LPC 压缩恢复信号。
     * @param order 谓词顺序
     * @param lpQuantization
     * @param data 恢复信号（输出）
     * @param startAt 数据数组中的起始位置
     */
    public static void restoreSignalWide(int[] residual, int dataLen, int[] qlpCoeff, int order, int lpQuantization, int[] data, int startAt) {
        for (int i = 0; i < dataLen; i++) {
            long sum = 0;
            for (int j = 0; j < order; j++)
                sum += (long) qlpCoeff[j] * (long) (data[startAt + i - j - 1]);
            data[startAt + i] = residual[i] + (int) (sum >> lpQuantization);
        }
    }
    
    /*
     double FLAC __ lpc_compute_expected_bits_per_residual_sample （ double lpc_error ， int total_samples ）
     {
     double error_scale;
     
     FLAC__ASSERT(total_samples > 0);
     
     error_scale = 0.5 * M_LN2 * M_LN2 / (double)total_samples;
     
     return FLAC__lpc_compute_expected_bits_per_residual_sample_with_error_scale(lpc_error, error_scale);
     }
     
     double FLAC __ lpc_compute_expected_bits_per_residual_sample_with_error_scale(double lpc_error, double error_scale)
     {
     if(lpc_error > 0.0) {
     double bps = (double) ((double) 0.5 * log(error_scale * lpc_error)/M_LN2) ；
     if(bps > = 0.0)
     return bps;
     else
     return 0.0;
     }
     else if(lpc_error < 0.0) {//错误不应为负，但可能由于浮点分辨率不足而发生
     return(double) 1e32;
     }
     否则
     return 0.0;
     }
     }
     
     int FLAC __ lpc_compute_best_order （ double lpc_error [] ， int max_order ， int total_samples ， int bits_per_signal_sample ）
     {
     int order, best_order;
     double best_bits, tmp_bits;
     double error_scale;
     
     FLAC__ASSERT(max_order > 0);
     FLAC__ASSERT(total_samples > 0);
     
     error_scale = 0.5 * M_LN2 * M_LN2 / (double)total_samples;
     
     best_order = 0;
     best_bits = FLAC __ lpc_compute_expected_bits_per_residual_sample_with_error_scale(lpc_error [0], error_scale) * (double) total_samples;
     
     for(order = 1; order < max_order; order++) {
     tmp_bits = FLAC __ lpc_compute_expected_bits_per_residual_sample_with_error_scale(lpc_error [order], error_scale) * (double) (total_samples - order) + (double) (order * bits_per_signal_sample);
     if(tmp_bits < best_bits) {
     best_order =订单；
     best_bits = tmp_bits;
     }
     }
     
     return best_order+1; // +1 since index of lpc_error[] is order-1
     }
     */
}
