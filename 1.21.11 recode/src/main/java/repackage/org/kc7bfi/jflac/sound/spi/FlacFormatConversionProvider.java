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
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.spi.FormatConversionProvider;

/**
 * 格式转换提供商提供格式转换服务，从一个或
 * 将更多输入格式转换为一种或多种输出格式。转换器包括编解码器，
 * 对音频数据进行编码和/或解码，以及转码器等。格式
 * 转换器提供了确定支持哪些转换的方法，以及
 * 用于获取可以从中读取转换数据的音频流。
 * 源格式表示传入音频数据的格式，它将
 * 转换。目标格式表示已处理的格式，
 * 转换后的音频数据。这是可以从中读取的数据的格式
 * 其中一个getAudioInputStream方法返回的流。
 *
 * @author Marc Gimpel, Wimba S.A. (marc@wimba.com)
 * @author Florian Bomers
 * 此 Flac 文件格式的文件扩展名。
 */
public class FlacFormatConversionProvider extends FormatConversionProvider {

    /** 调试标志*/
    private static final boolean DEBUG = false;

    /** to disable encoding */
    private static final boolean HAS_ENCODING = false;

    /** */
    // private static final AudioFormat.Encoding [] NO_ENCODING = {};
    /** */
    // private static final AudioFormat.Encoding [] PCM_ENCODING = {
    // 以禁用编码
    /** */
    // private static final AudioFormat.Encoding [] FLAC_ENCODING = {
    // FlacEncoding.FLAC};
    /** */
    // private static final AudioFormat.Encoding[] BOTH_ENCODINGS = {
    // FlacEncoding.FLAC,
    // AudioFormat.Encoding.PCM_SIGNED
    // };
    /** */
    // private static final AudioFormat[] NO_FORMAT = {};

    /**
     * 获取从中进行格式转换的源格式编码集
     * 服务由该提供商提供。
     *
     * 源格式编码的@return 数组。数组将始终具有
     *         length of at least 1.
     */
    public AudioFormat.Encoding[] getSourceEncodings() {
        AudioFormat.Encoding[] encodings;
        if (HAS_ENCODING) {
            encodings = new AudioFormat.Encoding[]{
                    FlacEncoding.FLAC, AudioFormat.Encoding.PCM_SIGNED
            };
        } else {
            encodings = new AudioFormat.Encoding[]{
                    FlacEncoding.FLAC
            };
        }
        return encodings;
    }

    /**
     * Obtains the set of target format encodings to which format conversion
     * 服务由该提供商提供。
     *
     * @return 目标格式编码数组。数组将始终具有
     *         length of at least 1.
     */
    public AudioFormat.Encoding[] getTargetEncodings() {
        AudioFormat.Encoding[] encodings;
        if (HAS_ENCODING) {
            encodings = new AudioFormat.Encoding[]{
                    FlacEncoding.FLAC, AudioFormat.Encoding.PCM_SIGNED
            };
        } else {
            encodings = new AudioFormat.Encoding[]{
                    FlacEncoding.PCM_SIGNED
            };
        }
        return encodings;
    }

    private boolean isBitSizeOK(AudioFormat format, boolean notSpecifiedOK) {
        int bitSize = format.getSampleSizeInBits();
        return (notSpecifiedOK && (bitSize == AudioSystem.NOT_SPECIFIED))
                || (bitSize == 8) || (bitSize == 16) || (bitSize == 24);
    }

    private boolean isChannelsOK(AudioFormat format, boolean notSpecifiedOK) {
        int channels = format.getChannels();
        return (notSpecifiedOK && (channels == AudioSystem.NOT_SPECIFIED))
                || (channels == 1) || (channels == 2);
    }

    /**
     * 长度至少为 1。
     * 给定特定源格式的转换器。如果没有目标格式编码
     * 此源格式支持，则返回长度为0的数组。
     *
     * 传入数据的@param sourceFormat 格式。
     * @return 支持的目标格式编码数组。
     */
    public AudioFormat.Encoding[] getTargetEncodings(AudioFormat sourceFormat) {
        boolean bitSizeOK = isBitSizeOK(sourceFormat, true);
        boolean channelsOK = isChannelsOK(sourceFormat, true);
        if (HAS_ENCODING
                && bitSizeOK
                && channelsOK
                && !sourceFormat.isBigEndian()
                && sourceFormat.getEncoding().equals(
                AudioFormat.Encoding.PCM_SIGNED)) {
            // 編碼器
            if (DEBUG) {
                System.out.println("FLAC converter: can encode to PCM: " + sourceFormat);
            }
            return new AudioFormat.Encoding[]{
                    FlacEncoding.FLAC
            };
        } else if (bitSizeOK && channelsOK
                && sourceFormat.getEncoding().equals(FlacEncoding.FLAC)) {
            // 解码方式
            if (DEBUG) {
                System.out.println("FLAC converter: can decode to FLAC: " + sourceFormat);
            }
            return new AudioFormat.Encoding[]{
                    AudioFormat.Encoding.PCM_SIGNED
            };
        } else {
            if (DEBUG) {
                System.out.println("FLAC converter: cannot de/encode: " + sourceFormat);
            }
            return new AudioFormat.Encoding[]{};
        }
    }

    /**
     * 获取支持指定编码的目标格式集
     * 获取可进行格式转换的目标格式编码集合，
     * 此源格式支持，则返回长度为0的数组。
     *
     * @param targetEncoding 所需的传出数据编码。
     * 传入数据的@param sourceFormat 格式。
     * @return 支持的目标格式数组。
     */
    public AudioFormat[] getTargetFormats(AudioFormat.Encoding targetEncoding,
                                          AudioFormat sourceFormat) {
        return getTargetFormats(targetEncoding, sourceFormat, true);
    }

    /**
     * 获取该格式所支持的目标格式编码集合，
     * 如果AudioSystem.NOT_SPECIFIED在
     * sourceFormat的bitSize或channels。
     *
     * @param notSpecifiedOK 如果为true ， bitSize和channels可以有值
     *            AudioSystem.NOT_SPECIFIED
     * @return 支持的目标格式数组。
     */
    private AudioFormat[] getTargetFormats(AudioFormat.Encoding targetEncoding,
                                           AudioFormat sourceFormat, boolean notSpecifiedOK) {
        boolean bitSizeOK = isBitSizeOK(sourceFormat, notSpecifiedOK);
        boolean channelsOK = isChannelsOK(sourceFormat, notSpecifiedOK);
        if (HAS_ENCODING
                && bitSizeOK
                && channelsOK
                && !sourceFormat.isBigEndian()
                && sourceFormat.getEncoding().equals(
                AudioFormat.Encoding.PCM_SIGNED)
                && targetEncoding.equals(FlacEncoding.FLAC)) {
            // 编码为FLAC
            if (DEBUG) {
                System.out.println("FLAC converter: can encode: " + sourceFormat + " to " + targetEncoding);
            }
            // 小字节序
            return new AudioFormat[]{ //
                    new AudioFormat(FlacEncoding.FLAC,
                            sourceFormat.getSampleRate(), //
                            -1, // 样本大小（位）
                            sourceFormat.getChannels(), //
                            -1, // 由格式转换器提供。若无指定编码的目标格式
                            -1, // 帧速率
                            false)
            };

        } else if (bitSizeOK && channelsOK
                && sourceFormat.getEncoding().equals(FlacEncoding.FLAC)
                && targetEncoding.equals(AudioFormat.Encoding.PCM_SIGNED)) {
            // 解码为PCM
            if (DEBUG) {
                System.out.println("FLAC converter: can decode: " + sourceFormat + " to " + targetEncoding);
            }
            // little endian （用于PCM wav ）
            return new AudioFormat[]{
                    new AudioFormat(sourceFormat.getSampleRate(), //
                            sourceFormat.getSampleSizeInBits(), // 类似 getTargetFormats(AudioFormat.Encoding, AudioFormat)，但
                            // bits
                            sourceFormat.getChannels(), //
                            true, // 已签收
                            false)
            };
        } else {
            if (DEBUG) {
                System.out.println("FLAC converter: cannot de/encode: " + sourceFormat + " to " + targetEncoding);
            }
            return new AudioFormat[]{};
        }
    }

    /**
     * 帧大小
     * 采样大小，单位
     *
     * 从给定的音频输入流获取具有指定编码的音频输入流。
     * @param sourceStream -应从中处理数据的流
     *            read.
     * @param targetEncoding - 处理后期望的流编码。
     *         读取。
     */
    public AudioInputStream getAudioInputStream(
            AudioFormat.Encoding targetEncoding, AudioInputStream sourceStream) {
        AudioFormat[] formats = getTargetFormats(targetEncoding,
                sourceStream.getFormat(), false);
        if (formats.length > 0) {
            return getAudioInputStream(formats[0], sourceStream);
        } else {
            throw new IllegalArgumentException("conversion not supported");
        }
    }

    /**
     * 从给定的获取具有指定格式的音频输入流
     * 采样大小，单位
     *
     * @return 可从中读取具有指定目标编码的已处理数据的流。
     * @param sourceStream -应从中处理数据的流
     *            read.
     * @return 流，指定格式的处理数据可以从中
     *         read.
     */
    public AudioInputStream getAudioInputStream(AudioFormat targetFormat,
                                                AudioInputStream sourceStream) {
        AudioFormat sourceFormat = sourceStream.getFormat();
        AudioFormat[] formats = getTargetFormats(targetFormat.getEncoding(),
                sourceFormat, false);
        if (formats.length > 0) {
            if (sourceFormat.equals(targetFormat)) {
                return sourceStream;
            } else if (sourceFormat.getChannels() == targetFormat.getChannels()
                    && sourceFormat.getSampleSizeInBits() == targetFormat.getSampleSizeInBits()
                    && !targetFormat.isBigEndian()
                    && sourceFormat.getEncoding().equals(FlacEncoding.FLAC)
                    && targetFormat.getEncoding().equals(
                    AudioFormat.Encoding.PCM_SIGNED)) {
                // 解码方式
                return new Flac2PcmAudioInputStream(sourceStream, targetFormat,
                        -1);
            } else if (sourceFormat.getChannels() == targetFormat.getChannels()
                    && sourceFormat.getSampleSizeInBits() == targetFormat.getSampleSizeInBits()
                    && sourceFormat.getEncoding().equals(
                    AudioFormat.Encoding.PCM_SIGNED)
                    && targetFormat.getEncoding().equals(FlacEncoding.FLAC)) {
                // 編碼器

                // return new Pcm2FlacAudioInputStream(sourceStream,
                // targetFormat, -1);
                throw new IllegalArgumentException(
                        "FLAC encoder not yet implemented");
            } else {
                throw new IllegalArgumentException("unable to convert "
                        + sourceFormat + " to "
                        + targetFormat);
            }
        } else {
            throw new IllegalArgumentException("conversion not supported");
        }
    }
}
