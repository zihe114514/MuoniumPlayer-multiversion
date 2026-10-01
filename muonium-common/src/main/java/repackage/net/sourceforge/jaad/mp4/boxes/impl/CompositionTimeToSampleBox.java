package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import java.io.IOException;

/**
 * 此 box 提供解码时间与合成时间之间的偏移。
 * 由于解码时间必须小于合成时间，偏移以无符号数表示，使得
 * CT(n) = DT(n) + CTTS(n)
 * 其中 CTTS(n) 是采样 n 的（未压缩）表项。
 *
 * 采样表的合成时间为可选项，仅当
 * 任何采样的 DT 与 CT 不同时才必须出现。
 *
 * Hint 轨道不使用此 box。
 * 
 * @author in-somnia
 */
public class CompositionTimeToSampleBox extends FullBox {

	private long[] sampleCounts, sampleOffsets;

	public CompositionTimeToSampleBox() {
		super("Time To Sample Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);
		
		final int entryCount = (int) in.readBytes(4);
		sampleCounts = new long[entryCount];
		sampleOffsets = new long[entryCount];

		for(int i = 0; i<entryCount; i++) {
			sampleCounts[i] = in.readBytes(4);
			sampleOffsets[i] = in.readBytes(4);
		}
	}

	public long[] getSampleCounts() {
		return sampleCounts;
	}

	public long[] getSampleOffsets() {
		return sampleOffsets;
	}
}
