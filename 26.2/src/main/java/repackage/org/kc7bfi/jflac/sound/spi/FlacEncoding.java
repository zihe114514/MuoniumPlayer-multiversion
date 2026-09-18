package repackage.org.kc7bfi.jflac.sound.spi;

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

import javax.sound.sampled.AudioFormat;

/**
 * FLAC音频解码器使用的编码。
 *
 * @author Marc Gimpel, Wimba S.A. (marc@wimba.com)
 * @version $Revision: 1.2 $
 */
public class FlacEncoding extends AudioFormat.Encoding {

    /** 指定任何Flac编码的数据。*/
    public static final FlacEncoding FLAC = new FlacEncoding("FLAC");

    /**
     * 获取完整属性映射的 Java 5.0 兼容方法。
     *
     * @param name -
     *            构造一个新的编码。
     */
    public FlacEncoding(String name) {
        super(name);
    }
}
