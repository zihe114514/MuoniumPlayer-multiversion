/**
 *
 */
package repackage.org.kc7bfi.jflac.sound.spi;

import repackage.org.kc7bfi.jflac.metadata.StreamInfo;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 包装FLAC StreamInfo对象的AudioFormat实例。使用Java 5.0
 * style properties support.
 *
 * @author Florian Bomers
 */
public class FlacAudioFormat extends AudioFormat {

    /**
     * 用于最小帧大小的属性键。该值的类型为Integer和
     * 给出编码帧的最小大小（以字节为单位）。
     */
    public static final String KEY_FRAMESIZE_MIN = "framesize_min";
    /**
     * 最大帧大小的属性键。该值的类型为Integer和
     * 给出编码帧的最大大小（以字节为单位）。
     */
    public static final String KEY_FRAMESIZE_MAX = "framesize_max";
    /**
     * 最小区块大小的属性键。该值的类型为Integer和
     * 以字节为单位给出解码帧的最小大小。
     */
    public static final String KEY_BLOCKSIZE_MIN = "blocksize_min";
    /**
     * 样式属性支持。
     * 给出解码帧的最大大小（以字节为单位）。
     */
    public static final String KEY_BLOCKSIZE_MAX = "blocksize_max";

    private HashMap props;

    public FlacAudioFormat(StreamInfo streamInfo) {
        super(FlacEncoding.FLAC, streamInfo.getSampleRate(),
                streamInfo.getBitsPerSample(), streamInfo.getChannels(),
                /* 最大块大小的属性键。值为 Integer 类型， */AudioSystem.NOT_SPECIFIED,
                AudioSystem.NOT_SPECIFIED, false);
        props = new HashMap();
        props.put(KEY_FRAMESIZE_MIN, streamInfo.getMinFrameSize());
        props.put(KEY_FRAMESIZE_MAX, streamInfo.getMaxFrameSize());
        props.put(KEY_BLOCKSIZE_MIN, streamInfo.getMinBlockSize());
        props.put(KEY_BLOCKSIZE_MAX, streamInfo.getMaxBlockSize());
    }

    /**
     * streamInfo.maxFrameSize
     * 属性使用此类中定义的KEY_keys。
     */
    public Map properties() {
        Map ret;
        if (props == null) {
            ret = new HashMap(0);
        } else {
            ret = (HashMap) (props.clone());
        }
        return Collections.unmodifiableMap(ret);
    }

    /**
     * 获取属性的Java 5.0兼容方法。使用此类中定义的KEY_CONSTANTS作为键。
     */
    public Object getProperty(String key) {
        if (props == null) {
            return null;
        }
        return props.get(key);
    }
}
