package repackage.net.sourceforge.jaad.mp4.api;

import java.io.EOFException;
import java.util.logging.Logger;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.Box;
import repackage.net.sourceforge.jaad.mp4.boxes.BoxTypes;
import repackage.net.sourceforge.jaad.mp4.boxes.impl.ChunkOffsetBox;
import repackage.net.sourceforge.jaad.mp4.boxes.impl.DataEntryUrlBox;
import repackage.net.sourceforge.jaad.mp4.boxes.impl.DataReferenceBox;
import repackage.net.sourceforge.jaad.mp4.boxes.impl.MediaHeaderBox;
import repackage.net.sourceforge.jaad.mp4.boxes.impl.SampleSizeBox;
import repackage.net.sourceforge.jaad.mp4.boxes.impl.SampleToChunkBox;
import repackage.net.sourceforge.jaad.mp4.boxes.impl.DecodingTimeToSampleBox;
import repackage.net.sourceforge.jaad.mp4.boxes.impl.TrackHeaderBox;
import repackage.net.sourceforge.jaad.mp4.od.DecoderSpecificInfo;
import repackage.net.sourceforge.jaad.mp4.boxes.impl.ESDBox;
import repackage.net.sourceforge.jaad.mp4.boxes.impl.sampleentries.SampleEntry;
import repackage.net.sourceforge.jaad.mp4.od.Descriptor;

/**
 * 此类表示电影中的一个轨道。
 *
 * 每个轨道要么包含一个字节数组形式的解码器特定信息，要么包含一个
 * <code>DecoderInfo</code> 对象，后者包含解码器所需的
 * 必要信息。
 *
 * @author in-somnia
 */
//TODO: 扩充 javadoc；子类是否使用泛型？
public abstract class Track {

	public interface Codec {
		//TODO: 目前仅是标记接口
	}
	private final MP4InputStream in;
	protected final TrackHeaderBox tkhd;
	private final MediaHeaderBox mdhd;
	private final boolean inFile;
	private final List<Frame> frames;
	private URL location;
	private int currentFrame;
	//信息结构
	protected DecoderSpecificInfo decoderSpecificInfo;
	protected DecoderInfo decoderInfo;
	protected Protection protection;

	Track(Box trak, MP4InputStream in) {
		this.in = in;

		tkhd = (TrackHeaderBox) trak.getChild(BoxTypes.TRACK_HEADER_BOX);

		final Box mdia = trak.getChild(BoxTypes.MEDIA_BOX);
		mdhd = (MediaHeaderBox) mdia.getChild(BoxTypes.MEDIA_HEADER_BOX);
		final Box minf = mdia.getChild(BoxTypes.MEDIA_INFORMATION_BOX);

		final Box dinf = minf.getChild(BoxTypes.DATA_INFORMATION_BOX);
		final DataReferenceBox dref = (DataReferenceBox) dinf.getChild(BoxTypes.DATA_REFERENCE_BOX);
		//TODO: 支持 URN
		if(dref.hasChild(BoxTypes.DATA_ENTRY_URL_BOX)) {
			DataEntryUrlBox url = (DataEntryUrlBox) dref.getChild(BoxTypes.DATA_ENTRY_URL_BOX);
			inFile = url.isInFile();
			if(!inFile) {
				try {
					location = new URL(url.getLocation());
				}
				catch(MalformedURLException e) {
					Logger.getLogger("MP4 API").log(Level.WARNING, "Parsing URL-Box failed: {0}, url: {1}", new String[]{e.toString(), url.getLocation()});
					location = null;
				}
			}
		}
		/*else if(dref.containsChild(BoxTypes.DATA_ENTRY_URN_BOX)) {
		DataEntryUrnBox urn = (DataEntryUrnBox) dref.getChild(BoxTypes.DATA_ENTRY_URN_BOX);
		inFile = urn.isInFile();
		location = urn.getLocation();
		}*/
		else {
			inFile = true;
			location = null;
		}

		//采样表
		final Box stbl = minf.getChild(BoxTypes.SAMPLE_TABLE_BOX);
		if(stbl.hasChildren()) {
			frames = new ArrayList<Frame>();
			parseSampleTable(stbl);
		}
		else frames = Collections.emptyList();
		currentFrame = 0;
	}

	private void parseSampleTable(Box stbl) {
		final double timeScale = mdhd.getTimeScale();
		final Type type = getType();

		//采样大小
		final long[] sampleSizes = ((SampleSizeBox) stbl.getChild(BoxTypes.SAMPLE_SIZE_BOX)).getSampleSizes();

		//块偏移
		final ChunkOffsetBox stco;
		if(stbl.hasChild(BoxTypes.CHUNK_OFFSET_BOX)) stco = (ChunkOffsetBox) stbl.getChild(BoxTypes.CHUNK_OFFSET_BOX);
		else stco = (ChunkOffsetBox) stbl.getChild(BoxTypes.CHUNK_LARGE_OFFSET_BOX);
		final long[] chunkOffsets = stco.getChunks();

		//采样到块
		final SampleToChunkBox stsc = ((SampleToChunkBox) stbl.getChild(BoxTypes.SAMPLE_TO_CHUNK_BOX));
		final long[] firstChunks = stsc.getFirstChunks();
		final long[] samplesPerChunk = stsc.getSamplesPerChunk();

		//采样时长/时间戳
		final DecodingTimeToSampleBox stts = (DecodingTimeToSampleBox) stbl.getChild(BoxTypes.DECODING_TIME_TO_SAMPLE_BOX);
		final long[] sampleCounts = stts.getSampleCounts();
		final long[] sampleDeltas = stts.getSampleDeltas();
		final long[] timeOffsets = new long[sampleSizes.length];
		long tmp = 0;
		int off = 0;
		for(int i = 0; i<sampleCounts.length; i++) {
			for(int j = 0; j<sampleCounts[i]; j++) {
				timeOffsets[off+j] = tmp;
				tmp += sampleDeltas[i];
			}
			off += sampleCounts[i];
		}

		//创建采样
		int current = 0;
		int lastChunk;
		double timeStamp;
		long offset = 0;
		//遍历所有块组
		for(int i = 0; i<firstChunks.length; i++) {
			if(i<firstChunks.length-1) lastChunk = (int) firstChunks[i+1]-1;
			else lastChunk = chunkOffsets.length;

			//遍历当前组中的所有块
			for(int j = (int) firstChunks[i]-1; j<lastChunk; j++) {
				offset = chunkOffsets[j];

				//遍历当前块中的所有采样
				for(int k = 0; k<samplesPerChunk[i]; k++) {
					//创建采样
					timeStamp = ((double) timeOffsets[current])/timeScale;
					frames.add(new Frame(type, offset, sampleSizes[current], timeStamp));
					offset += sampleSizes[current];
					current++;
				}
			}
		}

		//帧无需按时间排序：按时间戳排序
		//TODO: 是否可以将它们添加到指定位置？
		Collections.sort(frames);
	}

	//TODO: 实现其他条目描述符
	protected void findDecoderSpecificInfo(ESDBox esds) {
		final Descriptor ed = esds.getEntryDescriptor();
		final List<Descriptor> children = ed.getChildren();
		List<Descriptor> children2;

		for(Descriptor e : children) {
			children2 = e.getChildren();
			for(Descriptor e2 : children2) {
				switch(e2.getType()) {
					case Descriptor.TYPE_DECODER_SPECIFIC_INFO:
						decoderSpecificInfo = (DecoderSpecificInfo) e2;
						break;
				}
			}
		}
	}

	protected <T> void parseSampleEntry(Box sampleEntry, Class<T> clazz) {
		T type;
		try {
			type = clazz.newInstance();
			if(sampleEntry.getClass().isInstance(type)) {
				System.out.println("true");
			}
		}
		catch(InstantiationException ex) {
			ex.printStackTrace();
		}
		catch(IllegalAccessException ex) {
			ex.printStackTrace();
		}
	}

	public abstract Type getType();

	public abstract Codec getCodec();

	//tkhd
	/**
	 * 如果轨道已启用则返回 true。已禁用的轨道被视为
	 * 不存在。
	 * @return 如果轨道已启用则返回 true
	 */
	public boolean isEnabled() {
		return tkhd.isTrackEnabled();
	}

	/**
	 * 如果轨道在放映中被使用则返回 true。
	 * @return 如果轨道被使用则返回 true
	 */
	public boolean isUsed() {
		return tkhd.isTrackInMovie();
	}

	/**
	 * 如果轨道在预览中被使用则返回 true。
	 * @return 如果轨道在预览中被使用则返回 true
	 */
	public boolean isUsedForPreview() {
		return tkhd.isTrackInPreview();
	}

	/**
	 * 返回此轨道的创建时间。
	 * @return 创建时间
	 */
	public Date getCreationTime() {
		return Utils.getDate(tkhd.getCreationTime());
	}

	/**
	 * 返回此轨道的最后修改时间。
	 * @return 修改时间
	 */
	public Date getModificationTime() {
		return Utils.getDate(tkhd.getModificationTime());
	}

	//mdhd
	/**
	 * 返回此媒体的语言。
	 * @return 语言
	 */
	public Locale getLanguage() {
		return new Locale(mdhd.getLanguage());
	}

	/**
	 * 如果此轨道的数据存在于本文件（流）中则返回 true。
	 * 否则 <code>getLocation()</code> 返回数据所在的 URL。
	 * @return 如果数据在本文件（流）中则返回 true，否则返回 false
	 */
	public boolean isInFile() {
		return inFile;
	}

	/**
	 * 如果此轨道的数据不存在于本文件中（即
	 * <code>isInFile</code> 返回 false），此方法返回数据的
	 * 位置。否则返回 null。
	 * @return 数据的位置；若数据在本文件中则返回 null
	 */
	public URL getLocation() {
		return location;
	}

	//信息结构
	/**
	 * 返回解码器特定信息（若存在）。它包含解码器的配置
	 * 数据。如果不存在解码器特定信息，则该
	 * 轨道包含一个 <code>DecoderInfo</code>。
	 *
	 * @see #getDecoderInfo() 
	 * @return 解码器特定信息
	 */
	public byte[] getDecoderSpecificInfo() {
		return decoderSpecificInfo.getData();
	}

	/**
	 * 返回 <code>DecoderInfo</code>（若存在）。它包含解码器的
	 * 配置信息。如果不存在该结构，则该
	 * 轨道包含解码器特定信息。
	 *
	 * @see #getDecoderSpecificInfo()
	 * @return 编解码器特定结构
	 */
	public DecoderInfo getDecoderInfo() {
		return decoderInfo;
	}

	/**
	 * 返回 <code>ProtectionInformation</code> 对象，其中包含
	 * 所用 DRM 系统的详细信息。如果不存在保护，此
	 * 方法返回 null。
	 * 
	 * @return 一个 <code>ProtectionInformation</code> 对象；若未使用
	 * 保护则返回 null
	 */
	public Protection getProtection() {
		return protection;
	}

	//读取
	/**
	 * 表明此轨道中是否还有更多帧待读取。
	 * 
	 * @return 如果至少还有一帧待读取则返回 true。
	 */
	public boolean hasMoreFrames() {
		return currentFrame<frames.size();
	}

	/**
	 * 从此轨道读取下一帧。如果已无更多帧
	 * 可读，则返回 null。
	 * 
	 * @return 下一帧；若无更多帧可读则返回 null
	 * @throws IOException 如果读取失败
	 */
	public Frame readNextFrame() throws IOException {
		Frame frame = null;
		if(hasMoreFrames()) {
			frame = frames.get(currentFrame);

			final long diff = frame.getOffset()-in.getOffset();
			if(diff>0) in.skipBytes(diff);
			else if(diff<0) {
				if(in.hasRandomAccess()) in.seek(frame.getOffset());
				else {
					Logger.getLogger("MP4 API").log(Level.WARNING, "readNextFrame failed: frame {0} already skipped, offset:{1}, stream:{2}", new Object[]{currentFrame, frame.getOffset(), in.getOffset()});
					throw new IOException("frame already skipped and no random access");
				}
			}

			final byte[] b = new byte[(int) frame.getSize()];
			try {
				in.readBytes(b);
			}
			catch(EOFException e) {
				Logger.getLogger("MP4 API").log(Level.WARNING, "readNextFrame failed: tried to read {0} bytes at {1}", new Long[]{frame.getSize(), in.getOffset()});
				throw e;
			}
			frame.setData(b);
			currentFrame++;
		}
		return frame;
	}

	/**
	 * 此方法尝试跳转到最接近给定
	 * 时间戳的帧。它返回所跳转至帧的时间戳；若未找到则返回 -1。
	 * 
	 * @param timestamp 要跳转至的时间戳
	 * @return 该方法跳转至的帧的时间戳
	 */
	public double seek(double timestamp) {
		//查找第一帧 > 时间戳
		Frame frame = null;
		for(int i = 0; i<frames.size(); i++) {
			frame = frames.get(i++);
			if(frame.getTime()>timestamp) {
				currentFrame = i;
				break;
			}
		}
		return (frame==null) ? -1 : frame.getTime();
	}

	/**
	 * 返回下一待读帧的时间戳。这是读取
	 * 含多个轨道的电影中的帧所必需的。
	 *
	 * @return 下一帧的时间戳
	 */
	double getNextTimeStamp() {
		return frames.get(currentFrame).getTime();
	}
}
