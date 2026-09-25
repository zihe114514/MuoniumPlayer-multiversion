package repackage.org.kc7bfi.jflac.frame;

import repackage.org.kc7bfi.jflac.io.BitInputStream;

import java.io.IOException;

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

public abstract class EntropyCodingMethod {
    protected int order; // 分区顺序，即上下文的# = 2 ^顺序。
    protected EntropyPartitionedRiceContents contents; // 读取量化的 lp 系数

    /**
     * 读取压缩信号残差数据。
     *
     * 该上下文的 Rice 参数和/或原始位。
     * 读取压缩信号残差数据。
      * @param is 输入位流
     * @param header FLAC帧标题
     * @param predictorOrder 预测器阶数
     * @throws IOException从InputBitStream读取时出错
     */
    abstract void readResidual(BitInputStream is, int predictorOrder, int partitionOrder, Header header, int[] residual) throws IOException;
}
