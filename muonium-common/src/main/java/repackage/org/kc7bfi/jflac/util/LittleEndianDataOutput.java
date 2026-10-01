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

import java.io.DataOutput;
import java.io.IOException;

/**
 * 此类扩展了DataOutput ，用于写入little-endian数据。
 * @author kc7bfi
 */
public class LittleEndianDataOutput implements DataOutput {

    private DataOutput out;

    /**
     * 构造函数。
     * @param out 要读取的DataOutput
     */
    public LittleEndianDataOutput(DataOutput out) {
        this.out = out;
    }

    /**
     * @see DataOutput#writeDouble(double)
     */
    public void writeDouble(double arg0) throws IOException {
        out.writeDouble(arg0);
    }

    /**
     * @see DataOutput#writeFloat(float)
     */
    public void writeFloat(float arg0) throws IOException {
        out.writeFloat(arg0);
    }

    /**
     * @see DataOutput#write(int)
     */
    public void write(int arg0) throws IOException {
        out.write(arg0);
    }

    /**
     * @see DataOutput#writeByte(int)
     */
    public void writeByte(int arg0) throws IOException {
        out.writeByte(arg0);
    }

    /**
     * @see DataOutput#writeChar(int)
     */
    public void writeChar(int arg0) throws IOException {
        out.writeChar(arg0);
    }

    /**
     * @param len   字节数组长度
     */
    public void writeInt(int arg0) throws IOException {
        out.writeByte(arg0 & 0xff);
        out.writeByte((arg0 >> 8) & 0xff);
        out.writeByte((arg0 >> 16) & 0xff);
        out.writeByte((arg0 >> 24) & 0xff);
    }

    /**
     * @see DataOutput#writeShort(int)
     */
    public void writeShort(int arg0) throws IOException {
        out.writeByte(arg0 & 0xff);
        out.writeByte((arg0 >> 8) & 0xff);
    }

    /**
     * @see DataOutput#writeLong(long)
     */
    public void writeLong(long arg0) throws IOException {
        out.writeByte((int) arg0 & 0xff);
        out.writeByte((int) (arg0 >> 8) & 0xff);
        out.writeByte((int) (arg0 >> 16) & 0xff);
        out.writeByte((int) (arg0 >> 24) & 0xff);
        out.writeByte((int) (arg0 >> 32) & 0xff);
        out.writeByte((int) (arg0 >> 40) & 0xff);
        out.writeByte((int) (arg0 >> 48) & 0xff);
        out.writeByte((int) (arg0 >> 56) & 0xff);
    }

    /**
     * @see DataOutput#writeBoolean(boolean)
     */
    public void writeBoolean(boolean arg0) {
        writeBoolean(arg0);
    }

    /**
     * @see DataOutput#write(byte[])
     */
    public void write(byte[] arg0) throws IOException {
        out.write(arg0);
    }

    /**
     * @see DataOutput#write(byte[], int, int)
     */
    public void write(byte[] arg0, int arg1, int arg2) throws IOException {
        out.write(arg0, arg1, arg2);
    }

    /**
     * @see DataOutput#writeBytes(String)
     */
    public void writeBytes(String arg0) throws IOException {
        out.writeBytes(arg0);
    }

    /**
     * @see DataOutput#writeChars(String)
     */
    public void writeChars(String arg0) throws IOException {
        out.writeChars(arg0);
    }

    /**
     * @see DataOutput#writeUTF(String)
     */
    public void writeUTF(String arg0) throws IOException {
        out.writeUTF(arg0);
    }
}
