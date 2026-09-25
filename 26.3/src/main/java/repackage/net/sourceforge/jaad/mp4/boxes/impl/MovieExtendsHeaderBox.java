package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * Movie Extends Header 为可选项，给出分片电影的整体时长，
 * 包括各分片。若不存在此 box，则必须通过检查每个分片来
 * 计算整体时长。
 * 
 * @author in-somnia
 */
public class MovieExtendsHeaderBox extends FullBox {

	private long fragmentDuration;

	public MovieExtendsHeaderBox() {
		super("Movie Extends Header Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		final int len = (version==1) ? 8 : 4;
		fragmentDuration = in.readBytes(len);
	}

	/**
	 * 分片时长为整数，声明整部电影（含各分片）放映的长度
	 *（以 Movie Header Box 中指示的时间刻度为单位）。
	 * 此字段值对应最长轨道的时长，包括电影分片。
	 * 若 MP4 文件实时创建（如用于直播），
	 * 则不太可能事先知道分片时长，此 box
	 * 可省略。
	 * 
	 * @return 分片时长
	 */
	public long getFragmentDuration() {
		return fragmentDuration;
	}
}
