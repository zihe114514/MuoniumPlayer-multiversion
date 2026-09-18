package repackage.net.sourceforge.jaad.mp4.boxes.impl.meta;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;
import repackage.net.sourceforge.jaad.mp4.boxes.Utils;

public class ThreeGPPMetadataBox extends FullBox {

	private String languageCode, data;

	public ThreeGPPMetadataBox(String name) {
		super(name);
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		decodeCommon(in);

		data = in.readUTFString((int) getLeft(in));
	}

	//由不含 'data' 字符串的子 box 直接调用
	protected void decodeCommon(MP4InputStream in) throws IOException {
		super.decode(in);
		languageCode = Utils.getLanguageCode(in.readBytes(2));
	}

	/**
	 * 以下文本的语言代码。三个字符代码的集合见 ISO 639-2/T。
	 */
	public String getLanguageCode() {
		return languageCode;
	}

	public String getData() {
		return data;
	}
}
