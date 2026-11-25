/* Native recipe QA only; Java 17. Not part of the mod's playback implementation. */
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.ffmpeg.avcodec.*;
import org.bytedeco.ffmpeg.avformat.*;
import org.bytedeco.ffmpeg.avutil.*;
import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swresample.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

public final class RuntimeProbe {
    public static void main(String[] args) {
        System.out.println("version\t" + av_version_info().getString());
        System.out.println("swresample-version\t" + swresample_version());
        System.out.println("swscale-version\t" + swscale_version());
        System.out.println("configuration\t" + avformat_configuration().getString());
        try (PointerPointer<Pointer> state = new PointerPointer<>(1)) {
            state.put(0, (Pointer) null);
            for (;;) {
                AVCodec codec = av_codec_iterate(state);
                if (codec == null || codec.isNull()) break;
                // The only non-audio codecs are the two software cover-video decoders.
                boolean coverVideo = codec.type() == AVMEDIA_TYPE_VIDEO
                        && java.util.Set.of("h264", "hevc").contains(codec.name().getString());
                if ((codec.type() != AVMEDIA_TYPE_AUDIO && !coverVideo) || av_codec_is_decoder(codec) == 0) {
                    throw new IllegalStateException("Unexpected codec: " + codec.name().getString());
                }
                System.out.println("decoder\t" + codec.name().getString());
            }
            state.put(0, (Pointer) null);
            for (;;) {
                AVInputFormat input = av_demuxer_iterate(state);
                if (input == null || input.isNull()) break;
                System.out.println("demuxer\t" + input.name().getString());
            }
            state.put(0, (Pointer) null);
            for (;;) {
                BytePointer protocol = avio_enum_protocols(state, 0);
                if (protocol == null || protocol.isNull()) break;
                System.out.println("protocol\t" + protocol.getString());
            }
        }
        // The Python driver bounds process duration. It is not a production interrupt callback.
        if (args.length > 0) decode(args[0], args.length > 1 ? args[1] : null);
    }

    private static void checked(int result, String operation) {
        if (result < 0) {
            try (BytePointer error = new BytePointer(256)) {
                av_strerror(result, error, error.capacity());
                throw new IllegalStateException(operation + ": " + error.getString());
            }
        }
    }

    private static void decode(String source, String caFile) {
        AVFormatContext input = new AVFormatContext((Pointer) null);
        AVCodecContext decoder = null;
        AVPacket packet = null;
        AVFrame frame = null;
        AVDictionary options = new AVDictionary((Pointer) null);
        long samples = 0;
        try {
            checked(av_dict_set(options, "protocol_whitelist", "file,subfile,http,https,httpproxy,tcp,tls,crypto", 0), "protocol whitelist");
            checked(av_dict_set(options, "rw_timeout", "5000000", 0), "read timeout");
            checked(av_dict_set(options, "tls_verify", "1", 0), "TLS verify");
            if (caFile != null) checked(av_dict_set(options, "ca_file", caFile, 0), "CA file");
            checked(avformat_open_input(input, source, null, options), "open input");
            checked(avformat_find_stream_info(input, (PointerPointer<?>) null), "stream info");
            int selected = -1;
            for (int i = 0; i < input.nb_streams(); i++) {
                if (input.streams(i).codecpar().codec_type() == AVMEDIA_TYPE_AUDIO) { selected = i; break; }
            }
            if (selected < 0) throw new IllegalStateException("No audio stream");
            AVCodec codec = avcodec_find_decoder(input.streams(selected).codecpar().codec_id());
            if (codec == null || codec.isNull()) throw new IllegalStateException("Missing audio decoder");
            decoder = avcodec_alloc_context3(codec);
            if (decoder == null || decoder.isNull()) throw new OutOfMemoryError("decoder");
            checked(avcodec_parameters_to_context(decoder, input.streams(selected).codecpar()), "parameters");
            checked(avcodec_open2(decoder, codec, (PointerPointer<?>) null), "decoder open");
            packet = av_packet_alloc();
            frame = av_frame_alloc();
            if (packet == null || packet.isNull() || frame == null || frame.isNull()) throw new OutOfMemoryError("packet/frame");
            int read;
            while ((read = av_read_frame(input, packet)) >= 0) {
                if (packet.stream_index() == selected) {
                    checked(avcodec_send_packet(decoder, packet), "send packet");
                    samples += drain(decoder, frame);
                }
                av_packet_unref(packet);
            }
            if (read != AVERROR_EOF) checked(read, "demux EOF");
            checked(avcodec_send_packet(decoder, null), "flush");
            samples += drain(decoder, frame);
            if (samples <= 0) throw new IllegalStateException("No decoded audio samples");
            System.out.println("decoded-samples\t" + samples);
        } finally {
            if (frame != null) av_frame_free(frame);
            if (packet != null) av_packet_free(packet);
            if (decoder != null) avcodec_free_context(decoder);
            avformat_close_input(input);
            av_dict_free(options);
        }
    }

    private static long drain(AVCodecContext decoder, AVFrame frame) {
        long count = 0;
        // For fixed clean fixtures this checks that decoding produces frames and terminates.
        // Detailed EAGAIN/error/cancel semantics belong to production decoder tests.
        while (avcodec_receive_frame(decoder, frame) >= 0) {
            count += frame.nb_samples();
            av_frame_unref(frame);
        }
        return count;
    }
}
