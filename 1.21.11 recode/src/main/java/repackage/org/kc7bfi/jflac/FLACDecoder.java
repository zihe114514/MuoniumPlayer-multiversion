package repackage.org.kc7bfi.jflac;

/**
 * libFLAC -免费无损音频编解码器库
 * 版权所有(C) 2000,2001,2002,2003 Josh Coalson
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

import repackage.org.kc7bfi.jflac.frame.*;
import repackage.org.kc7bfi.jflac.io.BitInputStream;
import repackage.org.kc7bfi.jflac.io.RandomFileInputStream;
import repackage.org.kc7bfi.jflac.metadata.*;
import repackage.org.kc7bfi.jflac.util.ByteData;
import repackage.org.kc7bfi.jflac.util.CRC16;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Vector;

/**
 * Java FLAC解码器。
 * @author kc7bfi
 */
public class FLACDecoder {
    private static final int FRAME_FOOTER_CRC_LEN = 16; // bits
    private static final byte[] ID3V2_TAG = new byte[]{'I', 'D', '3'};

    private BitInputStream bitStream;
    private ChannelData[] channelData = new ChannelData[Constants.MAX_CHANNELS];
    private int outputCapacity;
    private int outputChannels;
    private long samplesDecoded;
    private StreamInfo streamInfo;
    private Frame frame = new Frame();
    private byte[] headerWarmup = new byte[2]; // 包含同步代码和保留位
    //private int state ；
    private int channels;
    private int channelAssignment;
    private int bitsPerSample;
    private int sampleRate; // 位
    private int blockSize; // 单位 Hz
    private InputStream inputStream;
    private int metadataLength;

    private int badFrames;
    private boolean eof = false;

    private FrameListeners frameListeners = new FrameListeners();
    private PCMProcessors pcmProcessors = new PCMProcessors();

    // 解码器状态
    //private static final int DECODER_SEARCH_FOR_METADATA = 0;
    //private static final int DECODER_READ_METADATA = 1 ；
    //单位采样数（每声道）
    //private static final int DECODER_READ_FRAME = 3;
    //private static final int DECODER_END_OF_STREAM = 4;
    //private static final int DECODER_ABORTED = 5;
    //private static final int DECODER_UNPARSEABLE_STREAM = 6;
    //private static final int STREAM_DECODER_MEMORY_ALLOCATION_ERROR = 7 ；
    //private static final int STREAM_DECODER_ALREADY_INITIALIZED = 8;
    //private static final int STREAM_DECODER_INVALID_CALLBACK = 9 ；
    //private static final int STREAM_DECODER_UNINITIALIZED = 10 ；

    /**
     * 构造函数。
     * @param inputStream 要从中读取数据的输入流
     */
    public FLACDecoder(InputStream inputStream) {
        this.inputStream = inputStream;
        this.bitStream = new BitInputStream(inputStream);
        //state = DECODER_SEARCH_FOR_METADATA;
        samplesDecoded = 0;
        //state = DECODER_SEARCH_FOR_METADATA;
    }

    /**
     * 返回已解析的StreamInfo元数据记录。
      * @return StreamInfo
     */
    public StreamInfo getStreamInfo() {
        return streamInfo;
    }

    /**
      * 返回 ChannelData 对象。
     * 返回 ChannelData 对象。
     */
    public ChannelData[] getChannelData() {
        return channelData;
    }

    /**
     * 返回输入位流。
     * @return 位流
     */
    public BitInputStream getBitInputStream() {
        return bitStream;
    }

    /**
     * 返回输入流。
     * @return 输入流
     */
    public InputStream getInputStream() {
        return inputStream;
    }

    /**
     * @return ChannelData 对象
     * @param listener 要添加的帧侦听器
     */
    public void addFrameListener(FrameListener listener) {
        frameListeners.addFrameListener(listener);
    }

    /**
     * 添加帧监听器。
     * 移除帧监听器。
     */
    public void removeFrameListener(FrameListener listener) {
        frameListeners.removeFrameListener(listener);
    }

    /**
     * 添加PCM处理器。
     * @param listener  要移除的帧监听器
     */
    public void addPCMProcessor(PCMProcessor processor) {
        pcmProcessors.addPCMProcessor(processor);
    }

    /**
     * 卸下PCM处理器。
     * @param processor 要删除的处理器监听程序
     */
    public void removePCMProcessor(PCMProcessor processor) {
        pcmProcessors.removePCMProcessor(processor);
    }

    /**
     * 返回元数据的长度，因此可以视为第一帧偏移量
     * @return
     */
    public long getMetadataLength() {
        return metadataLength;
    }

    private boolean callPCMProcessors(Frame frame) {
        ByteData bd = decodeFrame(frame, null);
        pcmProcessors.processPCM(bd);
        return !pcmProcessors.isCanceled();
    }

    /**
     * @param processor  要添加的处理器监听器
     *
     * @param frame 发送到PCM处理器的帧
     * 用帧的 PCM 数据填充给定的 ByteData 对象。
     * @return 填充的ByteData （可能是<code>空间</code>中的新实例）
     */
    public ByteData decodeFrame(Frame frame, ByteData pcmData) {
        // 所需的字节缓冲区大小
        int byteSize = frame.header.blockSize * channels * ((streamInfo.getBitsPerSample() + 7) / 2);
        if (pcmData == null || pcmData.getData().length < byteSize) {
            pcmData = new ByteData(byteSize);
        } else {
            pcmData.setLen(0);
        }
        if (streamInfo.getBitsPerSample() == 8) {
            for (int i = 0; i < frame.header.blockSize; i++) {
                for (int channel = 0; channel < channels; channel++) {
                    pcmData.append((byte) (channelData[channel].getOutput()[i] + 0x80));
                }
            }
        } else if (streamInfo.getBitsPerSample() == 16) {
            for (int i = 0; i < frame.header.blockSize; i++) {
                for (int channel = 0; channel < channels; channel++) {
                    short val = (short) (channelData[channel].getOutput()[i]);
                    pcmData.append((byte) (val & 0xff));
                    pcmData.append((byte) ((val >> 8) & 0xff));
                }
            }
        } else if (streamInfo.getBitsPerSample() == 24) {
            for (int i = 0; i < frame.header.blockSize; i++) {
                for (int channel = 0; channel < channels; channel++) {
                    int val = (channelData[channel].getOutput()[i]);
                    pcmData.append((byte) (val & 0xff));
                    pcmData.append((byte) ((val >> 8) & 0xff));
                    pcmData.append((byte) ((val >> 16) & 0xff));
                }
            }
        }
        return pcmData;
    }

    /**
     * 阅读FLAC流信息。
     * @return FLAC流信息记录
     * @throws 读取时IOException错误
     */
    public StreamInfo readStreamInfo() throws IOException {
        readStreamSync();
        Metadata metadata = readNextMetadata();
        if (!(metadata instanceof StreamInfo)) throw new IOException("StreamInfo metadata block missing");
        return (StreamInfo) metadata;
    }

    /**
     * 读取元数据块数组。
     * @param pcmData 待填充的字节数据；若需自行分配则为 null
     * @throws 读取时IOException错误
     */
    public Metadata[] readMetadata() throws IOException {
        readStreamSync();
        Vector<Metadata> metadataList = new Vector<>();
        metadataLength = 0;
        Metadata metadata;
        do {
            metadata = readNextMetadata();
            metadataList.add(metadata);
            metadataLength += metadata.getLength();
        } while (!metadata.isLast());
        return metadataList.toArray(new Metadata[0]);
    }

    /**
     * 读取元数据块数组。
     * @param streamInfo 之前读取的StreamInfo元数据块
     * @param pcmData 待填充的字节数据；若需自行分配则为 null
     * @throws 读取时IOException错误
     */
    public Metadata[] readMetadata(StreamInfo streamInfo) throws IOException {
        if (streamInfo.isLast()) return new Metadata[0];
        Vector<Metadata> metadataList = new Vector<>();
        metadataLength = 0;
        Metadata metadata;
        do {
            metadata = readNextMetadata();
            metadataList.add(metadata);
        } while (!metadata.isLast());
        return metadataList.toArray(new Metadata[0]);
    }

    /**
     * 处理单个元数据/帧。
     * @return 一个处理的TRUE
     * 读取错误时出现@throws IOException
     */
    /*
     @return 元数据块数组
     
     <g id="37705">while </g><g id="37708">True</g>:
     switch (state) {
     case DECODER_SEARCH_FOR_METADATA ：
     readStreamSync();
     break;
     case DECODER_READ_METADATA:
     readNextMetadata();//以上函数为我们设置状态
     return true;
     case DECODER_SEARCH_FOR_FRAME_SYNC ：
     frameSync();//以上函数为我们设置状态
     break;
     case DECODER_READ_FRAME :
     readFrame();
     return true;//上述函数为我们设置状态
     //break;
      case DECODER_END_OF_STREAM :
      case DECODER_ABORTED:
      return true;
      默认
      return false;
      }
      }
      }
      */

    /**
     * 处理所有元数据记录。
     * @throws 读取时IOException错误
     */
    /*
     public void processMetadata() throws IOException {
     
     <g id="37705">while </g><g id="37708">True</g>:
     switch (state) {
     case DECODER_SEARCH_FOR_METADATA ：
     readStreamSync();
     break;
     case DECODER_READ_METADATA:
     readNextMetadata();//以上函数为我们设置状态
     break;
     case DECODER_SEARCH_FOR_FRAME_SYNC ：
     case DECODER_READ_FRAME :
     case DECODER_END_OF_STREAM :
     case DECODER_ABORTED:
     默认
     報酬
     }
     }
     }
     */

    /**
     * 解码FLAC文件。
     * @throws 读取时IOException错误
     */
    public void decode() throws IOException {
        readMetadata();
        try {
            while (true) {
                //switch (state) {
                //case DECODER_SEARCH_FOR_METADATA ：
                //    readStreamSync();
                //    break;
                //case DECODER_READ_METADATA:
                //    Metadata metadata = readNextMetadata();
                //    if(metadata = = null) break;
                //    break;
                //case DECODER_SEARCH_FOR_FRAME_SYNC ：
                findFrameSync();
                //    break;
                //case DECODER_READ_FRAME :
                try {
                    readFrame();
                    frameListeners.processFrame(frame);
                    if (!callPCMProcessors(frame))
                        throw new EOFException();
                } catch (FrameDecodeException e) {
                    if (__DEBUG)
                        e.printStackTrace();
                    badFrames++;
                }
                //    break;
                //case DECODER_END_OF_STREAM :
                //case DECODER_ABORTED:
                //    報酬
                //默认
                //    throw new IOException("未知状态:" +状态);
                //}
            }
        } catch (EOFException e) {
            eof = true;
        }
    }

    /**
     * 解码数据帧。
     * @throws 读取时IOException错误
     */
    public void decodeFrames() throws IOException {
        //state = DECODER_SEARCH_FOR_FRAME_SYNC;
        try {
            while (true) {
                //switch (state) {
                //case DECODER_SEARCH_FOR_METADATA ：
                //    readStreamSync();
                //    break;
                //case DECODER_READ_METADATA:
                //    Metadata metadata = readNextMetadata();
                //    if(metadata = = null) break;
                //    break;
                //case DECODER_SEARCH_FOR_FRAME_SYNC ：
                findFrameSync();
                //    break;
                //case DECODER_READ_FRAME :
                try {
                    readFrame();
                    frameListeners.processFrame(frame);
                    callPCMProcessors(frame);
                } catch (FrameDecodeException e) {
                    badFrames++;
                }
                //    break;
                //case DECODER_END_OF_STREAM :
                //case DECODER_ABORTED:
                //    報酬
                //默认
                //    throw new IOException("未知状态:" +状态);
                //}
            }
        } catch (EOFException e) {
            eof = true;
        }
    }

    private final static boolean __SEEK_DEBUG = false;

    /** 寻找样品并提供寻找数据
     *
     * @param sampleOffset
     * @return 最佳匹配的 SeekPoint
     * @throws IOException
     */
    public SeekPoint seek(long target_sample) throws IOException {
        // 检查是否可以先使用查找表找到它
        if (!(inputStream instanceof RandomFileInputStream))
            return null;
        RandomFileInputStream rf = (RandomFileInputStream) inputStream;
        long stream_length = rf.getLength();
        int first_frame_offset = metadataLength;
        long total_samples = streamInfo.getTotalSamples();
        int min_blocksize = streamInfo.getMinBlockSize();
        int max_blocksize = streamInfo.getMaxBlockSize();
        int min_framesize = streamInfo.getMinFrameSize();
        int max_framesize = streamInfo.getMaxFrameSize();
        int channels = streamInfo.getChannels();
        int bps = streamInfo.getBitsPerSample();

        int approx_bytes_per_frame = 0;
        /* 我们只是在猜测，但我们想猜测高，而不是低。*/
        if (max_framesize > 0)
            approx_bytes_per_frame = max_framesize;
            /* 检查是否为已知的固定块大小流。 */
        else if (min_blocksize == max_blocksize && min_blocksize > 0)
            approx_bytes_per_frame = min_blocksize * channels * bps / 8 + 64;
        else
            approx_bytes_per_frame = 4608 * channels * bps / 8 + 64;
        if (__SEEK_DEBUG)
            System.err.printf("Seek in ts:%d, mib:%d, mab:%d, mif: %d, maf:%d bpf: %d%n", total_samples, min_blocksize,
                    max_blocksize, min_framesize, max_framesize, approx_bytes_per_frame);
        if (min_blocksize == 0)
            min_blocksize = max_blocksize / 2;
        if (min_framesize == 0)
            min_framesize = max_framesize / 2;
        /* 为我们将在流中搜索的位置设置上限和下限。*/

        long upper_bound;
        /* 计算UPPER_BOUND ，超出此范围，我们永远不想寻找。*/
        if (max_framesize > 0)
            /* 128（可能的 ID3V1 标签），2（索引差异） */
            upper_bound = stream_length - (max_framesize + 128 + 2);
        else
            upper_bound = stream_length - ((channels * bps * Constants.MAX_BLOCK_SIZE) / 8 + 128 + 2);

        long pos = -1;
        /* 如果没有查找表，我们需要使用元数据（如果我们
         * ）以及文件长度来估计
         * 含正确采样的帧位置。
         */
        if (total_samples > 0) {
            /* 为了获得最大精度，我们应该使用
             * (stream_length-first_frame_offset-1) ，但
             * 差异微不足道且（ stream_length-first_frame_offset ）
             * 没有下溢的可能性。
             */
            pos = first_frame_offset + ((target_sample * (stream_length - first_frame_offset)) / total_samples)
                    - approx_bytes_per_frame;
        }
        /* 如果没有查找表，并且total_samples未知，我们
         * 甚至不用费心去找出目标，我们只是使用
         * 我们当前位置。
         */
        int i = 0;
        boolean needs_seek = pos >= 0;
        long this_frame_sample = 0, last_frame_sample = 0;
        int this_block_size = 0;
        int this_jump = 0, last_jump = 0;
        long last_pos = pos;
        int sample_skip = 0;
        //保存当前文件位置
        long savedPos = rf.getPosition();
        BitInputStream savedState = bitStream;
        bitStream = new BitInputStream(rf);
        Frame savedFrame = frame;
        frame = new Frame();

        while (true) {
            /* 将位置剪切到边界，下限优先。*/
            if (pos >= upper_bound) {
                pos = upper_bound - 1;
                needs_seek = true;
            }
            if (pos < first_frame_offset) {
                pos = first_frame_offset;
                needs_seek = true;
            }

            if (needs_seek) {
                bitStream.reset();
                rf.seek(pos);
                needs_seek = false;
                i++;
            }

            /* 现在我们需要一个框架。我们的追求是可能的
             * 落在看起来完全相同的音频数据中间，
             * 来自编码器未来版本的帧标头。当
             * 这时 frame_sync() 会返回 false。
             * 但有一个很小的可能性，它恰当地
             * 在这样的“未来编解码器帧”上同步，所以为了确保，
             * 我们要先连续出现若干个“不可解析”错误，才会
             * 纾困。
             */
            boolean got_a_frame = false;
            for (int unparseable_count = 0; unparseable_count < 20; unparseable_count++) {
                try {
                    findFrameSync();
                    readFrame();
                    got_a_frame = true;
                    break;
                } catch (Exception e) {
                    if (__SEEK_DEBUG)
                        System.err.printf("iter %d (%s%n", unparseable_count, e);
                }
            }
            if (!got_a_frame) {
                restoreState(savedPos, savedState, savedFrame);
                return null;
            }

            /* 若定位陷入循环则跳出。 */
            if (i >= 30) {
                if (__SEEK_DEBUG)
                    System.err.printf("Nothing found after 30 iters ps %d%n", pos);
                //restoreState(savedPos, savedState, savedFrame);
                //return null;
                break;
            }
            this_frame_sample = frame.header.sampleNumber;
            this_block_size = frame.header.blockSize;

            if (target_sample >= this_frame_sample && target_sample < this_frame_sample + this_block_size) {
                /* 找到了含目标采样的帧。 */
                sample_skip = (int) (target_sample - this_frame_sample);
                break;
            } else if (target_sample < this_frame_sample) {
                if (this_frame_sample - target_sample <= this_block_size * 10) {
                    /* 目标在不超过 10 帧之前，
                     * 一次向后寻找一个帧。
                     */
                    if (__SEEK_DEBUG)
                        System.err.printf("Look back few frames %d to %d%n", approx_bytes_per_frame, target_sample);
                    if (this_frame_sample == last_frame_sample && pos < last_pos) {
                        /* 上次回退幅度不够，加倍。 */
                        pos -= (last_pos - pos);
                    } else {
                        last_pos = pos;
                        pos -= approx_bytes_per_frame; // 帧大小
                    }
                } else {
                    /* 目标可能超过10帧，
                     * 计算出的新搜索位置。
                     */
                    last_pos = pos;
                    long min_bytes_to_frame = ((this_frame_sample - target_sample + min_blocksize - 1) / min_blocksize)
                            * min_framesize;
                    long max_bytes_to_frame = ((this_frame_sample - target_sample + max_blocksize - 1) / max_blocksize)
                            * max_framesize;
                    long delta = this_frame_sample - last_frame_sample;
                    if (last_frame_sample > 0 && last_jump > 0 && delta != 0) {
                        this_jump = (int) (last_jump * (this_frame_sample - target_sample) / delta);
                        if (this_jump < 0)
                            this_jump = -this_jump;
                    } else if (delta == 0)
                        this_jump = last_jump + last_jump / 2;
                    else
                        this_jump = (int) ((min_bytes_to_frame + max_bytes_to_frame) / 2);

                    if (last_jump > 0 && this_jump >= last_jump)
                        this_jump = last_jump - approx_bytes_per_frame;
                    pos = rf.getPosition() - this_jump;
                    last_jump = this_jump;
                }
                if (__SEEK_DEBUG)
                    System.err.printf("Jump backward samples %d for %d to %d%n", this_frame_sample - target_sample,
                            this_jump, target_sample);
                needs_seek = true;
            } else if (target_sample > this_frame_sample) {
                last_pos = pos;
                if (target_sample - this_frame_sample <= min_blocksize * 10) {
                    if (__SEEK_DEBUG)
                        System.err.printf("Keep reading for %d%n", min_blocksize * 10);
                    /* 目标前方不超过10帧，
                     * 一次转发一个帧。
                     */

                    pos = rf.getPosition();
                    //needs_seek = true;
                    // 继续读取即可
                    /* 如果我们还没有达到目标框架和我们的位置
                     * 未变化，说明已到流末尾，
                     * 且定位目标不存在。
                     */
                } else {
                    /* 目标可能在前方超过10帧，
                     * 计算出的新搜索位置。
                     */

                    long min_bytes_to_frame = ((target_sample - this_frame_sample + max_blocksize - 1) / max_blocksize)
                            * min_framesize;
                    long max_bytes_to_frame = ((target_sample - this_frame_sample + min_blocksize - 1) / min_blocksize)
                            * max_framesize;
                    long delta = this_frame_sample - last_frame_sample;
                    //System.err.printf("del %d, thi %d, las %d lasj %d%n", delta, this_frame_sample,
                    //	last_frame_sample, last_jump);
                    if (last_frame_sample > 0 && last_jump > 0 && delta != 0) {
                        this_jump = (int) (last_jump * (this_frame_sample - target_sample) / delta);
                        if (this_jump < 0)
                            this_jump = -this_jump;
                    } else
                        this_jump = (int) ((min_bytes_to_frame + max_bytes_to_frame) / 2);

                    if (last_jump > 0 && this_jump >= last_jump)
                        this_jump = last_jump - approx_bytes_per_frame;
                    pos = rf.getPosition() + this_jump;
                    if (__SEEK_DEBUG)
                        System.err.printf("Need a jump forward samples  %d for %d to %d%n", target_sample
                                - this_frame_sample, this_jump, target_sample);
                    last_jump = this_jump;
                    needs_seek = true;
                }
            }
            last_frame_sample = this_frame_sample;
        }
        if (__SEEK_DEBUG)
            System.err.printf("Completed in %d%n", i);
        return new SeekPoint(target_sample - sample_skip, last_pos, sample_skip);
    }

    private void restoreState(long savedPos, BitInputStream savedState, Frame savedFrame) {
        if (!(inputStream instanceof RandomFileInputStream))
            return;
        try {
            ((RandomFileInputStream) inputStream).seek(savedPos);
            bitStream = savedState;
            frame = savedFrame;
        } catch (IOException e) {
            //e.printStackTrace();
        }
    }

    /**
     * 解码两个寻道点之间的数据帧。
     * @param from  起始定位点
     * @param to 终点（不含）
     * @throws 读取时IOException错误
     */
    public void decode(SeekPoint from, SeekPoint to) throws IOException {
        // 定位随机访问文件
        if (!(inputStream instanceof RandomFileInputStream))
            throw new IOException("Not a RandomFileInputStream: " + inputStream.getClass().getName());
        ((RandomFileInputStream) inputStream).seek(from.getStreamOffset());
        bitStream.reset();
        samplesDecoded = from.getSampleNumber();

        //state = DECODER_SEARCH_FOR_FRAME_SYNC;
        try {
            while (true) {
                //switch (state) {
                //case DECODER_SEARCH_FOR_METADATA ：
                //    readStreamSync();
                //    break;
                //case DECODER_READ_METADATA:
                //    Metadata metadata = readNextMetadata();
                //    if(metadata = = null) break;
                //    break;
                //case DECODER_SEARCH_FOR_FRAME_SYNC ：
                findFrameSync();
                //    break;
                //case DECODER_READ_FRAME :
                try {
                    readFrame();
                    frameListeners.processFrame(frame);
                    callPCMProcessors(frame);
                } catch (FrameDecodeException e) {
                    badFrames++;
                }
                //frameListeners.processFrame(frame);
                //callPCMProcessors(frame);
                //System.out.println(samplesDecoded +" "+ to.getSampleNumber());
                if (to != null && samplesDecoded >= to.getSampleNumber()) return;
                //    break;
                //case DECODER_END_OF_STREAM :
                //case DECODER_ABORTED:
                //    報酬
                //默认
                //    throw new IOException("未知状态:" +状态);
                //}
            }
        } catch (EOFException e) {
            eof = true;
        }
    }
    
    /*
     private boolean processUntilEndOfStream()抛出IOException {
     //boolean got_a_frame;
      
      <g id="37705">while </g><g id="37708">True</g>:
      switch (state) {
      case DECODER_SEARCH_FOR_METADATA ：
      readStreamSync();
      break;
      case DECODER_READ_METADATA:
      readNextMetadata();//以上函数为我们设置状态
      break;
      case DECODER_SEARCH_FOR_FRAME_SYNC ：
      frameSync();//以上函数为我们设置状态
      //System.exit(0);
       break;
       case DECODER_READ_FRAME :
       readFrame();
       break;
       case DECODER_END_OF_STREAM :
       case DECODER_ABORTED:
       return true;
       默认
       return false;
       }
       }
       }
       */

    /**
     * 读取下一数据帧。
     * @return 下一帧
     * 读取错误时出现@throws IOException
     */
    public Frame readNextFrame() throws IOException {
        //boolean got_a_frame;

        try {
            while (true) {
                //switch (state) {
                //案例STREAM_DECODER_SEARCH_FOR_METADATA ：
                //    findMetadata();
                //    break;
                //case STREAM_DECODER_READ_METADATA :
                //    readMetadata();/*以上函数设置我们的状态*/
                //    break;
                //case DECODER_SEARCH_FOR_FRAME_SYNC ：
                findFrameSync(); /* 上述函数会为我们设置状态 */
                //System.exit(0);
                //break;
                //case DECODER_READ_FRAME :
                try {
                    readFrame();
                    return frame;
                } catch (FrameDecodeException e) {
                    //e.printStackTrace();
                    //System.err.print("Error" + e + "at sample" + samplesDecoded + "of" +streamInfo.getTotalSamples());
                    badFrames++;
                }
                //break;
                //case DECODER_END_OF_STREAM :
                //case DECODER_ABORTED:
                //    return null;
                //默认
                //    return null;
                //}
            }
        } catch (EOFException e) {
            eof = true;
        }
        return null;
    }

    /**
     * 消耗的字节数。
     * @return 读取的字节数
     */
    //public long getBytesConsumed() {
    //    return is.getConsumedBlurbs();
    //}

    /**
     * 已读取的字节数。
     * @return 读取的字节数
     */
    public long getTotalBytesRead() {
        return bitStream.getTotalBytesRead();
    }
    
    /*
     public int getInputBytesUnconsumed() {
     return is.getInputBytesUnconsumed();
     }
     */

    private void allocateOutput(int size, int channels) {
        if (size <= outputCapacity && channels <= outputChannels) return;

        Arrays.fill(channelData, null);

        for (int i = 0; i < channels; i++) {
            channelData[i] = new ChannelData(size);
        }

        outputCapacity = size;
        outputChannels = channels;
    }

    /**
     * 读取流同步字符串。
     * @throws 读取时IOException错误
     */
    private void readStreamSync() throws IOException {
        int id = 0;
        for (int i = 0; i < 4; ) {
            int x = bitStream.readRawUInt(8);
            if (x == Constants.STREAM_SYNC_STRING[i]) {
                i++;
                id = 0;
            } else if (x == ID3V2_TAG[id]) {
                id++;
                i = 0;
                if (id == 3) {
                    skipID3v2Tag();
                    id = 0;
                }
            } else {
                throw new DecodeError("Could not find Stream Sync");
                //i = 0;
                //id = 0;
            }
        }
    }

    /**
     * 读取一条元数据记录。
     * @return 下一条元数据记录
     * 读取错误时出现@throws IOException
     */
    public Metadata readNextMetadata() throws IOException {
        Metadata metadata = null;

        boolean isLast = (bitStream.readRawUInt(Metadata.STREAM_METADATA_IS_LAST_LEN) != 0);
        int type = bitStream.readRawUInt(Metadata.STREAM_METADATA_TYPE_LEN);
        int length = bitStream.readRawUInt(Metadata.STREAM_METADATA_LENGTH_LEN);

        if (type == Metadata.METADATA_TYPE_STREAMINFO) {
            metadata = new StreamInfo(bitStream, length, isLast);
            if (((StreamInfo) metadata).getTotalSamples() > 0) {
                streamInfo = (StreamInfo) metadata;
                pcmProcessors.processStreamInfo(streamInfo);
            }
        } else if (type == Metadata.METADATA_TYPE_SEEKTABLE) {
            metadata = new SeekTable(bitStream, length, isLast);
        } else if (type == Metadata.METADATA_TYPE_APPLICATION) {
            metadata = new Application(bitStream, length, isLast);
        } else if (type == Metadata.METADATA_TYPE_PADDING) {
            metadata = new Padding(bitStream, length, isLast);
        } else if (type == Metadata.METADATA_TYPE_VORBIS_COMMENT) {
            metadata = new VorbisComment(bitStream, length, isLast);
        } else if (type == Metadata.METADATA_TYPE_CUESHEET) {
            metadata = new CueSheet(bitStream, length, isLast);
        } else if (type == Metadata.METADATA_TYPE_PICTURE) {
            metadata = new Picture(bitStream, length, isLast);
        } else {
            metadata = new Unknown(bitStream, length, isLast);
        }
        frameListeners.processMetadata(metadata);
        //if (isLast) state = DECODER_SEARCH_FOR_FRAME_SYNC;
        return metadata;
    }


    private void skipID3v2Tag() throws IOException {

        // 跳过版本和标记字节
        int verMajor = bitStream.readRawInt(8);
        int verMinor = bitStream.readRawInt(8);
        int flags = bitStream.readRawInt(8);

        // 获取要跳过的大小（字节）
        int skip = 0;
        for (int i = 0; i < 4; i++) {
            int x = bitStream.readRawUInt(8);
            skip <<= 7;
            skip |= (x & 0x7f);
        }

        // 跳过标签的其余部分
        bitStream.readByteBlockAlignedNoCRC(null, skip);
    }

    private void findFrameSync() throws IOException {
        boolean first = true;
        //int cnt = 0;

        // 如果我们知道流中的样本总数，那么如果我们读了那么多，请停止。
        // 例如，这将阻止我们浪费时间尝试在ID3V1标签上同步。
        if (streamInfo != null && (streamInfo.getTotalSamples() > 0)) {
            if (samplesDecoded >= streamInfo.getTotalSamples()) {
                //state = DECODER_END_OF_STREAM;
                return;
            }
        }

        // 确保我们的字节对齐
        if (!bitStream.isConsumedByteAligned()) {
            bitStream.readRawUInt(bitStream.bitsLeftForByteAlignment());
        }

        int x;
        try {
            while (true) {
                x = bitStream.readRawUInt(8);
                if (x == 0xff) { // 前8个帧同步位的幻数
                    headerWarmup[0] = (byte) x;
                    x = bitStream.peekRawUInt(8);

                    /* 我们必须检查是否连续读取两个0xff ；第二个可能实际上是同步代码的开始*/
                    /* 否则需检查第二个字节是否为同步码的结束 */
                    if (x >> 2 == 0x3e) { /* 最后6个同步位的幻数*/
                        headerWarmup[1] = (byte) bitStream.readRawUInt(8);
                        //state = DECODER_READ_FRAME;
                        return;
                    }
                }
                if (first) {
                    frameListeners.processError("FindSync LOST_SYNC: " + Integer.toHexString((x & 0xff)));
                    first = false;
                }
            }
        } catch (EOFException e) {
            if (!first) frameListeners.processError("FindSync LOST_SYNC: Left over data in file");
            //state = DECODER_END_OF_STREAM;
        }
    }

    /**
     * 读取下一数据帧。
     * @throws 读取时IOException错误
     * @throws FrameDecodeException帧解码错误
     */
    public void readFrame() throws IOException, FrameDecodeException {
        int channel;
        int i;
        int mid, side, left, right;
        short frameCRC; /* 我们从输入流中计算出的一个*/
        //INT( x)

        /* init the CRC*/
        frameCRC = 0;
        frameCRC = CRC16.update(headerWarmup[0], frameCRC);
        frameCRC = CRC16.update(headerWarmup[1], frameCRC);
        bitStream.resetReadCRC16(frameCRC);

        try {
            frame.header = new Header(bitStream, headerWarmup, streamInfo);
        } catch (BadHeaderException e) {
            frameListeners.processError("Found bad header: " + e);
            throw new FrameDecodeException("Bad Frame Header: " + e, e);
        }
        //if (state == DECODER_SEARCH_FOR_FRAME_SYNC) return false;
        allocateOutput(frame.header.blockSize, frame.header.channels);
        for (channel = 0; channel < frame.header.channels; channel++) {
            // 先确定子帧正确的每采样位数
            int bps = frame.header.bitsPerSample;
            switch (frame.header.channelAssignment) {
                case Constants.CHANNEL_ASSIGNMENT_INDEPENDENT:
                    /* 无需调整 */
                    break;
                case Constants.CHANNEL_ASSIGNMENT_LEFT_SIDE:
                    if (channel == 1)
                        bps++;
                    break;
                case Constants.CHANNEL_ASSIGNMENT_RIGHT_SIDE:
                    if (channel == 0)
                        bps++;
                    break;
                case Constants.CHANNEL_ASSIGNMENT_MID_SIDE:
                    if (channel == 1)
                        bps++;
                    break;
                default:
            }
            // 现在阅读它
            try {
                readSubframe(channel, bps);
            } catch (IOException e) {
                frameListeners.processError("ReadSubframe: " + e);
                throw e;
            }
        }
        readZeroPadding();

        // 从页脚读取框架CRC-16并检查
        frameCRC = bitStream.getReadCRC16();
        frame.setCRC((short) bitStream.readRawUInt(FRAME_FOOTER_CRC_LEN));
        if (frameCRC == frame.getCRC()) {
            /* 撤销任何特殊声道编码 */
            switch (frame.header.channelAssignment) {
                case Constants.CHANNEL_ASSIGNMENT_INDEPENDENT:
                    /* 什么都不做 */
                    break;
                case Constants.CHANNEL_ASSIGNMENT_LEFT_SIDE:
                    for (i = 0; i < frame.header.blockSize; i++)
                        channelData[1].getOutput()[i] = channelData[0].getOutput()[i] - channelData[1].getOutput()[i];
                    break;
                case Constants.CHANNEL_ASSIGNMENT_RIGHT_SIDE:
                    for (i = 0; i < frame.header.blockSize; i++)
                        channelData[0].getOutput()[i] += channelData[1].getOutput()[i];
                    break;
                case Constants.CHANNEL_ASSIGNMENT_MID_SIDE:
                    for (i = 0; i < frame.header.blockSize; i++) {
                        mid = channelData[0].getOutput()[i];
                        side = channelData[1].getOutput()[i];
                        mid <<= 1;
                        if ((side & 1) != 0) // 即若 'side' 为奇数……
                            mid++;
                        left = mid + side;
                        right = mid - side;
                        channelData[0].getOutput()[i] = left >> 1;
                        channelData[1].getOutput()[i] = right >> 1;
                    }
                    //System.exit(1);
                    break;
                default:
                    break;
            }
        } else {
            // 坏帧，发出错误并将输出信号清零
            frameListeners.processError("CRC Error: " + Integer.toHexString((frameCRC & 0xffff)) + " vs " + Integer.toHexString((frame.getCRC() & 0xffff)));
            for (channel = 0; channel < frame.header.channels; channel++) {
                for (int j = 0; j < frame.header.blockSize; j++)
                    channelData[channel].getOutput()[j] = 0;
            }
        }

        // 将最新值放入解码器实例的公共部分
        channels = frame.header.channels;
        channelAssignment = frame.header.channelAssignment;
        bitsPerSample = frame.header.bitsPerSample;
        sampleRate = frame.header.sampleRate;
        blockSize = frame.header.blockSize;

        //samplesDecoded = frame.header.sampleNumber + frame.header.blockSize;
        samplesDecoded += frame.header.blockSize;
        //System.out.println(samplesDecoded + "" +frame.header.sampleNumber + "" +frame.header.blockSize);

        //state = DECODER_SEARCH_FOR_FRAME_SYNC;
        //報酬
    }

    private void readSubframe(int channel, int bps) throws IOException, FrameDecodeException {
        int x;

        x = bitStream.readRawUInt(8); /* 魔術數字*/

        boolean haveWastedBits = ((x & 1) != 0);
        x &= 0xfe;

        int wastedBits = 0;
        if (haveWastedBits) {
            wastedBits = bitStream.readUnaryUnsigned() + 1;
            bps -= wastedBits;
        }

        // 这里有很多魔法数字
        if ((x & 0x80) != 0) {
            frameListeners.processError("ReadSubframe LOST_SYNC: " + Integer.toHexString(x & 0xff));
            //state = DECODER_SEARCH_FOR_FRAME_SYNC;
            throw new FrameDecodeException("ReadSubframe LOST_SYNC: " + Integer.toHexString(x & 0xff));
            //return true;
        } else if (x == 0) {
            frame.subframes[channel] = new ChannelConstant(bitStream, frame.header, channelData[channel], bps, wastedBits);
        } else if (x == 2) {
            frame.subframes[channel] = new ChannelVerbatim(bitStream, frame.header, channelData[channel], bps, wastedBits);
        } else if (x < 16) {
            //state = DECODER_UNPARSEABLE_STREAM;
            throw new FrameDecodeException("ReadSubframe Bad Subframe Type: " + Integer.toHexString(x & 0xff));
        } else if (x <= 24) {
            //FLACSubframe_Fixed subframe = read_subframe_fixed_(channel, bps, (x >> 1) & 7);
            frame.subframes[channel] = new ChannelFixed(bitStream, frame.header, channelData[channel], bps, wastedBits, (x >> 1) & 7);
        } else if (x < 64) {
            //state = DECODER_UNPARSEABLE_STREAM;
            throw new FrameDecodeException("ReadSubframe Bad Subframe Type: " + Integer.toHexString(x & 0xff));
        } else {
            frame.subframes[channel] = new ChannelLPC(bitStream, frame.header, channelData[channel], bps, wastedBits, ((x >> 1) & 31) + 1);
        }
        if (haveWastedBits) {
            int i;
            x = frame.subframes[channel].getWastedBits();
            for (i = 0; i < frame.header.blockSize; i++)
                channelData[channel].getOutput()[i] <<= x;
        }
    }

    private void readZeroPadding() throws IOException, FrameDecodeException {
        if (!bitStream.isConsumedByteAligned()) {
            int zero = bitStream.readRawUInt(bitStream.bitsLeftForByteAlignment());
            if (zero != 0) {
                frameListeners.processError("ZeroPaddingError: " + Integer.toHexString(zero));
                //state = DECODER_SEARCH_FOR_FRAME_SYNC;
                throw new FrameDecodeException("ZeroPaddingError: " + Integer.toHexString(zero));
            }
        }
    }

    /**
     * 获取已解码的采样数。
     * @return 返回已解码的采样数。
     */
    public long getSamplesDecoded() {
        return samplesDecoded;
    }

    public void setSamplesDecoded(long samples) {
        samplesDecoded = samples;
    }

    /**
     * @return 返回解码的坏帧数。
     */
    public int getBadFrames() {
        return badFrames;
    }

    /**
     * @return 到达文件末尾时返回 true。
     */
    public boolean isEOF() {
        return eof;
    }

    static final boolean __DEBUG = false;
}
