package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * 提示媒体头包含与协议无关的、用于提示轨道的通用信息。
 *
 * @author in-somnia
 */
public class HintMediaHeaderBox extends FullBox {

	private long maxPDUsize, avgPDUsize, maxBitrate, avgBitrate;

	public HintMediaHeaderBox() {
		super("Hint Media Header Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		maxPDUsize = in.readBytes(2);
		avgPDUsize = in.readBytes(2);

		maxBitrate = in.readBytes(4);
		avgBitrate = in.readBytes(4);

		in.skipBytes(4); //保留
	}

	/**
	 * 最大 PDU 大小给出此提示流中最大 PDU（协议
	 * 数据单元）的字节大小。
	 */
	public long getMaxPDUsize() {
		return maxPDUsize;
	}

	/**
	 * 平均 PDU 大小给出整个放映期间 PDU 的平均大小。
	 */
	public long getAveragePDUsize() {
		return avgPDUsize;
	}

	/**
	 * 最大比特率给出任意一秒时间窗内的最大速率（位/秒）。
	 */
	public long getMaxBitrate() {
		return maxBitrate;
	}

	/**
	 * 平均比特率给出整个放映期间的平均速率（位/秒）。
	 */
	public long getAverageBitrate() {
		return avgBitrate;
	}
}
