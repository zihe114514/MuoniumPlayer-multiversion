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
 * 提示表中的条目。
 * @author kc7bfi
 */
public class CueTrack {

    private static final int CUESHEET_TRACK_OFFSET_LEN = 64; // bits
    private static final int CUESHEET_TRACK_NUMBER_LEN = 8; // bits
    private static final int CUESHEET_TRACK_ISRC_LEN = 12 * 8; // bits
    private static final int CUESHEET_TRACK_TYPE_LEN = 1; // 位
    private static final int CUESHEET_TRACK_PRE_EMPHASIS_LEN = 1; // 位
    private static final int CUESHEET_TRACK_RESERVED_LEN = 6 + 13 * 8; // bits
    private static final int CUESHEET_TRACK_NUM_INDICES_LEN = 8; // bits

    protected long offset; // @param checkCdDaSubset   检查 CD 子集时为 true
    protected byte number; // 轨道偏移量（采样数），相对于 FLAC 音频流的起始。
    protected byte[] isrc = new byte[13]; // 跟踪ISRC。这是一个12位字母数字代码加上尾随的“\ 0”
    protected int type; // 轨道号。
    protected int preEmphasis; // 轨道类型：0 为音频，1 为非音频。
    protected byte numIndices; // 轨道索引点的数量。
    protected CueIndex[] indices; // 如果num_indices = = 0 ，则为空，否则指向索引点数组的指针。

    /**
     * 构造函数。
     * @param partitionOrder 分区阶数
     * @param isLast            若这是链中最后一个元数据块则为 true
     */
    public CueTrack(BitInputStream is) throws IOException {
        offset = is.readRawULong(CUESHEET_TRACK_OFFSET_LEN);
        number = (byte) is.readRawUInt(CUESHEET_TRACK_NUMBER_LEN);
        is.readByteBlockAlignedNoCRC(isrc, CUESHEET_TRACK_ISRC_LEN / 8);
        type = is.readRawUInt(CUESHEET_TRACK_TYPE_LEN);
        preEmphasis = is.readRawUInt(CUESHEET_TRACK_PRE_EMPHASIS_LEN);
        is.skipBitsNoCRC(CUESHEET_TRACK_RESERVED_LEN);
        numIndices = (byte) is.readRawUInt(CUESHEET_TRACK_NUM_INDICES_LEN);
        if (numIndices > 0) {
            indices = new CueIndex[numIndices];
            for (int j = 0; j < numIndices; j++) {
                indices[j] = new CueIndex(is);
            }
        }

    }
}
