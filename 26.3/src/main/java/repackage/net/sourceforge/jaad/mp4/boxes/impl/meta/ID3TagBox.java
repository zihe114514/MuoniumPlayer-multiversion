package repackage.net.sourceforge.jaad.mp4.boxes.impl.meta;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;
import repackage.net.sourceforge.jaad.mp4.boxes.Utils;

//TODO: 用 nio ByteBuffer 代替数组
public class ID3TagBox extends FullBox {

	private String language;
	private byte[] id3Data;

	public ID3TagBox() {
		super("ID3 Tag Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		language = Utils.getLanguageCode(in.readBytes(2));

		id3Data = new byte[(int) getLeft(in)];
		in.readBytes(id3Data);
	}

	public byte[] getID3Data() {
		return id3Data;
	}

	/**
	 * 以下文本的语言代码。三个字符代码的集合见 ISO 639-2/T。
	 */
	public String getLanguage() {
		return language;
	}
}
