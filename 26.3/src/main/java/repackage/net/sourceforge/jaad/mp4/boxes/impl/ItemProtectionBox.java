package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * item protection box 提供一个条目保护信息数组，供
 * Item Information Box 使用。
 *
 * @author in-somnia
 */
public class ItemProtectionBox extends FullBox {

	public ItemProtectionBox() {
		super("Item Protection Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		final int protectionCount = (int) in.readBytes(2);

		readChildren(in, protectionCount);
	}
}
