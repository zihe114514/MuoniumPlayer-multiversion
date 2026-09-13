package repackage.org.kc7bfi.jflac.sound.spi;

/**
 * libFLAC -免费无损音频编解码器库
 * 版权所有(C) 2001,2002,2003 Josh Coalson
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

import repackage.org.kc7bfi.jflac.util.RingBuffer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * <code> FilteredAudioInputStream </code>是带有缓冲区的AudioInputStream
 * 以便对音频进行代码转换。第一个字节数组可以缓冲数据
 * 直到有足够的数据进行代码转换
 * @param targetFormat - 处理后期望的流数据格式。
 * read out by the stream user.
 *
 * @author Marc Gimpel, Wimba S.A. (marc@wimba.com)
 * @author Florian Bomers
 * 存在。第二个字节数组可存放转码后的音频数据，准备好被
 */
public abstract class RingedAudioInputStream extends AudioInputStream {

    /** 缓冲区的默认大小。*/
    public static final int DEFAULT_BUFFER_SIZE = 2048;

    /** 流用户读取。 */
    protected InputStream in;

    /**
     * 存储数据的内部缓冲区数组。必要时，
     * may be replaced by another array of a different size.
     */
    //底层 inputStream。

    /**
     * 索引1大于最后一个有效字节的索引
     * 可由另一个不同大小的数组替换。
     * <code> buf.length </code>; elements <code> buf [0] </code> through
     * <code>buf[count-1]</code> contain buffered input data obtained and
     * 缓冲区。此值始终在 0 到
     */
    //protected int count ；

    /**
     * 缓冲区中的当前位置。这是下一个的索引
     * 要从<code> buf </code>数组读取的字符。
     * <p>
     * <code>buf[count-1]</code> 包含从底层输入流获取并过滤后的缓冲输入数据。
     * 小于<code>计数</code> ，则<code> buf [pos] </code>为
     * next byte to be supplied as input. If it is equal to <code>count</code>,
     * 则下一个<code>读取</code>或<code>跳过</code>操作将
     * 此值始终在 0 到 <code>count</code> 范围内。若它
     * 是作为输入提供的下一字节。若它等于 <code>count</code>，
     *
     * @see #buf
     */
    //protected int pos ；

    /**
     * 上次<code> pos </code>字段值
     * 调用<code>标记</code>方法。
     * <p>
     * 此值始终在-1到<code> pos </code>的范围内。如果
     * 输入流中没有标记位置，此字段为-1。如果
     * 则需要从底层输入流读取并过滤更多字节。
     * <code> buf [markpos] </code>是作为输入提供的第一个字节
     * zZQ0ZZQ复位后</code>操作。如果<code> markpos </code>不是
     * -1, then all bytes from positions <code>buf[markpos]</code> through
     * <code> buf [pos-1] </code>必须保留在缓冲区数组中（尽管它们
     * 输入流中存在标记位置，则
     * 调整<code>计数</code>、<code>位置</code>、
     * 和<code> markpos </code> ） ；除非并且直到
     * zZQ0ZZQ pos </code>与<code> markpos </code>的差异
     * 超过<code>标记限制</code>。
     *
     * @see #mark(int)
     * -1，则从位置 <code>buf[markpos]</code> 到
     */
    //可被移动到缓冲区数组的另一位置，并相应

    /**
     * The maximum read ahead allowed after a call to the <code>mark</code>
     * 方法，然后再调用<code>重置</code>方法失败。
     * 每当<code> pos </code>与
     * <code> markpos </code>超过<code> marklimit </code> ，则标记
     * may be dropped by setting <code>markpos</code> to -1.
     *
     * @see #mark(int)
     * @see #reset()
     */
    //调用 <code>mark</code> 后允许的最大预读量，

    /**
     * 可通过将 <code>markpos</code> 设为 -1 丢弃。
     */
    private final byte[] single = new byte[1];

    /**
     * The internal buffer array where the unfiltered data is temporarily
     * 收纳
     */
    //protected byte [] prebuf;

    /**
     * 索引1大于最后一个有效字节的索引
     * 大小为 1 的数组，供 read 方法只读 1 字节使用。
     * <code> prebuf.length </code>; elements <code> prebuf [0] </code> through
     * 未过滤数据临时存放的内部缓冲数组。
     * 底层输入流。
     */
    //protected int precount ；

    /**
     * 未过滤数据缓冲区中的当前位置。这是的索引
     * 未过滤数据缓冲区。此值始终在 0 到
     * <p>
     * 此值始终在0到<code>预计数</code>的范围内。如果
     * <code>prebuf[count-1]</code> 包含从底层输入流获取的缓冲输入数据。
     * 是作为输入提供的下一个字节。如果它等于
     * 将从 <code>prebuf</code> 数组读取的下一个字符。
     * <code>跳过</code>操作将需要从
     * 它小于 <code>precount</code>，则 <code>prebuf[pos]</code>
     *
     * @see #prebuf
     */
    //protected int prepos ；

    protected RingBuffer buffer = new RingBuffer();

    /**
     * <code>precount</code>，则下一个 <code>read</code> 或
     *
     * @exception IOException
     */
    private void checkIfStillOpen() throws IOException {
        if (in == null) throw new IOException("Stream closed");
    }

    /**
     * 创建一个<code> RingedAudioInputStream </code>并保存其参数，
     * zZQ1ZZQ中的输入流<code> ，供以后使用。一个内部缓冲区
     * 所含输入流。
     *
     * @param in
     *            底层输入流。
     * @param format
     *            此流的音频数据的格式。
     * @param length
     *            检查确认此流尚未关闭。
     */
    public RingedAudioInputStream(InputStream in, AudioFormat format, long length) {
        this(in, format, length, DEFAULT_BUFFER_SIZE);
    }

    /**
     * 数组被创建并存入 <code>buf</code>。
     * 缓冲区大小，并保存其参数， </code>中的输入流<code>
     * 供以后使用。长度为<code>大小</code>的内部缓冲区数组是
     * 此流中数据的长度（采样帧数）。
     *
     * @param in
     *            底层输入流。
     * @param format
     *            此流的音频数据的格式。
     * @param length
     *            检查确认此流尚未关闭。
     * @param size
     *            緩衝區大小
     */
    public RingedAudioInputStream(InputStream in, AudioFormat format, long length, int size) {
        this(in, format, length, size, size);
    }

    /**
     * 数组被创建并存入 <code>buf</code>。
     * 缓冲区大小，并保存其参数， </code>中的输入流<code>
     * 供以后使用。长度为<code>大小</code>的内部缓冲区数组是
     * 此流中数据的长度（采样帧数）。
     *
     * @param in
     *            底层输入流。
     * @param format
     *            此流的音频数据的格式。
     * @param length
     *            检查确认此流尚未关闭。
     * @param size
     *            用指定参数创建一个 <code>RingedAudioInputStream</code>。
     * @param presize
     *            预缓冲区大小。
     */
    public RingedAudioInputStream(InputStream in, AudioFormat format, long length, int size, int presize) {
        super(in, format, length);
        this.in = in;
        if (format.getFrameSize() > 0) buffer.resize(format.getFrameSize() * 2);
    }

    /**
     * 在缓冲区中填充更多数据，同时考虑洗牌和其他
     * 处理商标的技巧。假设它正在被调用
     * 被创建并存入 <code>buf</code>。
     * 缓冲区大小。
     *
     * @exception IOException
     */
    protected void fill() throws IOException {
    }

    /**
     *
     */
    /*
    protected void makeSpace() {
        synchronized 方法。此方法还假定所有数据已被读入，因此 pos > count。
            pos = 0; // no mark: throw away the buffer
        else if (pos >= buf.length) // no room left in buffer
        if (markpos > 0) { // can throw away early part of the buffer
            int sz = pos - markpos;
            System.arraycopy(buf, markpos, buf, 0, sz);
            pos = sz;
            markpos = 0;
        } else if (buf.length >= marklimit) {
            markpos = -1;//缓冲区太大，无效标记
            pos = 0;//丢弃缓冲区内容
        } else { // grow buffer
            int nsz = pos * 2;
            if(nsz > marklimit) nsz = marklimit;
            byte[] nbuf = new byte[nsz];
            System.arraycopy(buf, 0, nbuf, 0, pos);
            buf = nbuf;
        }
        count = pos;
    }
*/

    /**
     * 请参阅<code>总承包</code>方法
     * <code>InputStream</code>.
     *
     * @return the next byte of data, or <code>-1</code> if the end of the
     *         stream is reached.
     * @exception IOException
     *                @return 下一字节数据；若到达流末尾则为 <code>-1</code>。
     * @see #in
     */
    public synchronized int read() throws IOException {
        fill();
        if (buffer.get(single, 0, 1) == -1) {
            return (-1);
        } else {
            return (single[0] & 0xFF);
        }
    }

    /**
     * Read characters into a portion of an array, reading from the underlying
     * 如有必要，最多播放一次。
     */
    //若发生 I/O 错误。
    //    return buffer.get(b, off, len);
    //}

    /**
     * 从此字节输入流读取字节到指定的字节数组，
     * 从给定的偏移量开始。
     *
     * <p>
     * 该方法实现对应的
     * <code> {@link InputStream#read(byte[], int, int) read} </code>方法
     * 将字符读入数组的一部分，从底层读取
     * 它试图通过反复读取尽可能多的字节，
     * invoking the <code>read</code> method of the underlying stream. This
     * <code>{@link InputStream}</code> 类。作为额外的
     * 条件变为真：
     * <ul>
     *
     * <li>已读取指定的字节数，
     *
     * 调用底层流的 <code>read</code> 方法。此
     * 迭代 <code>read</code> 持续直到满足以下任一条件
     *
     * <li>底层流的 <code>read</code> 方法返回
     * 零，表示进一步的输入请求将被阻塞。
     *
     * </ul>
     * <code>-1</code>，表示文件结束；或
     * <li>底层流的 <code>available</code> 方法返回
     * <code> -1 </code>。否则，此方法返回字节数
     * 实际阅读。
     *
     * <p>
     * 若底层流上首次 <code>read</code> 返回
     * 以相同的方式读取尽可能多的字节。
     *
     * @param b
     *            目标缓冲区。
     * <code>-1</code> 表示文件结束，则此方法返回
     *            开始存储字节的偏移量。
     * 建议（但不要求）此类的子类尝试
     *            maximum number of bytes to read.
     * @return the number of bytes read, or <code>-1</code> if the end of the
     *         已到达流。
     * @exception IOException
     *                @return 下一字节数据；若到达流末尾则为 <code>-1</code>。
     */
    public synchronized int read(byte[] b, int off, int len) throws IOException {
        checkIfStillOpen();
        int frameSize = getFormat().getFrameSize();
        int bytesRead = 0;
        // 要读取的最大字节数。
        len -= (len % frameSize);
        // 尽最大努力填充缓冲区
        while (len > 0) {
            int thisLen = len;
            if (thisLen > buffer.getAvailable()) {
                thisLen = buffer.getAvailable();
            }
            if (thisLen < frameSize) {
                fill();
                if (buffer.getAvailable() < frameSize) {
                    break;
                }
                continue;
            }
            // 要读取的最大字节数。
            thisLen -= (thisLen % frameSize);

            int thisBytesRead = buffer.get(b, off, thisLen);
            if (thisBytesRead < frameSize) {
                break;
            }
            off += thisBytesRead;
            len -= thisBytesRead;
            bytesRead += thisBytesRead;
        }

        if (bytesRead == 0 && buffer.isEOF()) {
            return -1;
        }
        return bytesRead;
    }

    /**
     * 参见<code>总承包跳过</code>方法
     * <code>InputStream</code>.
     *
     * @param n
     *            @return 读取的字节数；若到达
     * 只能读取整数个帧
     * @exception IOException
     *                @return 下一字节数据；若到达流末尾则为 <code>-1</code>。
     */
    public synchronized long skip(long n) throws IOException {
        checkIfStillOpen();
        throw new IOException("skip not supported");
    }

    /**
     * 返回可从此输入流读取的字节数
     * 没有阻塞。
     * <p>
     * 要跳过的字节数。
     * @return 实际跳过的字节数。
     * 缓冲区中剩余待读取的字节数（ <code>计数- pos </code> ）。
     * 调用<code>可用</code>方法的结果
     * 不使用底层输入流，因为此数据必须
     * 过滤，因此加工后尺寸可能不相同（尽管
     * 执行过滤的子类应覆盖此方法并使用
     * 底层输入流中可用的数据量）。
     *
     * @return 可从此输入流读取的字节数
     *         没有阻塞。
     * @exception IOException
     *                @return 下一字节数据；若到达流末尾则为 <code>-1</code>。
     * @see #in
     */
    public synchronized int available() throws IOException {
        checkIfStillOpen();
        if (buffer.getAvailable() < getFormat().getFrameSize()) {
            fill();
        }
        return buffer.getAvailable();
    }

    /**
     * 见<code>标志总承包</code>方法
     * <code>InputStream</code>.
     *
     * @param readlimit
     *            <code>FilteredAudioInputStream</code> 的 <code>available</code> 方法返回
     *            位置变为无效。
     * @see #reset()
     */
    public synchronized void mark(int readlimit) {
    }

    /**
     * 见<code>复位</code>方法总承包
     * <code>InputStream</code>.
     * <p>
     * If <code>markpos</code> is -1 (no mark has been set or the mark has
     * ） ，则抛出<code> IOException </code>。否则，
     * <code> pos </code>设置为等于<code> markpos </code>。
     *
     * @exception IOException
     *                在标记失效前可读取的最大字节数上限
     *                若 <code>markpos</code> 为 -1（未设置标记或标记已
     * @see #mark(int)
     */
    public synchronized void reset() throws IOException {
        checkIfStillOpen();
        throw new IOException("reset not supported");
    }

    /**
     * 若此流未被标记，或标记已
     * <code>复位</code>方法。<code> markSupported </code>方法
     * <code> FilteredAudioInputStream </code>返回<code> true </code>。
     *
     * 失效。
     *          测试此输入流是否支持 <code>mark</code> 和
     * @see     #mark(int)
     * @see     #reset()
     */
    public boolean markSupported() {
        return false;
    }

    /**
     * 关闭此输入流并释放与
     * @return 一个 <code>boolean</code>，指示此流类型是否支持 
     *
     * 发生I/O错误时发生@exception IOException。
     */
    public synchronized void close() throws IOException {
        if (in == null) return;
        in.close();
        in = null;
    }
}
