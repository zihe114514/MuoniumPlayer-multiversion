/*
 * 11/19/04        1.0 moved to LGPL.
 * 01/12/99        Initial version.    mdm@techie.com
 *-----------------------------------------------------------------------
 *   GNU 库通用公共许可证条款下分发和/或修改它，许可证版本
 *   为许可证第 2 版，或（由你选择）任何后续版本。
 *   本程序的发布是希望它能有用，
 *   （由您选择）任何后续版本。
 *
 *   但不提供任何担保；甚至不包含对适销性或特定用途适用性的默示担保。
 *   基金会，地址：675 Mass Ave, Cambridge, MA 02139, USA。
 *   适销性或适合特定目的。请参阅 GNU，
 *   有关更多详细信息，请参阅GNU库通用公共许可证。
 *
 *   您应该已经收到了GNU Library General Public的副本
 *   随着这一计划。如果没有，写信给自由软件基金会。
 *   MPEG 音频文件转换为 .WAV 文件。要转换 MPEG 音频流，
 *----------------------------------------------------------------------
 */

package repackage.javazoom.jl.decoder;

/**
 * <code>Decoder</code> 类封装了
 * 解码MPEG音频帧。
 *
 * @author MDM
 * @version 0.0.7 12/12/99
 * @since 0.0.5
 */
public class Decoder implements DecoderErrors {
    static private final Params DEFAULT_PARAMS = new Params();

    /**
     * 将接收解码结果的 Obuffer 实例
     * PCM样品。
     */
    private Obuffer output;

    /**
     * 左通道的合成滤波器。
     */
    private SynthesisFilter filter1;

    /**
     * 用于右通道的合成滤波器。
     */
    private SynthesisFilter filter2;

    /**
     * 用于解码 Layer III 帧的解码器。
     */
    private LayerIIIDecoder l3decoder;
    private LayerIIDecoder l2decoder;
    private LayerIDecoder l1decoder;

    private int outputFrequency;
    private int outputChannels;

    private Equalizer equalizer = new Equalizer();

    private Params params;

    private boolean initialized;

    /**
     * 使用默认值创建新的<code>解码器</code>实例
     * <g id="7497"><g id="7500">c </g></g><g id="7503">× </g><g id="7506">k </g><g id="7509">× </g><g id="7512">w </g><g id="7515">× </g><g id="7521">w</g>个参数。
     */
    public Decoder() {
        this(null);
    }

    /**
     * 使用默认值创建新的<code>解码器</code>实例
     * <g id="7497"><g id="7500">c </g></g><g id="7503">× </g><g id="7506">k </g><g id="7509">× </g><g id="7512">w </g><g id="7515">× </g><g id="7521">w</g>个参数。
     *
     * @param params <code>参数</code>实例描述
     *               解码器的可定制方面。
     */
    public Decoder(Params params) {
        if (params == null)
            params = DEFAULT_PARAMS;

        this.params = params;

        Equalizer eq = this.params.getInitialEqualizerSettings();
        if (eq != null) {
            equalizer.setFrom(eq);
        }
    }

    static public Params getDefaultParams() {
        return (Params) DEFAULT_PARAMS.clone();
    }

    public void setEqualizer(Equalizer eq) {
        if (eq == null)
            eq = Equalizer.PASS_THRU_EQ;

        equalizer.setFrom(eq);

        float[] factors = equalizer.getBandFactors();

        if (filter1 != null)
            filter1.setEQ(factors);

        if (filter2 != null)
            filter2.setEQ(factors);
    }

    /**
     * 从 MPEG 音频比特流解码一帧。
     *
     * @param header 描述要解码的帧的标题。
     * @param stream 为帧体提供比特的比特流。
     * @return 包含解码样本的SampleBuffer。
     */
    public Obuffer decodeFrame(Header header, Bitstream stream)
            throws DecoderException {
        if (!initialized) {
            initialize(header);
        }

        int layer = header.layer();

        output.clearBuffer();

        FrameDecoder decoder = retrieveDecoder(header, stream, layer);

        decoder.decodeFrame();

        output.writeBuffer(1);

        return output;
    }

    /**
     * 更改输出缓冲区。这将在下次生效
     * decodeFrame()被调用。
     */
    public void setOutputBuffer(Obuffer out) {
        output = out;
    }

    /**
     * 检索PCM样本输出的采样频率
     * 由此解码器解码。通常对应于采样
     * mPEG音频流中编码的速率。
     *
     * @return 写入样本的采样率（以Hz为单位）
     * 解码时输出缓冲区。
     */
    public int getOutputFrequency() {
        return outputFrequency;
    }

    /**
     * 检索PCM样本输出的通道数
     * 由此解码器。通常对应于
     * mPEG音频流中的频道，但可能会有所不同。
     *
     * @return 解码样本输出通道数： 1
     * 用于单声道，或2个用于立体声。
     */
    public int getOutputChannels() {
        return outputChannels;
    }

    /**
     * 检索将被写入的最大样本数
     * 解码一帧时输出缓冲区的（采样数）。可用于
     * 帮助计算其他缓冲区的大小，这些缓冲区的大小基于
     * 写入输出缓冲区的采样数。注意：这是
     * 实际上可能会写入上限和更少的样本，这取决于
     * 基于采样率和通道数。
     *
     * @return 写入
     * 解码单帧MPEG音频时的输出缓冲区。
     */
    public int getOutputBlockSize() {
        return Obuffer.OBUFFERSIZE;
    }

    protected DecoderException newDecoderException(int errorCode) {
        return new DecoderException(errorCode, null);
    }

    protected DecoderException newDecoderException(int errorCode, Throwable throwable) {
        return new DecoderException(errorCode, throwable);
    }

    protected FrameDecoder retrieveDecoder(Header header, Bitstream stream, int layer) throws DecoderException {
        FrameDecoder decoder;

        switch (layer) {
            case 3: {
                if (l3decoder == null) {
                    l3decoder = new LayerIIIDecoder(stream,
                            header, filter1, filter2,
                            output, OutputChannels.BOTH_CHANNELS);
                }

                decoder = l3decoder;
                break;
            }
            case 2: {
                if (l2decoder == null) {
                    l2decoder = new LayerIIDecoder();
                    l2decoder.create(stream,
                            header, filter1, filter2,
                            output, OutputChannels.BOTH_CHANNELS);
                }
                decoder = l2decoder;
                break;
            }
            case 1: {
                if (l1decoder == null) {
                    l1decoder = new LayerIDecoder();
                    l1decoder.create(stream,
                            header, filter1, filter2,
                            output, OutputChannels.BOTH_CHANNELS);
                }
                decoder = l1decoder;
                break;
            }
            default: decoder = null; break;

            // REVIEW：允许声道输出选择类型
            // (LEFT, RIGHT, BOTH, DOWNMIX)
        }

        if (decoder == null) {
            throw newDecoderException(UNSUPPORTED_LAYER, null);
        }

        return decoder;
    }

    private void initialize(Header header) {

        // 审阅：允许自定义比例因子
        float scalefactor = 32700.0f;

        int mode = header.mode();
        @SuppressWarnings("unused")
        int layer = header.layer();
        int channels = mode == Header.SINGLE_CHANNEL ? 1 : 2;


        // 如果客户端未设置输出缓冲区，则设置输出缓冲区。
        if (output == null)
            output = new SampleBuffer(header.frequency(), channels);

        float[] factors = equalizer.getBandFactors();
        filter1 = new SynthesisFilter(0, scalefactor);

        // REVIEW：允许立体声的单声道输出
        if (channels == 2)
            filter2 = new SynthesisFilter(1, scalefactor);

        outputChannels = channels;
        outputFrequency = header.frequency();

        initialized = true;
    }

    /**
     * <code> Params </code>类提供可定制的
     * 解码器的各方面。
     * <p>
     * 此类的实例不是线程安全的。
     */
    public static class Params implements Cloneable {

        private OutputChannels outputChannels = OutputChannels.BOTH;

        private Equalizer equalizer = new Equalizer();

        public Params() {
        }

        @Override
        public Object clone() {
            try {
                return super.clone();
            } catch (CloneNotSupportedException ex) {
                throw new InternalError(this + ": " + ex);
            }
        }

        public void setOutputChannels(OutputChannels out) {
            if (out == null)
                throw new NullPointerException("out");

            outputChannels = out;
        }

        public OutputChannels getOutputChannels() {
            return outputChannels;
        }

        /**
         * 获取解码器均衡器初始化所用的均衡器设置。
         * 将从中初始化。
         * <p>
         * <code>均衡器</code>实例返回
         * 不能实时更改以影响
         * 解码器输出，因为它仅用于初始化解码器
         * EQ 设置。要实时影响解码器输出，
         * 使用从getEqualizer()方法返回的均衡器
         * 该解码器。
         *
         * @return 用于初始化解码器的 <code>Equalizer</code>
         * 解码器的均衡器设置。
         */
        public Equalizer getInitialEqualizerSettings() {
            return equalizer;
        }
    }
}

