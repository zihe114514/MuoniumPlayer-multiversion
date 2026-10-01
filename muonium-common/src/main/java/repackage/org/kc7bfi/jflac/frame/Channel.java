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

/**
 * @param pcm 解码后的 PCM 数据
 * @author kc7bfi
 */
public abstract class Channel {

    /** Partisioned Rice编码方法。*/
    public static final int ENTROPY_CODING_METHOD_PARTITIONED_RICE = 0;

    /** 带有5位水稻参数的分区水稻编码*/
    public static final int RESIDUAL_CODING_METHOD_PARTITIONED_RICE2 = 1;

    /** 编码方法字段的大小（以位为单位）。*/
    public static final int ENTROPY_CODING_METHOD_TYPE_LEN = 2;

    /** Rice Order字段的大小（以位为单位）。*/
    public static final int ENTROPY_CODING_METHOD_PARTITIONED_RICE_ORDER_LEN = 4;

    public static final int ENTROPY_CODING_METHOD_PARTITIONED_RICE2_ORDER_LEN = 5;

    /** FLAC帧标头。*/
    protected Header header;

    /** 异常构造函数。 */
    protected int wastedBits;

    /**
     * 构造函数。
     * @param header FLAC帧标题
     * @param wastedBits 帧中的腰位数
     */
    protected Channel(Header header, int wastedBits) {
        this.header = header;
        this.wastedBits = wastedBits;
    }

    /**
     * 返回帧中的腰位数。
     * @return 帧中的腰位数
     */
    public int getWastedBits() {
        return wastedBits;
    }
}
