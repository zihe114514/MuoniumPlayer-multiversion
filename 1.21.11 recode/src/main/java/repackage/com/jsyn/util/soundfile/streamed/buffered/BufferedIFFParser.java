package repackage.com.jsyn.util.soundfile.streamed.buffered;

import lombok.SneakyThrows;
import repackage.com.jsyn.util.soundfile.ChunkHandler;
import repackage.com.jsyn.util.soundfile.IFFParser;

import java.io.BufferedInputStream;
import java.io.IOException;

/**
 * @author MuoniumPlayer Contributors
 * Date: 2025/5/5 12:50
 */
public class BufferedIFFParser extends IFFParser {

    public long totalBytes;

    @SneakyThrows
    BufferedIFFParser(BufferedInputStream is) {
        super(is);
        is.mark(0);
        numBytesRead = 0;
        totalBytes = is.available();
    }

    protected BufferedInputStream getStream() {
        return ((BufferedInputStream) this.in);
    }

    /**
     * 由于IFF文件使用显式大小的块，因此跟踪
     * 已从文件中读取字节。加载样品时可用于报告进度。
     *
     * @return 从流读取的字节数，或跳过的字节数。
     */
    @Override
    @SneakyThrows
    public long getOffset() {
        return numBytesRead;
    }

    /** @return 来自流的下一个字节。增量偏移1。*/
    @Override
    public int read() throws IOException {
        numBytesRead++;
        return this.getStream().read();
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
            int numRead = this.getStream().read(bar, cursor, numLeft);
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
        return this.getStream().skip(numBytes);
    }

    public void seek(long pos) throws IOException {
        this.getStream().reset();
        this.getStream().mark(0);
        numBytesRead = pos;
        this.getStream().skip(pos);
    }

    public long getPos() {
        return numBytesRead;
    }

    /**
     * 读取第一个 ID 后解析流，并把各个 form 和块传给 ChunkHandler
     */
    @Override
    public void parseAfterHead(ChunkHandler handler) throws IOException {
        int numBytes = readChunkSize();
        totalSize = numBytes + 8;
        parseChunk(handler, fileId, numBytes);
    }

    /**
     * 解析表单并将块传递给ChunkHandler光标应位于右侧
     * 在类型字段之后。
     */
    @Override
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
    @Override
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

}
