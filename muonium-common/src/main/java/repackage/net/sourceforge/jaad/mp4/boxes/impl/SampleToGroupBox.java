package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * 此表可用于查找某个采样所属的组以及该采样组的关联描述。
 * 表以紧凑方式编码，每个条目给出具有相同采样组描述符的一段采样中
 * 第一个采样的索引。采样组描述 ID 是一个索引，
 * 指向 SampleGroupDescription box，其中包含描述各采样组特征的条目。
 *
 * 若轨道中的采样有多个采样分组，则此 box 可有多个实例。
 * SampleToGroup box 的每个实例都有一个类型代码，
 * 区分不同的采样分组。在一个轨道内，特定分组类型的此 box
 * 至多有一个实例。关联的 SampleGroupDescription 应对分组类型
 * 指示相同的值。
 */
public class SampleToGroupBox extends FullBox {

	private long groupingType;
	private long[] sampleCount, groupDescriptionIndex;

	public SampleToGroupBox() {
		super("Sample To Group Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		groupingType = in.readBytes(4);
		final int entryCount = (int) in.readBytes(4);
		sampleCount = new long[entryCount];
		groupDescriptionIndex = new long[entryCount];

		for(int i = 0; i<entryCount; i++) {
			sampleCount[i] = in.readBytes(4);
			groupDescriptionIndex[i] = in.readBytes(4);
		}
	}

	/**
	 * 分组类型为整数，标识采样分组的类型（即用于形成采样组的准则），
	 * 并将其链接到分组类型值相同的采样组描述表。
	 * 一个轨道中，特定 'grouping type' 的此 box 至多出现一次。
	 */
	public long getGroupingType() {
		return groupingType;
	}

	/**
	 * 采样数为整数，给出特定条目中具有相同采样组描述符的
	 * 连续采样数量。若此 box 中采样数之和小于总采样数，
	 * 则读取者应以一条将剩余采样关联到“无组”的条目有效地扩展它。
	 * 此 box 中的总和大于别处记录的采样数是错误的，
	 * 读取者行为将未定义。
	 */
	public long[] getSampleCount() {
		return sampleCount;
	}

	/**
	 * 组描述索引为整数，给出特定条目中描述该组内采样的
	 * 采样组条目的索引。索引范围为 1 到
	 * SampleGroupDescriptionBox 中采样组条目数，
	 * 或取 0 表示此采样不属于该类型的任何组。
	 */
	public long[] getGroupDescriptionIndex() {
		return groupDescriptionIndex;
	}
}
