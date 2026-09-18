package repackage.net.sourceforge.jaad.mp4.boxes.impl.oma;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * rights object box 可用于将 'OMA DRM v2.1' 第 5.3.9 节定义的受保护权利对象
 * 插入 DCF 或 PDCF。一个 Mutable DRM Information box 可含零个或多个 Rights Object box。
 * 
 * @author in-somnia
 */
public class OMARightsObjectBox extends FullBox {

	private byte[] data;

	public OMARightsObjectBox() {
		super("OMA DRM Rights Object Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);
		data = new byte[(int) getLeft(in)];
		in.readBytes(data);
	}

	/**
	 * 返回包含权利对象的数组。
	 * 
	 * @return 权利对象
	 */
	public byte[] getData() {
		return data;
	}
}
