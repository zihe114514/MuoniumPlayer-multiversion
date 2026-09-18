package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * Movie Fragment Random Access Offset Box 提供外层 Movie Fragment
 * Random Access Box 中 length 字段的一份拷贝。它被置于该 box 的
 * 末尾，使 size 字段也位于外层 Movie Fragment Random Access Box 的
 * 末尾。当 Movie Fragment Random Access Box 也位于文件末尾时，
 * 这便于其定位。此处的 size 字段必须正确。
 * 但 Movie Fragment Random Access Box 的存在、或其位于文件末尾，
 * 均不保证。
 *
 * @author in-somnia
 */
public class MovieFragmentRandomAccessOffsetBox extends FullBox {

	private long byteSize;

	public MovieFragmentRandomAccessOffsetBox() {
		super("Movie Fragment Random Access Offset Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		byteSize = in.readBytes(4);
	}

	public long getByteSize() {
		return byteSize;
	}
}
