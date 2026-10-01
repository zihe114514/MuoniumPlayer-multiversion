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

import repackage.org.kc7bfi.jflac.Constants;
import repackage.org.kc7bfi.jflac.FLACDecoder;
import repackage.org.kc7bfi.jflac.io.BitInputStream;
import repackage.org.kc7bfi.jflac.io.BitOutputStream;
import repackage.org.kc7bfi.jflac.metadata.StreamInfo;

import javax.sound.sampled.*;
import javax.sound.sampled.spi.AudioFileReader;
import java.io.*;
import java.net.URL;

/**
 * Flac 音频文件读取服务的提供者。此实现可解析
 * 来自Flac音频文件的格式信息，可以生成音频输入
 * Flac 音频文件读取服务的提供者。此实现可解析
 *
 * @author Marc Gimpel, Wimba S.A. (marc@wimba.com)
 * @version $Revision: 1.8 $
 */
public class FlacAudioFileReader extends AudioFileReader {

    private static final boolean DEBUG = false;

    private FLACDecoder decoder;
    private StreamInfo streamInfo;

    /**
     * 获取提供的文件的音频文件格式。文件必须指向
     * 来自此类型文件的流。
     *
     * 为有效的音频文件数据。
      * 应从中提取文件格式信息的 File，
     *            应从中提取文件格式信息的 File，
     * @return 描述音频文件格式的AudioFileFormat对象。
     * @exception UnsupportedAudioFileException
      * 如果 File 未指向有效的音频文件数据
     *                由系统识别。
     * @exception IOException
     *                如果发生I/O异常。
     */
    public AudioFileFormat getAudioFileFormat(File file) throws UnsupportedAudioFileException, IOException {
        try (InputStream inputStream = new FileInputStream(file)) {
            return getAudioFileFormat(inputStream, (int) file.length());
        }
    }

    /**
     * 若 File 不指向有效的音频文件数据，
     * 来自此类型文件的流。
     *
     * @param url
     *            从所提供的 URL 获取音频输入流。该 URL 必须指向
     * @return 基于指向的音频文件数据的AudioInputStream对象
     *         到URL。
     * @exception UnsupportedAudioFileException
      * 如果 File 未指向有效的音频文件数据
     *                由系统识别。
     * @exception IOException
     *                如果发生I/O异常。
     */
    public AudioFileFormat getAudioFileFormat(URL url) throws UnsupportedAudioFileException, IOException {
        try (InputStream inputStream = url.openStream()) {
            return getAudioFileFormat(inputStream);
        }
    }

    /**
     * 从提供的输入流中获取音频输入流。
     *
     * @param stream
     *            用于构造 AudioInputStream 的 URL。
     *            应从中构造 AudioInputStream 的输入流，
      * @return 基于所包含的音频文件数据的 AudioInputStream 对象，
     *         在输入流中。
     * @exception UnsupportedAudioFileException
      * 如果 File 未指向有效的音频文件数据
     *                由系统识别。
     * @exception IOException
     *                如果发生I/O异常。
     */
    public AudioFileFormat getAudioFileFormat(InputStream stream) throws UnsupportedAudioFileException, IOException {
        return getAudioFileFormat(stream, AudioSystem.NOT_SPECIFIED);
    }

    /**
     * 从给定的InputStream返回AudioFileFormat。
     *
     * @param stream
     *            用于构造 AudioInputStream 的 URL。
     *            应从中构造 AudioInputStream 的输入流，
     * @param medialength
      * @return 基于所包含的音频文件数据的 AudioInputStream 对象，
     *         在输入流中。
     * @exception UnsupportedAudioFileException
      * 如果 File 未指向有效的音频文件数据
     *                由系统识别。
     * @exception IOException
     *                如果发生I/O异常。
     */

    /**
     * 从给定的InputStream返回AudioFileFormat。实现。
     *
     * @param bitStream
     * @param baos
     * @param mediaLength
      * @return 基于所包含的音频文件数据的 AudioInputStream 对象，
     *         在输入流中。
     * @exception UnsupportedAudioFileException
      * 如果 File 未指向有效的音频文件数据
     *                由系统识别。
     * @exception IOException
     *                如果发生I/O异常。
     */
    protected AudioFileFormat getAudioFileFormat(InputStream bitStream, int mediaLength) throws UnsupportedAudioFileException {
        AudioFormat format;

        try {
            decoder = new FLACDecoder(bitStream);
            streamInfo = decoder.readStreamInfo();
            if (streamInfo == null) {
                if (DEBUG) {
                    System.out.println("FLAC file reader: no stream info found");
                }
                throw new UnsupportedAudioFileException("No StreamInfo found");
            }

            format = new FlacAudioFormat(streamInfo);
            //@return 基于所包含音频文件数据的 AudioInputStream 对象
            // 为其他提供程序重置流
            //if (bitStream.markSupported()) {
            //    bitStream.reset();
            //}
            // 只需重新抛出此异常
            //    throw e;
        } catch (IOException ioe) {
            if (DEBUG) {
                System.out.println("FLAC file reader: not a FLAC stream");
            }
            // 为其他提供程序重置流
            //if (bitStream.markSupported()) {
            //    bitStream.reset();
            //}
            throw new UnsupportedAudioFileException(ioe.getMessage());
        }
        if (DEBUG) {
            System.out.println("FLAC file reader: got stream with format " + format);
        }
        return new AudioFileFormat(FlacFileFormatType.FLAC, format, AudioSystem.NOT_SPECIFIED);
    }

    public StreamInfo getStreamInfo(InputStream bitStream) throws UnsupportedAudioFileException {
        StreamInfo info;

        try {
            decoder = new FLACDecoder(bitStream);
            streamInfo = decoder.readStreamInfo();
            if (streamInfo == null) {
                if (DEBUG) {
                    System.out.println("FLAC file reader: no stream info found");
                }
                throw new UnsupportedAudioFileException("No StreamInfo found");
            }

            info = streamInfo;
            //@return 基于所包含音频文件数据的 AudioInputStream 对象
            // 为其他提供程序重置流
            //if (bitStream.markSupported()) {
            //    bitStream.reset();
            //}
            // 只需重新抛出此异常
            //    throw e;
        } catch (IOException ioe) {
            if (DEBUG) {
                System.out.println("FLAC file reader: not a FLAC stream");
            }
            // 为其他提供程序重置流
            //if (bitStream.markSupported()) {
            //    bitStream.reset();
            //}
            throw new UnsupportedAudioFileException(ioe.getMessage());
        }
        if (DEBUG) {
            System.out.println("FLAC file reader: got stream with info " + info);
        }
        return info;
    }

    /**
      * 从给定的 File 获取音频输入流。该 File 必须指向
     * 来自此类型文件的流。
     *
     * 为有效的音频文件数据。
     *            应为其构造AudioInputStream的文件。
     * @return 基于指向的音频文件数据的AudioInputStream对象
     *         从所提供的 File 获取音频输入流。该 File 必须指向
     * @exception UnsupportedAudioFileException
      * 如果 File 未指向有效的音频文件数据
     *                由系统识别。
     * @exception IOException
     *                如果发生I/O异常。
     */
    public AudioInputStream getAudioInputStream(File file) throws UnsupportedAudioFileException, IOException {
        InputStream inputStream = new FileInputStream(file);
        try {
            return getAudioInputStream(inputStream, (int) file.length());
        } catch (UnsupportedAudioFileException | IOException e) {
            inputStream.close();
            throw e;
        }
    }

    /**
     * 若 File 不指向有效的音频文件数据，
     * 来自此类型文件的流。
     *
     * @param url
     *            从所提供的 URL 获取音频输入流。该 URL 必须指向
     * @return 基于指向的音频文件数据的AudioInputStream对象
     *         到URL。
     * @exception UnsupportedAudioFileException
      * 如果 File 未指向有效的音频文件数据
     *                由系统识别。
     * @exception IOException
     *                如果发生I/O异常。
     */
    public AudioInputStream getAudioInputStream(URL url) throws UnsupportedAudioFileException, IOException {
        InputStream inputStream = url.openStream();
        try {
            return getAudioInputStream(inputStream);
        } catch (UnsupportedAudioFileException | IOException e) {
            inputStream.close();
            throw e;
        }
    }

    /**
     * 由 File 指定的位置。
     * 必须指向有效的音频文件数据。
     *
     * @param stream
     *            用于构造 AudioInputStream 的 URL。
     *            应从中构造 AudioInputStream 的输入流，
      * @return 基于所包含的音频文件数据的 AudioInputStream 对象，
     *         在输入流中。
     * @exception UnsupportedAudioFileException
      * 如果 File 未指向有效的音频文件数据
     *                由系统识别。
     * @exception IOException
     *                如果发生I/O异常。
     */
    public AudioInputStream getAudioInputStream(InputStream stream) throws UnsupportedAudioFileException, IOException {
        return getAudioInputStream(stream, AudioSystem.NOT_SPECIFIED);
    }

    /**
     * 由 File 指定的位置。
     * 必须指向有效的音频文件数据。
     *
     * 从所提供的输入流获取音频输入流。该流
     *            用于构造 AudioInputStream 的 URL。
     *            应从中构造 AudioInputStream 的输入流，
     * @param medialength
      * @return 基于所包含的音频文件数据的 AudioInputStream 对象，
     *         在输入流中。
     * @exception UnsupportedAudioFileException
      * 如果 File 未指向有效的音频文件数据
     *                由系统识别。
     * @exception IOException
     *                如果发生I/O异常。
     */
    protected AudioInputStream getAudioInputStream(InputStream inputStream, int medialength) throws UnsupportedAudioFileException, IOException {
        AudioFileFormat audioFileFormat = getAudioFileFormat(inputStream, medialength);

        // 向后推送StreamInfo
        ByteArrayOutputStream byteOutStream = new ByteArrayOutputStream();
        BitOutputStream bitOutStream = new BitOutputStream(byteOutStream);
        bitOutStream.writeByteBlock(Constants.STREAM_SYNC_STRING, Constants.STREAM_SYNC_STRING.length);
        /** TODO 如果StreamInfo不持久怎么办？*/
        streamInfo.write(bitOutStream, false);

        // 刷新位输入流
        BitInputStream bis = decoder.getBitInputStream();
        int bytesLeft = bis.getInputBytesUnconsumed();
        byte[] b = new byte[bytesLeft];
        bis.readByteBlockAlignedNoCRC(b, bytesLeft);
        byteOutStream.write(b);

        ByteArrayInputStream byteInStream = new ByteArrayInputStream(byteOutStream.toByteArray());

        //ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        SequenceInputStream sequenceInputStream = new SequenceInputStream(byteInStream, inputStream);
        //返回新的AudioInputStream(sequenceInputStream, audioFileFormat
        //        .getFormat(), audioFileFormat.getFrameLength());
        return new AudioInputStream(sequenceInputStream, audioFileFormat.getFormat(), audioFileFormat.getFrameLength());
    }

    public AudioInputStream getAudioInputStream(AudioFileFormat audioFileFormat, InputStream inputStream) throws IOException {

        // 向后推送StreamInfo
        ByteArrayOutputStream byteOutStream = new ByteArrayOutputStream();
        BitOutputStream bitOutStream = new BitOutputStream(byteOutStream);
        bitOutStream.writeByteBlock(Constants.STREAM_SYNC_STRING, Constants.STREAM_SYNC_STRING.length);
        /** TODO 如果StreamInfo不持久怎么办？*/
        streamInfo.write(bitOutStream, false);

        // 刷新位输入流
        BitInputStream bis = decoder.getBitInputStream();
        int bytesLeft = bis.getInputBytesUnconsumed();
        byte[] b = new byte[bytesLeft];
        bis.readByteBlockAlignedNoCRC(b, bytesLeft);
        byteOutStream.write(b);

        ByteArrayInputStream byteInStream = new ByteArrayInputStream(byteOutStream.toByteArray());

        //ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        SequenceInputStream sequenceInputStream = new SequenceInputStream(byteInStream, inputStream);
        //返回新的AudioInputStream(sequenceInputStream, audioFileFormat
        //        .getFormat(), audioFileFormat.getFrameLength());
        return new AudioInputStream(sequenceInputStream, audioFileFormat.getFormat(), audioFileFormat.getFrameLength());
    }
}
