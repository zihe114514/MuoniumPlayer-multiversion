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
 * 逐字FLAC子帧（通道）。
 * @author kc7bfi
 */
public class ChannelVerbatim extends Channel {
    private int[] data; // 逐字信号指针。

    /**
     * 构造函数。
     * @param is InputBitStream
     * @param header FLAC帧标题
     * @param channelData 解码后的信道数据（输出）
     * @param bps 每秒位数
     * @param wastedBits 边框中的钻头
     * FLAC 子帧（声道）类的基类。
     */
    public ChannelVerbatim(BitInputStream is, Header header, ChannelData channelData, int bps, int wastedBits) throws IOException {
        super(header, wastedBits);

        data = channelData.getResidual();

        for (int i = 0; i < header.blockSize; i++) {
            data[i] = is.readRawInt(bps);
        }

        // 帧中浪费的位数。
        System.arraycopy(data, 0, channelData.getOutput(), 0, header.blockSize);
    }

    /**
     * @see Object#toString()
     */
    public String toString() {
        return "ChannelVerbatim: WastedBits=" + wastedBits;
    }
}
