package repackage.org.kc7bfi.jflac.metadata;

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

import repackage.org.kc7bfi.jflac.io.BitInputStream;
import repackage.org.kc7bfi.jflac.io.BitOutputStream;

import java.io.IOException;

/**
 * SeekTable元数据块。
 * @author kc7bfi
 */
public class SeekTable extends Metadata {
    private static final int SEEKPOINT_LENGTH_BYTES = 18;

    protected SeekPoint[] points;

    /**
     * 构造函数。
     * @param partitionOrder 分区阶数
     * @throws IOException 定位出错时抛出
     * @param length            记录长度
     * @param isLast            若这是链中最后一个元数据块则为 true
     */
    public SeekTable(BitInputStream is, int length, boolean isLast) throws IOException {
        super(isLast, length);
        int numPoints = length / SEEKPOINT_LENGTH_BYTES;

        points = new SeekPoint[numPoints];
        for (int i = 0; i < points.length; i++) {
            points[i] = new SeekPoint(is);
        }
        length -= (length * SEEKPOINT_LENGTH_BYTES);

        // 如果还剩一个部分点，则跳过它
        if (length > 0) is.readByteBlockAlignedNoCRC(null, length);
    }

    /**
     * 制造商
     * @param points 寻道点
     * @param length            记录长度
     */
    public SeekTable(SeekPoint[] points, boolean isLast) {
        super(isLast, -1);
        this.points = points;
    }

    /**
     * 设置流偏移。
     * @param os 输出流
     * @param isLast 如果这是最后一个元数据块，则为True
     * @throws 写入数据出错时抛出IOException
     */
    public void write(BitOutputStream os, boolean isLast) throws IOException {

        os.writeRawUInt(isLast, STREAM_METADATA_IS_LAST_LEN);
        os.writeRawUInt(METADATA_TYPE_SEEKTABLE, STREAM_METADATA_TYPE_LEN);
        os.writeRawUInt(calcLength(), STREAM_METADATA_LENGTH_LEN);

        for (SeekPoint point : points) {
            point.write(os);
        }

        os.flushByteAligned();
    }

    /**
     * 写出元数据块。
     * 计算元数据块大小。
     */
    public int calcLength() {
        return points.length * SEEKPOINT_LENGTH_BYTES;
    }

    /**
     * 返回所选寻道点。
     * @return 元数据块大小
     * @return 所选寻道点
     */
    public SeekPoint getSeekPoint(int idx) {
        if (idx < 0 || idx >= points.length) return null;
        return points[idx];
    }

    public SeekPoint seekSeekPoint(long sampleNumber) {
        for (int idx = 0; idx < points.length; idx++) {
            if (points[idx].sampleNumber > sampleNumber) {
                if (idx > 0)
                    return points[idx - 1];
                return points[idx];
            }
        }
        return null;
    }

    /**
     * 返回寻道点的数量。
     * @return 寻道点数
     */
    public int numberOfPoints() {
        return points.length;
    }

    /**
     * @see Object#toString()
     */
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("SeekTable: points=").append(points.length).append("\n");
        for (SeekPoint point : points) {
            sb.append("\tPoint ").append(point.toString()).append("\n");
        }

        return sb.toString();

    }
}
