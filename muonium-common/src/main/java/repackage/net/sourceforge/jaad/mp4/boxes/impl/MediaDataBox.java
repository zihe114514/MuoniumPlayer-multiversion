package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import repackage.net.sourceforge.jaad.mp4.boxes.BoxImpl;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import java.io.IOException;

/**
 * Media Data Box 包含媒体数据。在视频轨道中，此 box
 * 包含视频帧。一个放映可包含零个或多个 Media Data
 * Box。实际媒体数据紧随类型字段；其结构由
 * movie box 中的元数据描述。文件中可以有任意数量的此类 box
 *（若所有媒体数据都在其他文件中，则可为零）。元数据按媒体数据在文件中的
 * 绝对偏移来引用它。
 * @author in-somnia
 */
public class MediaDataBox extends BoxImpl {

	public MediaDataBox() {
		super("Media Data Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		//若有随机访问：跳过；否则：不做处理
	}
}
