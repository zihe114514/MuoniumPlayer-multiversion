package repackage.net.sourceforge.jaad.mp4.od;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;

/**
 * <code>DecoderSpecificInfo</code> 构成一个不透明容器，
 * 含特定媒体解码器的信息。根据所需数据量，
 * 提供两个分别最多 255 和 2<sup>32</sup>-1 字节数据的类。
 * <code>DecoderSpecificInfo</code> 的存在和语义取决于父
 * <code>DecoderConfigDescriptor</code> 的流类型和对象 profile。
 *
 * @author in-somnia
 */
public class DecoderSpecificInfo extends Descriptor {

	private byte[] data;

	@Override
	void decode(MP4InputStream in) throws IOException {
		data = new byte[size];
		in.readBytes(data);
	}

	/**
	 * 含解码器特定信息的字节数组。
	 *
	 * @return 解码器特定信息
	 */
	public byte[] getData() {
		return data;
	}
}
