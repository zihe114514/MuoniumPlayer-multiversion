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

import java.io.IOException;

/**
 * 进入球杆轨道的入口。
 * @author kc7bfi
 */
public class CueIndex {

    private static final int CUESHEET_INDEX_OFFSET_LEN = 64; // bits
    private static final int CUESHEET_INDEX_NUMBER_LEN = 8; // bits
    private static final int CUESHEET_INDEX_RESERVED_LEN = 3 * 8; // bits

    protected long offset; // 样本中的偏移量，相对于索引点的轨道偏移量。
    protected byte number; // @throws IOException      从 InputBitStream 读取出错时抛出

    /**
     * 构造函数。
     * @param partitionOrder 分区阶数
     * @param isLast            若这是链中最后一个元数据块则为 true
     */
    public CueIndex(BitInputStream is) throws IOException {
        offset = is.readRawULong(CUESHEET_INDEX_OFFSET_LEN);
        number = (byte) is.readRawUInt(CUESHEET_INDEX_NUMBER_LEN);
        is.skipBitsNoCRC(CUESHEET_INDEX_RESERVED_LEN);
    }
}
