package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.BoxTypes;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;
import repackage.net.sourceforge.jaad.mp4.boxes.Utils;

/**
 * Copyright box 包含一条版权声明，当它位于 Movie Box 内时适用于
 * 整个放映；当位于某个轨道内时适用于该整个轨道。可以有多个
 * 使用不同语言代码的 copyright box。
 */
public class CopyrightBox extends FullBox {

	private String languageCode, notice;

	public CopyrightBox() {
		super("Copyright Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		if(parent.getType()==BoxTypes.USER_DATA_BOX) {
			super.decode(in);
			//1 位填充，5*3 位语言代码（ISO-639-2/T）
			languageCode = Utils.getLanguageCode(in.readBytes(2));

			notice = in.readUTFString((int) getLeft(in));
		}
		else if(parent.getType()==BoxTypes.ITUNES_META_LIST_BOX) readChildren(in);
	}

	/**
	 * 以下文本的语言代码。三个字符代码的集合见 ISO 639-2/T。
	 */
	public String getLanguageCode() {
		return languageCode;
	}

	/**
	 * 版权声明。
	 */
	public String getNotice() {
		return notice;
	}
}
