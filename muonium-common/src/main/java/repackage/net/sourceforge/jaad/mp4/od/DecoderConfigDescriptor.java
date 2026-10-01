package repackage.net.sourceforge.jaad.mp4.od;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;

/**
 * <code>DecoderConfigDescriptor</code> 提供解码器类型及关联
 * 原始流所需解码器资源的信息。接收端据此判断能否解码该原始流。
 * stream type 标识流的类别，而可选的 decoder specific information
 * 以流特定格式包含设置解码器所需的流特定信息，对本层不透明。
 *
 * @author in-somnia
 */
public class DecoderConfigDescriptor extends Descriptor {

	private int objectProfile, streamType, decodingBufferSize;
	private boolean upstream;
	private long maxBitRate, averageBitRate;

	void decode(MP4InputStream in) throws IOException {
		objectProfile = in.read();
		//6 位 stream type，1 位 upstream 标志，1 位保留
		final int x = in.read();
		streamType = (x>>2)&0x3F;
		upstream = ((x>>1)&1)==1;

		decodingBufferSize = (int) in.readBytes(3);
		maxBitRate = in.readBytes(4);
		averageBitRate = in.readBytes(4);

		readChildren(in);
	}

	/**
	 * 对象 profile 的指示（若 streamType==scene description stream 则为场景描述 profile），
	 * 解码器须支持该 profile 才能解码此原始流，见下表。
	 * 对 streamType==object descriptor stream，应使用值 0xFF（“未指定 profile”）。
	 * 
	 * <table>
	 * <tr><th>值</th><th>描述</th></tr>
	 * <tr>0x00<td></td><td>保留给 ISO 使用</td></tr>
	 * <tr>0x01<td></td><td>ISO 14496-1:Systems Simple Scene Description</td></tr>
	 * <tr>0x02<td></td><td>ISO 14496-1:Systems 2D Scene Description</td></tr>
	 * <tr>0x03<td></td><td>ISO 14496-1:Systems VRML Scene Description</td></tr>
	 * <tr>0x04<td></td><td>ISO 14496-1:Systems Audio Scene Description</td></tr>
	 * <tr>0x05<td></td><td>ISO 14496-1:Systems Complete Scene Description</td></tr>
	 * <tr>0x06-0x1F<td></td><td>保留给 ISO 使用</td></tr>
	 * <tr>0x20<td></td><td>ISO 14496-2:Visual Simple Profile</td></tr>
	 * <tr>0x21<td></td><td>ISO 14496-2:Visual Core Profile</td></tr>
	 * <tr>0x22<td></td><td>ISO 14496-2:Visual Main Profile</td></tr>
	 * <tr>0x23<td></td><td>ISO 14496-2:Visual Simple Scalable Profile</td></tr>
	 * <tr>0x24<td></td><td>ISO 14496-2:Visual 12 Bit</td></tr>
	 * <tr>0x25<td></td><td>ISO 14496-2:Visual Basic Anim. 2D Texture</td></tr>
	 * <tr>0x26<td></td><td>ISO 14496-2:Visual Anim. 2D Mesh</td></tr>
	 * <tr>0x27<td></td><td>ISO 14496-2:Visual Simple Face</td></tr>
	 * <tr>0x28<td></td><td>ISO 14496-2:Visual Simple Scalable Texture</td></tr>
	 * <tr>0x29<td></td><td>ISO 14496-2:Visual Core Scalable Texture</td></tr>
	 * <tr>0x2A-0x3F<td></td><td>保留给 ISO 使用</td></tr>
	 * <tr>0x40<td></td><td>ISO 14496-3:Audio AAC Main</td></tr>
	 * <tr>0x41<td></td><td>ISO 14496-3:Audio AAC LC</td></tr>
	 * <tr>0x42<td></td><td>ISO 14496-3:Audio T/F</td></tr>
	 * <tr>0x43<td></td><td>ISO 14496-3:Audio T/F Main Scalable</td></tr>
	 * <tr>0x44<td></td><td>ISO 14496-3:Audio T/F LC Scalable</td></tr>
	 * <tr>0x45<td></td><td>ISO 14496-3:Audio TwinVQ Core</td></tr>
	 * <tr>0x46<td></td><td>ISO 14496-3:Audio CELP</td></tr>
	 * <tr>0x47<td></td><td>ISO 14496-3:Audio HVXC</td></tr>
	 * <tr>0x48<td></td><td>ISO 14496-3:Audio HILN</td></tr>
	 * <tr>0x49<td></td><td>ISO 14496-3:Audio TTSI</td></tr>
	 * <tr>0x4A<td></td><td>ISO 14496-3:Audio Main Synthetic</td></tr>
	 * <tr>0x4B<td></td><td>ISO 14496-3:Audio Wavetable Synthetic</td></tr>
	 * <tr>0x4C-0x5F<td></td><td>保留给 ISO 使用</td></tr>
	 * <tr>0x60<td></td><td>ISO 13818-2:Visual Simple Profile</td></tr>
	 * <tr>0x61<td></td><td>ISO 13818-2:Visual Main Profile</td></tr>
	 * <tr>0x62<td></td><td>ISO 13818-2:Visual SNR Profile</td></tr>
	 * <tr>0x63<td></td><td>ISO 13818-2:Visual Spatial Profile</td></tr>
	 * <tr>0x64<td></td><td>ISO 13818-2:Visual High Profile</td></tr>
	 * <tr>0x65<td></td><td>ISO 13818-2:Visual 442 Profile</td></tr>
	 * <tr>0x66<td></td><td>ISO 13818-7:Audio (MPEG-2 AAC)</td></tr>
	 * <tr>0x67<td></td><td>ISO 13818-3:Audio (MPEG-2 layer 1/2/3)</td></tr>
	 * <tr>0x68<td></td><td>ISO 11172-2:Visual (MPEG-1)</td></tr>
	 * <tr>0x69<td></td><td>ISO 11172-3:Audio (MPEG-1)</td></tr>
	 * <tr>0x6A-0xBF<td></td><td>保留给 ISO 使用</td></tr>
	 * <tr>0xC0-0xFE<td></td><td>用户私有</td></tr>
	 * <tr>0xFF<td></td><td>未指定 profile</td></tr>
	 * </table>
	 *
	 * @return 对象 profile
	 */
	public int getObjectProfile() {
		return objectProfile;
	}

	/**
	 * stream type 传达此原始流的类型，见下表：
	 * <table>
	 * <tr><th>值</th><th>描述</th></tr>
	 * <tr>0x00<td></td><td>保留给 ISO 使用</td></tr>
	 * <tr>0x01<td></td><td>ObjectDescriptorStream</td></tr>
	 * <tr>0x02<td></td><td>ClockReferenceStream</td></tr>
	 * <tr>0x03<td></td><td>SceneDescriptionStream</td></tr>
	 * <tr>0x04<td></td><td>VisualStream</td></tr>
	 * <tr>0x05<td></td><td>AudioStream</td></tr>
	 * <tr>0x06<td></td><td>MPEG-7 Stream</td></tr>
	 * <tr>0x07-0x09<td></td><td>保留给 ISO 使用</td></tr>
	 * <tr>0x0A<td></td><td>ObjectContentInfoStream</td></tr>
	 * <tr>0x0B-0x1F<td></td><td>保留给 ISO 使用</td></tr>
	 * <tr>0x20-0x3F<td></td><td>用户私有</td></tr>
	 * </table>
	 *
	  * @return 流类型
	 */
	public int getStreamType() {
		return streamType;
	}

	/**
	 * 指示此流是否用于上游（upstream）信息。
	 *
	 * @return 若此流用于上游信息则返回 true
	 */
	public boolean isUpstream() {
		return upstream;
	}

	/**
	 * 此原始流解码缓冲区的大小（字节）。
	 *
	 * @return 解码缓冲区大小
	 */
	public int getDecodingBufferSize() {
		return decodingBufferSize;
	}

	/**
	 * 此原始流在任意一秒时间窗内的最大比特率。
	 *
	 * @return 最大比特率
	 */
	public long getMaxBitRate() {
		return maxBitRate;
	}

	/**
	 * 原始流的平均比特率。对可变比特率流，此值应设为零。
	 *
	 * @return 平均比特率；若流为可变比特率则返回 0
	 */
	public long getAverageBitRate() {
		return averageBitRate;
	}
}
