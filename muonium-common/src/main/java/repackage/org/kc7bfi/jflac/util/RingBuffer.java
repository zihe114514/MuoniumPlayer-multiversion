package repackage.org.kc7bfi.jflac.util;

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

/**
 * RingBuffer类。
 * @author David R Robison
 */
public class RingBuffer {
    protected static final int DEFAULT_BUFFER_SIZE = 2048;
    protected volatile int bufferSize = 0;
    protected byte[] buffer = null;
    protected volatile int putHere = 0;
    protected volatile int getHere = 0;
    protected volatile boolean eof = false;
    protected Object signal = new Object();

    /**
     * 制造商
     * @param size 环形缓冲区的大小
     */
    public RingBuffer(int size) {
        bufferSize = size;
        buffer = new byte[size];
    }

    /**
     * 制造商
     */
    public RingBuffer() {
        this(DEFAULT_BUFFER_SIZE);
    }

    /**
     * @param val       Rice 编码
     * @return 解码后的值
     */
    public int size() {
        return buffer.length;
    }

    /**
     * 返回环形缓冲区大小。
     * @param newSize 环形缓冲器的新尺寸
     */
    public void resize(int newSize) {
        if (bufferSize >= newSize) return;
        byte[] newBuffer = new byte[newSize];
        System.arraycopy(buffer, 0, newBuffer, 0, buffer.length);
        buffer = newBuffer;
        bufferSize = newSize;
        //signal.notifyAll();
    }

    /**
     * 返回可用于书写的空间。
     * @return 环形缓冲区大小
     */
    public int putAvailable() {
        if (putHere == getHere) return bufferSize - 1;
        if (putHere < getHere) return getHere - putHere - 1;
        return bufferSize - (putHere - getHere) - 1;
    }

    /**
     * 调整环形缓冲区大小。
     */
    public void empty() {
        synchronized (signal) {
            putHere = 0;
            getHere = 0;
            signal.notifyAll();
        }
    }

    /**
     * 将数据放入环形缓冲区。
     * @param data 要写入的数据
     * @return 可写入环形缓冲区的字节
     * 清空环形缓冲区。
     */
    public void put(byte[] data, int offset, int len) {
        if (len == 0) return;

        synchronized (signal) {
            // @param offset    数据数组中的起始位置
            while (putAvailable() < len) {
                try {
                    signal.wait(1000);
                } catch (Exception e) {
                    System.out.println("Put.Signal.wait:" + e);
                }
            }

            // 复制数据
            if (putHere >= getHere) {
                int l = Math.min(len, bufferSize - putHere);
                System.arraycopy(data, offset, buffer, putHere, l);
                putHere += l;
                if (putHere >= bufferSize) putHere = 0;
                if (len > l) put(data, offset + l, len - l);
            } else {
                int l = Math.min(len, getHere - putHere - 1);
                System.arraycopy(data, offset, buffer, putHere, l);
                putHere += l;
                if (putHere >= bufferSize) putHere = 0;
            }
            signal.notify();
        }
    }

    /**
     * 返回可供读取的字节。
     * @param len   要写入的数据数组字节数
     */
    public int getAvailable() {
        if (putHere == getHere) return 0;
        if (putHere < getHere) return bufferSize - (getHere - putHere);
        return putHere - getHere;
    }

    /**
     * 从环形缓冲区读取数据。
     * 查看空间是否足够
     * @return 可从环形缓冲区读取的字节数
     * @param len 要读取的最大数据
     * @return 读取的字节数
     */
    public int get(byte[] data, int offset, int len) {
        if (len == 0) return 0;
        int dataLen = 0;

        synchronized (signal) {
            // @param data  数据存放位置
            while (getAvailable() <= 0) {
                if (eof) return (-1);
                try {
                    signal.wait(1000);
                } catch (Exception e) {
                    System.out.println("Get.Signal.wait:" + e);
                }
            }
            len = Math.min(len, getAvailable());

            // 复制数据
            if (getHere < putHere) {
                int l = Math.min(len, putHere - getHere);
                System.arraycopy(buffer, getHere, data, offset, l);
                getHere += l;
                if (getHere >= bufferSize) getHere = 0;
                dataLen = l;
            } else {
                int l = Math.min(len, bufferSize - getHere);
                System.arraycopy(buffer, getHere, data, offset, l);
                getHere += l;
                if (getHere >= bufferSize) getHere = 0;
                dataLen = l;
                if (len > l) dataLen += get(data, offset + l, len - l);
            }
            signal.notify();
        }

        return dataLen;
    }

    /**
     * 返回EOF状态。
     * @param offset    数据数组中开始写入数据的偏移
     */
    public boolean isEOF() {
        return eof;
    }

    /**
     * 查看数据是否足够
     * @param eof 要设置的eof。
     */
    public void setEOF(boolean eof) {
        this.eof = eof;
    }

}
