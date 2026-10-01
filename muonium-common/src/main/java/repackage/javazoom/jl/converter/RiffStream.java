/*
 * 本程序为自由软件；你可以在自由软件基金会发布的
 * 1999年2月23日E.B.的JavaConversion
 * Don Cross ， 1993年4月。
 * RIFF文件格式类。
 * 请参见《Multimedia Programmer's Reference》第 8 章，位于
 * Microsoft Windows SDK 中。
 *
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

package repackage.javazoom.jl.converter;

import lombok.Getter;


/**
 * 用于管理 RIFF 文件的类
 */
public class RiffStream {

    static class RiffChunkHeader {
        /** 四字符区块ID*/
        public int ckID = 0;
        /** 块中数据的长度 */
        public int ckSize = 0;
    }

    // DDCRET

    /** 操作成功*/
    public static final int DDC_SUCCESS = 0;
    /** 操作因未知原因失败 */
    public static final int DDC_FAILURE = 1;
    /** 因内存不足导致操作失败 */
    public static final int DDC_OUT_OF_MEMORY = 2;
    /** 操作遇到文件 I/O 错误 */
    public static final int DDC_FILE_ERROR = 3;
    /** 使用无效参数调用操作*/
    public static final int DDC_INVALID_CALL = 4;
    /** 操作被用户中止 */
    public static final int DDC_USER_ABORT = 5;
    /** 文件格式不匹配*/
    public static final int DDC_INVALID_FILE = 6;

    // RiffFileMode

    /** 未定义类型（可表示“N/A”或“未打开”） */
    public static final int RFM_UNKNOWN = 0;
    /** 打开并写入*/
    public static final int RFM_WRITE = 1;
    /** 打开以供阅读*/
    public static final int RFM_READ = 2;

    /** 整个文件的标题*/
    private RiffChunkHeader riffHeader;
    /** 当前文件 I/O 模式 */
    protected int fmode;
    /** 要使用的I/O流*/
    @Getter
    protected SeekableByteArrayOutputStream stream;

    /**
     * 哑构造函数
     */
    public RiffStream() {
        stream = null;
        fmode = RFM_UNKNOWN;
        riffHeader = new RiffChunkHeader();

        riffHeader.ckID = fourCC("RIFF");
        riffHeader.ckSize = 0;
    }

    /**
     * 返回文件模式。
     */
    public int CurrentFileMode() {
        return fmode;
    }

    /**
     * 打开一个 RIFF 文件。
     */
    public int Open() {
        int retcode = DDC_SUCCESS;

        if (fmode != RFM_UNKNOWN) {
            retcode = close();
        }

        if (retcode == DDC_SUCCESS) {
            this.stream = new SeekableByteArrayOutputStream();

            // 编写RIFF页眉...
            // 我们必须稍后再来修补它！
            byte[] br = new byte[8];
            br[0] = (byte) ((riffHeader.ckID >>> 24) & 0x000000FF);
            br[1] = (byte) ((riffHeader.ckID >>> 16) & 0x000000FF);
            br[2] = (byte) ((riffHeader.ckID >>> 8) & 0x000000FF);
            br[3] = (byte) (riffHeader.ckID & 0x000000FF);

            byte br4 = (byte) ((riffHeader.ckSize >>> 24) & 0x000000FF);
            byte br5 = (byte) ((riffHeader.ckSize >>> 16) & 0x000000FF);
            byte br6 = (byte) ((riffHeader.ckSize >>> 8) & 0x000000FF);
            byte br7 = (byte) (riffHeader.ckSize & 0x000000FF);

            br[4] = br7;
            br[5] = br6;
            br[6] = br5;
            br[7] = br4;

            stream.write(br, 0, 8);
            fmode = RFM_WRITE;
        }
        return retcode;
    }

    /**
     * 写入 numBytes 数据。
     */
    public int write(byte[] data, int numBytes) {
        if (fmode != RFM_WRITE) {
            return DDC_INVALID_CALL;
        }
        stream.write(data, 0, numBytes);
        riffHeader.ckSize += numBytes;
        return DDC_SUCCESS;
    }


    /**
     * 写入 numBytes 数据。
     */
    public int write(short[] data, int numBytes) {
        byte[] theData = new byte[numBytes];
        int yc = 0;
        for (int y = 0; y < numBytes; y = y + 2) {
            theData[y] = (byte) (data[yc] & 0x00FF);
            theData[y + 1] = (byte) ((data[yc++] >>> 8) & 0x00FF);
        }
        if (fmode != RFM_WRITE) {
            return DDC_INVALID_CALL;
        }
        stream.write(theData, 0, numBytes);
        riffHeader.ckSize += numBytes;
        return DDC_SUCCESS;
    }

    /**
     * 写入 numBytes 数据。
     */
    public int write(RiffChunkHeader triffHeader, int numBytes) {
        byte[] br = new byte[8];
        br[0] = (byte) ((triffHeader.ckID >>> 24) & 0x000000FF);
        br[1] = (byte) ((triffHeader.ckID >>> 16) & 0x000000FF);
        br[2] = (byte) ((triffHeader.ckID >>> 8) & 0x000000FF);
        br[3] = (byte) (triffHeader.ckID & 0x000000FF);

        byte br4 = (byte) ((triffHeader.ckSize >>> 24) & 0x000000FF);
        byte br5 = (byte) ((triffHeader.ckSize >>> 16) & 0x000000FF);
        byte br6 = (byte) ((triffHeader.ckSize >>> 8) & 0x000000FF);
        byte br7 = (byte) (triffHeader.ckSize & 0x000000FF);

        br[4] = br7;
        br[5] = br6;
        br[6] = br5;
        br[7] = br4;

        if (fmode != RFM_WRITE) {
            return DDC_INVALID_CALL;
        }
        stream.write(br, 0, numBytes);
        riffHeader.ckSize += numBytes;
        return DDC_SUCCESS;
    }

    public final void writeShort(int v) {
        stream.write((v >>> 8) & 0xFF);
        stream.write((v >>> 0) & 0xFF);
        //written += 2;
    }

    /**
     * 写入 numBytes 数据。
     */
    public int write(short data, int numBytes) {
        short theData = (short) (((data >>> 8) & 0x00FF) | ((data << 8) & 0xFF00));
        if (fmode != RFM_WRITE) {
            return DDC_INVALID_CALL;
        }
        writeShort(theData);
        fmode = RFM_WRITE;
        riffHeader.ckSize += numBytes;
        return DDC_SUCCESS;
    }

    public final void writeInt(int v) {
        stream.write((v >>> 24) & 0xFF);
        stream.write((v >>> 16) & 0xFF);
        stream.write((v >>>  8) & 0xFF);
        stream.write((v >>>  0) & 0xFF);
        //written += 4;
    }

    /**
     * 写入 numBytes 数据。
     */
    public int write(int data, int numBytes) {
        short theDataL = (short) ((data >>> 16) & 0x0000FFFF);
        short theDataR = (short) (data & 0x0000FFFF);
        short theDataLI = (short) (((theDataL >>> 8) & 0x00FF) | ((theDataL << 8) & 0xFF00));
        short theDataRI = (short) (((theDataR >>> 8) & 0x00FF) | ((theDataR << 8) & 0xFF00));
        int theData = ((theDataRI << 16) & 0xFFFF0000) | (theDataLI & 0x0000FFFF);
        if (fmode != RFM_WRITE) {
            return DDC_INVALID_CALL;
        }
        writeInt(theData);
        fmode = RFM_WRITE;
        riffHeader.ckSize += numBytes;
        return DDC_SUCCESS;
    }

    /**
     * 读取 numBytes 数据。
     */
    public int read(byte[] data, int numBytes) {
        return DDC_FILE_ERROR;
    }

    /**
     * 应为numBytes数据。
     */
    public int expect(String data, int numBytes) {
        return DDC_FILE_ERROR;
    }

    /**
     * 关闭 RIFF 文件。
     * 长度也写下来了。
     */
    public int close() {
        int retcode = DDC_SUCCESS;

        stream.seek(0);
        byte[] br = new byte[8];
        br[0] = (byte) ((riffHeader.ckID >>> 24) & 0x000000FF);
        br[1] = (byte) ((riffHeader.ckID >>> 16) & 0x000000FF);
        br[2] = (byte) ((riffHeader.ckID >>> 8) & 0x000000FF);
        br[3] = (byte) (riffHeader.ckID & 0x000000FF);

        br[7] = (byte) ((riffHeader.ckSize >>> 24) & 0x000000FF);
        br[6] = (byte) ((riffHeader.ckSize >>> 16) & 0x000000FF);
        br[5] = (byte) ((riffHeader.ckSize >>> 8) & 0x000000FF);
        br[4] = (byte) (riffHeader.ckSize & 0x000000FF);
        stream.write(br, 0, 8);
        stream.close();
        fmode = RFM_UNKNOWN;
        return retcode;
    }

    /**
     * 将数据写入指定偏移。
     */
    public int backpatch(long fileOffset, RiffChunkHeader data, int numBytes) {
        if (stream == null) {
            return DDC_INVALID_CALL;
        }
        stream.seek(fileOffset);
        return write(data, numBytes);
    }

    public int backpatch(long fileOffset, byte[] data, int numBytes) {
        if (stream == null) {
            return DDC_INVALID_CALL;
        }
        stream.seek(fileOffset);
        return write(data, numBytes);
    }

    /**
     * 在文件中定位。
     */
    protected int seek(long offset) {
        int rc;
        stream.seek(offset);
        rc = DDC_SUCCESS;
        return rc;
    }

    /**
     * 错误消息。
     */
    private String toDDCRETString(int retcode) {
        switch (retcode) {
            case DDC_SUCCESS:
                return "DDC_SUCCESS";
            case DDC_FAILURE:
                return "DDC_FAILURE";
            case DDC_OUT_OF_MEMORY:
                return "DDC_OUT_OF_MEMORY";
            case DDC_FILE_ERROR:
                return "DDC_FILE_ERROR";
            case DDC_INVALID_CALL:
                return "DDC_INVALID_CALL";
            case DDC_USER_ABORT:
                return "DDC_USER_ABORT";
            case DDC_INVALID_FILE:
                return "DDC_INVALID_FILE";
            default:
                return "Unknown Error";
        }
    }

    /**
     * 填充页眉。
     */
    public static int fourCC(String chunkName) {
        return RiffFile.fourCC(chunkName);
    }

    public long currentFilePosition() {
        return stream.getWritePointer();
    }
}
