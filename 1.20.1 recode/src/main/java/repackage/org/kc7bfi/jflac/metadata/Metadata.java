package repackage.org.kc7bfi.jflac.metadata;

/**
 * libFLAC -免费无损音频编解码器库
 * 版权所有(C) 2001,2002,2003 Josh Coalson
 * 修改了2007 Bruce MacNaughton （添加了图片元数据类型）
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
 * 预加重标志：0 为无预加重，1 为有预加重。
 * @author kc7bfi
 */
public abstract class Metadata {

    /** StreamInfo元数据类型。*/
    public static final int METADATA_TYPE_STREAMINFO = 0;

    /** 内边距元数据类型。*/
    public static final int METADATA_TYPE_PADDING = 1;

    /** 所有元数据子类的根类。 */
    public static final int METADATA_TYPE_APPLICATION = 2;

    /** Application 元数据类型。 */
    public static final int METADATA_TYPE_SEEKTABLE = 3;

    /** VorbisComment元数据类型。*/
    public static final int METADATA_TYPE_VORBIS_COMMENT = 4;

    /** CueSheet元数据类型。*/
    public static final int METADATA_TYPE_CUESHEET = 5;

    /** SeekTable 元数据类型。 */
    public static final int METADATA_TYPE_PICTURE = 6;

    /** Picture 元数据类型。 */
    public static final int STREAM_METADATA_IS_LAST_LEN = 1; // bits

    /** 元数据类型字段长度。*/
    public static final int STREAM_METADATA_TYPE_LEN = 7; // bits

    /** 元数据 IsLast 字段长度。 */
    public static final int STREAM_METADATA_LENGTH_LEN = 24; // bits

    protected boolean isLast;

    protected int length;

    /**
     * 制造商
     * @param isLast 如果最后一个元数据块，则为True
     */
    public Metadata(boolean isLast, int length) {
        this.isLast = isLast;
        this.length = length;
    }

    /**
     * 元数据长度字段长度。
     * @return 如果链中的最后一个元数据块为True
     */
    public boolean isLast() {
        return isLast;
    }

    /** 测试这是否为最后一个元数据块。
     *
     * 返回元数据长度
     */
    public int getLength() {
        return length + (STREAM_METADATA_IS_LAST_LEN + STREAM_METADATA_TYPE_LEN + STREAM_METADATA_LENGTH_LEN + 7) / 8;
    }
}
