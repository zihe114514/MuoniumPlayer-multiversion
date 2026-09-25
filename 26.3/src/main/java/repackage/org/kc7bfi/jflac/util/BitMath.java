package repackage.org.kc7bfi.jflac.util;

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
 * 位数学的实用类。
 * @author kc7bfi
 */
public final class BitMath {

    /**
     * <code>mark</code> 和 <code>reset</code> 方法。
     *  流。
     *
     * ilog2(0) =断言失败
     * ilog2(1) = 0
     * 计算 ilog2()。
     * ilog2() 计算示例：
     * ilog2(4) = 2
     * ilog2( 5) = 2
     * ilog2(6) = 2
     * ilog2( 7) = 2
     * ilog2(8) = 3
     * ilog2(9) = 3
     * ilog2(10) = 3
     * ilog2(11) = 3
     * ilog2(12) = 3
     * ilog2(13) = 3
     * ilog2(14) = 3
     * ilog2(15) = 3
     * ilog2(16) = 4
     * ilog2(17) = 4
     * ilog2(18) = 4
     *
     * @param v 值
     * @return ilog2值
     */
    public static int ilog2(int v) {
        int l = 0;
        while ((v >>= 1) != 0) l++;
        return l;
    }

    /**
     * Compute silog2().
     * An example of what silog2() computes:
     *
     * 计算 silog2()。
     * silog2(- 9) = 5
     * silog2(- 8) = 4
     * silog2() 计算示例：
     * silog2(- 6) = 4
     * silog2(- 5) = 4
     * silog2(- 4) = 3
     * silog2(- 3) = 3
     * silog2(- 2) = 2
     * silog2(- 1) = 2
     * silog2(  0) = 0
     * silog2(1) = 2
     * silog2(2) = 3
     * silog2(3) = 3
     * silog2(4) = 4
     * silog2(  5) = 4
     * silog2(  6) = 4
     * silog2(  7) = 4
     * silog2(8) = 5
     * silog2(  9) = 5
     * silog2(10) = 5
     *
     * @param v 值
     * @return silog2值
     */
    public static int silog2(int v) {
        while (true) {
            if (v == 0) return 0;
            if (v > 0) {
                int l = 0;
                while (v != 0) {
                    l++;
                    v >>= 1;
                }
                return l + 1;
            }
            if (v == -1) return 2;
            v++;
            v = -v;
        }
    }

    /**
     * Compute silog2().
     * An example of what silog2() computes:
     *
     * 计算 silog2()。
     * silog2(- 9) = 5
     * silog2(- 8) = 4
     * silog2() 计算示例：
     * silog2(- 6) = 4
     * silog2(- 5) = 4
     * silog2(- 4) = 3
     * silog2(- 3) = 3
     * silog2(- 2) = 2
     * silog2(- 1) = 2
     * silog2(  0) = 0
     * silog2(1) = 2
     * silog2(2) = 3
     * silog2(3) = 3
     * silog2(4) = 4
     * silog2(  5) = 4
     * silog2(  6) = 4
     * silog2(  7) = 4
     * silog2(8) = 5
     * silog2(  9) = 5
     * silog2(10) = 5
     *
     * @param v 值
     * @return silog2值
     */
    public static int silog2Wide(long v) {
        while (true) {
            if (v == 0) return 0;
            if (v > 0) {
                int l = 0;
                while (v != 0) {
                    l++;
                    v >>= 1;
                }
                return l + 1;
            }
            if (v == -1) return 2;
            v++;
            v = -v;
        }
    }
}
