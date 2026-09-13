package repackage.net.sourceforge.jaad.mp4.boxes.impl.oma;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * ContentID box 包含元数据所关联内容对象的唯一标识符。
 * content-ID 的值必须等于该内容对象 Common Headers 中存储的 content-ID 值。
 * 每个 User-Data box 中必须恰好有一个 ContentID 子 box，且作为容器中第一个子 box。
 * 
 * @author in-somnia
 */
public class OMAContentIDBox extends FullBox {

	private String contentID;

	public OMAContentIDBox() {
		super("OMA DRM Content ID Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		final int len = (int) in.readBytes(2);
		contentID = in.readString(len);
	}

	/**
	 * 返回 content-ID 字符串。
	 * 
	 * @return content-ID
	 */
	public String getContentID() {
		return contentID;
	}
}
