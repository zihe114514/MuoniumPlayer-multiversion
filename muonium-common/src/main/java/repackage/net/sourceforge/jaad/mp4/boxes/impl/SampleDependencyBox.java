package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.BoxTypes;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * 此 box 包含每个切换采样的采样依赖关系。
 * 依赖关系存于表中，每个采样一条记录。表大小取自
 * Sample Size Box（'stsz'）或 Compact Sample Size Box
 *（'stz2'）。
 *
 * @author in-somnia
 */
public class SampleDependencyBox extends FullBox {

	private int[] dependencyCount;
	private int[][] relativeSampleNumber;

	public SampleDependencyBox() {
		super("Sample Dependency Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		final int sampleCount = ((SampleSizeBox) parent.getChild(BoxTypes.SAMPLE_SIZE_BOX)).getSampleCount();

		int j;
		for(int i = 0; i<sampleCount; i++) {
			dependencyCount[i] = (int) in.readBytes(2);
			for(j = 0; j<dependencyCount[i]; j++) {
				relativeSampleNumber[i][j] = (int) in.readBytes(2);
			}
		}
	}

	/**
	 * 依赖计数为整数，统计此切换采样直接依赖的
	 * 源轨道中的采样数。
	 *
	 * @return 所有依赖计数
	 */
	public int[] getDependencyCount() {
		return dependencyCount;
	}

	/**
	 * 相对采样号为整数，标识源轨道中的一个采样。
	 * 相对采样号编码如下。
	 * 若源轨道中有解码时间相同的采样，
	 * 其相对采样号为 0。无论该采样是否存在，
	 * 紧邻切换采样解码时间之前的源轨道采样
	 * 相对采样号为 –1，再前一个为 –2，依此类推。
	 * 同样，紧邻切换采样解码时间之后的源轨道采样
	 * 相对采样号为 +1，再后一个为 +2，依此类推。
	 *
	 * @return 所有相对采样号
	 */
	public int[][] getRelativeSampleNumber() {
		return relativeSampleNumber;
	}
}
