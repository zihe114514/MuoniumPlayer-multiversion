package repackage.net.sourceforge.jaad.mp4.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.Box;
import repackage.net.sourceforge.jaad.mp4.boxes.BoxTypes;
import repackage.net.sourceforge.jaad.mp4.boxes.impl.HandlerBox;
import repackage.net.sourceforge.jaad.mp4.boxes.impl.MovieHeaderBox;

public class Movie {

	private final MP4InputStream in;
	private final MovieHeaderBox mvhd;
	private final List<Track> tracks;
	private final MetaData metaData;
	private final List<Protection> protections;

	public Movie(Box moov, MP4InputStream in) {
		this.in = in;

		//创建轨道
		mvhd = (MovieHeaderBox) moov.getChild(BoxTypes.MOVIE_HEADER_BOX);
		List<Box> trackBoxes = moov.getChildren(BoxTypes.TRACK_BOX);
		tracks = new ArrayList<Track>(trackBoxes.size());
		Track track;
		for(int i = 0; i<trackBoxes.size(); i++) {
			track = createTrack(trackBoxes.get(i));
			if(track!=null) tracks.add(track);
		}

		//读取元数据：moov.meta/moov.udta.meta
		metaData = new MetaData();
		if(moov.hasChild(BoxTypes.META_BOX)) metaData.parse(null, moov.getChild(BoxTypes.META_BOX));
		else if(moov.hasChild(BoxTypes.USER_DATA_BOX)) {
			final Box udta = moov.getChild(BoxTypes.USER_DATA_BOX);
			if(udta.hasChild(BoxTypes.META_BOX)) metaData.parse(udta, udta.getChild(BoxTypes.META_BOX));
		}

		//检测 DRM
		protections = new ArrayList<Protection>();
		if(moov.hasChild(BoxTypes.ITEM_PROTECTION_BOX)) {
			Box ipro = moov.getChild(BoxTypes.ITEM_PROTECTION_BOX);
			for(Box sinf : ipro.getChildren(BoxTypes.PROTECTION_SCHEME_INFORMATION_BOX)) {
				protections.add(Protection.parse(sinf));
			}
		}
	}

	//TODO: 支持 hint 和 meta
	private Track createTrack(Box trak) {
		final HandlerBox hdlr = (HandlerBox) trak.getChild(BoxTypes.MEDIA_BOX).getChild(BoxTypes.HANDLER_BOX);
		final Track track;
		switch((int) hdlr.getHandlerType()) {
			case HandlerBox.TYPE_VIDEO:
				track = new VideoTrack(trak, in);
				break;
			case HandlerBox.TYPE_SOUND:
				track = new AudioTrack(trak, in);
				break;
			default:
				track = null;
		}
		return track;
	}

	/**
	 * 返回此电影中所有轨道的不可修改列表。轨道按其在
	 * 文件/流中出现的顺序排列。
	 *
	 * @return 此电影包含的轨道
	 */
	public List<Track> getTracks() {
		return Collections.unmodifiableList(tracks);
	}

	/**
	 * 返回此电影中所有具有指定类型的轨道的不可修改列表。
	 * 轨道按其在
	 * 文件/流中出现的顺序排列。
	 *
	 * @return 此电影中具有指定类型的轨道
	 */
	public List<Track> getTracks(Type type) {
		final List<Track> l = new ArrayList<Track>();
		for(Track t : tracks) {
			if(t.getType().equals(type)) l.add(t);
		}
		return Collections.unmodifiableList(l);
	}

	/**
	 * 返回此电影中所有采样以指定编解码器编码的轨道的
	 * 不可修改列表。轨道按其在文件/流中出现的顺序排列。
	 *
	 * @return 此电影中具有指定类型的轨道
	 */
	public List<Track> getTracks(Track.Codec codec) {
		final List<Track> l = new ArrayList<Track>();
		for(Track t : tracks) {
			if(t.getCodec().equals(codec)) l.add(t);
		}
		return Collections.unmodifiableList(l);
	}

	/**
	 * 表明此电影是否包含元数据。如果为 false，则 <code>getMetaData()</code>
	 * 返回的 <code>MetaData</code> 对象不包含任何字段。
	 * 
	 * @return 如果此电影包含任何元数据则返回 true
	 */
	public boolean containsMetaData() {
		return metaData.containsMetaData();
	}

	/**
	 * 返回此电影的 MetaData 对象。
	 *
	 * @return 此电影的 MetaData
	 */
	public MetaData getMetaData() {
		return metaData;
	}

	/**
	 * 返回 <code>ProtectionInformation</code> 对象，其中包含
	 * 所用 DRM 系统的详细信息。如果不存在保护，则
	 * 返回的列表为空。
	 * 
	 * @return 保护信息列表
	 */
	public List<Protection> getProtections() {
		return Collections.unmodifiableList(protections);
	}

	//mvhd
	/**
	 * 返回此电影的创建时间。
	 * @return 创建时间
	 */
	public Date getCreationTime() {
		return Utils.getDate(mvhd.getCreationTime());
	}

	/**
	 * 返回此电影的最后修改时间。
	 * @return 修改时间
	 */
	public Date getModificationTime() {
		return Utils.getDate(mvhd.getModificationTime());
	}

	/**
	 * 返回时长（秒）。
	 * @return 时长
	 */
	public double getDuration() {
		return (double) mvhd.getDuration()/(double) mvhd.getTimeScale();
	}

	/**
	 * 表明此电影中是否还有更多帧待读取。
	 *
	 * @return 如果此电影中至少还有一个轨道尚有至少一帧待读取则返回 true。
	 */
	public boolean hasMoreFrames() {
		for(Track track : tracks) {
			if(track.hasMoreFrames()) return true;
		}
		return false;
	}

	/**
	 * 从此电影读取下一帧（来自某个所含轨道）。
	 * 该帧是时间顺序上的下一帧，即播放的下一帧。如果没有
	 * 任何轨道还含有更多帧，则返回 null。
	 *
	 * @return 下一帧；若此电影已无更多帧可读则返回 null。
	 * @throws IOException 如果读取失败
	 */
	public Frame readNextFrame() throws IOException {
		Track track = null;
		for(Track t : tracks) {
			if(t.hasMoreFrames()&&(track==null||t.getNextTimeStamp()<track.getNextTimeStamp())) track = t;
		}

		return (track==null) ? null : track.readNextFrame();
	}
}
