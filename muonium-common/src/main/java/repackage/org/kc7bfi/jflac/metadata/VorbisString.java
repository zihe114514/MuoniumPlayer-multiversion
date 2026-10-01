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
import java.nio.charset.StandardCharsets;

/**
 * Vorbis注释中的条目。
 * @author kc7bfi
 */
public class VorbisString {
    //private static final int VORBIS_COMMENT_ENTRY_LENGTH_LENT = 32;//位

    protected byte[] entry;

    /**
     * 构造函数。
     * @param partitionOrder 分区阶数
     * @param isLast            若这是链中最后一个元数据块则为 true
     */
    public VorbisString(BitInputStream is) throws IOException {
        int elen = is.readRawIntLittleEndian();
        if (elen == 0) return;
        entry = new byte[elen];
        is.readByteBlockAlignedNoCRC(entry, entry.length);
    }

    /**
     * @see Object#toString()
     */
    public String toString() {
        String s;
        s = new String(entry, StandardCharsets.UTF_8);
        return s;
    }
}
