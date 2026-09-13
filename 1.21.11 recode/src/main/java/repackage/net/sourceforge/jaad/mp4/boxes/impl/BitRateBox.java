package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.BoxImpl;

public class BitRateBox extends BoxImpl {

	private long decodingBufferSize, maxBitrate, avgBitrate;

	public BitRateBox() {
		super("Bitrate Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		decodingBufferSize = in.readBytes(4);
		maxBitrate = in.readBytes(4);
		avgBitrate = in.readBytes(4);
	}

	/**
	 * 以字节为单位给出该基本流的解码缓冲区大小。
	 * @return 解码缓冲区大小
	 */
	public long getDecodingBufferSize() {
		return decodingBufferSize;
	}

	/**
	 * 给出任意一秒时间窗内的最大速率（位/秒）。
	 * @return 最大比特率
	 */
	public long getMaximumBitrate() {
		return maxBitrate;
	}

	/**
	 * 给出整个放映期间的平均速率（位/秒）。
	 * @return 平均比特率
	 */
	public long getAverageBitrate() {
		return avgBitrate;
	}
}
