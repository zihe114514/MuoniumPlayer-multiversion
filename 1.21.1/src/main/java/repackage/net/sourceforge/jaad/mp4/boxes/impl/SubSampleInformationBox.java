package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * Sub-Sample Information box 用于容纳子采样信息。
 * 子采样是一个采样的连续字节区间。子采样的具体定义应由
 * 给定编码系统给出（例如对 ISO/IEC 14496-10 高级视频编码）。
 * 若没有这种具体定义，则此 box 不应应用于使用该编码系统的采样。
 * 表以稀疏方式编码；表通过记录每个条目之间采样号的差值，
 * 标识哪些采样具有子采样结构。表中第一个条目记录
 * 具有子采样信息的第一个采样的采样号。
 *
 * @author in-somnia
 */
public class SubSampleInformationBox extends FullBox {

	private long[] sampleDelta;
	private long[][] subsampleSize;
	private int[][] subsamplePriority;
	private boolean[][] discardable;

	public SubSampleInformationBox() {
		super("Sub Sample Information Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		final int len = (version==1) ? 4 : 2;
		final int entryCount = (int) in.readBytes(4);
		sampleDelta = new long[entryCount];
		subsampleSize = new long[entryCount][];
		subsamplePriority = new int[entryCount][];
		discardable = new boolean[entryCount][];

		int j, subsampleCount;
		for(int i = 0; i<entryCount; i++) {
			sampleDelta[i] = in.readBytes(4);
			subsampleCount = (int) in.readBytes(2);
			subsampleSize[i] = new long[subsampleCount];
			subsamplePriority[i] = new int[subsampleCount];
			discardable[i] = new boolean[subsampleCount];

			for(j = 0; j<subsampleCount; j++) {
				subsampleSize[i][j] = in.readBytes(len);
				subsamplePriority[i][j] = in.read();
				discardable[i][j] = (in.read()&1)==1;
				in.skipBytes(4); //保留
			}
		}
	}

	/**
	 * 每个条目的采样增量为整数，指定具有子采样结构的
	 * 采样的采样号。它编码为目标采样号与前一条目
	 * 所指示采样号之差。若当前条目是第一个条目，
	 * 该值指示具有子采样信息的第一个采样的采样号，
	 * 即该值为采样号与零之差。
	 *
	 * @return 所有条目的采样增量
	 */
	public long[] getSampleDelta() {
		return sampleDelta;
	}

	/**
	 * 子采样大小为整数，指定特定条目中某个子采样的大小（字节）。
	 *
	 * @return 所有子采样的大小
	 */
	public long[][] getSubsampleSize() {
		return subsampleSize;
	}

	/**
	 * 子采样优先级为整数，指定特定条目中某个子采样的降级优先级。
	 * 值越大表示该子采样对解码质量越重要、影响越大。
	 *
	 * @return 所有子采样优先级
	 */
	public int[][] getSubsamplePriority() {
		return subsamplePriority;
	}

	/**
	 * 若为 true，解码当前采样必须用到该子采样；
	 * false 表示解码当前采样不需要该子采样，
	 * 但可用于增强，例如该子采样由补充增强信息（SEI）消息组成。
	 *
	 * @return 标志列表，指示特定子采样是否可丢弃
	 */
	public boolean[][] getDiscardable() {
		return discardable;
	}
}
