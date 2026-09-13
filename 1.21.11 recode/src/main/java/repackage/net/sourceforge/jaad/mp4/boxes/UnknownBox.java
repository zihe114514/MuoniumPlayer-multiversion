package repackage.net.sourceforge.jaad.mp4.boxes;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;

/**
 * 用于未知类型的 Box 实现。
 * 
 * @author in-somnia
 */
class UnknownBox extends BoxImpl {

	UnknownBox() {
		super("unknown");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		//无需读取，该 box 将被跳过
	}
}
