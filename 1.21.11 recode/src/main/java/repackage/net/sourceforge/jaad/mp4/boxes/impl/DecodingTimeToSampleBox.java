package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import java.io.IOException;

/**
 * 此 box 包含一张紧凑版的表，用于从
 * 解码时间索引到采样号。其他表从采样号给出采样大小和指针。
 * 表中每个条目给出具有相同时间增量的连续采样数量，以及这些采样的增量。
 * 将各增量相加即可构建完整的时间到采样映射。
 * Decoding Time to Sample Box 包含解码时间增量：
 * DT(n+1) = DT(n) + STTS(n)
 * 其中 STTS(n) 是采样 n 的（未压缩）表项。
 * 采样条目按解码时间戳排序；因此各增量
 * 均为非负。
 * DT 轴以零为原点；DT(i) = SUM(j=0 到 i-1 的 delta(j))，
 * 所有增量之和给出该轨道中媒体的长度（未映射到整体时间刻度，
 * 也未考虑任何编辑列表）。
 * 若非空（非零），Edit List Box 提供初始 CT 值。
 * 
 * @author in-somnia
 */
public class DecodingTimeToSampleBox extends FullBox {

	private long[] sampleCounts, sampleDeltas;

	public DecodingTimeToSampleBox() {
		super("Time To Sample Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);
		
		final int entryCount = (int) in.readBytes(4);
		sampleCounts = new long[entryCount];
		sampleDeltas = new long[entryCount];

		for(int i = 0; i<entryCount; i++) {
			sampleCounts[i] = in.readBytes(4);
			sampleDeltas[i] = in.readBytes(4);
		}
	}

	public long[] getSampleCounts() {
		return sampleCounts;
	}

	public long[] getSampleDeltas() {
		return sampleDeltas;
	}
}
