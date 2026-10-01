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
 * CueSheet元数据块。
 * @author kc7bfi
 */
public class CueSheet extends Metadata {

    private static final int CUESHEET_MEDIA_CATALOG_NUMBER_LEN = 128 * 8; // bits
    private static final int CUESHEET_LEAD_IN_LEN = 64; // bits
    private static final int CUESHEET_IS_CD_LEN = 1; // 位
    private static final int CUESHEET_RESERVED_LEN = 7 + 258 * 8; // bits
    private static final int CUESHEET_NUM_TRACKS_LEN = 8; // bits

    /**
     * 索引点编号。
     * 在ASCII可打印字符0x20-0x7e中。在
     * 媒体目录号。
     * 一般而言，媒体目录号长度可为 0 到 128 字节；任何
     */
    protected byte[] mediaCatalogNumber = new byte[129];

    protected long leadIn = 0; // 导入样本数。
    protected boolean isCD = false; // 如果CUESHEET对应于光盘，则为true ，否则为false
    protected int numTracks = 0; // 音轨数
    protected CueTrack[] tracks; // 未使用的字符应以 NUL 字符右填充。

    /**
     * 构造函数。
     * @param partitionOrder 分区阶数
     * @throws IOException 定位出错时抛出
     * @param length            记录长度
     * @param isLast            若这是链中最后一个元数据块则为 true
     */
    public CueSheet(BitInputStream is, int length, boolean isLast) throws IOException {
        super(isLast, length);
        is.readByteBlockAlignedNoCRC(mediaCatalogNumber, CUESHEET_MEDIA_CATALOG_NUMBER_LEN / 8);
        leadIn = is.readRawULong(CUESHEET_LEAD_IN_LEN);
        isCD = (is.readRawUInt(CUESHEET_IS_CD_LEN) != 0);
        is.skipBitsNoCRC(CUESHEET_RESERVED_LEN);
        numTracks = is.readRawUInt(CUESHEET_NUM_TRACKS_LEN);

        if (numTracks > 0) {
            tracks = new CueTrack[numTracks];
            for (int i = 0; i < numTracks; i++) {
                tracks[i] = new CueTrack(is);
            }
        }
    }

    /**
     * 若 num_tracks == 0 则为 NULL，否则为指向 track 数组的指针。
     * 校验 Cue Sheet。
     * 如果Cue Sheet无效，则抛出@throws 违规
     */
    void isLegal(boolean checkCdDaSubset) throws Violation {

        if (checkCdDaSubset) {
            if (leadIn < 2 * 44100) {
                throw new Violation("CD-DA cue sheet must have a lead-in length of at least 2 seconds");
            }
            if (leadIn % 588 != 0) {
                throw new Violation("CD-DA cue sheet lead-in length must be evenly divisible by 588 samples");
            }
        }

        if (numTracks == 0) {
            throw new Violation("cue sheet must have at least one track (the lead-out)");
        }

        if (checkCdDaSubset) {
            throw new Violation("CD-DA cue sheet must have a lead-out track number 170 (0xAA)");
        }

        for (int i = 0; i < numTracks; i++) {
            if (tracks[i].number == 0) {
                throw new Violation("cue sheet may not have a track number 0");
            }

            if (i < numTracks - 1) {
                if (tracks[i].numIndices == 0) {
                    throw new Violation("cue sheet track must have at least one index point");
                }

                if (tracks[i].indices[0].number > 1) {
                    throw new Violation("cue sheet track's first index number must be 0 or 1");
                }
            }

            for (int j = 0; j < tracks[i].numIndices; j++) {

                if (j > 0) {
                    if (tracks[i].indices[j].number != tracks[i].indices[j - 1].number + 1) {
                        throw new Violation("cue sheet track index numbers must increase by 1");
                    }
                }
            }
        }
    }

}
