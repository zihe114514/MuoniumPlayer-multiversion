package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.BoxTypes;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * 此可选表回答关于采样依赖的三个问题：
 * <ul>
 * <li>此采样是否依赖其他采样（是否为 I 帧）？</li>
 * <li>是否没有其他采样依赖此采样？</li>
 * <li>此采样是否在同一时间点包含数据的多个（冗余）编码
 *（可能具有不同依赖）？</li>
 * </ul>
 *
 * 在缺省此表时：
 * <ul>
 * <li>同步采样表回答第一个问题；在多数视频编解码器中，
 * I 帧也是同步点</li>
 * <li>其他采样对此采样的依赖未知</li>
 * <li>是否存在冗余编码未知</li>
 * </ul>
 *
 * 在执行快进等“特技”模式时，可利用第一条信息定位可独立解码的采样。
 * 同样，在随机访问时，可能需要定位前一个同步点或随机访问恢复点，
 * 并从同步点或随机访问恢复点的预卷起点向前滚动
 * 到目标点。向前滚动时，没有其他采样依赖的采样
 * 无需检索或解码。
 * “sample is depended on”的值与冗余编码是否存在无关。
 * 但冗余编码可能与主编码具有不同依赖；若存在冗余编码，
 * “sample depends on”的值仅记录主编码。
 *
 * Sample Dependency Box 也可出现于 Track Fragment Box 中。
 * 
 * @author in-somnia
 */
public class SampleDependencyTypeBox extends FullBox {

	private int[] sampleDependsOn, sampleIsDependedOn, sampleHasRedundancy;

	public SampleDependencyTypeBox() {
		super("Sample Dependency Type Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		//从 SampleSizeBox 获取采样数
		long sampleCount = -1;
		if(parent.hasChild(BoxTypes.SAMPLE_SIZE_BOX)) sampleCount = ((SampleSizeBox) parent.getChild(BoxTypes.SAMPLE_SIZE_BOX)).getSampleCount();
		//TODO: 在 CompactSampleSizeBox 实现后取消注释
		//else if(parent.containsChild(BoxTypes.COMPACT_SAMPLE_SIZE_BOX)) sampleCount = ((CompactSampleSizeBox)parent.getChild(BoxTypes.SAMPLE_SIZE_BOX)).getSampleSize();
		sampleHasRedundancy = new int[(int) sampleCount];
		sampleIsDependedOn = new int[(int) sampleCount];
		sampleDependsOn = new int[(int) sampleCount];

		byte b;
		for(int i = 0; i<sampleCount; i++) {
			b = (byte) in.read();
			/* 2 位保留
			 * 2 位 sampleDependsOn
			 * 2 位 sampleIsDependedOn
			 * 2 位 sampleHasRedundancy
			 */
			sampleHasRedundancy[i] = b&3;
			sampleIsDependedOn[i] = (b>>2)&3;
			sampleDependsOn[i] = (b>>4)&3;
		}
	}

	/**
	 * “sample depends on”字段取以下四个值之一：
	 * 0：此采样的依赖未知
	 * 1：此采样确实依赖其他采样（非 I 帧）
	 * 2：此采样不依赖其他采样（I 帧）
	 * 3：保留
	 *
	 * @return 所有采样的“sample depends on”值列表
	 */
	public int[] getSampleDependsOn() {
		return sampleDependsOn;
	}

	/**
	 * “sample is depended on”字段取以下四个值之一：
	 * 0：其他采样对此采样的依赖未知
	 * 1：其他采样可能依赖此采样（不可丢弃）
	 * 2：没有其他采样依赖此采样（可丢弃）
	 * 3：保留
	 *
	 * @return 所有采样的“sample is depended on”值列表
	 */
	public int[] getSampleIsDependedOn() {
		return sampleIsDependedOn;
	}

	/**
	 * “sample has redundancy”字段取以下四个值之一：
	 * 0：此采样中是否存在冗余编码未知
	 * 1：此采样中存在冗余编码
	 * 2：此采样中不存在冗余编码
	 * 3：保留
	 * 
	 * @return 所有采样的“sample has redundancy”值列表
	 */
	public int[] getSampleHasRedundancy() {
		return sampleHasRedundancy;
	}
}
