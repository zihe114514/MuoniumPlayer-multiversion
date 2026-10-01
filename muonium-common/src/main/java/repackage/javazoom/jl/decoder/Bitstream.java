/*
 * 2004年11月19日1.0移至LGPL。
 *
 * 2004年11月17日未完成的帧被丢弃。E.B, javalayer@javazoom.net
 *
 * 12/05/03     ID3v2 tag returned. E.B, javalayer@javazoom.net
 *
 * 12/12/99     基于 Ibitstream。出错时抛出异常，
 *             临时移除了 seek 功能。mdm@techie.com
 *
 * 1999年2月12日： E.B的Java转换， javalayer@javazoom.net
 *
 * 04/14/97：为新的同步和定位添加了函数原型，
 * 机制。还使此文件可移植。Jeff Tsay所做的更改
 *
 *  @ (#) ibitstream.h 1.5 ，最后编辑： 6/15/94 16:55:34
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

import java.io.*;


/**
 * <code> Bistream </code>类负责解析
 * MPEG 音频比特流。
 *
 * <b>回顾： </b>目前大部分解析发生在
 * 各种解码器。这应移入此类及关联的
 * 内部类中。
 */
public final class Bitstream implements BitstreamErrors {

    /**
     * 初始同步控制常数
     * 同步到帧的开始。
     */
    static byte INITIAL_SYNC = 0;

    /**
     * 非起始帧的同步控制常量
     * 同步
     */
    static byte STRICT_SYNC = 1;

    /**
     * 帧缓冲区的最大大小。
     * <p>
     * 每帧最多 1730 字节：144 * 384kbit/s / 32000 Hz + 2 字节 CRC
     */
    private static final int BUFFER_INT_SIZE = 433;

    /**
     * 保存当前帧数据的帧缓冲区。
     */
    private final int[] frameBuffer = new int[BUFFER_INT_SIZE];

    /**
     * 帧缓冲区中的有效字节数。
     */
    private int frameSize;

    /**
     * 从流中读取的字节。
     */
    private byte[] frameBytes = new byte[BUFFER_INT_SIZE * 4];

    /**
     * 进入 <code>frameBuffer</code> 的索引，下一位所在位置
     * 已检索
     */
    private int wordPointer;

    /**
     * Get_bits()的下一位数（ 0-31 ，从MSB到LSB ）
     */
    private int bitindex;

    /**
     * 当前指定的syncword
     */
    private int syncWord;

    /**
     * 流中的音频标题位置。
     */
    private int headerPos = 0;

    /**
     *
     */
    private boolean singleChMode;

    private final int[] bitmask = {
            0, // dummy
            0x00000001, 0x00000003, 0x00000007, 0x0000000F, 0x0000001F, 0x0000003F, 0x0000007F, 0x000000FF, 0x000001FF, 0x000003FF,
            0x000007FF, 0x00000FFF, 0x00001FFF, 0x00003FFF, 0x00007FFF, 0x0000FFFF, 0x0001FFFF
    };

    private final PushbackInputStream source;

    private final Header header = new Header();

    private final byte[] syncBuf = new byte[4];

    private Crc16[] crc = new Crc16[1];

    private byte[] rawid3v2 = null;

    private boolean firstframe = true;

    /**
     * 构造一个IBitstream ，从
     * 给定InputStream。
     *
     * @param in 要读取的InputStream。
     */
    public Bitstream(InputStream in) {
        if (in == null)
            throw new NullPointerException("in");
        in = new BufferedInputStream(in);
        loadID3v2(in);
        firstframe = true;
        source = new PushbackInputStream(in, BUFFER_INT_SIZE * 4);

        closeFrame();
    }

    /**
     * 返回第一个音频标题的位置。
     *
     * @return ID3v2 标签帧的大小。
     */
    public int header_pos() {
        return headerPos;
    }

    /**
     * 加载ID3v2帧。
     *
     * @param in MP3输入流。
     * @author JavaZOOM
     */
    private void loadID3v2(InputStream in) {
        int size = -1;
        try {
            // 读取ID3v2标头（ 10字节）。
            in.mark(10);
            size = readID3v2Header(in);
            headerPos = size;
        } catch (IOException e) {
        } finally {
            try {
                // 未读的 ID3v2 头（10 字节）。
                in.reset();
            } catch (IOException e) {
            }
        }
        // 加载 ID3v2 标签。
        try {
            if (size > 0) {
                rawid3v2 = new byte[size];
                in.read(rawid3v2, 0, rawid3v2.length);
            }
        } catch (IOException e) {
        }
    }

    /**
     * 解析 ID3v2 标签头，以确定 ID3v2 帧的大小。
     *
     * @param in MP3输入流
     * ID3v2帧的@return 大小+标头
     * @throws IOException
     * @author JavaZOOM
     */
    private int readID3v2Header(InputStream in) throws IOException {
        byte[] id3header = new byte[4];
        int size = -10;
        in.read(id3header, 0, 3);
        // 查找ID3v2
        if ((id3header[0] == 'I') && (id3header[1] == 'D') && (id3header[2] == '3')) {
            in.read(id3header, 0, 3);
            @SuppressWarnings("unused")
            int majorVersion = id3header[0];
            @SuppressWarnings("unused")
            int revision = id3header[1];
            in.read(id3header, 0, 4);
            size = (id3header[0] << 21) + (id3header[1] << 14) + (id3header[2] << 7) + (id3header[3]);
        }
        return (size + 10);
    }

    /**
     * 返回原始 ID3v2 帧 + 头。
     *
     * @return ID3v2 InputStream或NULL （如果ID3v2帧不可用）。
     */
    public InputStream getRawID3v2() {
        if (rawid3v2 == null)
            return null;
        else {
            return new ByteArrayInputStream(rawid3v2);
        }
    }

    /**
     * 关闭比特流。
     *
     * @throws BitstreamException
     */
    public void close() throws BitstreamException {
        try {
            source.close();
        } catch (IOException ex) {
            throw newBitstreamException(STREAM_ERROR, ex);
        }
    }

    /**
     * 从输入源读取并解析下一帧。
     *
     * @return 描述帧读取细节的标题，
     * 或NULL （如果已到达流的末尾）。
     */
    public Header readFrame() throws BitstreamException {
        Header result = null;
        try {
            result = readNextFrame();
            // E.B，先解析 VBR（若有）首帧。
            if (firstframe) {
                result.parseVBR(frameBytes);
                firstframe = false;
            }
        } catch (BitstreamException ex) {
            if ((ex.getErrorCode() == INVALIDFRAME)) {
                // 请尝试跳过此帧。
                //System.out.println("INVALIDFRAME");
                try {
                    closeFrame();
                    result = readNextFrame();
                } catch (BitstreamException e) {
                    if ((e.getErrorCode() != STREAM_EOF)) {
                        // 包装原始异常以保留堆栈跟踪。
                        throw newBitstreamException(e.getErrorCode(), e);
                    }
                }
            } else if ((ex.getErrorCode() != STREAM_EOF)) {
                // 包装原始异常以保留堆栈跟踪。
                throw newBitstreamException(ex.getErrorCode(), ex);
            }
        }
        return result;
    }

    /**
     * 读取下一个MP3帧。
     *
     * @return MP3帧头。
     * @throws BitstreamException
     */
    private Header readNextFrame() throws BitstreamException {
        if (frameSize == -1) {
            nextFrame();
        }
        return header;
    }

    /**
     * 读取下一个MP3帧。
     *
     * @throws BitstreamException
     */
    private void nextFrame() throws BitstreamException {
        // 整个帧由header类读取。
        header.read_header(this, crc);
    }

    /**
     * 未读取从帧中读取的字节。
     *
     * @throws BitstreamException
     */
    // 审核：为此添加新的错误代码。
    public void unreadFrame() throws BitstreamException {
        if (wordPointer == -1 && bitindex == -1 && (frameSize > 0)) {
            try {
                source.unread(frameBytes, 0, frameSize);
            } catch (IOException ex) {
                throw newBitstreamException(STREAM_ERROR);
            }
        }
    }

    /**
     * 关闭 MP3 帧。
     */
    public void closeFrame() {
        frameSize = -1;
        wordPointer = -1;
        bitindex = -1;
    }

    /**
     * 判断流中接下来的 4 个字节是否表示一个
     * 帧头。
     */
    public boolean isSyncCurrentPosition(int syncmode) throws BitstreamException {
        int read = readBytes(syncBuf, 0, 4);
        int headerString = ((syncBuf[0] << 24) & 0xFF000000) | ((syncBuf[1] << 16) & 0x00FF0000)
                | ((syncBuf[2] << 8) & 0x0000FF00) | ((syncBuf[3] << 0) & 0x000000FF);

        try {
            source.unread(syncBuf, 0, read);
        } catch (IOException ex) {
        }

        switch (read) {
            case 0:
                return true;
            case 4:
                return isSyncMark(headerString, syncmode, syncWord);
            default:
                return false;
        }
    }

    // 复习：此课程应提供内部课程，
    // 解析框架内容。最终， readBits将
    // 已移除！
    public int readBits(int n) {
        return getBits(n);
    }

    public int readCheckedBits(int n) {
        // REVIEW：实现 CRC 校验。
        return getBits(n);
    }

    BitstreamException newBitstreamException(int errorcode) {
        return new BitstreamException(errorcode, null);
    }

    BitstreamException newBitstreamException(int errorcode, Throwable throwable) {
        return new BitstreamException(errorcode, throwable);
    }

    /**
     * 从比特流中获取接下来的32位。
     * 它们存储在 headerString 中。
     * syncMode 允许 Synchro 标志 ID
     * 流末尾时返回值为 False。
     */
    int syncHeader(byte syncMode) throws BitstreamException {
        boolean sync;
        int headerString;
        // 再读取 2 个字节
        int bytesRead = readBytes(syncBuf, 0, 3);

        if (bytesRead != 3)
            throw newBitstreamException(STREAM_EOF, null);

        headerString = ((syncBuf[0] << 16) & 0x00FF0000) | ((syncBuf[1] << 8) & 0x0000FF00) | ((syncBuf[2] << 0) & 0x000000FF);

        do {
            headerString <<= 8;

            if (readBytes(syncBuf, 3, 1) != 1)
                throw newBitstreamException(STREAM_EOF, null);

            headerString |= (syncBuf[3] & 0x000000FF);

            sync = isSyncMark(headerString, syncMode, syncWord);
        } while (!sync);

        return headerString;
    }

    public boolean isSyncMark(int headerstring, int syncmode, int word) {
        boolean sync = false;

        if (syncmode == INITIAL_SYNC) {
            //sync =  ((headerstring & 0xFFF00000) == 0xFFF00000);
            sync = ((headerstring & 0xFFE00000) == 0xFFE00000); // SZD: MPEG 2.5
        } else {
            sync = ((headerstring & 0xFFF80C00) == word) && (((headerstring & 0x000000C0) == 0x000000C0) == singleChMode);
        }

        // 过滤无效采样率
        if (sync)
            sync = (((headerstring >>> 10) & 3) != 3);
        // 过滤无效图层
        if (sync)
            sync = (((headerstring >>> 17) & 3) != 0);
        // 过滤掉无效版本
        if (sync)
            sync = (((headerstring >>> 19) & 3) != 1);

        return sync;
    }

    /**
     * 读取下一帧的数据。框架未解析
     * 直到调用解析帧。
     */
    int readFrameData(int bytesize) throws BitstreamException {
        int numread = 0;
        numread = readFully(frameBytes, 0, bytesize);
        frameSize = bytesize;
        wordPointer = -1;
        bitindex = -1;
        return numread;
    }

    /**
     * 解析先前用 read_frame_data() 读取的数据。
     */
    void parseFrame() {
        // 将读取的字节转换为整数
        int b = 0;
        byte[] byteread = frameBytes;
        int bytesize = frameSize;

        // 检查ID3v1标签（仅在最后一帧时为True ）。

        for (int k = 0; k < bytesize; k = k + 4) {
            @SuppressWarnings("unused")
            int convert = 0;
            byte b0 = 0;
            byte b1 = 0;
            byte b2 = 0;
            byte b3 = 0;
            b0 = byteread[k];
            if (k + 1 < bytesize)
                b1 = byteread[k + 1];
            if (k + 2 < bytesize)
                b2 = byteread[k + 2];
            if (k + 3 < bytesize)
                b3 = byteread[k + 3];
            frameBuffer[b++] = ((b0 << 24) & 0xFF000000) | ((b1 << 16) & 0x00FF0000) | ((b2 << 8) & 0x0000FF00)
                    | (b3 & 0x000000FF);
        }
        wordPointer = 0;
        bitindex = 0;
    }

    /**
     * 从缓冲区读取位，放入无符号 int 的低位。
     * LSB包含流的最新读取位。
     * (1 < = number_of_bits < = 16)
     */
    public int getBits(int number_of_bits) {
        int returnvalue = 0;
        int sum = bitindex + number_of_bits;

        // E.B
        // 这里有问题， wordPointer可能是-1 ？ ！
        if (wordPointer < 0)
            wordPointer = 0;
        // EB ：结束。

        if (sum <= 32) {
            // *wordPointer 中包含的所有位
            returnvalue = (frameBuffer[wordPointer] >>> (32 - sum)) & bitmask[number_of_bits];
            if ((bitindex += number_of_bits) == 32) {
                bitindex = 0;
                wordPointer++; // 由我添加！
            }
            return returnvalue;
        }

        int Right = (frameBuffer[wordPointer] & 0x0000FFFF);
        wordPointer++;
        int Left = (frameBuffer[wordPointer] & 0xFFFF0000);
        returnvalue = ((Right << 16) & 0xFFFF0000) | ((Left >>> 16) & 0x0000FFFF);

        returnvalue >>>= 48 - sum;
        returnvalue &= bitmask[number_of_bits];
        bitindex = sum - 32;
        return returnvalue;
    }

    /**
     * 设置要将页眉同步到的单词。
     * 按大端字节序
     */
    void setSyncWord(int syncWord) {
        this.syncWord = syncWord & 0xFFFFFF3F;
        singleChMode = ((syncWord & 0x000000C0) == 0x000000C0);
    }

    /**
     * 从源读取确切数量的字节到
     * 输入流到字节数组中。
     *
     * @param b 读取指定数字的字节数组
     *             。
     * @param offs 数组中第一个字节所在的索引
     *             读数应被存储。
     * @param len 要读取的字节数。
     * 如果指定，则抛出@throws BitstreamException
     *                            无法从流中读取字节数。
     */
    private int readFully(byte[] b, int offs, int len) throws BitstreamException {
        int nRead = 0;
        try {
            while (len > 0) {
                int bytesread = source.read(b, offs, len);
                if (bytesread == -1) {
                    while (len-- > 0) {
                        b[offs++] = 0;
                    }
                    break;
                    //throw newBitstreamException(UNEXPECTED_EOF, new EOFException());
                }
                nRead = nRead + bytesread;
                offs += bytesread;
                len -= bytesread;
            }
        } catch (IOException ex) {
            throw newBitstreamException(STREAM_ERROR, ex);
        }
        return nRead;
    }

    /**
     * 类似于readFully ，但在以下情况下不会抛出异常：
     * 达到EOF。
     */
    private int readBytes(byte[] b, int offs, int len) throws BitstreamException {
        int totalBytesRead = 0;
        try {
            while (len > 0) {
                int bytesread = source.read(b, offs, len);
                if (bytesread == -1) {
                    break;
                }
                totalBytesRead += bytesread;
                offs += bytesread;
                len -= bytesread;
            }
        } catch (IOException ex) {
            throw newBitstreamException(STREAM_ERROR, ex);
        }
        return totalBytesRead;
    }
}
