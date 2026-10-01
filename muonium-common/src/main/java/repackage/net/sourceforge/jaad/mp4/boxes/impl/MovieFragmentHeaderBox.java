package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * movie fragment header 含一个序列号，作为安全检查。
 * 序列号通常从 1 开始，且文件中每个 movie fragment
 * 必须按其出现顺序递增。这使读取者可验证序列的
 * 完整性；构造一个分片乱序的文件是错误的。
 */
public class MovieFragmentHeaderBox extends FullBox {

	private long sequenceNumber;

	public MovieFragmentHeaderBox() {
		super("Movie Fragment Header Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		sequenceNumber = in.readBytes(4);
	}

	/**
	 * 此分片的序号，按递增顺序。
	 */
	public long getSequenceNumber() {
		return sequenceNumber;
	}
}
