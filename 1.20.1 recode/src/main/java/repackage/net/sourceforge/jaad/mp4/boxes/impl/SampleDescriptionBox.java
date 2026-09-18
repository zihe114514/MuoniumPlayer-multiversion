package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;
import java.io.IOException;

/**
 * 采样描述表给出所用编码类型的详细信息，
 * 以及该编码所需的任何初始化信息。
 * @author in-somnia
 */
public class SampleDescriptionBox extends FullBox {

	public SampleDescriptionBox() {
		super("Sample Description Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		final int entryCount = (int) in.readBytes(4);

		readChildren(in, entryCount);
	}
}
