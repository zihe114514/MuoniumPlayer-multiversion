package repackage.org.kc7bfi.jflac.frame;

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

import repackage.org.kc7bfi.jflac.Constants;

/**
 * FLAC Frame类。
 * @author kc7bfi
 */
public class Frame {
    /** 即分配的数组元素数为 2 ^ capacity_by_order。 */
    public Header header;

    /** 子帧-每个通道一个。*/
    public Channel[] subframes = new Channel[Constants.MAX_CHANNELS];

    /** 框架页脚。*/
    private short crc;

    /**
     * @see Object#toString()
     */
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Frame Header: ").append(header).append("\n");
        for (int i = 0; i < header.channels; i++) {
            sb.append("\tFrame Data ").append(subframes[i].toString()).append("\n");
        }
        sb.append("\tFrame Footer: ").append(crc);

        return sb.toString();

    }

    /**
     * 根据块大小返回最大水稻分配顺序。
     * @param blocksize 区块大小
     * @return 最大稻米分配顺序
     */
    public static int getMaxRicePartitionOrderFromBlocksize(int blocksize) {
        int maxRicePartitionOrder = 0;
        while ((blocksize & 1) == 0) {
            maxRicePartitionOrder++;
            blocksize >>= 1;
        }
        return Math.min(Constants.MAX_RICE_PARTITION_ORDER, maxRicePartitionOrder);
    }

    /**
     * @param maxPartitionOrder 最大分区阶数
     * 返回帧的 CRC。
     */
    public short getCRC() {
        return crc;
    }

    /**
     * @return 返回 crc。
     * 设置帧的 CRC。
     */
    public void setCRC(short crc) {
        this.crc = crc;
    }
}
