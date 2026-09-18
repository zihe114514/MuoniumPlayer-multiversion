package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * 对于给定处理器，当希望主数据存放在他处或分为多个 extent 时，
 * 它可以是被引用条目之一；否则主元数据可包含在 meta-box 中
 *（例如在 XML box 中）。
 *
 * 要么必须出现此 box，要么 meta-box 内必须有一个 box（例如
 * XML box），按所标识处理器要求的格式包含主信息。
 *
 * @author in-somnia
 */
public class PrimaryItemBox extends FullBox {

	private int itemID;

	public PrimaryItemBox() {
		super("Primary Item Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		itemID = (int) in.readBytes(2);
	}

	/**
	 * item ID 是主条目的标识符。
	 *
	  * @return 项目 ID
	 */
	public int getItemID() {
		return itemID;
	}
}
