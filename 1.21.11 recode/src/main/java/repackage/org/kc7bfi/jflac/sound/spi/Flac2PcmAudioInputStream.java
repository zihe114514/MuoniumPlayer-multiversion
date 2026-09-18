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

import repackage.org.kc7bfi.jflac.FLACDecoder;
import repackage.org.kc7bfi.jflac.PCMProcessor;
import repackage.org.kc7bfi.jflac.frame.Frame;
import repackage.org.kc7bfi.jflac.metadata.Metadata;
import repackage.org.kc7bfi.jflac.metadata.StreamInfo;
import repackage.org.kc7bfi.jflac.util.ByteData;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.io.InputStream;

/**
 * 读取注释
 *
 * @author Marc Gimpel, Wimba S.A. (marc@wimba.com)
 * @author Florian Bomers
 * @version $Revision: 1.6 $
 */
public class Flac2PcmAudioInputStream extends RingedAudioInputStream implements
        PCMProcessor {

    /** FLAC解码器。*/
    private FLACDecoder decoder;

    private ByteData pcmData;

    /** 将 Flac 比特流转换为 PCM 16 位/采样音频流。 */
    private StreamInfo streamInfo;

    /** StreamInfo 元数据。 */
    private Metadata[] metaData;

    /**
     * 制造商
     *
     * @param in 底层输入流。
     * @param format 此流的音频数据的目标格式。
     * 来自流的元数据
     */
    public Flac2PcmAudioInputStream(InputStream in, AudioFormat format,
                                    long length) {
        this(in, format, length, DEFAULT_BUFFER_SIZE);
    }

    /**
     * 制造商
     *
     * @param in 底层输入流。
     * @param format 此流的音频数据的目标格式。
     * 来自流的元数据
     * @param length 此流中数据的长度（采样帧数）。
     */
    public Flac2PcmAudioInputStream(InputStream in, AudioFormat format,
                                    long length, int size) {
        super(in, format, length, size);
    }

    /**
     * 每当需要更多PCM数据时从超类调用。
     */
    protected void fill() throws IOException {
        if (decoder == null) {
            initDecoder();
        }
        if (decoder.isEOF()) {
            buffer.setEOF(true);
        } else {
            Frame frame = decoder.readNextFrame();
            if (frame != null) {
                pcmData = decoder.decodeFrame(frame, pcmData);
                processPCM(pcmData);
            }
        }
    }

    /**
     * @param size 缓冲区大小。
     *
     * @exception IOException
     */
    protected void initDecoder() throws IOException {
        decoder = new FLACDecoder(in);
        decoder.addPCMProcessor(this);
        metaData = decoder.readMetadata();
    }

    /**
     * 处理StreamInfo块。
     *
     * 读取帧头后初始化 Flac 解码器。
     * @see PCMProcessor#processStreamInfo(StreamInfo)
     */
    public void processStreamInfo(StreamInfo streamInfo) {
        this.streamInfo = streamInfo;
    }

    /**
     * 处理解码的PCM字节。这从同步调用
     * 注入方法
     *
      * @param pcm 解码后的 PCM 数据
     * @see PCMProcessor#processPCM(ByteData)
     */
    public void processPCM(ByteData pcm) {
        buffer.resize(pcm.getLen() * 2);
        buffer.put(pcm.getData(), 0, pcm.getLen());
    }

    /**
     * @return streamInfo
     */
    public StreamInfo getStreamInfo() {
        return streamInfo;
    }

    /**
     * @return 元数据
     */
    public Metadata[] getMetaData() {
        return metaData;
    }

}
