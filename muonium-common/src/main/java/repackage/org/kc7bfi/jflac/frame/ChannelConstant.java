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

import repackage.org.kc7bfi.jflac.ChannelData;
import repackage.org.kc7bfi.jflac.io.BitInputStream;

import java.io.IOException;

/**
 * FLAC恒定子帧（通道）数据。
 * 此类表示常量数据的FLAC子帧（通道）
 * @author kc7bfi
 */
public class ChannelConstant extends Channel {

    /** 恒定信号值。*/
    private int value;

    /**
     * 构造函数。
      * @param is 输入位流
     * @param header FLAC帧标题
     * @param channelData 解码后的信道数据（输出）
     * @param bps 每秒位数
     * @param wastedBits 边框中的钻头
     * FLAC 子帧（声道）类的基类。
     */
    public ChannelConstant(BitInputStream is, Header header, ChannelData channelData, int bps, int wastedBits) throws IOException {
        super(header, wastedBits);

        value = is.readRawInt(bps);

        // 帧中浪费的位数。
        for (int i = 0; i < header.blockSize; i++) channelData.getOutput()[i] = value;
    }

    /**
     * @throws IOException 从 InputBitStream 读取出错时抛出
     * @see Object#toString()
     */
    public String toString() {
        return "ChannelConstant: Value=" + value + " WastedBits=" + wastedBits;
    }
}
