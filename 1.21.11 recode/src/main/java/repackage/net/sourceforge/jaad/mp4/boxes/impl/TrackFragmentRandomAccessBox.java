package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

public class TrackFragmentRandomAccessBox extends FullBox {

	private long trackID;
	private int entryCount;
	private long[] times, moofOffsets, trafNumbers, trunNumbers, sampleNumbers;

	public TrackFragmentRandomAccessBox() {
		super("Track Fragment Random Access Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		trackID = in.readBytes(4);
		//26 位保留，2 位 trafSizeLen，2 位 trunSizeLen，2 位 sampleSizeLen
		final long l = in.readBytes(4);
		final int trafNumberLen = (int) ((l>>4)&0x3)+1;
		final int trunNumberLen = (int) ((l>>2)&0x3)+1;
		final int sampleNumberLen = (int) (l&0x3)+1;
		entryCount = (int) in.readBytes(4);

		final int len = (version==1) ? 8 : 4;

		for(int i = 0; i<entryCount; i++) {
			times[i] = in.readBytes(len);
			moofOffsets[i] = in.readBytes(len);
			trafNumbers[i] = in.readBytes(trafNumberLen);
			trunNumbers[i] = in.readBytes(trunNumberLen);
			sampleNumbers[i] = in.readBytes(sampleNumberLen);
		}
	}

	/**
	 * track ID 为整数，标识关联的轨道。
	 *
	 * @return track ID
	 */
	public long getTrackID() {
		return trackID;
	}

	public int getEntryCount() {
		return entryCount;
	}

	/**
	 * 时间为整数，以关联轨道 'mdhd' 中定义的单位表示
	 * 随机访问采样的放映时间。
	 *
	 * @return 所有条目的时间
	 */
	public long[] getTimes() {
		return times;
	}

	/**
	 * moof-Offset 为整数，给出某条目所用 'moof' 的偏移。
	 * 偏移是文件开头与 'moof' 开头之间的字节偏移。
	 *
	 * @return 所有条目的偏移
	 */
	public long[] getMoofOffsets() {
		return moofOffsets;
	}

	/**
	 * 含随机可访问采样的 'traf' 编号。
	 * 每个 'moof' 中编号从 1 开始（第一个 'traf' 编号为 1）。
	 *
	 * @return 所有条目的 'traf' 编号
	 */
	public long[] getTrafNumbers() {
		return trafNumbers;
	}

	/**
	 * 含随机可访问采样的 'trun' 编号。
	 * 每个 'traf' 中编号从 1 开始。
	 *
	 * @return 所有条目的 'trun' 编号
	 */
	public long[] getTrunNumbers() {
		return trunNumbers;
	}

	/**
	 * 含随机可访问采样的采样号。
	 * 每个 'trun' 中编号从 1 开始。
	 *
	 * @return 所有条目的采样号
	 */
	public long[] getSampleNumbers() {
		return sampleNumbers;
	}
}
