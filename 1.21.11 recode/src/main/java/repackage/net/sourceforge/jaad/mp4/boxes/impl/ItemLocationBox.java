package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * item location box 提供本文件或其他文件中资源的目录，
 * 通过定位其所在文件、文件内偏移和
 * 长度来实现。以二进制格式存放可使该数据得到通用处理，
 * 即使处理系统不理解所用的特定元数据系统
 *（处理器）。例如，系统可将所有外部
 * 引用的元数据资源整合到一个文件中，并相应调整文件偏移和
 * 文件引用。
 *
 * 条目可分段存放在多个扩展段（extent）中，例如以支持交织。一个
 * extent 是该资源字节的连续子集；资源由各 extent 拼接而成。
 * 若仅使用一个 extent，则偏移和长度之一或两者可缺省：
 * <ul>
 * <li>若未标识偏移（该字段长度为零），则隐含为
 * 文件开头（偏移 0）。</li>
 * <li>若未指定长度，或指定为零，则隐含为整个
 * 文件长度。引用到与本元数据同一文件、或被分为多个 extent 的条目，
 * 应给出显式偏移和长度，或使用要求对文件作不同解释的 MIME 类型，
 * 以避免无限递归。</li>
 * 
 * 条目的大小为各 extent 长度之和。
 *
 * 数据引用索引可取 0，表示引用到与本元数据同一文件，
 * 或为数据引用表中的索引。
 *
 * 某些被引用的数据本身也可能使用偏移/长度技术来寻址其内部资源
 *（例如 MP4 文件可能以此方式被“包含”）。
 * 通常此类偏移相对于所在文件的开头。
 * “base offset”字段为该所含数据内的偏移计算提供额外偏移。
 * 例如，若一个 MP4 文件被包含在按本规范格式化的文件中，
 * 则该 MP4 段内的数据偏移通常相对于文件开头；base offset
 * 叠加在这些偏移之上。
 *
 * @author in-somnia
 */
public class ItemLocationBox extends FullBox {

	private int[] itemID, dataReferenceIndex;
	private long[] baseOffset;
	private long[][] extentOffset, extentLength;

	public ItemLocationBox() {
		super("Item Location Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		/*4 位 offsetSize
		4 位 lengthSize
		4 位 baseOffsetSize
		4 位保留
			 */
		long l = in.readBytes(2);
		final int offsetSize = (int) (l>>12)&0xF;
		final int lengthSize = (int) (l>>8)&0xF;
		final int baseOffsetSize = (int) (l>>4)&0xF;

		final int itemCount = (int) in.readBytes(2);
		dataReferenceIndex = new int[itemCount];
		baseOffset = new long[itemCount];
		extentOffset = new long[itemCount][];
		extentLength = new long[itemCount][];

		int j, extentCount;
		for(int i = 0; i<itemCount; i++) {
			itemID[i] = (int) in.readBytes(2);
			dataReferenceIndex[i] = (int) in.readBytes(2);
			baseOffset[i] = in.readBytes(baseOffsetSize);

			extentCount = (int) in.readBytes(2);
			extentOffset[i] = new long[extentCount];
			extentLength[i] = new long[extentCount];

			for(j = 0; j<extentCount; j++) {
				extentOffset[i][j] = in.readBytes(offsetSize);
				extentLength[i][j] = in.readBytes(lengthSize);
			}
		}
	}

	/**
	 * item ID 是此资源的任意整数“名称”，可用于
	 * 引用它（例如在 URL 中）。
	 *
	 * @return item ID
	 */
	public int[] getItemID() {
		return itemID;
	}

	/**
	 * 数据引用索引要么为零（“本文件”），要么为数据信息 box 中
	 * 数据引用的从 1 开始的索引。
	 *
	 * @return 数据引用索引
	 */
	public int[] getDataReferenceIndex() {
		return dataReferenceIndex;
	}

	/**
	 * base offset 为引用数据内的偏移计算提供基值。
	 * 
	 * @return 所有条目的基偏移
	 */
	public long[] getBaseOffset() {
		return baseOffset;
	}

	/**
	 * extent offset 给出该条目相对于所在文件开头的
	 * 绝对字节偏移。
	 *
	 * @return 所有条目中所有 extent 的偏移
	 */
	public long[][] getExtentOffset() {
		return extentOffset;
	}

	/**
	 * extent length 给出此元数据条目的绝对字节长度。
	 * 若该值为 0，则条目的长度为整个被引用文件的长度。
	 *
	 * @return 所有条目中所有 extent 的长度
	 */
	public long[][] getExtentLength() {
		return extentLength;
	}
}
