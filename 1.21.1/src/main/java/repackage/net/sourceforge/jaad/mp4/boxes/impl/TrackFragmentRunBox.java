package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * Track Fragment Box 内有零个或多个 Track Run Box。
 * 若 track fragment box 中设置了 duration-is-empty 标志，则没有
 * track run。一个 track run 记录轨道的一组连续采样。
 *
 * 若不存在 data-offset，则此段数据紧接前一段数据之后开始，
 * 若这是 track fragment 中的第一段，则从 track fragment header
 * 定义的 base-data-offset 开始。
 * 若存在 data-offset，则它相对于 track fragment header
 * 建立的 base-data-offset。
 * 
 * @author in-somnia
 */
public class TrackFragmentRunBox extends FullBox {

	private int sampleCount;
	private boolean dataOffsetPresent, firstSampleFlagsPresent;
	private long dataOffset, firstSampleFlags;
	private boolean sampleDurationPresent, sampleSizePresent, sampleFlagsPresent,
			sampleCompositionTimeOffsetPresent;
	private long[] sampleDuration, sampleSize, sampleFlags, sampleCompositionTimeOffset;

	public TrackFragmentRunBox() {
		super("Track Fragment Run Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		sampleCount = (int) in.readBytes(4);

		//可选字段
		dataOffsetPresent = ((flags&1)==1);
		if(dataOffsetPresent) dataOffset = in.readBytes(4);

		firstSampleFlagsPresent = ((flags&4)==4);
		if(firstSampleFlagsPresent) firstSampleFlags = in.readBytes(4);

		//所有字段均为可选
		sampleDurationPresent = ((flags&0x100)==0x100);
		if(sampleDurationPresent) sampleDuration = new long[sampleCount];
		sampleSizePresent = ((flags&0x200)==0x200);
		if(sampleSizePresent) sampleSize = new long[sampleCount];
		sampleFlagsPresent = ((flags&0x400)==0x400);
		if(sampleFlagsPresent) sampleFlags = new long[sampleCount];
		sampleCompositionTimeOffsetPresent = ((flags&0x800)==0x800);
		if(sampleCompositionTimeOffsetPresent) sampleCompositionTimeOffset = new long[sampleCount];

		for(int i = 0; i<sampleCount&&getLeft(in)>0; i++) {
			if(sampleDurationPresent) sampleDuration[i] = in.readBytes(4);
			if(sampleSizePresent) sampleSize[i] = in.readBytes(4);
			if(sampleFlagsPresent) sampleFlags[i] = in.readBytes(4);
			if(sampleCompositionTimeOffsetPresent) sampleCompositionTimeOffset[i] = in.readBytes(4);
		}
	}

	public int getSampleCount() {
		return sampleCount;
	}

	public boolean isDataOffsetPresent() {
		return dataOffsetPresent;
	}

	public long getDataOffset() {
		return dataOffset;
	}

	public boolean isFirstSampleFlagsPresent() {
		return firstSampleFlagsPresent;
	}

	public long getFirstSampleFlags() {
		return firstSampleFlags;
	}

	public boolean isSampleDurationPresent() {
		return sampleDurationPresent;
	}

	public long[] getSampleDuration() {
		return sampleDuration;
	}

	public boolean isSampleSizePresent() {
		return sampleSizePresent;
	}

	public long[] getSampleSize() {
		return sampleSize;
	}

	public boolean isSampleFlagsPresent() {
		return sampleFlagsPresent;
	}

	public long[] getSampleFlags() {
		return sampleFlags;
	}

	public boolean isSampleCompositionTimeOffsetPresent() {
		return sampleCompositionTimeOffsetPresent;
	}

	public long[] getSampleCompositionTimeOffset() {
		return sampleCompositionTimeOffset;
	}
}
