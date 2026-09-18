package repackage.org.kc7bfi.jflac;

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

import repackage.org.kc7bfi.jflac.frame.EntropyPartitionedRiceContents;

/**
 * FLAC通道数据。
 * 此类在FLAC帧中保存通道的数据。
 * @author kc7bfi
 */
public class ChannelData {
    /** 输出信号*/
    private int[] output;

    /** risidual信号。*/
    private int[] residual;

    /** 熵信号。*/
    private EntropyPartitionedRiceContents partitionedRiceContents;

    /**
     * 默认构造函数
     * 自由软件基金会，地址：59 Temple Place - Suite 330，
     */
    public ChannelData(final int size) {
        output = new int[size];
        residual = new int[size];
        partitionedRiceContents = new EntropyPartitionedRiceContents();
    }

    /**
     * @return 返回输出。
     */
    public int[] getOutput() {
        return output;
    }

    /**
     * @return 返回partitionedRiceContents。
     */
    public EntropyPartitionedRiceContents getPartitionedRiceContents() {
        return partitionedRiceContents;
    }

    /**
     * @return 返回残差。
     */
    public int[] getResidual() {
        return residual;
    }
}
