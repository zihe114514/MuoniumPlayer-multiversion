package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * 章节 box 允许沿电影主时间轴指定独立章节。
 * 章节 box 出现于 movie box 内。
 * 定义见 "Adobe Video File Format Specification v10"。
 *
 * @author in-somnia
 */
public class ChapterBox extends FullBox {

	private final Map<Long, String> chapters;

	public ChapterBox() {
		super("Chapter Box");
		chapters = new HashMap<Long, String>();
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		in.skipBytes(4); //??

		final int count = in.read();

		long timestamp;
		int len;
		String name;
		for(int i = 0; i<count; i++) {
			timestamp = in.readBytes(8);
			len = in.read();
			name = in.readString(len);
			chapters.put(timestamp, name);
		}
	}

	/**
	 * 返回一个映射，将每个章节的时间戳映射到其名称。
	 *
	 * @return 各章节
	 */
	public Map<Long, String> getChapters() {
		return chapters;
	}
}
