package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * shadow sync 表提供一组可选的同步采样，可在跳转（seek）或类似
 * 场合使用。正常正向播放时忽略它们。
 *
 * ShadowSyncTable 中每个条目由一对采样号组成。
 * 第一个条目（shadowed-sample-number）指示要为其定义影子同步的采样号。
 * 它应始终是非同步采样（例如一帧差分）。第二个采样号
 *（sync-sample-number）指示同步采样（即关键帧）的采样号，
 * 当在 shadowed-sample-number 处或之前进行随机访问时可使用它。
 *
 * ShadowSyncBox 中的条目应按 shadowed-sample-number 字段排序。
 * 影子同步采样通常置于轨道中正常播放时不呈现的区域
 *（通过编辑列表裁掉），但这并非强制要求。
 * 影子同步表可被忽略，忽略后轨道仍能正确播放（和跳转）
 *（尽管可能不是最优）。
 *
 * ShadowSyncSample 替换、而非增补它所影子化的采样
 *（即发送的下一个采样为 shadowed-sample-number+1）。影子同步采样
 * 被视为发生在其影子化采样的时刻，具有该采样的时长。
 *
 * 若影子采样也用作正常播放的一部分，或被多次用作影子，
 * 提示与传输可能变得更复杂。这种情况下提示轨道可能需要单独的影子同步，
 * 它们都可从媒体轨道中的同一个影子同步获取媒体数据，
 * 以满足其头部所需的不同时间戳等。
 *
 * @author in-somnia
 */
public class ShadowSyncSampleBox extends FullBox {

	private long[][] sampleNumbers;

	public ShadowSyncSampleBox() {
		super("Shadow Sync Sample Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);
		
		final int entryCount = (int) in.readBytes(4);
		sampleNumbers = new long[entryCount][2];

		for(int i = 0; i<entryCount; i++) {
			sampleNumbers[i][0] = in.readBytes(4); //shadowedSampleNumber;
			sampleNumbers[i][1] = in.readBytes(4); //syncSampleNumber;
		}
	}

	/**
	 * 采样号对的映射：
	 * 0（shadowed-sample-number）：给出存在替代同步采样的采样号。
	 * 1（sync-sample-number）：给出替代同步采样的采样号。
	 *
	 * @return 采样号对
	 */
	public long[][] getSampleNumbers() {
		return sampleNumbers;
	}
}
