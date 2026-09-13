package repackage.org.kc7bfi.jflac.metadata;

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

import repackage.org.kc7bfi.jflac.io.BitInputStream;

import java.io.IOException;

/**
 * VorbisComment元数据块。
 * @author kc7bfi
 */
public class VorbisComment extends Metadata {

    //元数据格式违例异常。

    protected byte[] vendorString = new byte[0];
    protected int numComments = 0;
    protected VorbisString[] comments;

    /**
     * 构造函数。
     * @param partitionOrder 分区阶数
     * @throws IOException 定位出错时抛出
     * @param length            记录长度
     * @param isLast            若这是链中最后一个元数据块则为 true
     */
    public VorbisComment(BitInputStream is, int length, boolean isLast) throws IOException {
        super(isLast, length);

        // å¼
        int len = is.readRawIntLittleEndian();
        vendorString = new byte[len];
        is.readByteBlockAlignedNoCRC(vendorString, vendorString.length);

        // read comments
        numComments = is.readRawIntLittleEndian();
        if (numComments > 0) comments = new VorbisString[numComments];
        for (int i = 0; i < numComments; i++) {
            comments[i] = new VorbisString(is);
        }
    }

    /**
     * @see Object#toString()
     */
    public String toString() {
        StringBuilder sb = new StringBuilder("VendorString '" + new String(vendorString) + "'\n");
        sb.append("VorbisComment (count=").append(numComments).append(")");

        for (int i = 0; i < numComments; i++) {
            sb.append("\n\t").append(comments[i].toString());
        }

        return sb.toString();

    }

    public String[] getCommentByName(String key) {
        if (numComments == 0 || key == null) return null;
        java.util.Vector sbuff = new java.util.Vector();
        for (VorbisString vorbisString : comments) {
            String comment = vorbisString.toString();
            int eqpos = comment.indexOf(0x3D); //查找等于
            if (eqpos != -1)
                if (comment.substring(0, eqpos).equalsIgnoreCase(key))
                    sbuff.add(comment.substring(eqpos + 1));
        }
        return (String[]) sbuff.toArray(new String[0]);
        //return null;
    }
}
