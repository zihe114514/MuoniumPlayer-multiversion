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
    //static private final double M_LN2 = 0.6931471804530942 ；
    
    /*
     void FLAC__lpc_compute_autocorrelation(double data[], int data_len, int lag, double autoc[])
     {
     //
      //由于更好的数据局部性，此版本倾向于运行得更快
       //（ 'data_len'通常比'lag'大得多）
        //
         LPC 预测器工具类。
         int sample, coeff;
         int limit = data_len - lag;
         
         for(coeff = 0; coeff < lag; coeff++)
         autoc [coeff] = 0.0;
         for(sample = 0; sample < = limit; sample + +) {
         d = data [sample] ；
         for(coeff = 0; coeff < lag; coeff++)
         autoc [coeff] + = d * data [sample + coeff];
         }
         for(; sample < data_len; sample++) {
         d = data [sample] ；
         for(coeff = 0; coeff < data_len - sample; coeff + +)
         autoc [coeff] + = d * data [sample + coeff];
         }
         }
         
         void FLAC __ lpc_compute_lp_coefficients(double autoc [], int max_order, double [] [] lp_coeff, double error [])
         {
         int i, j;
         double r, err;
         double ref = new double [Constants.MAX_LPC_ORDER];
         double lpc = new double [Constants.MAX_LPC_ORDER];
         
         err = autoc [0];
         
         for(i = 0; i < max_order; i + +) {
         // 累加本次迭代的反射系数。
          r = -autoc [i +1];
          for(j = 0; j < i; j++)
          // 累加本次迭代的反射系数。
          ref[i] = (r/=err);
          
          //更新LPC系数和总错误。
           lpc [i] = r;
           for(j = 0; j < (i> > 1); j + +) {
           double tmp = lpc[j];
           lpc [j] + = r * lpc [i-1-j] ；
           lpc [i-1-j] + = r * tmp;
           }
           if(i & 1)
           lpc [j] + = lpc [j] * r ；
           
           err *= (1.0 - r * r);
           
           //保存此订单
            for(j = 0; j <= i; j++)
            lp_coeff [i] [j] = (double) (-lpc [j]);//否定FIR滤波器系数以获得预测系数
            error [i] = (double) err;
            }
            }
            
            int FLAC __ lpc_quantize_coefficients(double lp_coeff [], int order, int precision, FLAC __ int32 qlp_coeff [], int * shift)
            {
            int i;
            double d, cmax = -1e32;
            FLAC __ int32 qmax, qmin;
            int max_shiftlimit = (1 << (FLAC__SUBFRAME_LPC_QLP_SHIFT_LEN-1)) - 1;
            int min_shiftlimit = -max_shiftlimit - 1 ；
            
            FLAC__ASSERT(precision > 0);
            FLAC__ASSERT(precision >= FLAC__MIN_QLP_COEFF_PRECISION);
            
            //把一个位作为标志；从现在开始，我们只考虑| lp_coeff [i] |
             precision--;
             qmax = 1 < < precision;
             qmin = -qmax;
             qmax--;
             
             for(i = 0; i < order; i + +) {
             if(lp_coeff [i] = = 0.0)
             continue;
             d = fabs(lp_coeff[i]);
             if(d > cmax)
             cmax = d;
             }
             redo_it:
             if(cmax < = 0.0) {
             // => coefficients are all 0, which means our constant-detect didn't work 
              return 2;
              }
              否则
              // => 系数全为 0，说明常数检测未生效
              
              (void)frexp(cmax, &log2cmax);
              log2cmax--;
              *shift = (int) precision - log2cmax - 1;
              
              if(* shift < min_shiftlimit || *shift > max_shiftlimit) {
              # if 0
              // @@@ 这看起来完全没帮助，但也没有经过充分测试：
               if(* shift > max_shiftlimit)
               *shift = max_shiftlimit;
               else
               ENDIF!
               返回%1
               }
               }
               
               if(*shift >= 0) {
               for(i = 0; i < order; i + +) {
               qlp_coeff [i] = (FLAC __ int32) floor((double) lp_coeff [i] * (double) (1 < < * shift));
               
               //仔细检查结果
                if(qlp_coeff [i] > qmax || qlp_coeff [i] < qmin) {
                # ifdef FLAC __ OVERFLOW_DETECT
                fprintf(stderr, "FLAC __ lpc_quantize_coefficients:补偿溢出， qlp_coeff [% u] = %d, lp_coeff [% u] = %f, cmax = %f, precision = % u, shift = %d, q = %f, f(q) = %f\ n", i, qlp_coeff [i], i, lp_coeff [i], cmax, precision, * shift, (double) lp_coeff [i] * (double) (1 < < * shift), floor((double) lp_coeff [i] * (double) (1 < * shift)));
                ENDIF!
                cmax * = 2.0 ；
                goto redo_it;
                }
                }
                }
                else {//(* shift < 0)
                //@@@ 这似乎完全没用，但也没经过充分测试：
                # ifdef DEBUG
                fprintf(stderr,"FLAC__lpc_quantize_coefficients: negative shift = %d\n", *shift);
                ENDIF!
                for(i = 0; i < order; i + +) {
                qlp_coeff [i] = (FLAC __ int32) floor((double) lp_coeff [i]/(double) (1 < < nshift));
                
                //仔细检查结果
                 if(qlp_coeff [i] > qmax || qlp_coeff [i] < qmin) {
                 # ifdef FLAC __ OVERFLOW_DETECT
                 fprintf(stderr,"FLAC__lpc_quantize_coefficients: compensating for overflow, qlp_coeff[%u]=%d, lp_coeff[%u]=%f, cmax=%f, precision=%u, shift=%d, q=%f, f(q)=%f\n", i, qlp_coeff[i], i, lp_coeff[i], cmax, precision, *shift, (double)lp_coeff[i] / (double)(1 << nshift), floor((double)lp_coeff[i] / (double)(1 << nshift)));
                 ENDIF!
                 cmax * = 2.0 ；
                 goto redo_it;
                 }
                 }
                 }
                 
                 return 0;
                 }
                 */
    
    /*
     void FLAC __ lpc_compute_residual_from_qlp_coefficients(int [] data, int dataLen, int [] qlp_coeff, int order, int lp_quantization, int [] residual) {
     int[] history;
     
     
     for(int i = 0; i < dataLen; i++) {
     int sum = 0;
     history = data;
     for(j = 0; j < order; j + +) {
     sum + = qlp_coeff [j] * (* (--history));
     }
     residual [i] = data [i] - (sum > > lp_quantization);
     }
     }
     
     void FLAC __ lpc_compute_residual_from_qlp_coefficients_wide(int [] data, int data_len, int [] qlp_coeff, int order, int lp_quantization, int [] residual)
     {
     int[] history;
     
     
     for(int i = 0; i < data_len; i++) {
     long sum = 0;
     history = data;
     for(int j = 0; j < order; j + +)
     sum += (long)qlp_coeff[j] * (long)(*(--history));
     residual [i] = data [i] - (int) (sum > > lp_quantization);
     }
     }
     */

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
        //for(int i = 0; i < dataLen + startAt; i + +) System.out.print(data [i] + ""); System.out.println();
        //for(int j = 0; j < order; j + +) System.out.print(qlpCoeff [j] + ""); System.out.println();
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
