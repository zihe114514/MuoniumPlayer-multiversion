package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.BoxImpl;

/**
 * 此类用于所有已知但不含必要数据、可跳过的 box。
 * 主要用于 'skip'、'free' 和 'wide'。
 * 
 * @author in-somnia
 */
public class FreeSpaceBox extends BoxImpl {

	public FreeSpaceBox() {
		super("Free Space Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		//无需读取，该 box 将被跳过
	}
}
