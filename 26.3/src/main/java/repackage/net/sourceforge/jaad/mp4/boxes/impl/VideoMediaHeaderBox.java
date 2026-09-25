package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.awt.Color;
import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * 视频媒体头包含与编码无关的、用于视频媒体的通用放映信息
 * @author in-somnia
 */
public class VideoMediaHeaderBox extends FullBox {

	private long graphicsMode;
	private Color color;

	public VideoMediaHeaderBox() {
		super("Video Media Header Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		graphicsMode = in.readBytes(2);
		//6 字节 RGB 颜色
		final int[] c = new int[3];
		for(int i = 0; i<3; i++) {
			c[i] = (in.read()&0xFF)|((in.read()<<8)&0xFF);
		}
		color = new Color(c[0], c[1], c[2]);
	}

	/**
	 * graphics mode 指定此视频轨道的合成模式。
	 * 目前只定义了一种模式：
	 * '0'：覆盖到已有图像之上
	 */
	public long getGraphicsMode() {
		return graphicsMode;
	}

	/**
	 * graphics mode 可使用的一种颜色。
	 */
	public Color getColor() {
		return color;
	}
}
