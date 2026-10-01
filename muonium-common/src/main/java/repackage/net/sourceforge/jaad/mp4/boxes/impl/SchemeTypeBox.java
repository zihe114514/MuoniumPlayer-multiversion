package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * Scheme Type Box 标识保护方案。
 * 
 * @author in-somnia
 */
public class SchemeTypeBox extends FullBox {

	public static final long ITUNES_SCHEME = 1769239918; //itun
	private long schemeType, schemeVersion;
	private String schemeURI;

	public SchemeTypeBox() {
		super("Scheme Type Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		schemeType = in.readBytes(4);
		schemeVersion = in.readBytes(4);
		schemeURI = ((flags&1)==1) ? in.readUTFString((int) getLeft(in), MP4InputStream.UTF8) : null;
	}

	/**
	 * 方案类型是定义保护方案的代码。
	 *
	 * @return 方案类型
	 */
	public long getSchemeType() {
		return schemeType;
	}

	/**
	 * 方案版本是用于创建内容的方案版本。
	 *
	 * @return 方案版本
	 */
	public long getSchemeVersion() {
		return schemeVersion;
	}

	/**
	 * 可选的方案 URI 允许在用户系统未安装该方案时，
	 * 将其引导到一个网页。它是一个绝对 URI。
	 * 若方案 URI 不存在，此方法返回 null。
	 *
	 * @return 方案 URI；若不存在则返回 null
	 */
	public String getSchemeURI() {
		return schemeURI;
	}
}
