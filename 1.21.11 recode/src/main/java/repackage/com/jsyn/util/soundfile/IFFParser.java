/*
 * 版权所有1997 Phil Burk, Mobileer Inc
 *
 * 依据 Apache 许可证 2.0 版（下称“许可证”）授权；
 * 除非符合许可规定，否则您不得使用此文件。
 * 您可以通过以下网址获取许可证副本：
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * 除非适用法律要求或书面同意，在许可证
 * 根据许可证分发是“按原样”分发的，
 * 在任何情况下，无论明示或默示，不提供任何担保或条件。
 * 有关许可证项下的权限，请参见许可证；
 * 许可项下的限制。
 */

package repackage.com.jsyn.util.soundfile;

import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 解析Electronic Arts风格的IFF文件。IFF是一种文件格式，允许“块”数据
 * 放置在分层文件中。它是由Electronic Arts的Jerry Morrison为Amiga设计的
 * 计算机，现被 Apple Computer 及其他公司广泛使用。IFF 是一种开放
 * 这不会是标准SWATH。
 *
 * @see RIFFParser
 * @see AudioSampleAIFF
 * @author (C) 1997 Phil Burk, SoftSynth.com
 */

public class IFFParser extends FilterInputStream {

    protected long numBytesRead = 0;
    protected long totalSize = 0;
    protected int fileId;
    static boolean debug = false;

    public static final int RIFF_ID = ('R' << 24) | ('I' << 16) | ('F' << 8) | 'F';
    public static final int LIST_ID = ('L' << 24) | ('I' << 16) | ('S' << 8) | 'T';
    public static final int FORM_ID = ('F' << 24) | ('O' << 16) | ('R' << 8) | 'M';

    public IFFParser(InputStream stream) {
        super(stream);
        numBytesRead = 0;
    }

    /**
     * 基于最外层块大小加 8 得到的文件大小。可用于在
     * 加载采样时报告进度。
     *
     * @return 外块加标头中的字节数。
     */
    public long getFileSize() {
        return totalSize;
    }

    /**
     * 由于IFF文件使用显式大小的块，因此跟踪
     * 已从文件中读取字节。加载样品时可用于报告进度。
     *
     * @return 从流读取的字节数，或跳过的字节数。
     */
    public long getOffset() {
        return numBytesRead;
    }

    /** @return 来自流的下一个字节。增量偏移1。*/
    @Override
    public int read() throws IOException {
        numBytesRead++;
        return super.read();
    }

    /** @return 来自流的下一个字节数组。增量被镜头偏移。*/
    @Override
    public int read(byte[] bar) throws IOException {
        return read(bar, 0, bar.length);
    }

    /** @return 来自流的下一个字节数组。增量被镜头偏移。*/
    @Override
    public int read(byte[] bar, int off, int len) throws IOException {
        // 从URL读取可以在所有字节可用之前返回。
        // 所以我们继续阅读，直到我们得到全部内容。
        int cursor = off;
        int numLeft = len;
        // 继续读取数据，直到我们获得所有数据
        while (numLeft > 0) {
            int numRead = super.read(bar, cursor, numLeft);
            if (numRead < 0)
                return numRead;
            cursor += numRead;
            numBytesRead += numRead;
            numLeft -= numRead;
            // LOGGER.debug("read" + numRead + ", cursor =" + cursor +
            // ", len =" + len);
        }
        return cursor - off;
    }

    /** @return 在流中向前跳过，并在偏移量中添加numBytes。*/
    @Override
    public long skip(long numBytes) throws IOException {
        numBytesRead += numBytes;
        return super.skip(numBytes);
    }

    /** 读取假设大端字节顺序的32位有符号整数。*/
    public int readIntBig() throws IOException {
        int result = read() & 0xFF;
        result = (result << 8) | (read() & 0xFF);
        result = (result << 8) | (read() & 0xFF);
        int data = read();
        if (data == -1)
            throw new EOFException("readIntBig() - EOF in middle of word at offset " + numBytesRead);
        result = (result << 8) | (data & 0xFF);
        return result;
    }

    /** 按小端字节序读取 32 位有符号整数。 */
    public int readIntLittle() throws IOException {
        int result = read() & 0xFF; // LSB
        result |= ((read() & 0xFF) << 8);
        result |= ((read() & 0xFF) << 16);
        int data = read();
        if (data == -1)
            throw new EOFException("readIntLittle() - EOF in middle of word at offset "
                    + numBytesRead);
        result |= (data << 24);
        return result;
    }

    /** 按大端字节序读取 16 位有符号 short。 */
    public short readShortBig() throws IOException {
        short result = (short) ((read() << 8)); // MSB
        int data = read();
        if (data == -1)
            throw new EOFException("readShortBig() - EOF in middle of word at offset "
                    + numBytesRead);
        result |= data & 0xFF;
        return result;
    }

    /** 假设Little Endian字节顺序，读取16位有符号短码。*/
    public short readShortLittle() throws IOException {
        short result = (short) (read() & 0xFF); // LSB
        int data = read(); // MSB
        if (data == -1)
            throw new EOFException("readShortLittle() - EOF in middle of word at offset "
                    + numBytesRead);
        result |= data << 8;
        return result;
    }

    public int readUShortLittle() throws IOException {
        return (readShortLittle()) & 0x0000FFFF;
    }

    /** 读取8位带符号字节。*/
    public byte readByte() throws IOException {
        return (byte) read();
    }

    /** 假设IFF顺序，读取32位有符号int。*/
    public int readChunkSize() throws IOException {
        if (isRIFF()) {
            return readIntLittle();
        }
        {
            return readIntBig();
        }
    }

    /** 将4个字符的IFF ID转换为字符串*/
    public static String IDToString(int ID) {
        byte[] bar = new byte[4];
        bar[0] = (byte) (ID >> 24);
        bar[1] = (byte) (ID >> 16);
        bar[2] = (byte) (ID >> 8);
        bar[3] = (byte) ID;
        return new String(bar);
    }

    /**
     * 读取第一个 ID 后解析流，并把各个 form 和块传给 ChunkHandler
     */
    public void parseAfterHead(ChunkHandler handler) throws IOException {
        int numBytes = readChunkSize();
        totalSize = numBytes + 8;
        parseChunk(handler, fileId, numBytes);
    }

    /**
     * 解析表单并将块传递给ChunkHandler光标应位于右侧
     * 在类型字段之后。
     */
    public void parseForm(ChunkHandler handler, int ID, int numBytes, int type) throws IOException {
        while (numBytes > 8) {
            int ckid = readIntBig();
            int size = readChunkSize();
            numBytes -= 8;
            if (size < 0) {
                throw new IOException("Bad IFF chunk Size: " + IDToString(ckid) + " = 0x"
                        + Integer.toHexString(ckid) + ", Size = " + size);
            }
            parseChunk(handler, ckid, size);
            if ((size & 1) == 1)
                size++; // even-up
            numBytes -= size;
        }

        if (numBytes > 0) {
            skip(numBytes);
        }
    }

    /*
     * 从IFF文件解析一个块。调用处理程序后，请确保流位于末尾
     * 大块的。
     */
    public void parseChunk(ChunkHandler handler, int ckid, int numBytes) throws IOException {
        long startOffset, endOffset;
        int numRead;
        startOffset = getOffset();
        if (isForm(ckid)) {
            int type = readIntBig();
            handler.handleForm(this, ckid, numBytes - 4, type);
            endOffset = getOffset();
            numRead = (int) (endOffset - startOffset);
            if (numRead < numBytes)
                parseForm(handler, ckid, (numBytes - numRead), type);
        } else {
            handler.handleChunk(this, ckid, numBytes);
        }
        endOffset = getOffset();
        numRead = (int) (endOffset - startOffset);
        if ((numBytes & 1) == 1)
            numBytes++; // even-up
        if (numRead < numBytes)
            skip(numBytes - numRead);
    }

    public void readHead() throws IOException {
        numBytesRead = 0;
        fileId = readIntBig();
    }

    public boolean isRIFF() {
        return (fileId == RIFF_ID);
    }

    public boolean isIFF() {
        return (fileId == FORM_ID);
    }

    /**
     * 以下区块ID是否对应于类似表单的容器类型？
     */
    public boolean isForm(int ckid) {
        if (isRIFF()) {
            switch (ckid) {
                case LIST_ID:
                case RIFF_ID:
                    return true;
                default:
                    return false;
            }
        } else {
            switch (ckid) {
                case LIST_ID:
                case FORM_ID:
                    return true;
                default:
                    return false;
            }
        }
    }

}
