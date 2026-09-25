package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.boxes.Utils;

/**
 * media head 声明与媒体无关、且与轨道中媒体特性相关的总体信息。
 */
public class MediaHeaderBox extends FullBox {

	private long creationTime, modificationTime, timeScale, duration;
	private String language;

	public MediaHeaderBox() {
		super("Media Header Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);
		
		final int len = (version==1) ? 8 : 4;
		creationTime = in.readBytes(len);
		modificationTime = in.readBytes(len);
		timeScale = in.readBytes(4);
		duration = Utils.detectUndetermined(in.readBytes(len));

		language = Utils.getLanguageCode(in.readBytes(2));

		in.skipBytes(2); //预定义：0
	}

	/**
	 * 创建时间为整数，以自 1904 年 1 月 1 日午夜（UTC）起的秒数
	 * 声明放映的创建时间。
	 * @return 创建时间
	 */
	public long getCreationTime() {
		return creationTime;
	}

	/**
	 * 修改时间为整数，以自 1904 年 1 月 1 日午夜（UTC）起的秒数
	 * 声明放映的最近修改时间。
	 */
	public long getModificationTime() {
		return modificationTime;
	}

	/**
	 * 时间刻度为整数，指定此媒体的时间刻度；
	 * 即一秒内经过的时间单位数。例如，以六十分之一秒计量
	 * 时间的坐标系，其时标为 60。
	 * @return 时间刻度
	 */
	public long getTimeScale() {
		return timeScale;
	}

	/**
	 * 时长为整数，声明此媒体的时长（以
	 * 时间刻度为单位）。若无法确定时长，则
	 * duration 设为 -1。
	 * @return 此媒体的时长
	 */
	public long getDuration() {
		return duration;
	}

	/**
	 * 此媒体的语言代码，定义见 ISO 639-2/T。
	 * @return 语言代码
	 */
	public String getLanguage() {
		return language;
	}
}
