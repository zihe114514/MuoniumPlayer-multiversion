package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * 此 box 设置 movie fragment 所用的默认值。
 * 通过以这种方式设置默认值，可在每个 Track Fragment Box 中
 * 节省空间和复杂度。
 *
 * @author in-somnia
 */
public class TrackExtendsBox extends FullBox {

	private long trackID;
	private long defaultSampleDescriptionIndex, defaultSampleDuration, defaultSampleSize;
	private long defaultSampleFlags;

	public TrackExtendsBox() {
		super("Track Extends Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		trackID = in.readBytes(4);
		defaultSampleDescriptionIndex = in.readBytes(4);
		defaultSampleDuration = in.readBytes(4);
		defaultSampleSize = in.readBytes(4);
		/* 6 位保留
		 * 2 位 sampleDependsOn
		 * 2 位 sampleIsDependedOn
		 * 2 位 sampleHasRedundancy
		 * 3 位 samplePaddingValue
		 * 1 位 sampleIsDifferenceSample
		 * 16 位 sampleDegradationPriority
		 */
		defaultSampleFlags = in.readBytes(4);
	}

	/**
	 * track ID 标识该轨道；它应为 Movie Box 中某轨道的轨道 ID。
	 *
	  * @return 轨道 ID
	 */
	public long getTrackID() {
		return trackID;
	}

	/**
	 * 轨道 fragment 中使用的默认采样描述索引。
	 *
	 * @return 默认采样描述索引
	 */
	public long getDefaultSampleDescriptionIndex() {
		return defaultSampleDescriptionIndex;
	}

	/**
	 * 轨道 fragment 中使用的默认采样时长。
	 *
	 * @return 默认采样时长
	 */
	public long getDefaultSampleDuration() {
		return defaultSampleDuration;
	}

	/**
	 * 轨道 fragment 中使用的默认采样大小。
	 *
	 * @return 默认采样大小
	 */
	public long getDefaultSampleSize() {
		return defaultSampleSize;
	}

	/**
	 * SampleDependencyTypeBox 中定义的默认 “sample depends on” 值。
	 *
	 * @see SampleDependencyTypeBox#getSampleDependsOn()
	 * @return 默认 “sample depends on” 值
	 */
	public int getSampleDependsOn() {
		return (int) ((defaultSampleFlags>>24)&3);
	}

	/**
	 * SampleDependencyTypeBox 中定义的默认 “sample is depended on” 值。
	 *
	 * @see SampleDependencyTypeBox#getSampleIsDependedOn()
	 * @return 默认 “sample is depended on” 值
	 */
	public int getSampleIsDependedOn() {
		return (int) ((defaultSampleFlags>>22)&3);
	}

	/**
	 * SampleDependencyBox 中定义的默认 “sample has redundancy” 值。
	 *
	 * @see SampleDependencyTypeBox#getSampleHasRedundancy()
	 * @return 默认 “sample has redundancy” 值
	 */
	public int getSampleHasRedundancy() {
		return (int) ((defaultSampleFlags>>20)&3);
	}

	/**
	 * PaddingBitBox 中定义的默认填充值。
	 *
	 * @see PaddingBitBox#getPad1()
	 * @return 默认填充值
	 */
	public int getSamplePaddingValue() {
		return (int) ((defaultSampleFlags>>17)&7);
	}

	public boolean isSampleDifferenceSample() {
		return ((defaultSampleFlags>>16)&1)==1;
	}

	/**
	 * 采样的默认降级优先级。
	 * @return 默认降级优先级
	 */
	public int getSampleDegradationPriority() {
		return (int) (defaultSampleFlags&0xFFFF);
	}
}
