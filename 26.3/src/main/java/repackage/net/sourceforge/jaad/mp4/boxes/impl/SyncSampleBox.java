package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * 此表对流内的随机访问点提供紧凑标记。
 * 表按采样号严格递增排列。
 *
 * 若不存在同步采样 box，则每个采样都是随机访问点。
 *
 * @author in-somnia
 */
public class SyncSampleBox extends FullBox {

	private long[] sampleNumbers;

	public SyncSampleBox() {
		super("Sync Sample Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		final int entryCount = (int) in.readBytes(4);
		sampleNumbers = new long[entryCount];
		for(int i = 0; i<entryCount; i++) {
			sampleNumbers[i] = in.readBytes(4);
		}
	}

	/**
	 * 给出流中作为随机访问点的每个条目的采样号。
	 * 
	 * @return 采样号列表
	 */
	public long[] getSampleNumbers() {
		return sampleNumbers;
	}
}
