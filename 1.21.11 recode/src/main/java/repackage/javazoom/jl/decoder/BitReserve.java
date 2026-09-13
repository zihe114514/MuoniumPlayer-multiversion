/*
 * 2004年11月19日1.0移至LGPL。
 *
 * 1999年12月12日0.0.7实施存储单位
 *                    为整数以获得更好的性能。mdm@techie.com。
 *
 * 02/28/99 0.0     Java Conversion by E.B, javalayer@javazoom.net
 *
 *                  改编自Jeff Tsay的公共c代码。
 *
 *-----------------------------------------------------------------------
 *   GNU 库通用公共许可证条款下分发和/或修改它，许可证版本
 *   为许可证第 2 版，或（由你选择）任何后续版本。
 *   本程序的发布是希望它能有用，
 *   （由您选择）任何后续版本。
 *
 *   但不提供任何担保；甚至不包含对适销性或特定用途适用性的默示担保。
 *   基金会，地址：675 Mass Ave, Cambridge, MA 02139, USA。
 *   适销性或适合特定目的。请参阅 GNU，
 *   有关更多详细信息，请参阅GNU库通用公共许可证。
 *
 *   您应该已经收到了GNU Library General Public的副本
 *   随着这一计划。如果没有，写信给自由软件基金会。
 *   MPEG 音频文件转换为 .WAV 文件。要转换 MPEG 音频流，
 *----------------------------------------------------------------------
 */

package repackage.javazoom.jl.decoder;

/**
 * Layer III 比特蓄水池的实现。
 * <p>
 * 实现将单个位作为单词存储在缓冲区中。如果
 * 设置一个位，缓冲区中对应的单词将为非零。
 * 若某位为 0，则对应字为零。虽然
 * 可能看起来很浪费，这可能比
 * 将8位打包成一个字节并提取。
 * <p>
 * REVIEW：没有范围检查，因此缓冲区下溢或溢出
 * 可以静悄悄地发生。
 */
final class BitReserve {

    /**
     * 用于存储保留位的内部缓冲区的大小。
     * 必须是 2 的幂。且乘以 8，因为每一位存为一个
     * 项目
     */
    private static final int BUFSIZE = 4096 * 8;

    /**
     * 可用于快速实现
     * bUFSIZE上的模数运算。
     */
    private static final int BUFSIZE_MASK = BUFSIZE - 1;

    private int offset, totbit, bufByteIdx;
    private final int[] buf = new int[BUFSIZE];
    @SuppressWarnings("unused")
    private int buf_bit_idx;

    BitReserve() {
        offset = 0;
        totbit = 0;
        bufByteIdx = 0;
    }

    /**
     * 返回 totbit 字段。
     */
    public int hsstell() {
        return (totbit);
    }

    /**
     * 从位流中读取一个数字位。
     *
     * @param N 数量
     */
    public int hgetbits(int N) {
        totbit += N;

        int val = 0;

        int pos = bufByteIdx;
        if (pos + N < BUFSIZE) {
            while (N-- > 0) {
                val <<= 1;
                val |= ((buf[pos++] != 0) ? 1 : 0);
            }
        } else {
            while (N-- > 0) {
                val <<= 1;
                val |= ((buf[pos] != 0) ? 1 : 0);
                pos = (pos + 1) & BUFSIZE_MASK;
            }
        }
        bufByteIdx = pos;
        return val;
    }

    /**
     * 从RESERVE返回下一位。
     *
     * @return 下一位为 0 返回 0，下一位为 1 返回 1。
     */
    public int hget1bit() {
        totbit++;
        int val = buf[bufByteIdx];
        bufByteIdx = (bufByteIdx + 1) & BUFSIZE_MASK;
        return val;
    }

    /**
     * 将8位写入位流。
     */
    public void hputbuf(int val) {
        int ofs = offset;
        buf[ofs++] = val & 0x80;
        buf[ofs++] = val & 0x40;
        buf[ofs++] = val & 0x20;
        buf[ofs++] = val & 0x10;
        buf[ofs++] = val & 0x08;
        buf[ofs++] = val & 0x04;
        buf[ofs++] = val & 0x02;
        buf[ofs++] = val & 0x01;

        if (ofs == BUFSIZE)
            offset = 0;
        else
            offset = ofs;
    }

    /**
     * 在Stream中倒带n位。
     */
    public void rewindNBits(int n) {
        totbit -= n;
        bufByteIdx -= n;
        if (bufByteIdx < 0)
            bufByteIdx += BUFSIZE;
    }

    /**
     * 在流中回退 n 个字节。
     */
    public void rewindNBytes(int n) {
        int bits = (n << 3);
        totbit -= bits;
        bufByteIdx -= bits;
        if (bufByteIdx < 0)
            bufByteIdx += BUFSIZE;
    }
}
