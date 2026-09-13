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

import repackage.org.kc7bfi.jflac.Constants;
import repackage.org.kc7bfi.jflac.io.BitInputStream;
import repackage.org.kc7bfi.jflac.metadata.StreamInfo;
import repackage.org.kc7bfi.jflac.util.ByteData;
import repackage.org.kc7bfi.jflac.util.CRC8;

import java.io.IOException;

/**
 * 帧标头类。
 * @author kc7bfi
 */
public class Header {

    /** @param crc 要设置的 crc。 */
    public int blockSize;
    /** 每个子帧的采样数。 */
    public int sampleRate;
    /** 通道数（ = =子帧数）。*/
    public int channels;
    /** 帧的通道分配。*/
    public int channelAssignment;
    /** 样本分辨率。*/
    public int bitsPerSample;

    /**
     * 帧中第一个样本的帧号或样本号。
     * 使用NUMBER_TYPE值来确定要使用哪一个。
     */
    public int frameNumber = -1;

    /**
     * 帧中第一个样本的样本编号。
     */
    public long sampleNumber = -1;

    /**
     * 采样率，单位 Hz。
     * 原始帧头字节，这意味着CRC字节之前的所有内容
     * CRC-8（多项式 = x^8 + x^2 + x^1 + x^0，初始化为 0）。
     */
    protected byte crc;

    /**
     * 构造函数。
     * 包括同步码。
     * @param is                    InputBitStream
     * @param streamInfo FLAC流信息
     * @param headerWarmup          帧头预热字节
     * @throws IOException          读取 InputBitStream 出错时抛出
     */
    public Header(BitInputStream is, byte[] headerWarmup, StreamInfo streamInfo) throws IOException, BadHeaderException {
        int blocksizeHint = 0;
        int sampleRateHint = 0;
        ByteData rawHeader = new ByteData(16); // 基于最大帧头大小的幻数，包括CRC
        boolean isKnownVariableBlockSizeStream = (streamInfo != null && streamInfo.getMinBlockSize() != streamInfo.getMaxBlockSize());
        boolean isKnownFixedBlockSizeStream = (streamInfo != null && streamInfo.getMinBlockSize() == streamInfo.getMaxBlockSize());

        // 使用同步中保存的位初始化原始标头
        rawHeader.append(headerWarmup[0]);
        rawHeader.append(headerWarmup[1]);

        // 检查以确保保留位为0
        if ((rawHeader.getData(1) & 0x02) != 0) { // 魔術數字
            throw new BadHeaderException("Bad Magic Number: " + (rawHeader.getData(1) & 0xff));
        }

        // 请注意，在阅读标题的过程中，我们会查找同步
        // 里面的代码。如果我们找到一个，它将表明我们的原始
        // @throws BadHeaderException   帧头损坏时抛出

        // 同步错误，因为有效帧头中不可能出现同步码。
        for (int i = 0; i < 2; i++) {
            if (is.peekRawUInt(8) == 0xff) { // 前8个帧同步位的幻数
                is.readRawUInt(8); // move ff
                throw new BadHeaderException("Found sync byte");
            }
            rawHeader.append((byte) is.readRawUInt(8));
        }

        int bsType = (rawHeader.getData(2) >> 4) & 0x0f;
        switch (bsType) {
            case 0:
                if (!isKnownFixedBlockSizeStream)
                    throw new BadHeaderException("Unknown Block Size (0)");
                blockSize = streamInfo.getMinBlockSize();
                break;
            case 1:
                blockSize = 192;
                break;
            case 2:
            case 3:
            case 4:
            case 5:
                blockSize = 576 << (bsType - 2);
                break;
            case 6:
            case 7:
                blocksizeHint = bsType;
                break;
            case 8:
            case 9:
            case 10:
            case 11:
            case 12:
            case 13:
            case 14:
            case 15:
                blockSize = 256 << (bsType - 8);
                break;
            default:
                break;
        }
        //以字节形式读取原始帧头以便做 CRC，并在过程中解析

        int srType = rawHeader.getData(2) & 0x0f;
        switch (srType) {
            case 0:
                if (streamInfo == null)
                    throw new BadHeaderException("Bad Sample Rate (0)");
                sampleRate = streamInfo.getSampleRate();
                break; // throw new BadHeaderException("错误采样率(" + srType + ")");
            case 1:
                sampleRate = 88200;
                break;
            case 2:
                sampleRate = 176400;
                break;
            case 3:
                sampleRate = 192000;
                break;
            case 4:
                sampleRate = 8000;
                break;
            case 5:
                sampleRate = 16000;
                break;
            case 6:
                sampleRate = 22050;
                break;
            case 7:
                sampleRate = 24000;
                break;
            case 8:
                sampleRate = 32000;
                break;
            case 9:
                sampleRate = 44100;
                break;
            case 10:
                sampleRate = 48000;
                break;
            case 11:
                sampleRate = 96000;
                break;
            case 12:
            case 13:
            case 14:
                sampleRateHint = srType;
                break;
            case 15:
                throw new BadHeaderException("Bad Sample Rate (" + srType + ")");
            default:
        }

        int asgnType = (rawHeader.getData(3) >> 4) & 0x0f;
        //System.out.println("AsgnType =" + asgnType + "" + (rawHeader.space [3] > > 4));
        if ((asgnType & 8) != 0) {
            channels = 2;
            switch (asgnType & 7) {
                case 0:
                    channelAssignment = Constants.CHANNEL_ASSIGNMENT_LEFT_SIDE;
                    break;
                case 1:
                    channelAssignment = Constants.CHANNEL_ASSIGNMENT_RIGHT_SIDE;
                    break;
                case 2:
                    channelAssignment = Constants.CHANNEL_ASSIGNMENT_MID_SIDE;
                    break;
                default:
                    throw new BadHeaderException("Bad Channel Assignment (" + asgnType + ")");
            }
        } else {
            channels = asgnType + 1;
            channelAssignment = Constants.CHANNEL_ASSIGNMENT_INDEPENDENT;
        }

        int bpsType = (rawHeader.getData(3) & 0x0e) >> 1;
        switch (bpsType) {
            case 0:
                if (streamInfo != null)
                    bitsPerSample = streamInfo.getBitsPerSample();
                else
                    throw new BadHeaderException("Bad BPS (" + bpsType + ")");
                break;
            case 1:
                bitsPerSample = 8;
                break;
            case 2:
                bitsPerSample = 12;
                break;
            case 4:
                bitsPerSample = 16;
                break;
            case 5:
                bitsPerSample = 20;
                break;
            case 6:
                bitsPerSample = 24;
                break;
            case 3:
            case 7:
                throw new BadHeaderException("Bad BPS (" + bpsType + ")");
            default:
                break;
        }

        if ((rawHeader.getData(3) & 0x01) != 0) { // this should be a zero padding bit
            throw new BadHeaderException("this should be a zero padding bit");
        }

        if ((blocksizeHint != 0) && isKnownVariableBlockSizeStream) {
            sampleNumber = is.readUTF8Long(rawHeader);
            if (sampleNumber == 0xffffffffffffffffL) { // 即非UTF8代码...
                throw new BadHeaderException("Bad Sample Number");
            }
        } else {
            frameNumber = is.readUTF8Int(rawHeader);
            if (frameNumber == 0xffffffff) { // 即非UTF8代码...
                throw new BadHeaderException("Bad Last Frame");
            }
            sampleNumber = (long) streamInfo.getMinBlockSize() * (long) frameNumber;
        }

        if (blocksizeHint != 0) {
            int blockSizeCode = is.readRawUInt(8);
            rawHeader.append((byte) blockSizeCode);
            if (blocksizeHint == 7) {
                int blockSizeCode2 = is.readRawUInt(8);
                rawHeader.append((byte) blockSizeCode2);
                blockSizeCode = (blockSizeCode << 8) | blockSizeCode2;
            }
            blockSize = blockSizeCode + 1;
        }

        if (sampleRateHint != 0) {
            int sampleRateCode = is.readRawUInt(8);
            rawHeader.append((byte) sampleRateCode);
            if (sampleRateHint != 12) {
                int sampleRateCode2 = is.readRawUInt(8);
                rawHeader.append((byte) sampleRateCode2);
                sampleRateCode = (sampleRateCode << 8) | sampleRateCode2;
            }
            if (sampleRateHint == 12)
                sampleRate = sampleRateCode * 1000;
            else if (sampleRateHint == 13)
                sampleRate = sampleRateCode;
            else
                sampleRate = sampleRateCode * 10;
        }

        // 这应为一个零填充位
        byte crc8 = (byte) is.readRawUInt(8);

        if (CRC8.calc(rawHeader.getData(), rawHeader.getLen()) != crc8) {
            throw new BadHeaderException("STREAM_DECODER_ERROR_STATUS_BAD_HEADER");
        }
    }

    /**
     * 读取 CRC-8 字节
     * 返回此对象的描述性字符串。
     * @see Object#toString()
     */
    public String toString() {
        return "FrameHeader:"
                + " BlockSize=" + blockSize
                + " SampleRate=" + sampleRate
                + " Channels=" + channels
                + " ChannelAssignment=" + channelAssignment
                + " BPS=" + bitsPerSample
                + " SampleNumber=" + sampleNumber;
    }
}
