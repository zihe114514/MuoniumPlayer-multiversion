package repackage.net.sourceforge.jaad.mp4.boxes.impl.oma;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * Discrete Media headers box 包含 DCF 格式特有字段和 Common Headers box，
 * 后跟一个可选的 user-data box。单个 OMA DRM Container box 中必须恰好有一个
 * OMADiscreteHeaders box，且作为容器中第一个 box。
 * 
 * @author in-somnia
 */
public class OMADiscreteMediaHeadersBox extends FullBox {

	private String contentType;

	public OMADiscreteMediaHeadersBox() {
		super("OMA DRM Discrete Media Headers Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		final int len = in.read();
		contentType = in.readString(len);
		
		readChildren(in);
	}

	/**
	 * content type 指示内容对象的原始 MIME 媒体类型，
	 * 即成功提取 OMAContentBox 后所得内容所代表的内容类型。
	 * 
	  * @return 内容类型
	 */
	public String getContentType() {
		return contentType;
	}
}
