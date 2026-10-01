package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * 声音媒体头包含与编码无关的、用于音频媒体的通用放映信息。
 * 此头用于所有含音频的轨道。
 *
 * @author in-somnia
 */
public class SoundMediaHeaderBox extends FullBox {

	private double balance;

	public SoundMediaHeaderBox() {
		super("Sound Media Header Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		balance = in.readFixedPoint(8, 8);
		in.skipBytes(2); //保留
	}

	/**
	 * 平衡度是一个浮点数，将单声道音频轨道置于
	 * 立体声空间：0 为居中（正常值），最左为 -1.0，最右
	 * 为 1.0。
	 *
	 * @return 单声道轨道的立体声平衡
	 */
	public double getBalance() {
		return balance;
	}
}
