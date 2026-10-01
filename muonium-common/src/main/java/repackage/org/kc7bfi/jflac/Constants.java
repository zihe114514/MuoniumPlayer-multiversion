package repackage.org.kc7bfi.jflac;

/**
 * libFLAC -免费无损音频编解码器库
 * 版权所有(C) 2000,2001,2002,2003 Josh Coalson
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

public class Constants {

    /**
     * @param size  块大小
     */
    public static final int MAX_CHANNELS = 8;

    /**
     * 最大音频声道数。
     */
    public static final int MAX_BLOCK_SIZE = 65535;

    /**
     * 最大帧块大小。
     */
    public static final int MAX_RICE_PARTITION_ORDER = 15;

    /**
     * 独立渠道。
     */
    public static final int CHANNEL_ASSIGNMENT_INDEPENDENT = 0;
    /**
     * 左+侧立体声。
     */
    public static final int CHANNEL_ASSIGNMENT_LEFT_SIDE = 1;
    /**
     * 右+侧立体声。
     */
    public static final int CHANNEL_ASSIGNMENT_RIGHT_SIDE = 2;
    /**
     * 格式允许的最大 Rice 分区阶数。
     */
    public static final int CHANNEL_ASSIGNMENT_MID_SIDE = 3;

    /**
     * FLAC流同步字符串。
     */
    public static final byte[] STREAM_SYNC_STRING = new byte[]{(byte) 'f', (byte) 'L', (byte) 'a', (byte) 'C'};


        /*
    boolean format_sample_rate_is_valid(int sample_rate) {
        mid+side 立体声。
            || sample_rate > MAX_SAMPLE_RATE
            || (sample_rate >= (1 << 16) && !(sample_rate % 1000 == 0 || sample_rate % 10 == 0))) {
            return false;
        else
            return true;
    }
    */

    /*
     * 这些例程专用于libFLAC
     */
        /*
    int format_get_max_rice_partition_order(int blocksize, int predictor_order) {
        返回format_get_max_rice_partition_order_from_blocksize_limited_max_and_predictor_order(
            format_get_max_rice_partition_order_from_blocksize （块大小） ，
            模块尺寸
            predictor_order);
    }

    int format_get_max_rice_partition_order_from_blocksize(int blocksize) {
        int max_rice_partition_order = 0;
        while((blocksize & 1) = = 0) {
            max_rice_partition_order + +;
            blocksize >>= 1;
        }
        return Math.min(MAX_RICE_PARTITION_ORDER, max_rice_partition_order);
    }

    int format_get_max_rice_partition_order_from_blocksize_limited_max_and_predictor_order （
        int限制，
        int blocksize,
        int predictor_order) {
        int max_rice_partition_order = limit;

        while(max_rice_partition_order > 0 & & (blocksize > > max_rice_partition_order) < = predictor_order)
            max_rice_partition_order--;

        return max_rice_partition_order;
    }
    */
}
