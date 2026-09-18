package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * 在某些流中，媒体采样并不占满采样大小所给字节的所有位，
 * 末尾被填充到字节边界。在某些情况下，需要在外部记录
 * 所使用的填充位数。此表提供该信息。
 * 
 * @author in-somnia
 */
public class PaddingBitBox extends FullBox {

	private int[] pad1, pad2;

	public PaddingBitBox() {
		super("Padding Bit Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		final int sampleCount = (int) (in.readBytes(4)+1)/2;
		pad1 = new int[sampleCount];
		pad2 = new int[sampleCount];

		byte b;
		for(int i = 0; i<sampleCount; i++) {
			b = (byte) in.read();
			//1 位保留
			//3 位 pad1
			pad1[i] = (b>>4)&7;
			//1 位保留
			//3 位 pad2
			pad2[i] = b&7;
		}
	}

	/**
	 * 0 到 7 的整数值，指示采样 (i*2)+1 末尾的位数。
	 */
	public int[] getPad1() {
		return pad1;
	}

	/**
	 * 0 到 7 的整数值，指示采样 (i*2)+2 末尾的位数。
	 */
	public int[] getPad2() {
		return pad2;
	}
}
