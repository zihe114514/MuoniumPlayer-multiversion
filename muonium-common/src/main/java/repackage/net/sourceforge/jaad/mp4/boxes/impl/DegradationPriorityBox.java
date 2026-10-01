package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.BoxTypes;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * 此 box 包含每个采样的降级优先级。各值
 * 存于表中，每个采样一个。由此派生的规范
 * 定义了优先级字段的确切含义和可接受范围。
 * 
 * @author in-somnia
 */
public class DegradationPriorityBox extends FullBox {

	private int[] priorities;

	public DegradationPriorityBox() {
		super("Degradation Priority Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		//从 SampleSizeBox 获取采样数
		final int sampleCount = ((SampleSizeBox) parent.getChild(BoxTypes.SAMPLE_SIZE_BOX)).getSampleCount();

		priorities = new int[sampleCount];
		for(int i = 0; i<sampleCount; i++) {
			priorities[i] = (int) in.readBytes(2);
		}
	}

	/**
	 * 该优先级为整数，指定每个采样的降级优先级。
	 * @return 优先级列表
	 */
	public int[] getPriorities() {
		return priorities;
	}
}
