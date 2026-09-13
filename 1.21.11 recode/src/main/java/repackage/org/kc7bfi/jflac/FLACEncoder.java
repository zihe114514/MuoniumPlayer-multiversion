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

import repackage.org.kc7bfi.jflac.frame.EntropyPartitionedRiceContents;
import repackage.org.kc7bfi.jflac.io.BitOutputStream;

import java.io.File;

public class FLACEncoder {

    private static class verify_input_fifo {
        int[][] data = new int[Constants.MAX_CHANNELS][Constants.MAX_BLOCK_SIZE];
        int size; /* samples 中每个 data[] 的 */
        int tail;
    }

    private static class verify_output {
        byte[] data;
        int capacity;
        int bytes;
    }

    private static final int ENCODER_IN_MAGIC = 0;
    private static final int ENCODER_IN_METADATA = 1;
    private static final int ENCODER_IN_AUDIO = 2;

    // 流编码器状态

    private static final int STREAM_ENCODER_OK = 0;
    /**<编码器处于正常正常状态。*/

    private static final int STREAM_ENCODER_VERIFY_DECODER_ERROR = 1;
    /**< 底层校验流解码器发生错误；
     * 检查stream_encoder_get_verify_decoder_state()。
     */

    private static final int STREAM_ENCODER_VERIFY_MISMATCH_IN_AUDIO_DATA = 2;
    /**<验证解码器检测到原始解码器不匹配
     * 音频信号和解码的音频信号。
     */

    private static final int STREAM_ENCODER_INVALID_CALLBACK = 3;
    /**< 编码器在设置所有必需回调之前就被初始化。 */

    private static final int STREAM_ENCODER_INVALID_NUMBER_OF_CHANNELS = 4;
    /**< 编码器的声道数设置无效。 */

    private static final int STREAM_ENCODER_INVALID_BITS_PER_SAMPLE = 5;
    /**<编码器的每次采样位数设置无效。
     * FLAC 支持 4-32 bps，但参考编码器目前只支持
     * 仅高达24个基点。
     */

    private static final int STREAM_ENCODER_INVALID_SAMPLE_RATE = 6;
    /**<编码器的输入采样率设置无效。*/

    private static final int STREAM_ENCODER_INVALID_BLOCK_SIZE = 7;
    /**<编码器的块大小设置无效。*/

    private static final int STREAM_ENCODER_INVALID_MAX_LPC_ORDER = 8;
    /**<编码器具有最大LPC顺序的无效设置。*/

    private static final int STREAM_ENCODER_INVALID_QLP_COEFF_PRECISION = 9;
    /**< 编码器对量化线性预测系数精度的设置无效。 */

    private static final int STREAM_ENCODER_MID_SIDE_CHANNELS_MISMATCH = 10;
    /**< 指定了 mid/side 编码，但声道数不等于 2。 */

    private static final int STREAM_ENCODER_MID_SIDE_SAMPLE_SIZE_MISMATCH = 11;
    /**Description*/

    private static final int STREAM_ENCODER_ILLEGAL_MID_SIDE_FORCE = 12;
    /**< 指定了松散 mid/side 编码，但未启用 mid/side 编码。 */

    private static final int STREAM_ENCODER_BLOCK_SIZE_TOO_SMALL_FOR_LPC_ORDER = 13;
    /**< 指定的块大小小于最大 LPC 阶数。 */

    private static final int STREAM_ENCODER_NOT_STREAMABLE = 14;
    /**<编码器绑定到“可流式传输子集” ，但其他设置违反了它。*/

    private static final int STREAM_ENCODER_FRAMING_ERROR = 15;
    /**<写入流时出错；通常情况下， write_callback返回错误。*/

    private static final int STREAM_ENCODER_INVALID_METADATA = 16;
    /**<输入到编码器的元数据无效，方式如下：
     * -使用空指针调用stream_encoder_set_metadata() ，但块计数> 0
     * -其中一个元数据块包含未定义的类型
     * - 包含 format_cuesheet_is_legal() 判定为非法的 CUESHEET
     * - 包含 format_seektable_is_legal() 判定为非法的 SEEKTABLE
     * -它包含多个SEEKTABLE块或多个VORBIS_COMMENT块
     */

    private static final int STREAM_ENCODER_FATAL_ERROR_WHILE_ENCODING = 17;
    /**<写入流时出错；通常情况下， write_callback返回错误。*/

    private static final int STREAM_ENCODER_FATAL_ERROR_WHILE_WRITING = 18;
    /**< write_callback返回错误。*/

    private static final int STREAM_ENCODER_MEMORY_ALLOCATION_ERROR = 19;
    /**<内存分配失败。*/

    private static final int STREAM_ENCODER_ALREADY_INITIALIZED = 20;
    /**< 编码器已初始化时又调用了 stream_encoder_init()，
     * 通常是因为未调用 stream_encoder_finish()。
     * stream_encoder_finish() was not called.
     */

    private static final int STREAM_ENCODER_UNINITIALIZED = 21;
    /**<编码器处于未初始化状态。*/

    /***********************************************************************
     *
     * 私人定制课程
     *
     ***********************************************************************/

    int input_capacity;                          /* 信号和残差缓冲区的当前大小（采样数） */
    int[][] integer_signal = new int[Constants.MAX_CHANNELS][Constants.MAX_BLOCK_SIZE];  /* 输入信号的整数版本*/
    int[][] integer_signal_mid_side = new int[2][Constants.MAX_BLOCK_SIZE];          /* 中间侧输入信号的整数版本（仅限立体声）*/
    double[][] real_signal = new double[Constants.MAX_CHANNELS][Constants.MAX_BLOCK_SIZE];      /* 输入信号的浮点版本*/
    double[][] real_signal_mid_side = new double[2][Constants.MAX_BLOCK_SIZE];              /* 中间侧输入信号的浮点版本（仅限立体声）*/
    int[] subframe_bps = new int[Constants.MAX_CHANNELS];        /* 输入信号的有效每采样位数（流 bps - 浪费位） */
    int[] subframe_bps_mid_side = new int[2];                /* mid-side 输入信号的有效每采样位数（流 bps - 浪费位 + 0/1） */
    int[][] residual_workspace = new int[Constants.MAX_CHANNELS][2]; /* 每个通道都有一个候选和最佳工作空间，其中将存储子帧残差信号*/
    int[][] residual_workspace_mid_side = new int[2][2];
    //Subframe subframe_workspace[Constants.MAX_CHANNELS][2];
    //子帧subframe_workspace_mid_side [2] [2] ；
    //子帧* subframe_workspace_ptr [Constants.MAX_CHANNELS] [2];
    //Subframe *subframe_workspace_ptr_mid_side[2][2];
    EntropyPartitionedRiceContents[][] partitioned_rice_contents_workspace = new EntropyPartitionedRiceContents[Constants.MAX_CHANNELS][2];
    EntropyPartitionedRiceContents[][] partitioned_rice_contents_workspace_mid_side = new EntropyPartitionedRiceContents[Constants.MAX_CHANNELS][2];
    EntropyPartitionedRiceContents[][] partitioned_rice_contents_workspace_ptr = new EntropyPartitionedRiceContents[Constants.MAX_CHANNELS][2];
    EntropyPartitionedRiceContents[][] partitioned_rice_contents_workspace_ptr_mid_side = new EntropyPartitionedRiceContents[Constants.MAX_CHANNELS][2];
    int[] best_subframe = new int[Constants.MAX_CHANNELS];       /* 索引到上述工作区*/
    int[] best_subframe_mid_side = new int[2];
    int[] best_subframe_bits = new int[Constants.MAX_CHANNELS];  /* 每个通道的最佳子帧的位大小*/
    int[] best_subframe_bits_mid_side = new int[2];
    //uint32 * abs_residual;/*存储abs(candidate residual)的工作区*/
    //uint64 *abs_residual_partition_sums;        /* workspace where the sum of abs(candidate residual) for each partition is stored */
    //unsigned * raw_bits_per_partition;/*存储每个分区的silog2 （候选残差）之和的工作区*/
    BitOutputStream frame = new BitOutputStream();                           /* 当前正在处理的框架*/
    double loose_mid_side_stereo_frames_exact;        /* 在再次尝试独立帧和中/侧帧之前，编码器将使用的确切帧数*/
    int loose_mid_side_stereo_frames;            /* 在再次尝试独立帧和中/侧帧之前，编码器将使用的四舍五入帧数*/
    int loose_mid_side_stereo_frame_count;       /* 使用当前通道分配的帧数*/
    int last_channel_assignment;
    //StreamMetadata元数据;
    int current_sample_number;
    int current_frame_number;
    //struct MD5Context md5context;
    //CPUInfo cpuinfo;
    //unsigned (*local_fixed_compute_best_predictor)(int data[], unsigned data_len, double residual_bits_per_sample[MAX_FIXED_ORDER+1]);
    //void(* local_lpc_compute_autocorrelation) (double data [], unsigned data_len, unsigned lag, double autoc []);
    //void (*local_lpc_compute_residual_from_qlp_coefficients)(int data[], unsigned data_len, int qlp_coeff[], unsigned order, int lp_quantization, int residual[]);
    //void(* local_lpc_compute_residual_from_qlp_coefficients_64bit) (int data [], unsigned data_len, int qlp_coeff [], unsigned order, int lp_quantization, int residual []);
    //void (*local_lpc_compute_residual_from_qlp_coefficients_16bit)(int data[], unsigned data_len, int qlp_coeff[], unsigned order, int lp_quantization, int residual[]);
    boolean use_wide_by_block;          /* 因块大小原因，某些函数使用较慢的 64 位版本 */
    boolean use_wide_by_partition;      /* 由于最小分区顺序和块大小，使用某些函数的64位版本较慢*/
    boolean use_wide_by_order;          /* 由于lpc顺序，使用某些函数的64位版本较慢*/
    boolean precompute_partition_sums;  /* 我们最初的猜测是，预先计算分区总和是否会提高速度*/
    boolean disable_constant_subframes;
    boolean disable_fixed_subframes;
    boolean disable_verbatim_subframes;
    //StreamEncoderWriteCallback write_callback;
    //StreamEncoderMetadataCallback metadata_callback;
    //void * client_data;
    /* 指向已分配数据的未对齐（原始）指针*/
    int[] integer_signal_unaligned = new int[Constants.MAX_CHANNELS];
    int[] integer_signal_mid_side_unaligned = new int[2];
    double[] real_signal_unaligned = new double[Constants.MAX_CHANNELS];
    double[] real_signal_mid_side_unaligned = new double[2];
    int[][] residual_workspace_unaligned = new int[Constants.MAX_CHANNELS][2];
    int[][] residual_workspace_mid_side_unaligned = new int[2][2];
    //uint32 * abs_residual_unaligned;
    //uint64 * abs_residual_partition_sums_unaligned;
    //unsigned * raw_bits_per_partition_unaligned;
    /*
     * 这些字段已从本地私有函数移至此处
     * 声明只是为了在编码过程中节省堆栈空间。
     */
    //double [] lp_coeff = new double [Constants.MAX_LPC_ORDER] [Constants.MAX_LPC_ORDER];/* from process_subframe_() */
    EntropyPartitionedRiceContents[] partitioned_rice_contents_extra = new EntropyPartitionedRiceContents[2]; /* 从find_best_partition_order_()*/

    /*
     * 验证部分的数据
     */
    private class VerifyData {
        FLACDecoder decoder;
        int state_hint;
        boolean needs_magic_hack;
        verify_input_fifo input_fifo;
        verify_output output;

        private class error_stats {
            long absolute_sample;
            int frame_number;
            int channel;
            int sample;
            int expected;
            int got;
        }
    }

    private VerifyData verifyData = new VerifyData();
    boolean is_being_deleted; /* 如果为true ，则从..._delete()调用..._finish()将不会调用回调*/

    // 受保护
    int state;
    boolean verify;
    boolean streamable_subset;
    boolean do_mid_side_stereo;
    boolean loose_mid_side_stereo;
    int channels;
    int bits_per_sample;
    int sample_rate;
    int blocksize;
    int max_lpc_order;
    int qlp_coeff_precision;
    boolean do_qlp_coeff_prec_search;
    boolean do_exhaustive_model_search;
    boolean do_escape_coding;
    int min_residual_partition_order;
    int max_residual_partition_order;
    int rice_parameter_search_dist;
    long total_samples_estimate;
    //StreamMetadata * *元数据；
    int num_metadata_blocks;

    // 私有

    /***********************************************************************
     *
     * 公共静态类 data
     *
     ***********************************************************************/

    private static final String[] StreamEncoderStateString = new String[]{
            "STREAM_ENCODER_OK",
            "STREAM_ENCODER_VERIFY_DECODER_ERROR",
            "STREAM_ENCODER_VERIFY_MISMATCH_IN_AUDIO_DATA",
            "STREAM_ENCODER_INVALID_CALLBACK",
            "STREAM_ENCODER_INVALID_NUMBER_OF_CHANNELS",
            "STREAM_ENCODER_INVALID_BITS_PER_SAMPLE",
            "STREAM_ENCODER_INVALID_SAMPLE_RATE",
            "STREAM_ENCODER_INVALID_BLOCK_SIZE",
            "STREAM_ENCODER_INVALID_MAX_LPC_ORDER",
            "STREAM_ENCODER_INVALID_QLP_COEFF_PRECISION",
            "STREAM_ENCODER_MID_SIDE_CHANNELS_MISMATCH",
            "STREAM_ENCODER_MID_SIDE_SAMPLE_SIZE_MISMATCH",
            "STREAM_ENCODER_ILLEGAL_MID_SIDE_FORCE",
            "STREAM_ENCODER_BLOCK_SIZE_TOO_SMALL_FOR_LPC_ORDER",
            "STREAM_ENCODER_NOT_STREAMABLE",
            "STREAM_ENCODER_FRAMING_ERROR",
            "STREAM_ENCODER_INVALID_METADATA",
            "STREAM_ENCODER_FATAL_ERROR_WHILE_ENCODING",
            "STREAM_ENCODER_FATAL_ERROR_WHILE_WRITING",
            "STREAM_ENCODER_MEMORY_ALLOCATION_ERROR",
            "STREAM_ENCODER_ALREADY_INITIALIZED",
            "STREAM_ENCODER_UNINITIALIZED"
    };

    private static final String[] StreamEncoderWriteStatusString = new String[]{
            "STREAM_ENCODER_WRITE_STATUS_OK",
            "STREAM_ENCODER_WRITE_STATUS_FATAL_ERROR"
    };

    /***********************************************************************
     *
     * 类构造函数/析构函数
     *
     */
    public FLACEncoder() {
        setDefaults();

        is_being_deleted = false;
        
        /*
         for(int i = 0; i < Constants.MAX_CHANNELS; i + +) {
         encoder- > private_- > subframe_workspace_ptr [i] [0] = & encoder- > private_- > subframe_workspace [i] [0];
         encoder->private_->subframe_workspace_ptr[i][1] = &encoder->private_->subframe_workspace[i][1];
         }
         for(i = 0; i < 2; i + +) {
         encoder->private_->subframe_workspace_ptr_mid_side[i][0] = &encoder->private_->subframe_workspace_mid_side[i][0];
         编码器- > private_- > subframe_workspace_ptr_mid_side [i] [1] = & encoder- > private_- > subframe_workspace_mid_side [i] [1] ；
         }
         for(i = 0; i < Constants.MAX_CHANNELS; i++) {
         encoder->private_->partitioned_rice_contents_workspace_ptr[i][0] = &encoder->private_->partitioned_rice_contents_workspace[i][0];
         编码器- > private_- > partitioned_rice_contents_workspace_ptr [i] [1] = & encoder- > private_- > partitioned_rice_contents_workspace [i] [1] ；
         }
         for(i = 0; i < 2; i + +) {
         编码器- > private_- > partitioned_rice_contents_workspace_ptr_mid_side [i] [0] = & encoder- > private_- > partitioned_rice_contents_workspace_mid_side [i] [0] ；
         encoder->private_->partitioned_rice_contents_workspace_ptr_mid_side[i][1] = &encoder->private_->partitioned_rice_contents_workspace_mid_side[i][1];
         }
         
         for(i = 0; i < Constants.MAX_CHANNELS; i++) {
         format_entropy_coding_method_partitioned_rice_contents_init （ & encoder- > private_- > partitioned_rice_contents_workspace [i] [0] ） ；
         format_entropy_coding_method_partitioned_rice_contents_init （ & encoder- > private_- > partitioned_rice_contents_workspace [i] [1] ） ；
         }
         for(i = 0; i < 2; i + +) {
         format_entropy_coding_method_partitioned_rice_contents_init （ & encoder- > private_- > partitioned_rice_contents_workspace_mid_side [i] [0] ） ；
         format_entropy_coding_method_partitioned_rice_contents_init （ & encoder- > private_- > partitioned_rice_contents_workspace_mid_side [i] [1] ） ；
         }
         for(i = 0; i < 2; i++)
         format_entropy_coding_method_partitioned_rice_contents_init （ & encoder- > private_- > partitioned_rice_contents_extra [i] ） ；
         */

        state = STREAM_ENCODER_UNINITIALIZED;
    }

    public void encode(File inputFile, File outputFile) {
        throw new UnsupportedOperationException("encode");
    }
    
    /*
     void stream_encoder_delete(StreamEncoder *encoder)
     {
     unsigned i;
     
     ASSERT(0! = encoder);
     ASSERT(0! = encoder- > protected_);
     ASSERT(0 != encoder->private_);
     ASSERT(0! = encoder- > private_- > frame);
     
     encoder->private_->is_being_deleted = true;
     
     stream_encoder_finish （编码器） ；
     
     if(0! = encoder- > private_- > verify.decoder)
     stream_decoder_delete(encoder- > private_- > verify.decoder);
     
     for(i = 0; i < Constants.MAX_CHANNELS; i++) {
     format_entropy_coding_method_partitioned_rice_contents_clear （ & encoder- > private_- > partitioned_rice_contents_workspace [i] [0] ） ；
     format_entropy_coding_method_partitioned_rice_contents_clear （ & encoder- > private_- > partitioned_rice_contents_workspace [i] [1] ） ；
     }
     for(i = 0; i < 2; i + +) {
     format_entropy_coding_method_partitioned_rice_contents_clear （ & encoder- > private_- > partitioned_rice_contents_workspace_mid_side [i] [0] ） ；
     format_entropy_coding_method_partitioned_rice_contents_clear （ & encoder- > private_- > partitioned_rice_contents_workspace_mid_side [i] [1] ） ；
     }
     for(i = 0; i < 2; i++)
     format_entropy_coding_method_partitioned_rice_contents_clear(&encoder->private_->partitioned_rice_contents_extra[i]);
     
     bitbuffer_delete(encoder- > private_- > frame);
     free(encoder->private_);
     free(encoder->protected_);
     free(encoder);
     }
     */

    /***********************************************************************
     *
     * 公共类方法
     *
     ***********************************************************************/
    
    /*
     StreamEncoderState STREAM_ENCODER_INIT （ StreamEncoder * encoder ）
     {
     unsigned i;
     boolean metadata_has_seektable, metadata_has_vorbis_comment;
     
     ASSERT(0! = encoder);
     
     if(encoder->protected_->state != STREAM_ENCODER_UNINITIALIZED)
     返回编码器- > protected_- > state = STREAM_ENCODER_ALREADY_INITIALIZED;
     
     encoder->protected_->state = STREAM_ENCODER_OK;
     
     if(0 = = encoder- > private_- > write_callback || 0 = = encoder- > private_- > metadata_callback)
     返回编码器- > protected_- > state = STREAM_ENCODER_INVALID_CALLBACK;
     
     if(encoder->protected_->channels == 0 || encoder->protected_->channels > Constants.MAX_CHANNELS)
     return encoder->protected_->state = STREAM_ENCODER_INVALID_NUMBER_OF_CHANNELS;
     
     if(encoder- > protected_- > do_mid_side_stereo & & encoder- > protected_- > channels! = 2)
     返回编码器- > protected_- > state = STREAM_ENCODER_MID_SIDE_CHANNELS_MISMATCH;
     
     if(encoder- > protected_- > loose_mid_side_stereo & &! encoder- > protected_- > do_mid_side_stereo)
     返回编码器- > protected_- > state = STREAM_ENCODER_ILLEGAL_MID_SIDE_FORCE;
     
     if(encoder- > protected_- > bits_per_sample > = 32)
     encoder- > protected_- > do_mid_side_stereo = false;//由于我们执行32位数学运算，因此侧通道将具有33 bps且溢出
     
     if(encoder- > protected_- > bits_per_sample < MIN_BITS_PER_SAMPLE || encoder-> protected_- > bits_per_sample > REFERENCE_CODEC_MAX_BITS_PER_SAMPLE)
     返回编码器- > protected_- > state = STREAM_ENCODER_INVALID_BITS_PER_SAMPLE;
     
     if(!format_sample_rate_is_valid(encoder->protected_->sample_rate))
     return encoder->protected_->state = STREAM_ENCODER_INVALID_SAMPLE_RATE;
     
     if(encoder- > protected_- > blocksize < MIN_BLOCK_SIZE || encoder-> protected_- > blocksize > MAX_BLOCK_SIZE)
     return encoder->protected_->state = STREAM_ENCODER_INVALID_BLOCK_SIZE;
     
     if(encoder- > protected_- > max_lpc_order > MAX_LPC_ORDER)
     返回编码器- > protected_- > state = STREAM_ENCODER_INVALID_MAX_LPC_ORDER;
     
     if(encoder- > protected_- > blocksize < encoder-> protected_- > max_lpc_order)
     return encoder->protected_->state = STREAM_ENCODER_BLOCK_SIZE_TOO_SMALL_FOR_LPC_ORDER;
     
     if(encoder- > protected_- > qlp_coeff_precision = = 0) {
     if(encoder- > protected_- > bits_per_sample < 16) {
     // @@@ need some data about how to set this here w.r.t. blocksize and sample rate
      // @@@ until then we'll make a guess
       encoder- > protected_- > qlp_coeff_precision = max(MIN_QLP_COEFF_PRECISION, 2 + encoder- > protected_- > bits_per_sample/2);
       }
       // @@@ 需要一些关于如何根据块大小和采样率设置此项的数据
       if(encoder- > protected_- > blocksize < = 192)
       编码器- > protected_- > qlp_coeff_precision = 7 ；
       else if(encoder- > protected_- > blocksize < = 384)
       // @@@ 在此之前我们先猜一个
       else if(encoder- > protected_- > blocksize < = 576)
       编码器- > protected_- > qlp_coeff_precision = 9 ；
       else if(encoder- > protected_- > blocksize < = 1152)
       encoder- > protected_- > qlp_coeff_precision = 10;
       else if(encoder- > protected_- > blocksize < = 2304)
       encoder->protected_->qlp_coeff_precision = 11;
       else if(encoder- > protected_- > blocksize < = 4608)
       encoder- > protected_- > qlp_coeff_precision = 12;
       else
       encoder- > protected_- > qlp_coeff_precision = 13;
       }
       否则
       if(encoder- > protected_- > blocksize < = 384)
       编码器- > protected_- > qlp_coeff_precision = MAX_QLP_COEFF_PRECISION-2;
       else if(encoder- > protected_- > blocksize < = 1152)
       编码器- > protected_- > qlp_coeff_precision = MAX_QLP_COEFF_PRECISION-1;
       else
       编码器- > protected_- > qlp_coeff_precision = MAX_QLP_COEFF_PRECISION;
       }
       ASSERT(encoder- > protected_- > qlp_coeff_precision < = MAX_QLP_COEFF_PRECISION);
       }
       else if （编码器- > protected_- > qlp_coeff_precision < MIN_QLP_COEFF_PRECISION || encoder-> protected_- > qlp_coeff_precision > MAX_QLP_COEFF_PRECISION ）
       return encoder->protected_->state = STREAM_ENCODER_INVALID_QLP_COEFF_PRECISION;
       
       if(encoder- > protected_- > streamable_subset) {
       5
       encoder->protected_->blocksize != 192 &&
       encoder- > protected_- > blocksize! = 576 & &
       encoder- > protected_- > blocksize! = 1152 & &
       encoder- > protected_- > blocksize! = 2304 & &
       encoder- > protected_- > blocksize! = 4608 & &
       encoder->protected_->blocksize != 256 &&
       encoder->protected_->blocksize != 512 &&
       encoder->protected_->blocksize != 1024 &&
       encoder- > protected_- > blocksize! = 2048 & &
       encoder- > protected_- > blocksize! = 4096 & &
       encoder->protected_->blocksize != 8192 &&
       encoder->protected_->blocksize != 16384
       )
       返回编码器- > protected_- > state = STREAM_ENCODER_NOT_STREAMABLE;
       5
       encoder- > protected_- > sample_rate! = 8000 & &
       encoder->protected_->sample_rate != 16000 &&
       encoder->protected_->sample_rate != 22050 &&
       encoder->protected_->sample_rate != 24000 &&
       encoder->protected_->sample_rate != 32000 &&
       encoder- > protected_- > sample_rate! = 44100 & &
       encoder->protected_->sample_rate != 48000 &&
       encoder->protected_->sample_rate != 96000
       )
       返回编码器- > protected_- > state = STREAM_ENCODER_NOT_STREAMABLE;
       5
       encoder->protected_->bits_per_sample != 8 &&
       encoder- > protected_- > bits_per_sample! = 12 & &
       encoder->protected_->bits_per_sample != 16 &&
       encoder->protected_->bits_per_sample != 20 &&
       encoder- > protected_- > bits_per_sample! = 24
       )
       返回编码器- > protected_- > state = STREAM_ENCODER_NOT_STREAMABLE;
       if(encoder- > protected_- > max_residual_partition_order > SUBSET_MAX_RICE_PARTITION_ORDER)
       返回编码器- > protected_- > state = STREAM_ENCODER_NOT_STREAMABLE;
       }
       
       if(encoder- > protected_- > max_residual_partition_order > = (1u < < ENTROPY_CODING_METHOD_PARTITIONED_RICE_ORDER_LEN))
       encoder->protected_->max_residual_partition_order = (1u << ENTROPY_CODING_METHOD_PARTITIONED_RICE_ORDER_LEN) - 1;
       if(encoder- > protected_- > min_residual_partition_order > = encoder- > protected_- > max_residual_partition_order)
       encoder- > protected_- > min_residual_partition_order = encoder- > protected_- > max_residual_partition_order;
       
       // validate metadata
        // 校验元数据
        return encoder->protected_->state = STREAM_ENCODER_INVALID_METADATA;
        metadata_has_seektable = false;
        metadata_has_vorbis_comment = false;
        for(i = 0; i < encoder-> protected_- > num_metadata_blocks; i + +) {
        if(encoder- > protected_- > metadata [i] - > type = = METADATA_TYPE_STREAMINFO)
        return encoder->protected_->state = STREAM_ENCODER_INVALID_METADATA;
        else if(encoder->protected_->metadata[i]->type == METADATA_TYPE_SEEKTABLE) {
        if(metadata_has_seektable) // only one is allowed
        return encoder->protected_->state = STREAM_ENCODER_INVALID_METADATA;
        metadata_has_seektable = true;
        if(! format_seektable_is_legal(& encoder- > protected_- > metadata [i] - > data.seek_table))
        return encoder->protected_->state = STREAM_ENCODER_INVALID_METADATA;
        }
        else if(encoder->protected_->metadata[i]->type == METADATA_TYPE_VORBIS_COMMENT) {
        if(metadata_has_vorbis_comment) // only one is allowed 
        return encoder->protected_->state = STREAM_ENCODER_INVALID_METADATA;
        metadata_has_vorbis_comment = true;
        }
        else if(encoder->protected_->metadata[i]->type == METADATA_TYPE_CUESHEET) {
        如果(! format_cuesheet_is_legal(& encoder- > protected_- > metadata [i] - > data.cue_sheet, encoder- > protected_- > metadata [i] - > data.cue_sheet.is_cd, 0))
        return encoder->protected_->state = STREAM_ENCODER_INVALID_METADATA;
        }
        }
        
        encoder- > private_- > input_capacity = 0 ；
        for(i = 0; i < encoder->protected_->channels; i++) {
        encoder- > private_- > integer_signal_unaligned [i] = encoder- > private_- > integer_signal [i] = 0 ；
        编码器- > private_- > real_signal_unaligned [i] =编码器- > private_- > real_signal [i] = 0 ；
        }
        for(i = 0; i < 2; i + +) {
        encoder->private_->integer_signal_mid_side_unaligned[i] = encoder->private_->integer_signal_mid_side[i] = 0;
        编码器- > private_- > real_signal_mid_side_unaligned [i] =编码器- > private_- > real_signal_mid_side [i] = 0 ；
        }
        for(i = 0; i < encoder->protected_->channels; i++) {
        编码器- > private_- > residual_workspace_unaligned [i] [0] =编码器- > private_- > residual_workspace [i] [0] = 0 ；
        encoder- > private_- > residual_workspace_unaligned [i] [1] = encoder- > private_- > residual_workspace [i] [1] = 0 ；
        encoder->private_->best_subframe[i] = 0;
        }
        for(i = 0; i < 2; i + +) {
        编码器- > private_- > residual_workspace_mid_side_unaligned [i] [0] =编码器- > private_- > residual_workspace_mid_side [i] [0] = 0 ；
        编码器- > private_- > residual_workspace_mid_side_unaligned [i] [1] =编码器- > private_- > residual_workspace_mid_side [i] [1] = 0 ；
        编码器- > private_- > best_subframe_mid_side [i] = 0 ；
        }
        编码器- > private_- > abs_residual_unaligned =编码器- > private_- > abs_residual = 0 ；
        编码器- > private_- > abs_residual_partition_sums_unaligned =编码器- > private_- > abs_residual_partition_sums = 0 ；
        encoder- > private_- > raw_bits_per_partition_unaligned = encoder- > private_- > raw_bits_per_partition = 0 ；
        编码器- > private_- > loose_mid_side_stereo_frames_exact = (double) encoder- > protected_- > sample_rate * 0.4/(double) encoder- > protected_- > blocksize ；
        编码器- > private_- > loose_mid_side_stereo_frames = （无符号） （编码器- > private_- > loose_mid_side_stereo_frames_exact + 0.5 ） ；
        if(encoder->private_->loose_mid_side_stereo_frames == 0)
        encoder->private_->loose_mid_side_stereo_frames = 1;
        编码器- > private_- > loose_mid_side_stereo_frame_count = 0 ；
        编码器- > private_- > current_sample_number = 0 ；
        编码器- > private_- > current_frame_number = 0 ；
        
        encoder- > private_- > use_wide_by_block = (encoder- > protected_- > bits_per_sample + bitmath_ilog2(encoder- > protected_- > blocksize) +1 > 30);
        encoder- > private_- > use_wide_by_order = (encoder- > protected_- > bits_per_sample + bitmath_ilog2(max(encoder- > protected_- > max_lpc_order, MAX_FIXED_ORDER)) +1 > 30);//@ @ @需要使用此功能吗？
        encoder->private_->use_wide_by_partition = (false); //@@@ need to set this 
        
        //获取CPU信息并设置函数指针
         cpu_info(& encoder- > private_- > cpuinfo);
         //首次默认为非asm例程
          encoder- > private_- > local_lpc_compute_autocorrelation = lpc_compute_autocorrelation;
          encoder->private_->local_fixed_compute_best_predictor = fixed_compute_best_predictor;
          encoder->private_->local_lpc_compute_residual_from_qlp_coefficients = lpc_compute_residual_from_qlp_coefficients;
          编码器- > private_- > local_lpc_compute_residual_from_qlp_coefficients_64bit = lpc_compute_residual_from_qlp_coefficients_wide ；
          编码器- > private_- > local_lpc_compute_residual_from_qlp_coefficients_16bit = lpc_compute_residual_from_qlp_coefficients ；
          // now override with asm where appropriate 
           # ifndef NO_ASM
           if(encoder- > private_- > cpuinfo.use_asm) {
           #ifdef CPU_IA32
           ASSERT(encoder- > private_- > cpuinfo.type = = CPUINFO_TYPE_IA32);
           # ifdef HAS_NASM
           // 现在在合适处用汇编覆盖
           if(encoder- > private_- > cpuinfo.data.ia32.sse) {
           if(encoder- > protected_- > max_lpc_order < 4)
           编码器- > private_- > local_lpc_compute_autocorrelation = lpc_compute_autocorrelation_asm_ia32_sse_lag_4;
           else if(encoder- > protected_- > max_lpc_order < 8)
           encoder->private_->local_lpc_compute_autocorrelation = lpc_compute_autocorrelation_asm_ia32_sse_lag_8;
           else if(encoder- > protected_- > max_lpc_order < 12)
           编码器- > private_- > local_lpc_compute_autocorrelation = lpc_compute_autocorrelation_asm_ia32_sse_lag_12;
           else
           encoder->private_->local_lpc_compute_autocorrelation = lpc_compute_autocorrelation_asm_ia32;
           }
           else
           ENDIF!
           if(encoder- > private_- > cpuinfo.data.ia32._3dnow)
           编码器- > private_- > local_lpc_compute_autocorrelation = lpc_compute_autocorrelation_asm_ia32_3dnow;
           else
           encoder->private_->local_lpc_compute_autocorrelation = lpc_compute_autocorrelation_asm_ia32;
           if(encoder- > private_- > cpuinfo.data.ia32.mmx & & encoder- > private_- > cpuinfo.data.ia32.cmov)
           encoder->private_->local_fixed_compute_best_predictor = fixed_compute_best_predictor_asm_ia32_mmx_cmov;
           if(encoder- > private_- > cpuinfo.data.ia32.mmx) {
           encoder->private_->local_lpc_compute_residual_from_qlp_coefficients = lpc_compute_residual_from_qlp_coefficients_asm_ia32;
           编码器- > private_- > local_lpc_compute_residual_from_qlp_coefficients_16bit = lpc_compute_residual_from_qlp_coefficients_asm_ia32_mmx ；
           }
           否则
           encoder->private_->local_lpc_compute_residual_from_qlp_coefficients = lpc_compute_residual_from_qlp_coefficients_asm_ia32;
           编码器- > private_- > local_lpc_compute_residual_from_qlp_coefficients_16bit = lpc_compute_residual_from_qlp_coefficients_asm_ia32 ；
           }
           ENDIF!
           ENDIF!
           }
           ENDIF!
           //如有必要，最终基于宽度覆盖
            if(encoder->private_->use_wide_by_block) {
            encoder- > private_- > local_fixed_compute_best_predictor = fixed_compute_best_predictor_wide;
            }
            
            //如果do_escape_coding由于其相互交织的性质，我们需要precompute_partition_sums
             encoder- > private_- > precompute_partition_sums = (encoder- > protected_- > max_residual_partition_order > encoder- > protected_- > min_residual_partition_order) || encoder- > protected_- > do_escape_coding;
             
             if(! resize_buffers_(encoder- > protected_- > blocksize)) {
             // the above function sets the state for us in case of an error 
              返回编码器- > protected_- > state ；
              }
              
              if(! bitbuffer_init(encoder- > private_- > frame))
              return encoder- > protected_- > state = STREAM_ENCODER_MEMORY_ALLOCATION_ERROR;
              
              // Set up the verify stuff if necessary
               if(encoder- > protected_- > verify) {
               // 出错时上述函数会为我们设置状态
                // 必要时设置校验相关内容
                for(i = 0; i < encoder->protected_->channels; i++) {
                // 首先，设置用于存放待比较原始信号的 fifo
                return encoder- > protected_- > state = STREAM_ENCODER_MEMORY_ALLOCATION_ERROR;
                }
                编码器- > private_- > verify.input_fifo.tail = 0 ；
                
                //现在设置一个流解码器进行验证
                 encoder- > private_- > verify.decoder = stream_decoder_new();
                 if(0 == encoder->private_->verify.decoder)
                 return encoder- > protected_- > state = STREAM_ENCODER_VERIFY_DECODER_ERROR;
                 
                 stream_decoder_set_read_callback(encoder- > private_- > verify.decoder, verify_read_callback_);
                 stream_decoder_set_write_callback(encoder- > private_- > verify.decoder, verify_write_callback_);
                 stream_decoder_set_metadata_callback(encoder->private_->verify.decoder, verify_metadata_callback_);
                 stream_decoder_set_error_callback(encoder- > private_- > verify.decoder, verify_error_callback_);
                 stream_decoder_set_client_data(encoder->private_->verify.decoder, encoder);
                 if(stream_decoder_init(encoder->private_->verify.decoder) != STREAM_DECODER_SEARCH_FOR_METADATA)
                 return encoder- > protected_- > state = STREAM_ENCODER_VERIFY_DECODER_ERROR;
                 }
                 encoder->private_->verify.error_stats.absolute_sample = 0;
                 编码器- > private_- > verify.error_stats.frame_number = 0 ；
                 encoder->private_->verify.error_stats.channel = 0;
                 encoder->private_->verify.error_stats.sample = 0;
                 encoder->private_->verify.error_stats.expected = 0;
                 encoder->private_->verify.error_stats.got = 0;
                 
                 // write the stream header
                  if(encoder->protected_->verify)
                  encoder- > private_- > verify.state_hint = ENCODER_IN_MAGIC;
                  if(! bitbuffer_write_raw_uint32(encoder- > private_- > frame, STREAM_SYNC, STREAM_SYNC_LEN))
                  // 写入流头
                  if(! write_bitbuffer_(0)) {
                  //上述函数在发生错误时为我们设置状态
                   返回编码器- > protected_- > state ；
                   }
                   
                   //编写STREAMINFO元数据块
                    if(encoder->protected_->verify)
                    encoder->private_->verify.state_hint = ENCODER_IN_METADATA;
                    encoder- > private_- > metadata.type = METADATA_TYPE_STREAMINFO;
                    encoder- > private_- > metadata.is_last = false;//之后我们将至少有一个VORBIS_COMMENT
                    编码器- > private_- > metadata.length = STREAM_METADATA_STREAMINFO_LENGTH;
                    encoder- > private_- > metadata.data.stream_info.min_blocksize = encoder- > protected_- > blocksize;//此编码器对整个流使用相同的块大小
                    encoder->private_->metadata.data.stream_info.max_blocksize = encoder->protected_->blocksize;
                    encoder->private_->metadata.data.stream_info.min_framesize = 0; // we don't know this yet; have to fill it in later 
                    encoder->private_->metadata.data.stream_info.max_framesize = 0; // we don't know this yet; have to fill it in later 
                    encoder- > private_- > metadata.data.stream_info.sample_rate = encoder- > protected_- > sample_rate ；
                    encoder- > private_- > metadata.data.stream_info.channels = encoder- > protected_- > channels ；
                    encoder- > private_- > metadata.data.stream_info.bits_per_sample = encoder- > protected_- > bits_per_sample ；
                    encoder- > private_- > metadata.data.stream_info.total_samples = encoder- > protected_- > total_samples_estimate;//我们稍后将用实际总数替换此值
                    memset(encoder- > private_- > metadata.data.stream_info.md5sum, 0, 16);//我们还不知道；必须在以后填写
                    MD5Init(& encoder- > private_- > md5context);
                    if(! bitbuffer_clear(encoder- > private_- > frame))
                    return encoder- > protected_- > state = STREAM_ENCODER_MEMORY_ALLOCATION_ERROR;
                    if(! add_metadata_block(& encoder- > private_- > metadata, encoder- > private_- > frame))
                    // 写入流头
                    if(! write_bitbuffer_(0)) {
                    //上述函数在发生错误时为我们设置状态
                     返回编码器- > protected_- > state ；
                     }
                     
                     //现在编写了STREAMINFO块，我们可以将其初始化为荒谬的高值...
                      编码器- > private_- > metadata.data.stream_info.min_framesize = (1u < < STREAM_METADATA_STREAMINFO_MIN_FRAME_SIZE_LEN) - 1;
                      // ... and clear this to 0 
                       encoder- > private_- > metadata.data.stream_info.total_samples = 0 ；
                       
                       // Check to see if the supplied metadata contains a VORBIS_COMMENT;
                        //如果没有，我们将写入一个空值(add_metadata_block()
                         //自动提供供应商字符串）。
                          if(! metadata_has_vorbis_comment) {
                          // ... 并将其清零
                          vorbis_comment.type = METADATA_TYPE_VORBIS_COMMENT;
                          // 检查所提供的元数据是否包含 VORBIS_COMMENT；
                          vorbis_comment.length = 4 + 4; // MAGIC NUMBER 
                          vorbis_comment.data.vorbis_comment.vendor_string.length = 0 ；
                          vorbis_comment.data.vorbis_comment.vendor_string.entry = 0;
                          vorbis_comment.data.vorbis_comment.num_comments = 0;
                          vorbis_comment.data.vorbis_comment.comments = 0;
                          if(! bitbuffer_clear(encoder- > private_- > frame))
                          return encoder- > protected_- > state = STREAM_ENCODER_MEMORY_ALLOCATION_ERROR;
                          if(! add_metadata_block(& vorbis_comment, encoder- > private_- > frame))
                          // 写入流头
                          if(! write_bitbuffer_(0)) {
                          // the above function sets the state for us in case of an error 
                           返回编码器- > protected_- > state ；
                           }
                           }
                           
                           // write the user's metadata blocks 
                            for(i = 0; i < encoder-> protected_- > num_metadata_blocks; i + +) {
                            encoder- > protected_- > metadata [i] - > is_last = (i = = encoder- > protected_- > num_metadata_blocks - 1);
                            if(! bitbuffer_clear(encoder- > private_- > frame))
                            return encoder- > protected_- > state = STREAM_ENCODER_MEMORY_ALLOCATION_ERROR;
                            if(! add_metadata_block(encoder- > protected_- > metadata [i], encoder- > private_- > frame))
                            // 写入流头
                            if(! write_bitbuffer_(0)) {
                            //上述函数在发生错误时为我们设置状态
                             返回编码器- > protected_- > state ；
                             }
                             }
                             
                             if(encoder->protected_->verify)
                             encoder- > private_- > verify.state_hint = ENCODER_IN_AUDIO;
                             
                             返回编码器- > protected_- > state ；
                             }
                             */
    
    /*
     void stream_encoder_finish （ StreamEncoder *编码器）
     {
     ASSERT(0! = encoder);
     
     if(encoder- > protected_- > state = = STREAM_ENCODER_UNINITIALIZED)
     報酬
     
     if(encoder- > protected_- > state = = STREAM_ENCODER_OK & &! encoder- > private_- > is_being_deleted) {
     if(encoder- > private_- > current_sample_number! = 0) {
     encoder->protected_->blocksize = encoder->private_->current_sample_number;
     // 写入用户的元数据块
     }
     }
     
     MD5Final(encoder->private_->metadata.data.stream_info.md5sum, &encoder->private_->md5context);
     
     if(encoder- > protected_- > state = = STREAM_ENCODER_OK & &! encoder- > private_- > is_being_deleted) {
     encoder- > private_- > metadata_callback(& encoder- > private_- > metadata, encoder- > private_- > client_data);
     }
     
     if(encoder- > protected_- > verify & & 0! = encoder- > private_- > verify.decoder)
     stream_decoder_finish(encoder- > private_- > verify.decoder);
     
     free_(encoder);
     set_defaults_(encoder);
     
     encoder- > protected_- > state = STREAM_ENCODER_UNINITIALIZED ；
     }
     */
    public void setVerify(boolean value) {
        if (state != STREAM_ENCODER_UNINITIALIZED) return;
        verify = value;
    }

    public void setStreamableSubset(boolean value) {
        if (state != STREAM_ENCODER_UNINITIALIZED) return;
        streamable_subset = value;
    }

    public void setDoMidSideStereo(boolean value) {
        if (state != STREAM_ENCODER_UNINITIALIZED) return;
        do_mid_side_stereo = value;
    }

    public void setLooseMidSideStereo(boolean value) {
        if (state != STREAM_ENCODER_UNINITIALIZED) return;
        loose_mid_side_stereo = value;
    }

    public void setChannels(int value) {
        if (state != STREAM_ENCODER_UNINITIALIZED) return;
        channels = value;
    }

    public void setBitsPerSample(int value) {
        if (state != STREAM_ENCODER_UNINITIALIZED) return;
        bits_per_sample = value;
    }

    public void setSampleRate(int value) {
        if (state != STREAM_ENCODER_UNINITIALIZED) return;
        sample_rate = value;
    }

    public void setBlocksize(int value) {
        if (state != STREAM_ENCODER_UNINITIALIZED) return;
        blocksize = value;
    }

    public void setMaxLPCOrder(int value) {
        if (state != STREAM_ENCODER_UNINITIALIZED) return;
        max_lpc_order = value;
    }

    public void setQLPCoeffPrecision(int value) {
        if (state != STREAM_ENCODER_UNINITIALIZED) return;
        qlp_coeff_precision = value;
    }

    public void setDoQLPCoeffPrecSearch(boolean value) {
        if (state != STREAM_ENCODER_UNINITIALIZED) return;
        do_qlp_coeff_prec_search = value;
    }

    public void setDoExhaustiveModelSearch(boolean value) {
        if (state != STREAM_ENCODER_UNINITIALIZED) return;
        do_exhaustive_model_search = value;
    }

    public void setMinResidualPartitionOrder(int value) {
        if (state != STREAM_ENCODER_UNINITIALIZED) return;
        min_residual_partition_order = value;
    }

    public void setMaxResidualPartitionOrder(int value) {
        if (state != STREAM_ENCODER_UNINITIALIZED) return;
        max_residual_partition_order = value;
    }

    public void setTotalSamplesEstimate(long value) {
        if (state != STREAM_ENCODER_UNINITIALIZED) return;
        total_samples_estimate = value;
    }
    
    /*
     public void set_metadata(StreamMetadata **metadata, int num_blocks) {
     if(state! = STREAM_ENCODER_UNINITIALIZED)返回；
     metadata = metadata;
     num_metadata_blocks = num_blocks;
     }
     */
    
    /*
     boolean set_write_callback(StreamEncoderWriteCallback值)
     {
     ASSERT(0! = encoder);
     ASSERT(0! = VALUE);
     if(state! = STREAM_ENCODER_UNINITIALIZED)
     return false;
     encoder- > private_- > write_callback = value;
     return true;
     }
     */
    
    /*
     boolean set_metadata_callback(StreamEncoderMetadataCallback值)
     {
     ASSERT(0! = encoder);
     ASSERT(0! = VALUE);
     if(state! = STREAM_ENCODER_UNINITIALIZED)
     return false;
     encoder->private_->metadata_callback = value;
     return true;
     }
     */
    
    /*
     boolean set_client_data(void *value)
     {
     ASSERT(0! = encoder);
     if(state! = STREAM_ENCODER_UNINITIALIZED)
     return false;
     encoder->private_->client_data = value;
     return true;
     }
     */

    /*
     * These three functions are not static, but not publically exposed in
     * include/FLAC/ either.  They are used by the test suite.
     */
    public void disableConstantSubframes(boolean value) {
        if (state != STREAM_ENCODER_UNINITIALIZED) return;
        disable_constant_subframes = value;
    }

    public void disableFixedSubframes(boolean value) {
        if (state != STREAM_ENCODER_UNINITIALIZED) return;
        disable_fixed_subframes = value;
    }

    public void disableVerbatimSubframes(boolean value) {
        if (state != STREAM_ENCODER_UNINITIALIZED) return;
        disable_verbatim_subframes = value;
    }

    public int getState() {
        return state;
    }
    
    /*
     public int getVerifyDecoderState() {
     这三个函数不是 static，但在 include/FLAC/ 中也未公开暴露。它们
     return stream_decoder_get_state(verify.decoder);
     else
     return STREAM_DECODER_UNINITIALIZED;
     }
     */
    
    /*
     供测试套件使用。
     {
     if (state != STREAM_ENCODER_VERIFY_DECODER_ERROR)
     return StreamEncoderStateString[state];
     else
     return StreamDecoderStateString [stream_decoder_get_state(verify.decoder)];
     }
     */
    
    /*
     void get_verify_decoder_error_stats(StreamEncoder * encoder, uint64 * absolute_sample, unsigned * frame_number, unsigned * channel, unsigned * sample, int * expected, int * got)
     {
     ASSERT(0! = encoder);
     if(0! = ABSOLUTE_SAMPLE)
     *absolute_sample = verify.error_stats.absolute_sample;
     if(0! = frame_number)
     *frame_number = verify.error_stats.frame_number;
     if (0 != channel)
     *channel = verify.error_stats.channel;
     if (0 != sample)
     *sample = verify.error_stats.sample;
     if (0 != expected)
     *expected = verify.error_stats.expected;
     if(0! = got)
     *got = verify.error_stats.got;
     }
     */

    public boolean getVerify() {
        return verify;
    }

    public boolean getStreamableSubset() {
        return streamable_subset;
    }

    public boolean getDoMidSideStereo() {
        return do_mid_side_stereo;
    }

    public boolean getLooseMidSideStereo() {
        return loose_mid_side_stereo;
    }

    public int getChannels() {
        return channels;
    }

    public int getBitsPerSample() {
        return bits_per_sample;
    }

    public int getSampleRate() {
        return sample_rate;
    }

    public int getBlocksize() {
        return blocksize;
    }

    public int getMaxLPCOrder() {
        return max_lpc_order;
    }

    public int getQLPCoeffPrecision() {
        return qlp_coeff_precision;
    }

    public boolean getDoQLPCoeffPrecSearch() {
        return do_qlp_coeff_prec_search;
    }

    public boolean getDoEscapeCoding() {
        return do_escape_coding;
    }

    public boolean getDoExhaustiveModelSearch() {
        return do_exhaustive_model_search;
    }

    public int getMinResidualPartitionOrder() {
        return min_residual_partition_order;
    }

    public int getMaxResidualPartitionOrder() {
        return max_residual_partition_order;
    }

    public int getRiceParameterSearchDist() {
        return rice_parameter_search_dist;
    }

    public long getTotalSamplesEstimate() {
        return total_samples_estimate;
    }
    
    /*
     public boolean process(int * buffer[], unsigned samples)
     {
     unsigned i, j, channel;
     int x, mid, side;
     int channels = channels, blocksize = blocksize;
     
     ASSERT(0! = encoder);
     ASSERT(state == STREAM_ENCODER_OK);
     
     j
     if (do_mid_side_stereo && channels == 2) {
     做
     这三个函数不是 static，但在 include/FLAC/ 中也未公开暴露。它们
     append_to_verify_fifo_(&verify.input_fifo, buffer, j, channels, min(blocksize-current_sample_number, samples-j));
     
     for(i = current_sample_number; i < blocksize & & j < samples; i + +, j + +) {
     x = mid = side = buffer [0] [j];
     integer_signal[0][i] = x;
     real_signal[0][i] = (double)x;
     x = buffer [1] [j];
     integer_signal[1][i] = x;
     real_signal[1][i] = (double)x;
     mid += x;
     side - = x;
     mid >>= 1; // NOTE: not the same as 'mid = (buffer[0][j] + buffer[1][j]) / 2' ! 
     integer_signal_mid_side[1][i] = side;
     integer_signal_mid_side [0] [i] = mid;
     real_signal_mid_side[1][i] = (double)side;
     real_signal_mid_side[0][i] = (double)mid;
     current_sample_number + +;
     }
     if(i = = blocksize) {
     if(! process_frame_(false))//false = >不是最后一帧
     return false;
     }
     } while(j < samples);
     }
     否则
     做
     这三个函数不是 static，但在 include/FLAC/ 中也未公开暴露。它们
     append_to_verify_fifo_(&verify.input_fifo, buffer, j, channels, min(blocksize-current_sample_number, samples-j));
     
     for(i = current_sample_number; i < blocksize & & j < samples; i + +, j + +) {
     for(channel = 0; channel < channels; channel + +) {
     x = buffer[channel][j];
     integer_signal [channel] [i] = x;
     real_signal [channel] [i] = (double) x;
     }
     current_sample_number + +;
     }
     if(i = = blocksize) {
     if (!process_frame_(false)) // false => not last frame
     return false;
     }
     } while(j < samples);
     }
     
     return true;
     }
     */
    
    /*
     public boolean process_interleaved(int [] buffer, int samples) {
     int i, j, k, channel;
     int x, mid, side;
     int channels = channels, blocksize = blocksize;
     
     j k
     if (do_mid_side_stereo && channels == 2) {
     做
     这三个函数不是 static，但在 include/FLAC/ 中也未公开暴露。它们
     appendToVerifyFifoInterleaved(verifyData.input_fifo, buffer, j, channels, Math.min(blocksize-current_sample_number, samples-j));
     
     for(i = current_sample_number; i < blocksize & & j < samples; i + +, j + +) {
     x = mid = side = buffer[k++];
     integer_signal[0][i] = x;
     real_signal[0][i] = (double)x;
     x = buffer[k++];
     integer_signal[1][i] = x;
     real_signal[1][i] = (double)x;
     mid += x;
     side - = x;
     mid > > = 1;//注意：与'mid = (left + right)/2'不同！
     integer_signal_mid_side[1][i] = side;
     integer_signal_mid_side [0] [i] = mid;
     real_signal_mid_side[1][i] = (double)side;
     real_signal_mid_side[0][i] = (double)mid;
     current_sample_number + +;
     }
     if(i = = blocksize) {
     if (!processFrame(false)) // false => not last frame
     return false;
     }
     } while(j < samples);
     }
     否则
     做
     这三个函数不是 static，但在 include/FLAC/ 中也未公开暴露。它们
     appendToVerifyFifoInterleaved(verifyData.input_fifo, buffer, j, channels, Math.min(blocksize-current_sample_number, samples-j));
     
     for(i = current_sample_number; i < blocksize & & j < samples; i + +, j + +) {
     for(channel = 0; channel < channels; channel + +) {
     x = buffer[k++];
     integer_signal [channel] [i] = x;
     real_signal [channel] [i] = (double) x;
     }
     current_sample_number + +;
     }
     if(i = = blocksize) {
     if (!processFrame(false)) // false => not last frame
     return false;
     }
     } while(j < samples);
     }
     
     return true;
     }
     */

    /***********************************************************************
     *
     * 私有类方法
     *
     ***********************************************************************/

    public void setDefaults() {

        verify = false;
        streamable_subset = true;
        do_mid_side_stereo = false;
        loose_mid_side_stereo = false;
        channels = 2;
        bits_per_sample = 16;
        sample_rate = 44100;
        blocksize = 1152;
        max_lpc_order = 0;
        qlp_coeff_precision = 0;
        do_qlp_coeff_prec_search = false;
        do_exhaustive_model_search = false;
        do_escape_coding = false;
        min_residual_partition_order = 0;
        max_residual_partition_order = 0;
        rice_parameter_search_dist = 0;
        total_samples_estimate = 0;
        //metadata = null;
        num_metadata_blocks = 0;

        disable_constant_subframes = false;
        disable_fixed_subframes = false;
        disable_verbatim_subframes = false;
        //write_callback = 0;
        //metadata_callback = 0;
        //client_data = 0;
    }
    
    /*
     void free_()
     {
     unsigned i, channel;
     
     ASSERT(0! = encoder);
     for(i = 0; i < channels; i++) {
     if(0! = integer_signal_unaligned [i]) {
     free(integer_signal_unaligned [i]);
     integer_signal_unaligned [i] = 0 ；
     }
     if(0! = real_signal_unaligned [i]) {
     free(real_signal_unaligned[i]);
     real_signal_unaligned [i] = 0 ；
     }
     }
     for(i = 0; i < 2; i + +) {
     if(0! = integer_signal_mid_side_unaligned [i]) {
     free(integer_signal_mid_side_unaligned[i]);
     integer_signal_mid_side_unaligned[i] = 0;
     }
     if(0! = real_signal_mid_side_unaligned [i]) {
     free(real_signal_mid_side_unaligned[i]);
     real_signal_mid_side_unaligned[i] = 0;
     }
     }
     for(channel = 0; channel < channels; channel + +) {
     for(i = 0; i < 2; i + +) {
     if(0! = residual_workspace_unaligned [channel] [i]) {
     free(residual_workspace_unaligned [channel] [i]);
     residual_workspace_unaligned[channel][i] = 0;
     }
     }
     }
     for(channel = 0; channel < 2; channel++) {
     for(i = 0; i < 2; i + +) {
     if(0! = residual_workspace_mid_side_unaligned [channel] [i]) {
     free(residual_workspace_mid_side_unaligned[channel][i]);
     residual_workspace_mid_side_unaligned [通道] [i] = 0 ；
     }
     }
     }
     if (0 != abs_residual_unaligned) {
     free(abs_residual_unaligned);
     abs_residual_unaligned = 0;
     }
     if(0! = abs_residual_partition_sums_unaligned) {
     free(abs_residual_partition_sums_unaligned);
     abs_residual_partition_sums_unaligned = 0 ；
     }
     if(0! = raw_bits_per_partition_unaligned) {
     free(raw_bits_per_partition_unaligned);
     raw_bits_per_partition_unaligned = 0;
     }
     if(verify) {
     for(i = 0; i < channels; i++) {
     if (0 != verifyData.input_fifo.data[i]) {
     free(verifyData.input_fifo.data[i]);
     verifyData.input_fifo.data [i] = 0;
     }
     }
     }
     bitbuffer_free(frame);
     }
     */
    
    /*
     private boolean resize_buffers_(int new_size) {
     boolean ok;
     int i, channel;
     
     //为了避免过度malloc 'ing ，我们只生长缓冲区；不收缩。
      if(new_size < = input_capacity)返回true ；
      
      ok = true;
      
      //WATCHOUT: lpc_compute_residual_from_qlp_coefficients_asm_ia32_mmx()
       //需要输入数组（在我们的例子中是整数信号）
        //前面最多有3个零的缓冲区（在负索引处）
         //对齐目的；我们使用4来保持数据的良好对齐。
          
          for(i = 0; ok && i < channels; i++) {
          ok = ok && memory_alloc_aligned_int32_array(new_size+4, integer_signal_unaligned[i], integer_signal[i]);
          ok = ok & & memory_alloc_aligned_real_array(new_size, real_signal_unaligned [i], real_signal [i]);
          memset(integer_signal [i], 0, sizeof(int) * 4);
          integer_signal[i] += 4;
          }
          for(i = 0; ok & & i < 2; i + +) {
          ok = ok && memory_alloc_aligned_int32_array(new_size+4, &integer_signal_mid_side_unaligned[i], &integer_signal_mid_side[i]);
          ok = ok & & memory_alloc_aligned_real_array(new_size, & real_signal_mid_side_unaligned [i], & real_signal_mid_side [i]);
          memset(integer_signal_mid_side[i], 0, sizeof(int)*4);
          integer_signal_mid_side [i] + = 4 ；
          }
          for(channel = 0; ok & & channel < channels; channel + +) {
          for(i = 0; ok & & i < 2; i + +) {
          ok = ok & & memory_alloc_aligned_int32_array(new_size, & residual_workspace_unaligned [channel] [i], & residual_workspace [channel] [i]);
          }
          }
          for(channel = 0; ok & & channel < 2; channel + +) {
          for(i = 0; ok & & i < 2; i + +) {
          ok = ok & & memory_alloc_aligned_int32_array(new_size, & residual_workspace_mid_side_unaligned [channel] [i], & residual_workspace_mid_side [channel] [i]);
          }
          }
          ok = ok && memory_alloc_aligned_uint32_array(new_size, &abs_residual_unaligned, &abs_residual);
          if(precompute_partition_sums || do_escape_coding)//如果do_escape_coding由于其相互交织的性质，我们需要precompute_partition_sums
          ok = ok & & memory_alloc_aligned_uint64_array(new_size * 2, & abs_residual_partition_sums_unaligned, & abs_residual_partition_sums);
          if (do_escape_coding)
          ok = ok & & memory_alloc_aligned_unsigned_array(new_size * 2, & raw_bits_per_partition_unaligned, & raw_bits_per_partition);
          
          if(ok)
          input_capacity = new_size;
          else
          state = STREAM_ENCODER_MEMORY_ALLOCATION_ERROR;
          
          return ok;
          }
          */
    
    /*
     private boolean writeBitBuffer(int samples) {
     byte [] buffer;
     无符号字节；
     
     bitbuffer_get_buffer(frame, & buffer, & bytes);
     
     if(verify) {
     verifyData.output.data = buffer;
     verifyData.output.bytes = bytes;
     if(verifyData.state_hint = = ENCODER_IN_MAGIC) {
     verifyData.needs_magic_hack = true;
     }
     否则
     if (!stream_decoder_process_single(verifyData.decoder)) {
     bitbuffer_release_buffer(frame);
     if(state! = STREAM_ENCODER_VERIFY_MISMATCH_IN_AUDIO_DATA
     state = STREAM_ENCODER_VERIFY_DECODER_ERROR;
     return false;
     }
     }
     }
     
     if(write_callback(buffer, bytes, samples, current_frame_number, client_data)! = STREAM_ENCODER_WRITE_STATUS_OK ） {
     bitbuffer_release_buffer(frame);
     state = STREAM_ENCODER_FATAL_ERROR_WHILE_WRITING;
     return false;
     }
     
     bitbuffer_release_buffer(frame);
     
     if(samples > 0) {
     metadata.data.stream_info.min_framesize = min(bytes, metadata.data.stream_info.min_framesize);
     metadata.data.stream_info.max_framesize = max(bytes, metadata.data.stream_info.max_framesize);
     }
     
     return true;
     }
     */
    
    /*
     private boolean processFrame(boolean is_last_frame) {
     
     //将原始信号累加到MD5签名
      if (!MD5Accumulate(&md5context, (int * *)integer_signal, channels, blocksize, (bits_per_sample+7) / 8)) {
      state = STREAM_ENCODER_MEMORY_ALLOCATION_ERROR;
      return false;
      }
      
      //将帧头和子帧处理为帧位缓冲区
       if(! process_subframes_(is_last_frame)) {
       //上述函数在发生错误时为我们设置状态
        return false;
        }
        
        //将帧零填充到BYTE_BOUNDARY
         if (!bitbuffer_zero_pad_to_byte_boundary(frame)) {
         state = STREAM_ENCODER_MEMORY_ALLOCATION_ERROR;
         return false;
         }
         
         // CRC-16 the whole thing
          // 对整体做 CRC-16
          bitbuffer_write_raw_uint32(frame, bitbuffer_get_write_crc16(frame), FRAME_FOOTER_CRC_LEN);
          
          // Write it
           // 写入它
           //上述函数在发生错误时为我们设置状态
            return false;
            }
            
            // Get ready for the next frame
             current_sample_number = 0;
             current_frame_number + + ；
             metadata.data.stream_info.total_samples + = (uint64)块大小；
             
             return true;
             }
             */
    
    /*
     // 为下一帧做准备
     Header frame_header;
     int channel, min_partition_order = min_residual_partition_order, max_partition_order;
     boolean do_independent, do_mid_side, precompute_partition_sums;
     
     // Calculate the min,max Rice partition orders
      if(is_last_frame) {
      max_partition_order = 0;
      }
      否则
      max_partition_order = Frame.get_max_rice_partition_order_from_blocksize(blocksize);
      // 计算最小、最大 Rice 分区阶数
      }
      min_partition_order = Math.min(min_partition_order, max_partition_order);
      
      precompute_partition_sums = precompute_partition_sums & & ((max_partition_order > min_partition_order) || do_escape_coding);
      
      //设置框架
       frame.clear();
       frame_header.blockSize =块大小;
       frame_header.sampleRate = sample_rate;
       frame_header.channels = channels;
       frame_header.channelAssignment = Constants.CHANNEL_ASSIGNMENT_INDEPENDENT; // the default unless the encoder determines otherwise
       frame_header.bitsPerSample = bits_per_sample;
       frame_header.frameNumber = CURRENT_FRAME_NUMBER ；
       
       //找出要尝试的频道分配
        if(do_mid_side_stereo) {
        if (loose_mid_side_stereo) {
        if (loose_mid_side_stereo_frame_count == 0) {
        do_independent = true;
        do_mid_side = true;
        }
        否则
        do_independent = (last_channel_assignment == Constants.CHANNEL_ASSIGNMENT_INDEPENDENT);
        do_mid_side =! do_independent;
        }
        }
        否则
        do_independent = true;
        do_mid_side = true;
        }
        }
        否则
        do_independent = true;
        do_mid_side = false;
        }
        
        // Check for wasted bits; set effective bps for each subframe
         // 检查浪费位；为每个子帧设置有效 bps
         for(channel = 0; channel < channels; channel + +) {
         int w = get_wasted_bits_(integer_signal[channel], blocksize);
         subframe_workspace[channel][0].wasted_bits = subframe_workspace[channel][1].wasted_bits = w;
         subframe_bps [channel] = bits_per_sample - w;
         }
         }
         if(do_mid_side) {
         ASSERT(channels = = 2);
         for(channel = 0; channel < 2; channel++) {
         unsigned w = get_wasted_bits_(integer_signal_mid_side[channel], blocksize);
         subframe_workspace_mid_side [channel] [0] .wasted_bits = subframe_workspace_mid_side [channel] [1] .wasted_bits = w;
         subframe_bps_mid_side[channel] = bits_per_sample - w + (channel==0? 0:1);
         }
         }
         
         //首先对每个独立通道进行正常编码传递
          // 检查浪费位；为每个子帧设置有效 bps
          for(channel = 0; channel < channels; channel + +) {
          5
          process_subframe_(
          編碼器
          min_partition_order,
          max_partition_order,
          precompute_partition_sums ，
          frame_header,
          subframe_bps[channel],
          integer_signal[channel],
          real_signal [channel] ，
          subframe_workspace_ptr[channel],
          partitioned_rice_contents_workspace_ptr[channel],
          residual_workspace[channel],
          best_subframe + channel ，
          best_subframe_bits +通道
          )
          )
          return false;
          }
          }
          
          //如果需要，现在进行中间和侧面频道
           if(do_mid_side) {
           
           for(channel = 0; channel < 2; channel++) {
           5
           process_subframe_(
           編碼器
           min_partition_order,
           max_partition_order,
           precompute_partition_sums ，
           frame_header,
           subframe_bps_mid_side [通道] ，
           integer_signal_mid_side [通道] ，
           real_signal_mid_side [通道] ，
           subframe_workspace_ptr_mid_side[channel],
           partitioned_rice_contents_workspace_ptr_mid_side[channel],
           residual_workspace_mid_side[channel],
           best_subframe_mid_side+channel,
           best_subframe_bits_mid_side +通道
           )
           )
           return false;
           }
           }
           
           // Compose the frame bitbuffer
            if(do_mid_side) {
            unsigned left_bps = 0, right_bps = 0;//仅初始化以防止多余的编译器警告
            ChannelBase left_subframe, right_subframe;//仅初始化以防止多余的编译器警告
            // 组装帧 bitbuffer
            
            if (loose_mid_side_stereo && loose_mid_side_stereo_frame_count > 0) {
            channel_assignment = (last_channel_assignment == CHANNEL_ASSIGNMENT_INDEPENDENT? CHANNEL_ASSIGNMENT_INDEPENDENT : CHANNEL_ASSIGNMENT_MID_SIDE);
            }
            否则
            int [] bits = new int [4];//WATCHOUT -由ChannelAssignment索引
            int min_bits;
            int ca;
            
            // We have to figure out which channel assignent results in the smallest frame
             // 我们需确定哪种声道分配产生最小的帧
             bits [CHANNEL_ASSIGNMENT_LEFT_SIDE] = best_subframe_bits [0] + best_subframe_bits_mid_side [1] ；
             bits[CHANNEL_ASSIGNMENT_RIGHT_SIDE ] = best_subframe_bits         [1] + best_subframe_bits_mid_side[1];
             bits [CHANNEL_ASSIGNMENT_MID_SIDE] = best_subframe_bits_mid_side [0] + best_subframe_bits_mid_side [1] ；
             
             for(channel_assignment = (ChannelAssignment) 0, min_bits = bits [0], ca = (ChannelAssignment) 1; (int) ca < = 3; ca = (ChannelAssignment) ((int) ca + 1)) {
             if(bits [ca] < min_bits) {
             min_bits = bits [ca];
             channel_assignment = ca;
             }
             }
             }
             
             frame_header.channel_assignment = channel_assignment;
             
             if(! frame_add_header(frame_header, streamable_subset, is_last_frame, frame)) {
             state = STREAM_ENCODER_FRAMING_ERROR;
             return false;
             }
             
             switch(channel_assignment) {
             case CHANNEL_ASSIGNMENT_INDEPENDENT:
             left_subframe = subframe_workspace [0] [best_subframe [0]];
             right_subframe = subframe_workspace [1] [best_subframe [1]];
             break;
             案例CHANNEL_ASSIGMENT_LEFT_SIDE
             left_subframe = subframe_workspace [0] [best_subframe [0]];
             right_subframe = subframe_workspace_mid_side[1][best_subframe_mid_side[1]];
             break;
             案例CHANNEL_ASSIGNMENT_RIGHT_SIDE ：
             left_subframe  = subframe_workspace_mid_side[1][best_subframe_mid_side[1]];
             right_subframe = subframe_workspace [1] [best_subframe [1]];
             break;
             案例CHANNEL_ASSIGNMENT_MID_SIDE ：
             left_subframe  = subframe_workspace_mid_side[0][best_subframe_mid_side[0]];
             right_subframe = subframe_workspace_mid_side[1][best_subframe_mid_side[1]];
             break;
             默认：
             ɏһ¼¶
             }
             
             switch(channel_assignment) {
             case CHANNEL_ASSIGNMENT_INDEPENDENT:
             left_bps  = subframe_bps         [0];
             right_bps = subframe_bps         [1];
             break;
             案例CHANNEL_ASSIGMENT_LEFT_SIDE
             left_bps  = subframe_bps         [0];
             right_bps = subframe_bps_mid_side[1];
             break;
             案例CHANNEL_ASSIGNMENT_RIGHT_SIDE ：
             left_bps = subframe_bps_mid_side [1];
             right_bps = subframe_bps         [1];
             break;
             案例CHANNEL_ASSIGNMENT_MID_SIDE ：
             left_bps  = subframe_bps_mid_side[0];
             right_bps = subframe_bps_mid_side[1];
             break;
             默认：
             ɏһ¼¶
             }
             
             // note that encoder_add_subframe_ sets the state for us in case of an error
              if(! add_subframe_(frame_header, left_bps, left_subframe, frame))
              return false;
              // 注意，出错时 encoder_add_subframe_ 会为我们设置状态
              return false;
              }
              否则
              if(! frame_add_header(frame_header, streamable_subset, is_last_frame, frame)) {
              state = STREAM_ENCODER_FRAMING_ERROR;
              return false;
              }
              
              for(channel = 0; channel < channels; channel + +) {
              if(! add_subframe_(frame_header, subframe_bps [channel], subframe_workspace [channel] [best_subframe [channel]], frame)) {
              //上述函数在发生错误时为我们设置状态
               return false;
               }
               }
               }
               
               if (loose_mid_side_stereo) {
               loose_mid_side_stereo_frame_count + + ；
               if (loose_mid_side_stereo_frame_count >= loose_mid_side_stereo_frames)
               loose_mid_side_stereo_frame_count = 0;
               }
               
               last_channel_assignment = frame_header.channel_assignment;
               
               return true;
               }
               */
    
    /*
     private boolean processSubframe(int min_partition_order,
     int max_partition_order,
     boolean precompute_partition_sums,
     HEADER FRAME_HEADER ，
     int subframe_bps,
     int [] integer_signal ，
     double[] real_signal,
     子帧[]子帧] ，
     EntropyPartitionedRiceContents [] partitioned_rice_contents ，
     int [] residual,
     int * best_subframe ，
     int * best_bits
     )
     {
     double [] fixed_residual_bits_per_sample = new double [MAX_FIXED_ORDER +1];
     double lpc_residual_bits_per_sample;
     double [] autoc = new double [MAX_LPC_ORDER +1];//WATCHOUT ：即使max_lpc_order可能更小，大小也很重要；一些asm例程需要所有空间
     double [] lpc_error = new double [MAX_LPC_ORDER];
     int min_lpc_order, max_lpc_order, lpc_order;
     int min_fixed_order, max_fixed_order, guess_fixed_order, fixed_order;
     int min_qlp_coeff_precision、max_qlp_coeff_precision、qlp_coeff_precision ；
     int rice_parameter;
     int _candidate_bits, _best_bits;
     int _best_subframe;
     
     // verbatim subframe is the baseline against which we measure other compressed subframes
      // 逐字子帧是衡量其他压缩子帧的基线
      if(disable_verbatim_subframes & & frame_header.blocksize > = MAX_FIXED_ORDER)
      _best_bits = UINT_MAX;
      else
      _best_bits = evaluateVerbatimSubframe(integer_signal, frame_header.blocksize, subframe_bps, subframe [_best_subframe]);
      
      if (frame_header.blocksize >= MAX_FIXED_ORDER) {
      unsigned signal_is_constant = false;
      guess_fixed_order = local_fixed_compute_best_predictor(integer_signal+MAX_FIXED_ORDER, frame_header.blocksize-MAX_FIXED_ORDER, fixed_residual_bits_per_sample);
      // check for constant subframe
       if(! disable_constant_subframes & & fixed_residual_bits_per_sample [1] = = 0.0) {
       //以上意味着INTEGER_SIGNAL + MAX_FIXED_ORDER是常量，现在我们只需要检查预热样本
        unsigned i;
        signal_is_constant = true;
        for(i = 1; i < = MAX_FIXED_ORDER; i + +) {
        // 检查是否为常数子帧
        signal_is_constant = false;
        break;
        }
        }
        }
        if (signal_is_constant) {
        _candidate_bits = evaluate_constant_subframe_(integer_signal[0], subframe_bps, subframe[!_best_subframe]);
        if (_candidate_bits < _best_bits) {
        _best_subframe =!_best_subframe;
        _best_bits =_candidate_bits;
        }
        }
        否则
        if(! disable_fixed_subframes || (max_lpc_order = = 0 & &_best_bits = = UINT_MAX)) {
        // encode fixed
         if(do_exhaustive_model_search) {
         min_fixed_order = 0;
         max_fixed_order = MAX_FIXED_ORDER;
         }
         否则
         // 编码为 fixed
         }
         for(fixed_order = min_fixed_order; fixed_order < = max_fixed_order; fixed_order + +) {
         if (fixed_residual_bits_per_sample[fixed_order] >= (double)subframe_bps)
         continue; // don't even try
         rice_parameter = (fixed_residual_bits_per_sample [fixed_order] > 0.0)? (unsigned) (fixed_residual_bits_per_sample [fixed_order] +0.5): 0;//0.5用于舍入
         rice_parameter + +;//解释水稻编码期间的签名- >未签名转换
         if (rice_parameter >= ENTROPY_CODING_METHOD_PARTITIONED_RICE_ESCAPE_PARAMETER) {
         fprintf(stderr, "clipping rice_parameter (%u -> %u) @0\n", rice_parameter, ENTROPY_CODING_METHOD_PARTITIONED_RICE_ESCAPE_PARAMETER - 1);
         rice_parameter = ENTROPY_CODING_METHOD_PARTITIONED_RICE_ESCAPE_PARAMETER - 1;
         }
         _candidate_bits =
         evaluate_fixed_subframe_(
         編碼器
         integer_signal ，
         剩余[!_best_subframe] ，
         abs_residual,
         abs_residual_partition_sums ，
         raw_bits_per_partition ，
         frame_header.blocksize、
         subframe_bps,
         fixed_order,
         rice_parameter,
         min_partition_order,
         max_partition_order,
         precompute_partition_sums ，
         do_escape_coding ，
         rice_parameter_search_dist,
         subframe[!_best_subframe],
         partitioned_rice_contents [!_best_subframe]
         );
         if (_candidate_bits < _best_bits) {
         _best_subframe =!_best_subframe;
         _best_bits =_candidate_bits;
         }
         }
         }
         
         //encode lpc
          if(max_lpc_order > 0) {
          if (max_lpc_order >= frame_header.blocksize)
          max_lpc_order = frame_header.blocksize-1;
          else
          max_lpc_order = max_lpc_order;
          if(max_lpc_order > 0) {
          local_lpc_compute_autocorrelation(real_signal, frame_header.blocksize, max_lpc_order+1, autoc);
          // if autoc[0] == 0.0, the signal is constant and we usually won't get here, but it can happen
           // 若 autoc[0] == 0.0，则信号为常数，通常不会走到这里，但也可能发生
           lpc_compute_lp_coefficients(autoc, max_lpc_order, lp_coeff, lpc_error);
           if(do_exhaustive_model_search) {
           min_lpc_order = 1;
           }
           否则
           unsigned guess_lpc_order = lpc_compute_best_order(lpc_error, max_lpc_order, frame_header.blocksize, subframe_bps);
           min_lpc_order = max_lpc_order = guess_lpc_order;
           }
           for(lpc_order = min_lpc_order; lpc_order <= max_lpc_order; lpc_order++) {
           lpc_residual_bits_per_sample = lpc_compute_expected_bits_per_residual_sample(lpc_error[lpc_order-1], frame_header.blocksize-lpc_order);
           if(lpc_residual_bits_per_sample > = (double) subframe_bps)
           continue; // don't even try
           rice_parameter = (lpc_residual_bits_per_sample > 0.0)? (unsigned) (lpc_residual_bits_per_sample+0.5): 0;//0.5为舍入
           rice_parameter + +;//解释水稻编码期间的签名- >未签名转换
           if (rice_parameter >= ENTROPY_CODING_METHOD_PARTITIONED_RICE_ESCAPE_PARAMETER) {
           fprintf(stderr, "clipping rice_parameter (%u -> %u) @1\n", rice_parameter, ENTROPY_CODING_METHOD_PARTITIONED_RICE_ESCAPE_PARAMETER - 1);
           rice_parameter = ENTROPY_CODING_METHOD_PARTITIONED_RICE_ESCAPE_PARAMETER - 1;
           }
           if(do_qlp_coeff_prec_search) {
           min_qlp_coeff_precision = MIN_QLP_COEFF_PRECISION;
           // ensure a 32-bit datapath throughout for 16bps or less
            // 对于 16bps 及以下，全程确保 32 位数据通路
            max_qlp_coeff_precision = min(32 - subframe_bps - lpc_order, MAX_QLP_COEFF_PRECISION);
            else
            max_qlp_coeff_precision = MAX_QLP_COEFF_PRECISION;
            }
            否则
            min_qlp_coeff_precision = max_qlp_coeff_precision = qlp_coeff_precision ；
            }
            for(qlp_coeff_precision = min_qlp_coeff_precision; qlp_coeff_precision < = max_qlp_coeff_precision; qlp_coeff_precision + +) {
            _candidate_bits =
            evaluate_lpc_subframe_(
            編碼器
            integer_signal ，
            剩余[!_best_subframe] ，
            abs_residual,
            abs_residual_partition_sums ，
            raw_bits_per_partition ，
            lp_coeff[lpc_order-1],
            frame_header.blocksize、
            subframe_bps,
            lpc_order,
            qlp_coeff_precision,
            rice_parameter,
            min_partition_order,
            max_partition_order,
            precompute_partition_sums ，
            do_escape_coding ，
            rice_parameter_search_dist,
            subframe[!_best_subframe],
            partitioned_rice_contents [!_best_subframe]
            );
            如果(_candidate_bits > 0) {//if = = 0 ，则量化lpcoeffs时出现问题
            if (_candidate_bits < _best_bits) {
            _best_subframe =!_best_subframe;
            _best_bits =_candidate_bits;
            }
            }
            }
            }
            }
            }
            }
            }
            }
            
            //在极少数情况下，当禁用除lpc子帧类型之外的所有子帧类型时，可能会发生这种情况：
             if(_best_bits = = UINT_MAX) {
             ASSERT(_best_subframe = = 0);
             _best_bits = evaluateVerbatimSubframe(integer_signal, frame_header.blocksize, subframe_bps, subframe [_best_subframe]);
             }
             
             *best_subframe =_best_subframe;
             *best_bits =_best_bits;
             
             return true;
             }
             */
    
    /*
     private boolean addSubframe(Header frame_header, int subframe_bps, ChannelBase subframe, BitBuffer frame) {
     switch(subframe->type) {
     case SUBFRAME_TYPE_CONSTANT:
     if(! subframe_add_constant(& (subframe- > data.constant), subframe_bps, subframe- > wasted_bits, frame)) {
     state = STREAM_ENCODER_FATAL_ERROR_WHILE_ENCODING;
     return false;
     }
     break;
     case SUBFRAME_TYPE_FIXED:
     if(! subframe_add_fixed(& (subframe- > data.fixed), frame_header.blocksize - subframe- > data.fixed.order, subframe_bps, subframe- > wasted_bits, frame)) {
     state = STREAM_ENCODER_FATAL_ERROR_WHILE_ENCODING;
     return false;
     }
     break;
     case SUBFRAME_TYPE_LPC:
     if(! subframe_add_lpc(& (subframe- > data.lpc), frame_header.blocksize - subframe- > data.lpc.order, subframe_bps, subframe- > wasted_bits, frame)) {
     state = STREAM_ENCODER_FATAL_ERROR_WHILE_ENCODING;
     return false;
     }
     break;
     case SUBFRAME_TYPE_VERBATIM:
     if (!subframe_add_verbatim(&(subframe->data.verbatim), frame_header.blocksize, subframe_bps, subframe->wasted_bits, frame)) {
     state = STREAM_ENCODER_FATAL_ERROR_WHILE_ENCODING;
     return false;
     }
     break;
     默认：
     ɏһ¼¶
     }
     
     return true;
     }
     */
    
    /*
     private int evaluateConstantSubframe(int signal, int subframe_bps, ChannelBase subframe) {
     subframe- > type = SUBFRAME_TYPE_CONSTANT;
     subframe->data.constant.value = signal;
     
     return SUBFRAME_ZERO_PAD_LEN + SUBFRAME_TYPE_LEN + SUBFRAME_WASTED_BITS_FLAG_LEN + subframe_bps ；
     }
     */
    
    /*
     private int evaluateFixedSubframe(int [] signal, int [] residual, int [] abs_residual, long [] abs_residual_partition_sums, int [] raw_bits_per_partition, int blocksize, int subframe_bps, int order, int rice_parameter, int min_partition_order, int max_partition_order, boolean precompute_partition_sums, boolean do_escape_coding, int rice_parameter_search_dist, ChannelBase子帧, EntropyPartitionedRiceContents partitioned_rice_contents) {
     unsigned i, residual_bits;
     unsigned residual_samples = blocksize -订单；
     
     fixed_compute_residual(signal + order, residual_samples, order, residual);
     
     subframe->type = SUBFRAME_TYPE_FIXED;
     
     subframe->data.fixed.entropy_coding_method.type = ENTROPY_CODING_METHOD_PARTITIONED_RICE;
     subframe- > data.fixed.entropy_coding_method.data.partitioned_rice.contents = partitioned_rice_contents;
     subframe->data.fixed.residual = residual;
     
     residual_bits =
     find_best_partition_order_(
     encoder- > private_,
     零头
     abs_residual,
     abs_residual_partition_sums ，
     raw_bits_per_partition ，
     residual_samples,
     订单
     rice_parameter,
     min_partition_order,
     max_partition_order,
     precompute_partition_sums ，
     do_escape_coding ，
     rice_parameter_search_dist,
     &subframe->data.fixed.entropy_coding_method.data.partitioned_rice
     );
     
     subframe- > data.fixed.order = order;
     for(i = 0; i < order; i + +)
     subframe- > data.fixed.warmup [i] = signal [i] ；
     
     return SUBFRAME_ZERO_PAD_LEN + SUBFRAME_TYPE_LEN + SUBFRAME_WASTED_BITS_FLAG_LEN + (order * subframe_bps) + residual_bits;
     }
     */
    
    /*
     private int evaluateLPCSubframe(int [] signal, int [] residual, int [] abs_residual, long [] abs_residual_partition_sums, int [] raw_bits_per_partition, double [] lp_coeff, int blocksize, int subframe_bps, int order, int qlp_coeff_precision, int rice_parameter, int min_partition_order, int max_partition_order, boolean precompute_partition_sums, boolean do_escape_coding, int rice_parameter_search_dist, ChannelBase subframe, EntropyPartitionedRiceContents partitioned_rice_contents) {
     int [] qlp_coeff = new int [MAX_LPC_ORDER];
     int i, residual_bits;
     int quantization, ret;
     int residual_samples = blocksize - order;
     
     // try to keep qlp coeff precision such that only 32-bit math is required for decode of <=16bps streams
      if(subframe_bps < = 16) {
      // 尽量保持 qlp 系数量精度，使 <=16bps 流的解码只需 32 位运算
      ASSERT(order <= MAX_LPC_ORDER);
      qlp_coeff_precision = min(qlp_coeff_precision, 32 - subframe_bps - bitmath_ilog2(order));
      }
      
      ret = lpc_quantize_coefficients(lp_coeff, order, qlp_coeff_precision, qlp_coeff, quantization);
      if(ret! = 0)
      return 0;//这是一个hack ，用于向调用者表明我们无法在此子帧上以此顺序执行lp
      
      if (subframe_bps + qlp_coeff_precision + bitmath_ilog2(order) <= 32)
      if (subframe_bps <= 16 && qlp_coeff_precision <= 16)
      local_lpc_compute_residual_from_qlp_coefficients_16bit(signal + order, residual_samples, qlp_coeff, order, quantization, residual);
      else
      local_lpc_compute_residual_from_qlp_coefficients(signal + order, residual_samples, qlp_coeff, order, quantization, residual);
      else
      local_lpc_compute_residual_from_qlp_coefficients_64bit(signal+order, residual_samples, qlp_coeff, order, quantization, residual);
      
      subframe- > type = SUBFRAME_TYPE_LPC;
      
      subframe->data.lpc.entropy_coding_method.type = ENTROPY_CODING_METHOD_PARTITIONED_RICE;
      subframe- > data.lpc.entropy_coding_method.data.partitioned_rice.contents = partitioned_rice_contents;
      subframe- > data.lpc.residual = residual;
      
      residual_bits =
      find_best_partition_order_(
      encoder- > private_,
      零头
      abs_residual,
      abs_residual_partition_sums ，
      raw_bits_per_partition ，
      residual_samples,
      订单
      rice_parameter,
      min_partition_order,
      max_partition_order,
      precompute_partition_sums ，
      do_escape_coding ，
      rice_parameter_search_dist,
      &subframe->data.fixed.entropy_coding_method.data.partitioned_rice
      );
      
      subframe- > data.lpc.order = order;
      subframe- > data.lpc.qlp_coeff_precision = qlp_coeff_precision;
      subframe->data.lpc.quantization_level = quantization;
      memcpy(subframe->data.lpc.qlp_coeff, qlp_coeff, sizeof(int)*MAX_LPC_ORDER);
      for(i = 0; i < order; i + +)
      subframe- > data.lpc.warmup [i] = signal [i] ；
      
      return SUBFRAME_ZERO_PAD_LEN + subframe_TYPE_LEN + subframe_WASTED_BITS_FLAG_LEN + subframe_LPC_QLP_COEFF_PRECISION_LEN + subframe_LPC_QLP_SHIFT_LEN + (order * (qlp_coeff_precision + subframe_bps)) + residual_bits;
      }
      */
    
    /*
     private int evaluateVerbatimSubframe(int[] signal, int blocksize, int subframe_bps, ChannelVerbatim subframe) {
     
     subframe.data = signal;
     
     return SUBFRAME_ZERO_PAD_LEN + SUBFRAME_TYPE_LEN + SUBFRAME_WASTED_BITS_FLAG_LEN + (blocksize * subframe_bps);
     }
     */
    
    /*
     private int findBestPartitionOrder(int [] residual, int [] abs_residual, long [] abs_residual_partition_sums, int [] raw_bits_per_partition, int residual_samples, int predictor_order, int rice_parameter, int min_partition_order, int max_partition_order, boolean precompute_partition_sums, boolean do_escape_coding, int rice_parameter_search_dist, EntropyPartitionedRice best_partitioned_rice) {
     int r;
     unsigned residual_bits, best_residual_bits = 0;
     unsigned residual_sample;
     unsigned best_parameters_index = 0;
     unsigned blocksize = residual_samples + predictor_order;
     
     //计算abs （残差）供以后使用
      for(residual_sample = 0; residual_sample < residual_samples; residual_sample++) {
      r =残留[residual_sample] ；
      abs_residual[residual_sample] = (uint32)(r<0? -r : r);
      }
      
      max_partition_order = format_get_max_rice_partition_order_from_blocksize_limited_max_and_predictor_order(max_partition_order, blocksize, predictor_order);
      min_partition_order = min(min_partition_order, max_partition_order);
      
      if (precompute_partition_sums) {
      int partition_order;
      unsigned sum;
      
      precompute_partition_info_sums_(abs_residual, abs_residual_partition_sums, residual_samples, predictor_order, min_partition_order, max_partition_order);
      
      if (do_escape_coding)
      precompute_partition_info_escapes_(residual, raw_bits_per_partition, residual_samples, predictor_order, min_partition_order, max_partition_order);
      
      for(partition_order = (int) max_partition_order, sum = 0; partition_order > = (int) min_partition_order; partition_order--) {
      5
      set_partitioned_rice_with_precompute_(
      abs_residual,
      abs_residual_partition_sums+sum,
      raw_bits_per_partition + sum,
      residual_samples,
      predictor_order,
      rice_parameter,
      rice_parameter_search_dist,
      (unsigned) partition_order,
      do_escape_coding ，
      & partitioned_rice_contents_extra [! best_parameters_index] ，
      & residual_bits
      )
      )
      {
      ASSERT(best_residual_bits! = 0);
      break;
      }
      sum + = 1 < < partition_order;
      if(best_residual_bits = = 0 || residual_bits < best_residual_bits) {
      best_residual_bits = residual_bits;
      best_parameters_index =! best_parameters_index;
      best_partitioned_rice.order = partition_order;
      }
      }
      }
      否则
      unsigned partition_order;
      for(partition_order = min_partition_order; partition_order <= max_partition_order; partition_order++) {
      5
      set_partitioned_rice_(
      abs_residual,
      residual_samples,
      predictor_order,
      rice_parameter,
      rice_parameter_search_dist,
      partition_order,
      & partitioned_rice_contents_extra [! best_parameters_index] ，
      & residual_bits
      )
      )
      {
      break;
      }
      if(best_residual_bits = = 0 || residual_bits < best_residual_bits) {
      best_residual_bits = residual_bits;
      best_parameters_index =! best_parameters_index;
      best_partitioned_rice.order = partition_order;
      }
      }
      }
      
      //根据我们的专业知识，我们可以删除指针；指向外面的世界。
       {
       EntropyPartitionedRiceContents best_partitioned_rice_contents = (EntropyPartitionedRiceContents)best_partitioned_rice->contents;
       format_entropy_coding_method_partitioned_rice_contents_ensure_size(best_partitioned_rice_contents, max(6, best_partitioned_rice->order));
       memcpy(best_partitioned_rice_contents- > parameters, partitioned_rice_contents_extra [best_parameters_index] .parameters, sizeof(unsigned) * (1 < <(best_partitioned_rice-> order)));
       memcpy(best_partitioned_rice_contents->raw_bits, partitioned_rice_contents_extra[best_parameters_index].raw_bits, sizeof(unsigned)*(1<<(best_partitioned_rice->order)));
       }
       
       return best_residual_bits;
       }
       */
    
    /*
     private void precomputePartitionInfoSums （
     int[] abs_residual,
     long [] abs_residual_partition_sums ，
     int residual_samples,
     int predictor_order,
     int min_partition_order,
     int max_partition_order
     )
     {
     int partition_order;
     unsigned from_partition, to_partition = 0;
     unsigned blocksize = residual_samples + predictor_order;
     
     // first do max_partition_order
      for(partition_order = (int)max_partition_order; partition_order >= 0; partition_order--) {
      long abs_residual_partition_sum ；
      int abs_r;
      // 先处理 max_partition_order
      int partitions = 1 < < partition_order;
      int default_partition_samples = blocksize > > partition_order;
      
      for(partition = residual_sample = 0; partition < partitions; partition + +) {
      partition_samples = default_partition_samples ；
      if(partition = = 0)
      partition_samples - = predictor_order ；
      abs_residual_partition_sum = 0;
      for(partition_sample = 0; partition_sample < partition_samples; partition_sample++) {
      abs_r = abs_residual[residual_sample];
      abs_residual_partition_sum += abs_r;
      residual_sample + +;
      }
      abs_residual_partition_sums [partition] = abs_residual_partition_sum ；
      }
      to_partition =分区；
      break;
      }
      
      //现在合并分区以获得较低的订单
       for(from_partition = 0, --partition_order; partition_order > = (int) min_partition_order; partition_order--) {
       long s;
       int i;
       int partitions = 1 < < partition_order;
       for(i = 0; i < partitions; i++) {
       s = abs_residual_partition_sums [from_partition] ；
       from_partition + + ；
       abs_residual_partition_sums[to_partition] = s + abs_residual_partition_sums[from_partition];
       from_partition + + ；
       to_partition++;
       }
       }
       }
       */
    
    /*
     private void precomputePartitionInfoEscapes （
     int [] residual,
     int[] raw_bits_per_partition,
     int residual_samples,
     int predictor_order,
     int min_partition_order,
     int max_partition_order
     )
     {
     int partition_order;
     unsigned from_partition, to_partition = 0;
     unsigned blocksize = residual_samples + predictor_order;
     
     // first do max_partition_order
      for(partition_order = (int)max_partition_order; partition_order >= 0; partition_order--) {
      int r, residual_partition_min, residual_partition_max;
      int silog2_min, silog2_max;
      // 先处理 max_partition_order
      int partitions = 1 < < partition_order;
      int default_partition_samples = blocksize > > partition_order;
      
      for(partition = residual_sample = 0; partition < partitions; partition + +) {
      partition_samples = default_partition_samples ；
      if(partition = = 0)
      partition_samples - = predictor_order ；
      residual_partition_min = residual_partition_max = 0 ；
      for(partition_sample = 0; partition_sample < partition_samples; partition_sample++) {
      r =残留[residual_sample] ；
      if(r < residual_partition_min)
      residual_partition_min = r;
      else if (r > residual_partition_max)
      residual_partition_max = r;
      residual_sample + +;
      }
      silog2_min = bitmath_silog2(residual_partition_min);
      silog2_max = bitmath_silog2(residual_partition_max);
      raw_bits_per_partition [partition] = max(silog2_min, silog2_max);
      }
      to_partition =分区；
      break;
      }
      
      //现在合并分区以获得较低的订单
       for(from_partition = 0, --partition_order; partition_order > = (int) min_partition_order; partition_order--) {
       int m;
       int i;
       int partitions = 1 < < partition_order;
       for(i = 0; i < partitions; i++) {
       m = raw_bits_per_partition [from_partition];
       from_partition + + ；
       raw_bits_per_partition [to_partition] = max(m, raw_bits_per_partition [from_partition]);
       from_partition + + ；
       to_partition++;
       }
       }
       }
       */
    
    /*
     private VARIABLE_RICE_BITS(value, parameter) {return((value) > > (parameter));}
     
     boolean set_partitioned_rice_(
     int[] abs_residual,
     int residual_samples,
     int predictor_order,
     int suggested_rice_parameter ，
     int rice_parameter_search_dist,
     int partition_order,
     EntropyPartitionedRiceContents partitioned_rice_contents,
     int * bits
     )
     {
     int rice_parameter, partition_bits;
     int best_partition_bits;
     int min_rice_parameter ， max_rice_parameter ， best_rice_parameter = 0 ；
     int bits_ = ENTROPY_CODING_METHOD_TYPE_LEN + ENTROPY_CODING_METHOD_PARTITIONED_RICE_ORDER_LEN;
     int *parameters;
     
     format_entropy_coding_method_partitioned_rice_contents_ensure_size(partitioned_rice_contents, max(6, partition_order));
     parameters = partitioned_rice_contents.parameters;
     
     if (partition_order == 0) {
     unsigned i;
     
     if(rice_parameter_search_dist) {
     if (suggested_rice_parameter < rice_parameter_search_dist)
     min_rice_parameter = 0;
     else
     min_rice_parameter = suggested_rice_parameter - rice_parameter_search_dist;
     max_rice_parameter = suggested_rice_parameter + rice_parameter_search_dist;
     if (max_rice_parameter >= ENTROPY_CODING_METHOD_PARTITIONED_RICE_ESCAPE_PARAMETER) {
     fprintf(stderr, "clipping rice_parameter(% u - > % u) @ 2\ n", max_rice_parameter, ENTROPY_CODING_METHOD_PARTITIONED_RICE_ESCAPE_PARAMETER - 1);
     max_rice_parameter = ENTROPY_CODING_METHOD_PARTITIONED_RICE_ESCAPE_PARAMETER - 1;
     }
     }
     else
     min_rice_parameter = max_rice_parameter = suggested_rice_parameter;
     
     best_partition_bits = 0xffffffff;
     for(rice_parameter = min_rice_parameter; rice_parameter <= max_rice_parameter; rice_parameter++) {
     unsigned rice_parameter_estimate = rice_parameter-1;
     partition_bits = (1+rice_parameter) * residual_samples;
     partition_bits + = ENTROPY_CODING_METHOD_PARTITIONED_RICE_PARAMETER_LEN;
     for(i = 0; i < residual_samples; i++) {
     partition_bits + = VARIABLE_RICE_BITS(abs_residual [i], rice_parameter_estimate);
     }
     if (partition_bits < best_partition_bits) {
     best_rice_parameter = rice_parameter;
     best_partition_bits = partition_bits;
     }
     }
     parameters [0] = best_rice_parameter;
     bits_+ = best_partition_bits ；
     }
     否则
     unsigned partition, residual_sample, save_residual_sample, partition_sample;
     unsigned partition_samples;
     uint64 mean, k;
     int partitions = 1 < < partition_order;
     for(partition = residual_sample = 0; partition < partitions; partition + +) {
     partition_samples = (residual_samples+predictor_order) >> partition_order;
     if(partition = = 0) {
     if(partition_samples < = predictor_order)
     return false;
     else
     partition_samples - = predictor_order ；
     }
     Mean<g id="3331">0</g><g id="3332">:</g>
     save_residual_sample = residual_sample;
     for(partition_sample = 0; partition_sample < partition_samples; residual_sample + +, partition_sample + +)
     mean + = abs_residual [residual_sample] ；
     residual_sample = save_residual_sample;
     //calc rice_parameter ala LOCO-I
      for(rice_parameter = 0, k = partition_samples; k < mean; rice_parameter + +, k < < = 1)
      ;
      if (rice_parameter >= ENTROPY_CODING_METHOD_PARTITIONED_RICE_ESCAPE_PARAMETER) {
      fprintf(stderr, "clipping rice_parameter(% u - > % u) @ 3\ n", rice_parameter, ENTROPY_CODING_METHOD_PARTITIONED_RICE_ESCAPE_PARAMETER - 1);
      rice_parameter = ENTROPY_CODING_METHOD_PARTITIONED_RICE_ESCAPE_PARAMETER - 1;
      }
      
      if(rice_parameter_search_dist) {
      if(rice_parameter < rice_parameter_search_dist)
      min_rice_parameter = 0;
      else
      min_rice_parameter = rice_parameter - rice_parameter_search_dist;
      max_rice_parameter = rice_parameter + rice_parameter_search_dist;
      if (max_rice_parameter >= ENTROPY_CODING_METHOD_PARTITIONED_RICE_ESCAPE_PARAMETER) {
      fprintf(stderr, "clipping rice_parameter (%u -> %u) @4\n", max_rice_parameter, ENTROPY_CODING_METHOD_PARTITIONED_RICE_ESCAPE_PARAMETER - 1);
      max_rice_parameter = ENTROPY_CODING_METHOD_PARTITIONED_RICE_ESCAPE_PARAMETER - 1;
      }
      }
      else
      min_rice_parameter = max_rice_parameter = rice_parameter;
      
      best_partition_bits = 0xffffffff;
      for(rice_parameter = min_rice_parameter; rice_parameter <= max_rice_parameter; rice_parameter++) {
      unsigned rice_parameter_estimate = rice_parameter-1;
      partition_bits = (1 + rice_parameter) * partition_samples;
      partition_bits + = ENTROPY_CODING_METHOD_PARTITIONED_RICE_PARAMETER_LEN;
      save_residual_sample = residual_sample;
      for(partition_sample = 0; partition_sample < partition_samples; residual_sample + +, partition_sample + +) {
      partition_bits + = VARIABLE_RICE_BITS(abs_residual [residual_sample], rice_parameter);
      }
      if(rice_parameter! = max_rice_parameter)
      residual_sample = save_residual_sample;
      if (partition_bits < best_partition_bits) {
      best_rice_parameter = rice_parameter;
      best_partition_bits = partition_bits;
      }
      }
      parameters [partition] = best_rice_parameter;
      bits_+ = best_partition_bits ；
      }
      }
      
      *bits = bits_;
      return true;
      }
      */
    
    /*
     boolean set_partitioned_rice_with_precompute_(
     int[] abs_residual,
     long [] abs_residual_partition_sums ，
     int[] raw_bits_per_partition,
     int residual_samples,
     int predictor_order,
     int suggested_rice_parameter ，
     int rice_parameter_search_dist,
     int partition_order,
     boolean search_for_escapes ，
     EntropyPartitionedRiceContents partitioned_rice_contents,
     int * bits
     )
     {
     int rice_parameter, partition_bits;
     int best_partition_bits;
     int min_rice_parameter ， max_rice_parameter ， best_rice_parameter = 0 ；
     int flat_bits;
     int bits_ = ENTROPY_CODING_METHOD_TYPE_LEN + ENTROPY_CODING_METHOD_PARTITIONED_RICE_ORDER_LEN;
     int *parameters, *raw_bits;
     
     format_entropy_coding_method_partitioned_rice_contents_ensure_size(partitioned_rice_contents, max(6, partition_order));
     parameters = partitioned_rice_contents.parameters;
     raw_bits = partitioned_rice_contents.raw_bits;
     
     if (partition_order == 0) {
     unsigned i;
     
     if(rice_parameter_search_dist) {
     if (suggested_rice_parameter < rice_parameter_search_dist)
     min_rice_parameter = 0;
     else
     min_rice_parameter = suggested_rice_parameter - rice_parameter_search_dist;
     max_rice_parameter = suggested_rice_parameter + rice_parameter_search_dist;
     if (max_rice_parameter >= ENTROPY_CODING_METHOD_PARTITIONED_RICE_ESCAPE_PARAMETER) {
     fprintf(stderr, "clipping rice_parameter(% u - > % u) @ 5\ n", max_rice_parameter, ENTROPY_CODING_METHOD_PARTITIONED_RICE_ESCAPE_PARAMETER - 1);
     max_rice_parameter = ENTROPY_CODING_METHOD_PARTITIONED_RICE_ESCAPE_PARAMETER - 1;
     }
     }
     else
     min_rice_parameter = max_rice_parameter = suggested_rice_parameter;
     
     best_partition_bits = 0xffffffff;
     for(rice_parameter = min_rice_parameter; rice_parameter <= max_rice_parameter; rice_parameter++) {
     unsigned rice_parameter_estimate = rice_parameter-1;
     partition_bits = (1+rice_parameter) * residual_samples;
     partition_bits + = ENTROPY_CODING_METHOD_PARTITIONED_RICE_PARAMETER_LEN;
     for(i = 0; i < residual_samples; i++) {
     partition_bits + = VARIABLE_RICE_BITS(abs_residual [i], rice_parameter_estimate);
     }
     if (partition_bits < best_partition_bits) {
     best_rice_parameter = rice_parameter;
     best_partition_bits = partition_bits;
     }
     }
     if (search_for_escapes) {
     flat_bits = ENTROPY_CODING_METHOD_PARTITIONED_RICE_PARAMETER_LEN + ENTROPY_CODING_METHOD_PARTITIONED_RICE_RAW_LEN + raw_bits_per_partition [0] * residual_samples ；
     if(flat_bits < = best_partition_bits) {
     raw_bits[0] = raw_bits_per_partition[0];
     best_rice_parameter = ENTROPY_CODING_METHOD_PARTITIONED_RICE_ESCAPE_PARAMETER;
     best_partition_bits = flat_bits ；
     }
     }
     parameters [0] = best_rice_parameter;
     bits_+ = best_partition_bits ；
     }
     否则
     unsigned partition, residual_sample, save_residual_sample, partition_sample;
     unsigned partition_samples;
     uint64 mean, k;
     unsigned partitions = 1 < < partition_order;
     for(partition = residual_sample = 0; partition < partitions; partition + +) {
     partition_samples = (residual_samples+predictor_order) >> partition_order;
     if(partition = = 0) {
     if(partition_samples < = predictor_order)
     return false;
     else
     partition_samples - = predictor_order ；
     }
     mean = abs_residual_partition_sums [分区] ；
     //calc rice_parameter ala LOCO-I
      for(rice_parameter = 0, k = partition_samples; k < mean; rice_parameter + +, k < < = 1)
      ;
      if (rice_parameter >= ENTROPY_CODING_METHOD_PARTITIONED_RICE_ESCAPE_PARAMETER) {
      fprintf(stderr, "clipping rice_parameter(% u - > % u) @ 6\ n", rice_parameter, ENTROPY_CODING_METHOD_PARTITIONED_RICE_ESCAPE_PARAMETER - 1);
      rice_parameter = ENTROPY_CODING_METHOD_PARTITIONED_RICE_ESCAPE_PARAMETER - 1;
      }
      
      if(rice_parameter_search_dist) {
      if(rice_parameter < rice_parameter_search_dist)
      min_rice_parameter = 0;
      else
      min_rice_parameter = rice_parameter - rice_parameter_search_dist;
      max_rice_parameter = rice_parameter + rice_parameter_search_dist;
      if (max_rice_parameter >= ENTROPY_CODING_METHOD_PARTITIONED_RICE_ESCAPE_PARAMETER) {
      fprintf(stderr, "clipping rice_parameter(% u - > % u) @ 7\ n", max_rice_parameter, ENTROPY_CODING_METHOD_PARTITIONED_RICE_ESCAPE_PARAMETER - 1);
      max_rice_parameter = ENTROPY_CODING_METHOD_PARTITIONED_RICE_ESCAPE_PARAMETER - 1;
      }
      }
      else
      min_rice_parameter = max_rice_parameter = rice_parameter;
      
      best_partition_bits = 0xffffffff;
      for(rice_parameter = min_rice_parameter; rice_parameter <= max_rice_parameter; rice_parameter++) {
      unsigned rice_parameter_estimate = rice_parameter-1;
      partition_bits = (1 + rice_parameter) * partition_samples;
      partition_bits + = ENTROPY_CODING_METHOD_PARTITIONED_RICE_PARAMETER_LEN;
      save_residual_sample = residual_sample;
      for(partition_sample = 0; partition_sample < partition_samples; residual_sample + +, partition_sample + +) {
      partition_bits += VARIABLE_RICE_BITS(abs_residual[residual_sample], rice_parameter_estimate);
      }
      if(rice_parameter! = max_rice_parameter)
      residual_sample = save_residual_sample;
      if (partition_bits < best_partition_bits) {
      best_rice_parameter = rice_parameter;
      best_partition_bits = partition_bits;
      }
      }
      if (search_for_escapes) {
      flat_bits = ENTROPY_CODING_METHOD_PARTITIONED_RICE_PARAMETER_LEN + ENTROPY_CODING_METHOD_PARTITIONED_RICE_RAW_LEN + raw_bits_per_partition [partition] * partition_samples ；
      if(flat_bits < = best_partition_bits) {
      raw_bits[partition] = raw_bits_per_partition[partition];
      best_rice_parameter = ENTROPY_CODING_METHOD_PARTITIONED_RICE_ESCAPE_PARAMETER;
      best_partition_bits = flat_bits ；
      }
      }
      parameters [partition] = best_rice_parameter;
      bits_+ = best_partition_bits ；
      }
      }
      
      *bits = bits_;
      return true;
      }
      */
    
    /*
     private int getWastedBits(int [] signal, int samples) {
     int i, shift;
     INT( x)
     
     for(i = 0; i < samples & &! (x & 1); i + +)
     x | = signal [i];
     
     if(x = = 0) {
     转移
     }
     否则
     for(shift = 0; !(x&1); shift++)
     X-1
     }
     
     if(shift > 0) {
     for(i = 0; i < samples; i++)
     signal [i] > > = shift;
     }
     
     回程班次；
     }
     */
    
    /*
     private void appendToVerifyFifo(verify_input_fifo fifo, int[][] input, int input_offset, unsigned channels, unsigned wide_samples) {
     未签名频道；
     
     for(channel = 0; channel < channels; channel++)
     memcpy(&fifo.data[channel][fifo->tail], &input[channel][input_offset], sizeof(int) * wide_samples);
     
     fifo.tail += wide_samples;
     }
     
     private void appendToVerifyFifoInterleaved(verify_input_fifo fifo, int input [], int input_offset, int channels, int wide_samples) {
     int tail = fifo.tail;
     
     int sample = input_offset * channels;
     for(int wide_sample = 0; wide_sample < wide_samples; wide_sample++) {
     for(int channel = 0; channel < channels; channel + +)
     fifo.data[channel][tail] = input[sample++];
     tail++;
     }
     fifo.tail = tail;
     }
     */
    
    /*
     StreamDecoderReadStatus verify_read_callback_(StreamDecoder decoder, byte buffer[], unsigned *bytes, void *client_data)
     {
     StreamEncoder * encoder = (StreamEncoder *) client_data;
     unsigned encoded_bytes = verifyData.output.bytes;
     (void)decoder;
     
     if (verifyData.needs_magic_hack) {
     ASSERT(* bytes > = STREAM_SYNC_LENGTH);
     *bytes = STREAM_SYNC_LENGTH;
     memcpy(buffer, STREAM_SYNC_STRING, *bytes);
     verifyData.needs_magic_hack = false;
     }
     否则
     if(encoded_bytes = = 0) {
     // If we get here, a FIFO underflow has occurred, which means there is a bug somewhere.
      ɏһ¼¶
      return STREAM_DECODER_READ_STATUS_ABORT;
      }
      else if(encoded_bytes < * bytes)
      *bytes = encoded_bytes;
      memcpy(buffer, verifyData.output.data, * bytes);
      verifyData.output.data + = *字节；
      verifyData.output.bytes -= *bytes;
      }
      
      返回STREAM_DECODER_READ_STATUS_CONTINUE ；
      }
      */
    
    /*
     // 若走到这里，说明发生了 FIFO 下溢，即某处存在 bug。
     {
     StreamEncoder * encoder = (StreamEncoder *) client_data;
     未签名频道；
     unsigned channels = stream_decoder_get_channels （解码器） ；
     unsigned blocksize = frame- > header.blocksize;
     unsigned bytes_per_block = sizeof(int) * blocksize;
     
     for(channel = 0; channel < channels; channel + +) {
     if(0! = memcmp(buffer [channel], verifyData.input_fifo.data [channel], bytes_per_block)) {
     unsigned i, sample = 0;
     int expect = 0, got = 0;
     
     for(i = 0; i < blocksize; i + +) {
     if(buffer [channel] [i]! = verifyData.input_fifo.data [channel] [i]) {
     sample = i;
     expect = (int) verifyData.input_fifo.data [channel] [i];
     got = (int) buffer [channel] [i];
     break;
     }
     }
     ASSERT(i < blocksize);
     ASSERT(frame->header.number_type == FRAME_NUMBER_TYPE_SAMPLE_NUMBER);
     verifyData.error_stats.absolute_sample = frame->header.number.sample_number + sample;
     verifyData.error_stats.frame_number = (unsigned)(frame->header.number.sample_number / blocksize);
     verifyData.error_stats.channel = channel;
     verifyData.error_stats.sample = sample;
     verifyData.error_stats.expected = expect;
     verifyData.error_stats.got = got;
     state = STREAM_ENCODER_VERIFY_MISMATCH_IN_AUDIO_DATA;
     return STREAM_DECODER_WRITE_STATUS_ABORT;
     }
     }
     // dequeue the frame from the fifo
      for(channel = 0; channel < channels; channel + +) {
      memmove(& verifyData.input_fifo.data [channel] [0], & verifyData.input_fifo.data [channel] [blocksize], verifyData.input_fifo.tail - blocksize);
      }
      verifyData.input_fifo.tail - = blocksize;
      return STREAM_DECODER_WRITE_STATUS_CONTINUE;
      }
      */
    
    /*
     void verify_metadata_callback_(StreamDecoder * decoder, StreamMetadata * metadata, void * client_data)
     {
     (void)decoder, (void)metadata, (void)client_data;
     }
     */
    
    /*
     // 从 fifo 中出队该帧
     {
     StreamEncoder * encoder = (StreamEncoder *) client_data;
     （无效）解码器， （无效）状态；
     state = STREAM_ENCODER_VERIFY_DECODER_ERROR;
     }
     */
}
