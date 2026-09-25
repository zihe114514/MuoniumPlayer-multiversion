package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * 每个 movie fragment 可向每个轨道添加零个或多个片段；
 * 一个 track fragment 可添加零个或多个连续采样段。
 * track fragment header 为这些采样段设置信息和默认值。
 * 
 * @author in-somnia
 */
public class TrackFragmentHeaderBox extends FullBox {

	private long trackID;
	private boolean baseDataOffsetPresent, sampleDescriptionIndexPresent,
			defaultSampleDurationPresent, defaultSampleSizePresent,
			defaultSampleFlagsPresent;
	private boolean durationIsEmpty;
	private long baseDataOffset, sampleDescriptionIndex, defaultSampleDuration,
			defaultSampleSize, defaultSampleFlags;

	public TrackFragmentHeaderBox() {
		super("Track Fragment Header Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		trackID = in.readBytes(4);

		//可选字段
		baseDataOffsetPresent = ((flags&1)==1);
		baseDataOffset = baseDataOffsetPresent ? in.readBytes(8) : 0;

		sampleDescriptionIndexPresent = ((flags&2)==2);
		sampleDescriptionIndex = sampleDescriptionIndexPresent ? in.readBytes(4) : 0;

		defaultSampleDurationPresent = ((flags&8)==8);
		defaultSampleDuration = defaultSampleDurationPresent ? in.readBytes(4) : 0;

		defaultSampleSizePresent = ((flags&16)==16);
		defaultSampleSize = defaultSampleSizePresent ? in.readBytes(4) : 0;

		defaultSampleFlagsPresent = ((flags&32)==32);
		defaultSampleFlags = defaultSampleFlagsPresent ? in.readBytes(4) : 0;

		durationIsEmpty = ((flags&0x10000)==0x10000);
	}

	public long getTrackID() {
		return trackID;
	}

	public boolean isBaseDataOffsetPresent() {
		return baseDataOffsetPresent;
	}

	public long getBaseDataOffset() {
		return baseDataOffset;
	}

	public boolean isSampleDescriptionIndexPresent() {
		return sampleDescriptionIndexPresent;
	}

	public long getSampleDescriptionIndex() {
		return sampleDescriptionIndex;
	}

	public boolean isDefaultSampleDurationPresent() {
		return defaultSampleDurationPresent;
	}

	public long getDefaultSampleDuration() {
		return defaultSampleDuration;
	}

	public boolean isDefaultSampleSizePresent() {
		return defaultSampleSizePresent;
	}

	public long getDefaultSampleSize() {
		return defaultSampleSize;
	}

	public boolean isDefaultSampleFlagsPresent() {
		return defaultSampleFlagsPresent;
	}

	public long getDefaultSampleFlags() {
		return defaultSampleFlags;
	}

	public boolean isDurationIsEmpty() {
		return durationIsEmpty;
	}
}
