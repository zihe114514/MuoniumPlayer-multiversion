package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * 此 box 包含一张显式时间轴映射。每个条目定义轨道时间线的一部分：
 * 通过映射媒体时间线的一部分，或指示
 * “空”时间，或定义一个“驻留（dwell）”，即媒体中的某个时间点被
 * 保持一段时间。
 *
 * 轨道（流）的起始偏移由一个初始空编辑表示。
 * 例如，要从轨道起点播放 30 秒、但在放映开始 10
 * 秒后才播放，编辑列表如下：
 *
 * [0]:
 * Segment-duration = 10 秒
 * Media-Time = -1
 * Media-Rate = 1
 *
 * [1]:
 * Segment-duration = 30 秒（可为整个轨道的长度）
 * Media-Time = 0 秒
 * Media-Rate = 1
 */
public class EditListBox extends FullBox {

	private long[] segmentDuration, mediaTime;
	private double[] mediaRate;

	public EditListBox() {
		super("Edit List Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		final int entryCount = (int) in.readBytes(4);
		final int len = (version==1) ? 8 : 4;

		segmentDuration = new long[entryCount];
		mediaTime = new long[entryCount];
		mediaRate = new double[entryCount];

		for(int i = 0; i<entryCount; i++) {
			segmentDuration[i] = in.readBytes(len);
			mediaTime[i] = in.readBytes(len);

			//int(16) mediaRate_integer;
			//int(16) media_rate_fraction = 0;
			mediaRate[i] = in.readFixedPoint(16, 16);
		}
	}

	/**
	 * 段时长为整数，以 Movie Header Box 中的时间刻度为单位
	 * 指定此编辑段的时长。
	 */
	public long[] getSegmentDuration() {
		return segmentDuration;
	}

	/**
	 * 媒体时间为整数，包含某个编辑段在媒体中的起始时间
	 *（以媒体时间刻度为单位，处于合成时间）。若此字段设为 –1，
 * 则为空编辑。轨道中最后一个编辑绝不能是空编辑。
 * Movie Header Box 中的时长与轨道时长之间的任何差异
 * 以末尾的隐式空编辑表示。
	 */
	public long[] getMediaTime() {
		return mediaTime;
	}

	/**
	 * 媒体速率指定播放对应某个编辑段的媒体的相对速率。
	 * 若此值为 0，则该编辑指定一个“驻留”：媒体时间处的媒体
	 * 按段时长呈现。否则此字段应包含值 1。
	 */
	public double[] getMediaRate() {
		return mediaRate;
	}
}
