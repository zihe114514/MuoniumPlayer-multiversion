package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import repackage.net.sourceforge.jaad.mp4.boxes.BoxImpl;

/**
 * Track Reference Box 提供从所在轨道到放映中另一轨道的引用。
 * 这些引用是有类型的。'hint' 引用从所在提示轨道链接到它所提示的
 * 媒体数据。内容描述引用 'cdsc' 将描述性或元数据轨道链接到它所描述的内容。
 *
 * Track Box 内恰好可含一个 Track Reference Box。
 *
 * 若不存在此 box，则该轨道不以任何方式引用其他轨道。
 * 引用数组的大小填满引用类型 box。
 * @author in-somnia
 */
public class TrackReferenceBox extends BoxImpl {

	private String referenceType;
	private List<Long> trackIDs;

	public TrackReferenceBox() {
		super("Track Reference Box");
		trackIDs = new ArrayList<Long>();
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		referenceType = in.readString(4);

		while(getLeft(in)>3) {
			trackIDs.add(in.readBytes(4));
		}
	}

	/**
	 * 引用类型应设为以下值之一：
	 * <ul>
	 * <li>'hint'：被引用轨道包含此提示轨道的原始媒体。</li>
	 * <li>'cdsc'：此轨道描述被引用轨道。</li>
	 * <li>'hind'：此轨道依赖被引用的提示轨道，即仅当被引用提示轨道
	 * 被使用时才应使用它。</li>
	 * @return 引用类型
	 */
	public String getReferenceType() {
		return referenceType;
	}

	/**
	 * track ID 为整数，提供从所在轨道到放映中其他轨道的引用。
	 * 轨道 ID 永不重用，且不能为零。
	 * @return 此 box 引用的 track ID
	 */
	public List<Long> getTrackIDs() {
		return Collections.unmodifiableList(trackIDs);
	}
}
