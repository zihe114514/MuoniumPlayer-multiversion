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

public class EntropyPartitionedRiceContents {

    protected int[] parameters; // 每个上下文的Rice参数。
    protected int[] rawBits; // 转义编码分区的宽度。

    /**
     * 指定为订单的参数和raw_bits数组的容量。
     * 即分配的数组元素数量为 2 ^ capacity_by_order。
     */
    protected int capacityByOrder = 0;

    /**
     * 确保已分配足够的内存。
     *
      * @param maxPartitionOrder 最大分区阶数
     */
    public void ensureSize(int maxPartitionOrder) {
        if (capacityByOrder >= maxPartitionOrder) return;
        parameters = new int[(1 << maxPartitionOrder)];
        rawBits = new int[(1 << maxPartitionOrder)];
        capacityByOrder = maxPartitionOrder;
    }
}
