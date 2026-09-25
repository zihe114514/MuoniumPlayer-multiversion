package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * 当主数据为 XML 格式，且希望将 XML 直接存入 meta-box 时，
 * 使用 XMLBox 或 BinaryXMLBox。
 * Binary XML Box 仅在该 XML 针对处理程序所标识的已定义格式
 * 具有单一、明确定义的二值化方案时才可使用。
 *
 * @see BinaryXMLBox
 * @author in-somnia
 */
public class XMLBox extends FullBox {

	private String content;

	public XMLBox() {
		super("XML Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		content = in.readUTFString((int) getLeft(in));
	}

	/**
	 * XML 内容。
	 */
	public String getContent() {
		return content;
	}
}
