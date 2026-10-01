package repackage.net.sourceforge.jaad.mp4.boxes.impl.oma;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * 此 box 用于 OMA DRM 文件中 user-data box 的若干子 box。
 * 这些 box 的共同点是都只含一个 String。
 * 
 * @author in-somnia
 */
public class OMAURLBox extends FullBox {

	private String content;

	public OMAURLBox(String name) {
		super(name);
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		final byte[] b = new byte[(int) getLeft(in)];
		in.readBytes(b);
		content = new String(b, MP4InputStream.UTF8);
	}

	/**
	 * 返回此 box 所含的 String。其含义取决于此 box 的类型。
	 * 
	 * @return 此 box 的内容
	 */
	public String getContent() {
		return content;
	}
}
