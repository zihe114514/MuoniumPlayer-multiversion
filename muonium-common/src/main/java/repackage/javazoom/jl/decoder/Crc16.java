/*
 * 11/19/04 : 1.0 moved to LGPL.
 *
 * 1999年2月12日： E.B的Java转换， javalayer@javazoom.net
 *
 *  @ (#) crc.h 1.5 ，最后编辑： 1994年6月15日16:55:32
 *  @ (#)版权所有(C) 1993, 1994 Tobias Bading(bading@cs.tu-berlin.de)
 *  @ (#)柏林理工大学
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
 * 16位CRC校验和
 */
public final class Crc16 {

    private static final short polynomial = (short) 0x8005;
    private short crc;

    /**
     * 哑构造函数
     */
    public Crc16() {
        crc = (short) 0xFFFF;
    }

    /**
     * 将bitString馈送到CRC计算（ 0 < length < = 32 ）。
     */
    public void addBits(int bitString, int length) {
        int bitmask = 1 << (length - 1);
        do
            if (((crc & 0x8000) == 0) ^ ((bitString & bitmask) == 0)) {
                crc <<= 1;
                crc ^= polynomial;
            } else
                crc <<= 1;
        while ((bitmask >>>= 1) != 0);
    }

    /**
     * 返回计算的校验和。
     * 将其擦除，以便接下来调用add_bits()。
     */
    public short checksum() {
        short sum = crc;
        crc = (short) 0xFFFF;
        return sum;
    }
}
