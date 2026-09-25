package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * Progressive download information box 辅助 ISO 文件的渐进式下载。
 * 该 box 包含若干数对（直至 box 末尾），以
 * 字节/秒为单位给出有效文件下载比特率与以毫秒为单位的建议初始
 * 播放延迟的组合。
 *
 * 可根据下载率估计下载速率，并通过数对之间的线性插值、
 * 或对首/末条目外推，得到合适初始延迟的上限估计。
 * @author in-somnia
 */
public class ProgressiveDownloadInformationBox extends FullBox {

	private Map<Long, Long> pairs;

	public ProgressiveDownloadInformationBox() {
		super("Progressive Download Information Box");
		pairs = new HashMap<Long, Long>();
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		long rate, initialDelay;
		while(getLeft(in)>0) {
			rate = in.readBytes(4);
			initialDelay = in.readBytes(4);
			pairs.put(rate, initialDelay);
		}
	}

	/**
	 * 该映射包含比特率与播放延迟的数对。
	 * @return 信息数对
	 */
	public Map<Long, Long> getInformationPairs() {
		return pairs;
	}
}
