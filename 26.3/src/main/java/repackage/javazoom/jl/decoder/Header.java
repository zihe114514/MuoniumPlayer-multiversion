/*
 * 11/19/04 : 1.0 moved to LGPL.
 *            已添加VBRI标头支持， E.B javalayer@javazoom.net
 *
 * 12/04/03 : VBR (XING) header support added, E.B javalayer@javazoom.net
 *
 * 02/13/99 ： JavaZOOM的Java转换， E.B javalayer@javazoom.net
 *
 * MPEG 头类的声明
 * 由 Jeff Tsay 做的少量 layer III、MPEG-2 LSF 和 seek 方面的修改。
 * 最后修改日期： 1997年4月19日
 *
 *  @(#) header.h 1.7, last edit: 6/15/94 16:55:33
 *  @ (#)版权所有(C) 1993, 1994 Tobias Bading(bading@cs.tu-berlin.de)
 *  @ (#)柏林理工大学
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
 * 用于从帧头提取信息的类。
 */
public final class Header {

    public static final int[][] frequencies ={
            {22050, 24000, 16000, 1},
            {44100, 48000, 32000, 1},
            {11025, 12000, 8000, 1} // SZD: MPEG25
    };

    /**
     * MPEG-2 LSF 版本的常量
     */
    public static final int MPEG2_LSF = 0;
    public static final int MPEG25_LSF = 2; // SZD

    /**
     * MPEG-1版本的常量
     */
    public static final int MPEG1 = 1;

    public static final int STEREO = 0;
    public static final int JOINT_STEREO = 1;
    public static final int DUAL_CHANNEL = 2;
    public static final int SINGLE_CHANNEL = 3;
    public static final int FOURTYFOUR_POINT_ONE = 0;
    public static final int FOURTYEIGHT = 1;
    public static final int THIRTYTWO = 2;

    private int hLayer, h_protection_bit, hBitrateIndex,
            h_padding_bit, h_mode_extension;
    private int hVersion;
    private int h_mode;
    private int h_sample_frequency;
    private int hNumberOfSubbands, hIntensityStereoBound;
    private boolean h_copyright, h_original;
    // 由 E.B 添加的 VBR 支持
    private static final double[] h_vbr_time_per_frame = {-1, 384, 1152, 1152};
    private boolean h_vbr;
    private int h_vbr_frames;
    private int h_vbr_scale;
    private int h_vbr_bytes;
    private byte[] h_vbr_toc;

    private byte syncmode = Bitstream.INITIAL_SYNC;
    private Crc16 crc;

    public short checksum;
    public int framesize;
    public int nSlots;

    private int _headerstring = -1; // E.B

    Header() {
    }

    public String toString() {
        StringBuilder buffer = new StringBuilder(200);
        buffer.append("Layer ");
        buffer.append(layerString());
        buffer.append(" frame ");
        buffer.append(modeString());
        buffer.append(' ');
        buffer.append(versionString());
        if (!checksums())
            buffer.append(" no");
        buffer.append(" checksums");
        buffer.append(' ');
        buffer.append(sampleFrequencyString());
        buffer.append(',');
        buffer.append(' ');
        buffer.append(bitrateString());

        return buffer.toString();
    }

    /**
     * 从比特流中读取32位标头。
     */
    void read_header(Bitstream stream, Crc16[] crcp) throws BitstreamException {
        int headerString;
        int channelBitrate;
        boolean sync = false;
        do {
            headerString = stream.syncHeader(syncmode);
            _headerstring = headerString; // E.B
            if (syncmode == Bitstream.INITIAL_SYNC) {
                hVersion = ((headerString >>> 19) & 1);
                if (((headerString >>> 20) & 1) == 0) // SZD ： MPEG2.5检测
                    if (hVersion == MPEG2_LSF)
                        hVersion = MPEG25_LSF;
                    else
                        throw stream.newBitstreamException(Bitstream.UNKNOWN_ERROR);
                if ((h_sample_frequency = ((headerString >>> 10) & 3)) == 3) {
                    throw stream.newBitstreamException(Bitstream.UNKNOWN_ERROR);
                }
            }
            hLayer = 4 - (headerString >>> 17) & 3;
            h_protection_bit = (headerString >>> 16) & 1;
            hBitrateIndex = (headerString >>> 12) & 0xF;
            h_padding_bit = (headerString >>> 9) & 1;
            h_mode = ((headerString >>> 6) & 3);
            h_mode_extension = (headerString >>> 4) & 3;
            if (h_mode == JOINT_STEREO)
                hIntensityStereoBound = (h_mode_extension << 2) + 4;
            else
                hIntensityStereoBound = 0; // 切勿使用
            if (((headerString >>> 3) & 1) == 1)
                h_copyright = true;
            if (((headerString >>> 2) & 1) == 1)
                h_original = true;
            // 计算子带数：
            if (hLayer == 1)
                hNumberOfSubbands = 32;
            else {
                channelBitrate = hBitrateIndex;
                // 计算每个通道的比特率：
                if (h_mode != SINGLE_CHANNEL)
                    if (channelBitrate == 4)
                        channelBitrate = 1;
                    else
                        channelBitrate -= 4;
                if ((channelBitrate == 1) || (channelBitrate == 2))
                    if (h_sample_frequency == THIRTYTWO)
                        hNumberOfSubbands = 12;
                    else
                        hNumberOfSubbands = 8;
                else if ((h_sample_frequency == FOURTYEIGHT) || ((channelBitrate >= 3) && (channelBitrate <= 5)))
                    hNumberOfSubbands = 27;
                else
                    hNumberOfSubbands = 30;
            }
            if (hIntensityStereoBound > hNumberOfSubbands)
                hIntensityStereoBound = hNumberOfSubbands;
            // 计算框架大小和nSlots
            calculateFrameSize();
            // 读取帧数据：
            int framesizeloaded = stream.readFrameData(framesize);
            if ((framesize >= 0) && (framesizeloaded != framesize)) {
                // 加载的数据与预期帧大小不符，
                // 它可能是一个ID3v1标签。（修复2004年11月17日）。
                throw stream.newBitstreamException(Bitstream.INVALIDFRAME);
            }
            if (stream.isSyncCurrentPosition(syncmode)) {
                if (syncmode == Bitstream.INITIAL_SYNC) {
                    syncmode = Bitstream.STRICT_SYNC;
                    stream.setSyncWord(headerString & 0xFFF80CC0);
                }
                sync = true;
            } else {
                stream.unreadFrame();
            }
        }
        while (!sync);
        stream.parseFrame();
        if (h_protection_bit == 0) {
            // 帧包含crc校验和
            checksum = (short) stream.getBits(16);
            if (crc == null)
                crc = new Crc16();
            crc.addBits(headerString, 16);
            crcp[0] = crc;
        } else
            crcp[0] = null;
        if (h_sample_frequency == FOURTYFOUR_POINT_ONE) {
        }
    }

    /**
     * 解析帧以提取可选的VBR帧。
     *
     * @param firstFrame
     * @author E.B (javalayer@javazoom.net)
     */
    void parseVBR(byte[] firstFrame) throws BitstreamException {
        // 正在尝试Xing标头。
        String xing = "Xing";
        byte[] tmp = new byte[4];
        int offset = 0;
        // 根据MPEG版本和频道计算“Xing”偏移量。
        if (hVersion == MPEG1) {
            if (h_mode == SINGLE_CHANNEL) offset = 21 - 4;
            else offset = 36 - 4;
        } else {
            if (h_mode == SINGLE_CHANNEL) offset = 13 - 4;
            else offset = 21 - 4;
        }
        try {
            System.arraycopy(firstFrame, offset, tmp, 0, 4);
            // 是“Xing”吗？
            if (xing.equals(new String(tmp))) {
                //是的。
                h_vbr = true;
                h_vbr_frames = -1;
                h_vbr_bytes = -1;
                h_vbr_scale = -1;
                h_vbr_toc = new byte[100];

                int length = 4;
                // 读取标志。
                byte[] flags = new byte[4];
                System.arraycopy(firstFrame, offset + length, flags, 0, flags.length);
                length += flags.length;
                // 读取帧数（若可用）。
                if ((flags[3] & (byte) (1 << 0)) != 0) {
                    System.arraycopy(firstFrame, offset + length, tmp, 0, tmp.length);
                    h_vbr_frames = (tmp[0] << 24) & 0xFF000000 | (tmp[1] << 16) & 0x00FF0000 | (tmp[2] << 8) & 0x0000FF00 | tmp[3] & 0x000000FF;
                    length += 4;
                }
                // 读取大小（若可用）。
                if ((flags[3] & (byte) (1 << 1)) != 0) {
                    System.arraycopy(firstFrame, offset + length, tmp, 0, tmp.length);
                    h_vbr_bytes = (tmp[0] << 24) & 0xFF000000 | (tmp[1] << 16) & 0x00FF0000 | (tmp[2] << 8) & 0x0000FF00 | tmp[3] & 0x000000FF;
                    length += 4;
                }
                // 读取 TOC（若可用）。
                if ((flags[3] & (byte) (1 << 2)) != 0) {
                    System.arraycopy(firstFrame, offset + length, h_vbr_toc, 0, h_vbr_toc.length);
                    length += h_vbr_toc.length;
                }
                // 读取 scale（若可用）。
                if ((flags[3] & (byte) (1 << 3)) != 0) {
                    System.arraycopy(firstFrame, offset + length, tmp, 0, tmp.length);
                    h_vbr_scale = (tmp[0] << 24) & 0xFF000000 | (tmp[1] << 16) & 0x00FF0000 | (tmp[2] << 8) & 0x0000FF00 | tmp[3] & 0x000000FF;
                    length += 4;
                }
                //System.out.println("VBR:"+xing+" Frames:"+ h_vbr_frames +" Size:"+h_vbr_bytes);
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new BitstreamException("XingVBRHeader Corrupted", e);
        }

        // 正在尝试VBRI标头。
        String vbri = "VBRI";
        offset = 36 - 4;
        try {
            System.arraycopy(firstFrame, offset, tmp, 0, 4);
            // 是否为 "VBRI"？
            if (vbri.equals(new String(tmp))) {
                //是的。
                h_vbr = true;
                h_vbr_frames = -1;
                h_vbr_bytes = -1;
                h_vbr_scale = -1;
                h_vbr_toc = new byte[100];
                // 字节。
                int length = 4 + 6;
                System.arraycopy(firstFrame, offset + length, tmp, 0, tmp.length);
                h_vbr_bytes = (tmp[0] << 24) & 0xFF000000 | (tmp[1] << 16) & 0x00FF0000 | (tmp[2] << 8) & 0x0000FF00 | tmp[3] & 0x000000FF;
                length += 4;
                // 帧数。
                System.arraycopy(firstFrame, offset + length, tmp, 0, tmp.length);
                h_vbr_frames = (tmp[0] << 24) & 0xFF000000 | (tmp[1] << 16) & 0x00FF0000 | (tmp[2] << 8) & 0x0000FF00 | tmp[3] & 0x000000FF;
                length += 4;
                //System.out.println("VBR:"+vbri+" Frames:"+ h_vbr_frames +" Size:"+h_vbr_bytes);
                // 目录
                // TODO
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new BitstreamException("VBRIVBRHeader Corrupted", e);
        }
    }

    // 查询标头内容的函数：

    /**
     * 返回版本。
     */
    public int version() {
        return hVersion;
    }

    /**
     * 返回图层ID。
     */
    public int layer() {
        return hLayer;
    }

    /**
     * 返回比特率索引。
     */
    public int bitrateIndex() {
        return hBitrateIndex;
    }

    /**
     * 返回采样频率。
     */
    public int sampleFrequency() {
        return h_sample_frequency;
    }

    /**
     * 返回频率。
     */
    public int frequency() {
        return frequencies[hVersion][h_sample_frequency];
    }

    /**
     * 返回模式。
     */
    public int mode() {
        return h_mode;
    }

    /**
     * 返回保护位。
     */
    public boolean checksums() {
        return h_protection_bit == 0;
    }

    /**
     * 返回版权。
     */
    public boolean copyright() {
        return h_copyright;
    }

    /**
     * 返回Original。
     */
    public boolean original() {
        return h_original;
    }

    /**
     * 返回 VBR。
     *
     * 如果找到VBR标头，则@return 为true
     */
    public boolean vbr() {
        return h_vbr;
    }

    /**
     * 返回VBR刻度。
     *
     * @return 量表-1 （如果没有）
     */
    public int vbrScale() {
        return h_vbr_scale;
    }

    /**
     * 返回VBR TOC。
     *
     * @return vbr toc ot null （如果不可用）
     */
    public byte[] vbrToc() {
        return h_vbr_toc;
    }

    /**
     * 返回校验和标志。
     * 将计算出的校验和与流中的校验和比较。
     */
    public boolean checksumOk() {
        return (checksum == crc.checksum());
    }

    // 定位和 layer III 相关内容

    /**
     * 返回 Layer III 填充位。
     */
    public boolean padding() {
        return h_padding_bit != 0;
    }

    /**
     * 返回 slots。
     */
    public int slots() {
        return nSlots;
    }

    /**
     * 返回模式扩展。
     */
    public int modeExtension() {
        return h_mode_extension;
    }

    // E.B -> private 改为 public
    public static final int[][][] bitrates = {
            {{0 /*自由格式*/, 32000, 48000, 56000, 64000, 80000, 96000,
            112000, 128000, 144000, 160000, 176000, 192000, 224000, 256000, 0},
            {0 /*自由格式*/, 8000, 16000, 24000, 32000, 40000, 48000,
            56000, 64000, 80000, 96000, 112000, 128000, 144000, 160000, 0},
            {0 /*自由格式*/, 8000, 16000, 24000, 32000, 40000, 48000,
            56000, 64000, 80000, 96000, 112000, 128000, 144000, 160000, 0}},

            {{0 /*自由格式*/, 32000, 64000, 96000, 128000, 160000, 192000,
            224000, 256000, 288000, 320000, 352000, 384000, 416000, 448000, 0},
            {0 /*自由格式*/, 32000, 48000, 56000, 64000, 80000, 96000,
            112000, 128000, 160000, 192000, 224000, 256000, 320000, 384000, 0},
            {0 /*自由格式*/, 32000, 40000, 48000, 56000, 64000, 80000,
            96000, 112000, 128000, 160000, 192000, 224000, 256000, 320000, 0}},
            // SZD: MPEG2.5
            {{0 /*自由格式*/, 32000, 48000, 56000, 64000, 80000, 96000,
            112000, 128000, 144000, 160000, 176000, 192000, 224000, 256000, 0},
            {0 /*自由格式*/, 8000, 16000, 24000, 32000, 40000, 48000,
            56000, 64000, 80000, 96000, 112000, 128000, 144000, 160000, 0},
            {0 /*自由格式*/, 8000, 16000, 24000, 32000, 40000, 48000,
            56000, 64000, 80000, 96000, 112000, 128000, 144000, 160000, 0}},
    };


    /**
     * 计算帧大小。
     * 计算帧大小（字节，不含头大小）。
     * <p>
     * E.B -> private 改为 public
     */
    public int calculateFrameSize() {

        if (hLayer == 1) {
            framesize = (12 * bitrates[hVersion][0][hBitrateIndex]) /
                    frequencies[hVersion][h_sample_frequency];
            if (h_padding_bit != 0) framesize++;
            framesize <<= 2; // 一个插槽长4字节
            nSlots = 0;
        } else {
            framesize = (144 * bitrates[hVersion][hLayer - 1][hBitrateIndex]) /
                    frequencies[hVersion][h_sample_frequency];
            if (hVersion == MPEG2_LSF || hVersion == MPEG25_LSF) framesize >>= 1; // SZD
            if (h_padding_bit != 0) framesize++;
            // Layer III slots
            if (hLayer == 3) {
                if (hVersion == MPEG1) {
                    nSlots = framesize - ((h_mode == SINGLE_CHANNEL) ? 17 : 32) // 侧边信息大小
                            - ((h_protection_bit != 0) ? 0 : 2)                 // CRC 大小
                            - 4;                                                // 头大小
                } else {  // MPEG-2 LSF, SZD: MPEG-2.5 LSF
                    nSlots = framesize - ((h_mode == SINGLE_CHANNEL) ? 9 : 17) // 侧边信息大小
                            - ((h_protection_bit != 0) ? 0 : 2)                // CRC 大小
                            - 4;                                               // 头大小
                }
            } else {
                nSlots = 0;
            }
        }
        framesize -= 4; // 减去标题大小
        return framesize;
    }

    /**
     * 返回流中的最大帧数。
     *
     * @param streamSize 流大小
     * @return 帧数
     */
    public int maxNumberOfFrames(int streamSize) { // E.B
        if (h_vbr) return h_vbr_frames;
        else {
            if ((framesize + 4 - h_padding_bit) == 0) return 0;
            else return (streamSize / (framesize + 4 - h_padding_bit));
        }
    }

    /**
     * 返回流中的最大帧数。
     *
     * @param streamSize 流大小
     * @return 帧数
     */
    public int minNumberOfFrames(int streamSize) { // E.B
        if (h_vbr) return h_vbr_frames;
        else {
            if ((framesize + 5 - h_padding_bit) == 0) return 0;
            else return (streamSize / (framesize + 5 - h_padding_bit));
        }
    }

    /**
     * 返回ms/frame。
     *
     * 每帧@return 毫秒
     */
    public float msPerFrame() { // E.B
        if (h_vbr) {
            double tpf = h_vbr_time_per_frame[layer()] / frequency();
            if ((hVersion == MPEG2_LSF) || (hVersion == MPEG25_LSF)) tpf /= 2;
            return ((float) (tpf * 1000));
        } else {
            float[][] ms_per_frame_array = {
                    {8.707483f, 8.0f, 12.0f},
                    {26.12245f, 24.0f, 36.0f},
                    {26.12245f, 24.0f, 36.0f}
            };
            return (ms_per_frame_array[hLayer - 1][h_sample_frequency]);
        }
    }

    /**
     * 返回总毫秒数。
     *
     * @param streamSize 流大小
     * @return 总毫秒数
     */
    public float totalMs(int streamSize) { // E.B
        return (maxNumberOfFrames(streamSize) * msPerFrame());
    }

    /**
     * 返回已同步的头。
     */
    public int getSyncHeader() { // E.B
        return _headerstring;
    }

    // 以字符串形式返回标头信息的函数：

    /**
     * 返回图层版本。
     */
    public String layerString() {
        switch (hLayer) {
            case 1:
                return "I";
            case 2:
                return "II";
            case 3:
                return "III";
            default:
                return null;
        }
    }

    // E.B -> private 改为 public
    public static final String[][][] bitrate_str = {
            {{"free format", "32 kbit/s", "48 kbit/s", "56 kbit/s", "64 kbit/s",
                    "80 kbit/s", "96 kbit/s", "112 kbit/s", "128 kbit/s", "144 kbit/s",
                    "160 kbit/s", "176 kbit/s", "192 kbit/s", "224 kbit/s", "256 kbit/s",
                    "forbidden"},
            {"free format", "8 kbit/s", "16 kbit/s", "24 kbit/s", "32 kbit/s",
                    "40 kbit/s", "48 kbit/s", "56 kbit/s", "64 kbit/s", "80 kbit/s",
                    "96 kbit/s", "112 kbit/s", "128 kbit/s", "144 kbit/s", "160 kbit/s",
                    "forbidden"},
            {"free format", "8 kbit/s", "16 kbit/s", "24 kbit/s", "32 kbit/s",
                    "40 kbit/s", "48 kbit/s", "56 kbit/s", "64 kbit/s", "80 kbit/s",
                    "96 kbit/s", "112 kbit/s", "128 kbit/s", "144 kbit/s", "160 kbit/s",
                    "forbidden"}},

            {{"free format", "32 kbit/s", "64 kbit/s", "96 kbit/s", "128 kbit/s",
                    "160 kbit/s", "192 kbit/s", "224 kbit/s", "256 kbit/s", "288 kbit/s",
                    "320 kbit/s", "352 kbit/s", "384 kbit/s", "416 kbit/s", "448 kbit/s",
                    "forbidden"},
            {"free format", "32 kbit/s", "48 kbit/s", "56 kbit/s", "64 kbit/s",
                    "80 kbit/s", "96 kbit/s", "112 kbit/s", "128 kbit/s", "160 kbit/s",
                    "192 kbit/s", "224 kbit/s", "256 kbit/s", "320 kbit/s", "384 kbit/s",
                    "forbidden"},
            {"free format", "32 kbit/s", "40 kbit/s", "48 kbit/s", "56 kbit/s",
                    "64 kbit/s", "80 kbit/s", "96 kbit/s", "112 kbit/s", "128 kbit/s",
                    "160 kbit/s", "192 kbit/s", "224 kbit/s", "256 kbit/s", "320 kbit/s",
                    "forbidden"}},
            // SZD: MPEG2.5
            {{"free format", "32 kbit/s", "48 kbit/s", "56 kbit/s", "64 kbit/s",
                    "80 kbit/s", "96 kbit/s", "112 kbit/s", "128 kbit/s", "144 kbit/s",
                    "160 kbit/s", "176 kbit/s", "192 kbit/s", "224 kbit/s", "256 kbit/s",
                    "forbidden"},
            {"free format", "8 kbit/s", "16 kbit/s", "24 kbit/s", "32 kbit/s",
                    "40 kbit/s", "48 kbit/s", "56 kbit/s", "64 kbit/s", "80 kbit/s",
                    "96 kbit/s", "112 kbit/s", "128 kbit/s", "144 kbit/s", "160 kbit/s",
                    "forbidden"},
            {"free format", "8 kbit/s", "16 kbit/s", "24 kbit/s", "32 kbit/s",
                    "40 kbit/s", "48 kbit/s", "56 kbit/s", "64 kbit/s", "80 kbit/s",
                    "96 kbit/s", "112 kbit/s", "128 kbit/s", "144 kbit/s", "160 kbit/s",
                    "forbidden"}},
    };

    /**
     * 返回比特率。
     *
     * @return 比特率（ bps ）
     */
    public String bitrateString() {
        if (h_vbr) {
            return bitrate() / 1000 + " kb/s";
        } else return bitrate_str[hVersion][hLayer - 1][hBitrateIndex];
    }

    /**
     * 返回比特率。
     *
     * @return 比特率（bps），VBR 头为平均比特率
     */
    public int bitrate() {
        if (h_vbr) {
            return ((int) ((h_vbr_bytes * 8) / (msPerFrame() * h_vbr_frames))) * 1000;
        } else return bitrates[hVersion][hLayer - 1][hBitrateIndex];
    }

    /**
     * 返回即时比特率。
     * VBR的比特率不是恒定的。
     *
     * @return 比特率（ bps ）
     */
    public int bitrateInstant() {
        return bitrates[hVersion][hLayer - 1][hBitrateIndex];
    }

    /**
     * 返回频率
     *
     * @return 频率串，单位为kHz
     */
    public String sampleFrequencyString() {
        switch (h_sample_frequency) {
        case THIRTYTWO:
            if (hVersion == MPEG1)
                return "32 kHz";
            else if (hVersion == MPEG2_LSF)
                return "16 kHz";
            else    // SZD
                return "8 kHz";
        case FOURTYFOUR_POINT_ONE:
            if (hVersion == MPEG1)
                return "44.1 kHz";
            else if (hVersion == MPEG2_LSF)
                return "22.05 kHz";
            else    // SZD
                return "11.025 kHz";
        case FOURTYEIGHT:
            if (hVersion == MPEG1)
                return "48 kHz";
            else if (hVersion == MPEG2_LSF)
                return "24 kHz";
            else    // SZD
                return "12 kHz";
        }
        return null;
    }

    /**
     * 返回模式。
     */
    public String modeString() {
        switch (h_mode) {
            case STEREO:
                return "Stereo";
            case JOINT_STEREO:
                return "Joint stereo";
            case DUAL_CHANNEL:
                return "Dual channel";
            case SINGLE_CHANNEL:
                return "Single channel";
            default:
                return null;
        }
    }

    /**
     * 返回版本。
     *
     * @return MPEG-1或MPEG-2 LSF或MPEG-2.5 LSF
     */
    public String versionString() {
        switch (hVersion) {
            case MPEG1:
                return "MPEG-1";
            case MPEG2_LSF:
                return "MPEG-2 LSF";
// SZD
            case MPEG25_LSF:
                return "MPEG-2.5 LSF";
            default:
                return null;
        }
    }

    /**
     * 返回当前帧中的子带数。
     *
     * @return 子带数
     */
    public int numberOfSubbands() {
        return hNumberOfSubbands;
    }

    /**
     * 返回 Intensity Stereo。
     * （仅限 Layer II 联合立体声）。
     * 返回处于立体声模式的子带数，
     * 高于该限制的子带处于强度立体声模式。
     *
     * @return 强度
     */
    public int intensityStereoBound() {
        return hIntensityStereoBound;
    }
}
