/*
 * 本程序为自由软件；你可以在自由软件基金会发布的
 * 1999年12月12日原始版本。mdm@techie.com。
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

package repackage.javazoom.jl.converter;

import repackage.javazoom.jl.decoder.*;

import java.io.*;
import java.nio.file.Files;


/**
 * <code> Converter </code>类实现了
 * 方法，传入输入和输出文件名。你还可以
 * 只需创建此类的实例并调用convert()
 * <code>Decoder.Params</code> 对象来自定义转换。
 * 传入可选的<code> ProgressListener </code>和
 * 创建一个新的转换器实例。
 *
 * @author MDM 12/12/99
 * @since 0.0.7
 */
public class Converter {

    /**
     * 解码器应提供已解码的
     */
    public Converter() {
    }

    public synchronized void convert(String sourceName, String destName) throws JavaLayerException {
        convert(sourceName, destName, null, null);
    }

    public synchronized void convert(String sourceName,
                                     String destName,
                                     ProgressListener progressListener) throws JavaLayerException {
        convert(sourceName, destName, progressListener, null);
    }

    public void convert(String sourceName,
                        String destName,
                        ProgressListener progressListener,
                        Decoder.Params decoderParams) throws JavaLayerException {
        if (destName.isEmpty())
            destName = null;
        try {
            InputStream in = openInput(sourceName);
            convert(in, destName, progressListener, decoderParams);
            in.close();
        } catch (IOException ioe) {
            throw new JavaLayerException(ioe.getLocalizedMessage(), ioe);
        }
    }

    public synchronized void convert(InputStream sourceStream,
                                     String destName,
                                     ProgressListener progressListener,
                                     Decoder.Params decoderParams) throws JavaLayerException {
        if (progressListener == null)
            progressListener = PrintWriterProgressListener.newStdOut(PrintWriterProgressListener.NO_DETAIL);
        try {
            if (!(sourceStream instanceof BufferedInputStream))
                sourceStream = new BufferedInputStream(sourceStream);
            int frameCount = -1;
            if (sourceStream.markSupported()) {
                sourceStream.mark(-1);
                frameCount = countFrames(sourceStream);
                sourceStream.reset();
            }
            progressListener.converterUpdate(ProgressListener.UPDATE_FRAME_COUNT, frameCount, 0);

            Obuffer output = null;
            Decoder decoder = new Decoder(decoderParams);
            Bitstream stream = new Bitstream(sourceStream);

            if (frameCount == -1)
                frameCount = Integer.MAX_VALUE;

            int frame = 0;
            long startTime = System.currentTimeMillis();

            try {
                for (; frame < frameCount; frame++) {
                    try {
                        Header header = stream.readFrame();
                        if (header == null)
                            break;

                        progressListener.readFrame(frame, header);

                        if (output == null) {
                            // 审核：功能不正确。
                            // 对解码器的判断有点靠不住。尽管
                            // 频率和信道输出，因为它可能与
                            // 音源（例如，将立体声降混成单声道时。）
                            int channels = (header.mode() == Header.SINGLE_CHANNEL) ? 1 : 2;
                            int freq = header.frequency();
                            output = new WaveFileObuffer(channels, freq, destName);
                            decoder.setOutputBuffer(output);
                        }

                        Obuffer decoderOutput = decoder.decodeFrame(header, stream);

                        // 回顾：输出缓冲区的设置方式
                        // 这个异常本不该发生，我们仍加以校验以确保。
                        // 此接口供 Converter 用来提供
                        if (decoderOutput != output)
                            throw new InternalError("Output buffers are different.");

                        progressListener.decodedFrame(frame, header, output);

                        stream.closeFrame();

                    } catch (Exception ex) {
                        boolean stop = !progressListener.converterException(ex);

                        if (stop) {
                            throw new JavaLayerException(ex.getLocalizedMessage(), ex);
                        }
                    }
                }

            } finally {

                if (output != null)
                    output.close();
            }

            int time = (int) (System.currentTimeMillis() - startTime);
            progressListener.converterUpdate(ProgressListener.UPDATE_CONVERT_COMPLETE, time, frame);
        } catch (IOException ex) {
            throw new JavaLayerException(ex.getLocalizedMessage(), ex);
        }
    }

    public synchronized byte[] convert(InputStream sourceStream,
                                     ProgressListener progressListener,
                                     Decoder.Params decoderParams) throws JavaLayerException {
        if (progressListener == null)
            progressListener = PrintWriterProgressListener.newStdOut(PrintWriterProgressListener.NO_DETAIL);
        try {
            if (!(sourceStream instanceof BufferedInputStream))
                sourceStream = new BufferedInputStream(sourceStream);
            int frameCount = -1;
            if (sourceStream.markSupported()) {
                sourceStream.mark(-1);
                frameCount = countFrames(sourceStream);
                sourceStream.reset();
            }
            progressListener.converterUpdate(ProgressListener.UPDATE_FRAME_COUNT, frameCount, 0);

            WaveStreamObuffer output = null;
            Decoder decoder = new Decoder(decoderParams);
            Bitstream stream = new Bitstream(sourceStream);

            if (frameCount == -1)
                frameCount = Integer.MAX_VALUE;

            int frame = 0;
            long startTime = System.currentTimeMillis();

            try {
                for (; frame < frameCount; frame++) {
                    try {
                        Header header = stream.readFrame();
                        if (header == null)
                            break;

                        progressListener.readFrame(frame, header);

                        if (output == null) {
                            // 审核：功能不正确。
                            // 对解码器的判断有点靠不住。尽管
                            // 频率和信道输出，因为它可能与
                            // 音源（例如，将立体声降混成单声道时。）
                            int channels = (header.mode() == Header.SINGLE_CHANNEL) ? 1 : 2;
                            int freq = header.frequency();
                            output = new WaveStreamObuffer(channels, freq);
                            decoder.setOutputBuffer(output);
                        }

                        Obuffer decoderOutput = decoder.decodeFrame(header, stream);

                        // 回顾：输出缓冲区的设置方式
                        // 这个异常本不该发生，我们仍加以校验以确保。
                        // 此接口供 Converter 用来提供
                        if (decoderOutput != output)
                            throw new InternalError("Output buffers are different.");

                        progressListener.decodedFrame(frame, header, output);

                        stream.closeFrame();

                    } catch (Exception ex) {
                        boolean stop = !progressListener.converterException(ex);

                        if (stop) {
                            throw new JavaLayerException(ex.getLocalizedMessage(), ex);
                        }
                    }
                }

            } finally {

                if (output != null)
                    output.close();
            }

            int time = (int) (System.currentTimeMillis() - startTime);
            progressListener.converterUpdate(ProgressListener.UPDATE_CONVERT_COMPLETE, time, frame);

            return output.getOutWave().getStream().toByteArray();
        } catch (IOException ex) {
            throw new JavaLayerException(ex.getLocalizedMessage(), ex);
        }
    }

    protected int countFrames(InputStream in) {
        return -1;
    }

    protected InputStream openInput(String fileName) throws IOException {
        // 确保名称是抽象路径名称
        File file = new File(fileName);
        InputStream fileIn = Files.newInputStream(file.toPath());

        return new BufferedInputStream(fileIn);
    }

    /**
     * 转换任务执行情况的通知，
     * 并在新信息可用时提供它。
     * 转换完成。Param1 包含
     */
    public interface ProgressListener {
        int UPDATE_FRAME_COUNT = 1;

        /**
         * 已转换 MPEG 音频帧的时间。
         * 以毫秒为单位进行转换。Param2包含数字
         * 通知监听器有新信息可用。
         */
        int UPDATE_CONVERT_COMPLETE = 2;

        /**
         * <code>updateID</code> 参数可取以下值：
         *
         * @param updateID 代码，表示已
         *                 已更新。
         * @param param1 参数的值取决于更新代码。
         * @param param2 参数的值取决于更新代码。
         *                 <p>
         *                 UPDATE_CONVERT_COMPLETE：param1 为转换时间，
         *                 <p>
         *                 UPDATE_FRAME_COUNT: param1是帧数，如果
         *                 未知
         *                 若转换器希望对
         *                 param2
         *                 是转换的帧数。
         */
        void converterUpdate(int updateID, int param1, int param2);

        /**
         * 每读完一帧后调用此方法，
         * 音频帧，这在解析每个帧时称为。
         */
        void parsedFrame(int frameNo, Header header);

        /**
         * 每解码完一帧后调用此方法。
         * 但在解码之前。
         *
         * @param frameNo 基于0的帧序列号。
         * @param header 重新输入刚刚读取的帧的Header。
         */
        void readFrame(int frameNo, Header header);

        /**
         * @param t 该 <code>Throwable</code> 实例
         *
         * @param frameNo 基于0的帧序列号。
         * @param header 重新输入刚刚读取的帧的Header。
         * @param o 写入去除数据的Obuffer。
         */
        void decodedFrame(int frameNo, Header header, Obuffer o);

        /**
         * 转换期间抛出异常时调用
         * 一帧。
         *
         * @return <code>true</code> 继续处理，或 false
         *          被抛出。
         * 以中止转换。
         * 调试细节。显示所有帧读取通知。
         * <p>
         * 如果此方法返回<code> FALSE </code> ，则异常
         * 被传播到convert()方法的调用者。如果
         * <code> true返回</code> ，异常为无提示
         * 忽略，转换器移到下一帧。
         */
        boolean converterException(Throwable t);
    }

    /**
     * <code> ProgressListener </code>的实现
     * zZQ0ZZQ PrintWriter </code>的通知文本。
     * <p>
     * 审核：需要I18N的文本和顺序。
     */
    static public class PrintWriterProgressListener implements ProgressListener {
        static public final int NO_DETAIL = 0;

        /**
         * 专家通常期望的细节水平
         * users
         */
        static public final int EXPERT_DETAIL = 1;

        /**
         * 详细的细节。
         */
        static public final int VERBOSE_DETAIL = 2;

        /**
         * 捕获除零错误。
         */
        static public final int DEBUG_DETAIL = 7;

        static public final int MAX_DETAIL = 10;

        private PrintWriter pw;

        private int detailLevel;

        static public PrintWriterProgressListener newStdOut(int detail) {
            return new PrintWriterProgressListener(new PrintWriter(System.out, true), detail);
        }

        public PrintWriterProgressListener(PrintWriter writer, int detailLevel) {
            this.pw = writer;
            this.detailLevel = detailLevel;
        }

        public boolean isDetail(int detail) {
            return (this.detailLevel >= detail);
        }

        @Override
        public void converterUpdate(int updateID, int param1, int param2) {
            if (isDetail(VERBOSE_DETAIL)) {
                if (updateID == UPDATE_CONVERT_COMPLETE) {// catch divide by zero errors.
                    if (param2 == 0)
                        param2 = 1;

                    pw.println();
                    pw.println("Converted " + param2 + " frames in " + param1 + " ms (" + (param1 / param2)
                            + " ms per frame.)");
                }
            }
        }

        @Override
        public void parsedFrame(int frameNo, Header header) {
            if ((frameNo == 0) && isDetail(VERBOSE_DETAIL)) {
                String headerString = header.toString();
                pw.println("File is a " + headerString);
            } else if (isDetail(MAX_DETAIL)) {
                String headerString = header.toString();
                pw.println("Prased frame " + frameNo + ": " + headerString);
            }
        }

        @Override
        public void readFrame(int frameNo, Header header) {
            if ((frameNo == 0) && isDetail(VERBOSE_DETAIL)) {
                String headerString = header.toString();
                pw.println("File is a " + headerString);
            } else if (isDetail(MAX_DETAIL)) {
                String headerString = header.toString();
                pw.println("Read frame " + frameNo + ": " + headerString);
            }
        }

        @Override
        public void decodedFrame(int frameNo, Header header, Obuffer o) {
            if (isDetail(MAX_DETAIL)) {
                String headerString = header.toString();
                pw.println("Decoded frame " + frameNo + ": " + headerString);
                pw.println("Output: " + o);
            } else if (isDetail(VERBOSE_DETAIL)) {
                if (frameNo == 0) {
                    pw.print("Converting.");
                    pw.flush();
                }

                if ((frameNo % 10) == 0) {
                    pw.print('.');
                    pw.flush();
                }
            }
        }

        @Override
        public boolean converterException(Throwable t) {
            if (this.detailLevel > NO_DETAIL) {
                t.printStackTrace(pw);
                pw.flush();
            }
            return false;
        }
    }
}
