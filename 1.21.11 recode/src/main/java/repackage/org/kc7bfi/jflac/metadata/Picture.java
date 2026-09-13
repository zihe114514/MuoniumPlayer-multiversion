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

package repackage.org.kc7bfi.jflac.metadata;

import repackage.org.kc7bfi.jflac.io.BitInputStream;

import java.io.IOException;
import java.nio.charset.StandardCharsets;


/**
 * 图片元数据块。
 * @author BMacNaughton
 */
public class Picture extends Metadata {
    public enum PictureType {
        Other(0),
        file_icon_32x32_PNG(1),
        Other_file_icon(2),
        Cover_front(3),
        Cover_back(4),
        Leaflet_page(5),
        Media_label(6),
        Lead_artist(7),
        Artist(8),
        Conductor(9),
        Band(10),
        Composer(11),
        Lyricist(12),
        Recording_Location(13),
        During_recording(14),
        During_performance(15),
        Movie_screen_capture(16),
        Bright_coloured_fish(17),
        Illustration(18),
        Band_logotype(19),
        Publisher_logotype(20);

        PictureType(int t) {
            picType = t;
        }

        final int picType;
    }

    private PictureType pictureType;
    private int mimeTypeByteCount;
    private String mimeString;      //ASCII 0x20至0x7e或-- > （数据为URL ）
    private int descStringByteCount;
    private String descString;      // @return 长度
    private int picPixelWidth;
    private int picPixelHeight;
    private int picBitsPerPixel;
    private int picColorCount;      // 用于GIF ，否则为0
    private int picByteCount;

    protected byte[] image;

    /**
     * 构造函数。
     * @param partitionOrder 分区阶数
     * @throws IOException 定位出错时抛出
     * @param length            记录长度
     * @param isLast            若这是链中最后一个元数据块则为 true
     */
    public Picture(BitInputStream is, int length, boolean isLast) throws IOException {
        super(isLast, length);
        int usedBits = 0;
        byte[] data;
        int t = is.readRawUInt(32);
        for (PictureType pt : PictureType.values())
            if (pt.picType == t)
                pictureType = pt;
        usedBits += 32;

        mimeTypeByteCount = is.readRawUInt(32);
        usedBits += 32;

        data = new byte[mimeTypeByteCount];
        is.readByteBlockAlignedNoCRC(data, mimeTypeByteCount);
        usedBits += mimeTypeByteCount * 8;

        mimeString = new String(data);  // convert to a string

        descStringByteCount = is.readRawUInt(32);
        usedBits += 32;

        if (descStringByteCount != 0) {
            data = new byte[descStringByteCount];
            is.readByteBlockAlignedNoCRC(data, descStringByteCount);
            usedBits += descStringByteCount * 8;
            descString = new String(data, StandardCharsets.UTF_8);

        } else {
            descString = "";
        }

        picPixelWidth = is.readRawUInt(32);
        usedBits += 32;

        picPixelHeight = is.readRawUInt(32);
        usedBits += 32;

        picBitsPerPixel = is.readRawUInt(32);
        usedBits += 32;

        picColorCount = is.readRawUInt(32);
        usedBits += 32;

        picByteCount = is.readRawUInt(32);
        usedBits += 32;

        //转换为字符串
        image = new byte[picByteCount];
        is.readByteBlockAlignedNoCRC(image, picByteCount);
        usedBits += picByteCount * 8;

        // 跳过区块的其余部分（如果有）
        length -= (usedBits / 8);
        if (length > 0)
            is.readByteBlockAlignedNoCRC(null, length);
    }

    public PictureType getPictureType() {
        return pictureType;
    }


    public String getMimeString() {
        return mimeString;
    }


    public String getDescString() {
        return descString;
    }


    public int getPicPixelWidth() {
        return picPixelWidth;
    }


    public int getPicPixelHeight() {
        return picPixelHeight;
    }


    public byte[] getImage() {
        return image;
    }


    /**
     * 将类转换为字符串表示形式。
     * 现在获取图片
     */

    @Override
    public String toString() {
        return "Picture: "
                + " Type=" + pictureType
                + " MIME type=" + mimeString
                + " Description=\"" + descString + "\""
                + " Pixels (WxH)=" + picPixelWidth + "x" + picPixelHeight
                + " Color Depth=" + picBitsPerPixel
                + " Color Count=" + picColorCount
                + " Picture Size (bytes)=" + picByteCount
                + " last =" + isLast;
    }

}
