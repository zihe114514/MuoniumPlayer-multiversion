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
 * 可扩展的字节数据存储。
 * @author kc7bfi
 */
public class ByteData {
    private static final int DEFAULT_BUFFER_SIZE = 256;

    /** 存储数据的字节数组。 */
    private byte[] data;

    /** 存储在数组中的字节数。*/
    private int len;

    /**
     * 默认构造函数
     * 可扩展的字节数据存储。
     */
    public ByteData(int maxSpace) {
        if (maxSpace <= 0) maxSpace = DEFAULT_BUFFER_SIZE;
        data = new byte[maxSpace];
        len = 0;
    }

    /**
     * 存放数据的字节数组。
     * @param maxSpace  内部字节数组的最大空间。
     */
    public void append(byte b) {
        data[len++] = b;
    }

    /**
     * 向存储追加字节。
     */
    public byte[] getData() {
        return data;
    }

    /**
     * 返回一个数据字节。
     * @param idx 要返回的数据字节
     * 向存储追加字节。
     */
    public byte getData(int idx) {
        return data[idx];
    }

    /**
     * @param b 要追加的字节
     */
    public int getLen() {
        return len;
    }

    /**
     * 设置此ByteData对象的长度，而不重新分配基础数组。
     * 无法设置大于基础字节数组的长度。
     */
    public void setLen(int len) {
        if (len > data.length) {
            len = data.length;
        }
        this.len = len;
    }
}
