/*
 * 版权所有(c) 1994, 2013 ，甲骨文和/或其附属公司。保留所有权利。
 * Oracle 专有/机密。使用须遵守许可条款。
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

package repackage.javazoom.jl.converter;

import lombok.Getter;

import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;

/**
 * 此类实现一个输出流，其中数据
 * 写入字节数组。缓冲区会随着数据自动增长
 * 被写入其中。
 * 可使用 <code>toByteArray()</code> 获取数据，
 * <code>toString()</code>.
 * <p>
 * 关闭 <tt>ByteArrayOutputStream</tt> 没有效果。
 * 可以在流关闭后调用此类，而无需
 * 生成 <tt>IOException</tt>。
 *
 * @author  Arthur van Hoff
 * @since   JDK1.0
 */

public class SeekableByteArrayOutputStream extends OutputStream {

    /**
     * 存储数据的缓冲区。
     */
    protected byte[] buf;

    /**
     * 缓冲区中的有效字节数。
     */
    @Getter
    protected int writePointer, count;

    /**
     * 创建新的字节数组输出流。缓冲区容量为
     * 最初为32个字节，但如果需要，其大小会增加。
     */
    public SeekableByteArrayOutputStream() {
        this(32);
    }

    /**
     * 创建一个新的字节数组输出流，缓冲区容量为
     * 指定大小，单位字节。
     *
     * @param   size   初始大小。
     * @exception IllegalArgumentException （如果大小为负）。
     */
    public SeekableByteArrayOutputStream(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("Negative initial size: "
                                               + size);
        }
        buf = new byte[size];
    }

    /**
     * 必要时增加容量，以确保它可以容纳
     * 至少由最小值指定的元素数量
     * 产能参数。
     *
     * @param minCapacity 所需的最小容量
     * @throws OutOfMemoryError 若 {@code minCapacity < 0}。这是
     * 解释为对无法满足的大容量的要求
     * {@code (long) Integer.MAX_VALUE + (minCapacity - Integer.MAX_VALUE)}.
     */
    private void ensureCapacity(int minCapacity) {
        // 有溢出意识的代码
        if (minCapacity - buf.length > 0)
            grow(minCapacity);
    }

    /**
     * 要分配的数组的最大大小。
     * 有些 JVM 会在数组中预留一些头字。
     * 尝试分配更大的数组可能导致
     * OutOfMemoryError：请求的数组大小超出 JVM 限制
     */
    private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;

    /**
     * 增加容量，以确保它至少可以容纳
     * 最小容量参数指定的元素数。
     *
     * @param minCapacity 所需的最小容量
     */
    private void grow(int minCapacity) {
        // 有溢出意识的代码
        int oldCapacity = buf.length;
        int newCapacity = oldCapacity << 1;
        if (newCapacity - minCapacity < 0)
            newCapacity = minCapacity;
        if (newCapacity - MAX_ARRAY_SIZE > 0)
            newCapacity = hugeCapacity(minCapacity);
        buf = Arrays.copyOf(buf, newCapacity);
    }

    private static int hugeCapacity(int minCapacity) {
        if (minCapacity < 0) // 溢出
            throw new OutOfMemoryError();
        return (minCapacity > MAX_ARRAY_SIZE) ?
            Integer.MAX_VALUE :
            MAX_ARRAY_SIZE;
    }

    /**
     * 将指定的字节写入此字节数组输出流。
     *
     * @param   b   要写入的字节。
     */
    public synchronized void write(int b) {
        ensureCapacity(count + 1);
        buf[writePointer] = (byte) b;
        this.updateCount(1);
        writePointer += 1;
    }

    /**
     * 从指定字节数组写入 <code>len</code> 个字节，
     * 从偏移量<code> off </code>开始到此字节数组输出流。
     *
     * @param   b     数据。
     * @param off 数据中的起始偏移量。
     * @param len 要写入的字节数。
     */
    public synchronized void write(byte[] b, int off, int len) {
        if ((off < 0) || (off > b.length) || (len < 0) ||
            ((off + len) - b.length > 0)) {
            throw new IndexOutOfBoundsException();
        }
        ensureCapacity(count + len);
        System.arraycopy(b, off, buf, writePointer, len);
        this.updateCount(len);
        writePointer += len;
    }

    private void updateCount(int count) {
        int newCount = writePointer + count;
        if (newCount > this.count) {
            this.count = newCount;
        }
    }

    public void seek(long pos) {
        this.writePointer = Math.toIntExact(pos);
    }


    public synchronized void reset() {
        writePointer = count = 0;
    }

    public synchronized byte[] toByteArray() {
        return Arrays.copyOf(buf, count);
    }

    public synchronized int size() {
        return count;
    }

    /**
     * 将缓冲区的内容转换为字符串解码字节，使用
     * 平台的默认字符集。新<tt>字符串</tt>的长度
     * 取决于字符集，因此可能不等于
     * 缓冲区的大小。
     *
     * <p> 此方法始终用此字符集的默认替换字符串替换畸形输入和不可映射字符，
     * 包含平台的默认替换字符串的序列
     * 默认字符集。{@linkplain java.nio.charset.CharsetDecoder}
     * 当需要对解码过程进行更多控制时，应使用该类。
     * 需要。
     *
     * @return 从缓冲区内容解码的字符串。
     * @since  JDK1.1
     */
    public synchronized String toString() {
        return new String(buf, 0, count);
    }

    /**
     * 通过使用指定字符集解码字节，将缓冲区内容转换为字符串，
     * 命名为{@link java.nio.charset.Charset charset}。新
     * <tt>String</tt> 的长度取决于字符集，因此可能不等于
     * 字节数组的长度。
     *
     * <p> 此方法始终用此字符集的默认替换字符串替换畸形输入和不可映射字符，
     * 序列替换为此字符集的默认替换字符串。需要更多解码控制时，应使用 {@link
     * 更多控制时应使用java.nio.charset.CharsetDecoder}类
     * 该类。
     *
     * @param charsetName 支持的名称
     *             {@link java.nio.charset.Charset charset}
     * @return 从缓冲区内容解码的字符串。
     * @exception UnsupportedEncodingException
     *             如果不支持命名字符集
     * @since      JDK1.1
     */
    public synchronized String toString(String charsetName)
        throws UnsupportedEncodingException
    {
        return new String(buf, 0, count, charsetName);
    }

    /**
     * 创建新分配的字符串。它的大小是当前的大小
     * 输出流以及缓冲区的有效内容已被
     * 复制到其中。结果字符串中的每个字符 <i>c</i>
     * 由字节中对应的元素<i> b </i>构造
     * 对应关系如下：
     * <blockquote><pre>
     *     c == (char)(((hibyte &amp; 0xff) &lt;&lt; 8) | (b &amp; 0xff))
     * </pre> </blockquote>
     *
     * @deprecated 此方法不能正确地将字节转换为字符。
     * 从 JDK&nbsp;1.1 起，首选方式是通过
     * <code>toString(String enc)</code> 方法，它接受编码名称
     * 或<code> toString() </code>方法，该方法使用
     * 平台的默认字符编码。
     *
     * @param      hibyte    每个结果 Unicode 字符的高字节。
     * @return 输出流的当前内容，作为字符串。
     * @see        java.io.ByteArrayOutputStream#size()
     * @see        java.io.ByteArrayOutputStream#toString(String)
     * @see        java.io.ByteArrayOutputStream#toString()
     */
    @Deprecated
    public synchronized String toString(int hibyte) {
        return new String(buf, hibyte, 0, count);
    }

    /**
     * 关闭 <tt>ByteArrayOutputStream</tt> 没有效果。
     * 可以在流关闭后调用此类，而无需
     * 生成 <tt>IOException</tt>。
     */
    public void close() {
    }

}
