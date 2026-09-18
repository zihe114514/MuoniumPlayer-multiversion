package repackage.net.sourceforge.jaad.mp4.boxes.impl.oma;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

public class OMAContentObjectBox extends FullBox {

	private byte[] data;

	public OMAContentObjectBox() {
		super("OMA Content Object Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		final int len = (int) in.readBytes(4);
		data = new byte[len];
		in.readBytes(data);
	}

	/**
	 * 返回此内容对象的数据。
	 * 
	 * @return 数据
	 */
	public byte[] getData() {
		return data;
	}
}
