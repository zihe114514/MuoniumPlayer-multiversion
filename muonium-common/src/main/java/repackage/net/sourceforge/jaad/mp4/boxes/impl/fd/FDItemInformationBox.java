package repackage.net.sourceforge.jaad.mp4.boxes.impl.fd;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * FD item information box 为可选，但对使用 FD 提示轨道的文件为必需。
 * 它提供源文件分区以及 FD 提示轨道如何组合成 FD 会话的信息。
 * 每个分区条目给出某个文件分区、FEC 编码及关联 FEC 储备库的细节。
 * 若文件中使用了备用 FEC 编码方案或分区，可对一个源文件
 *（以其 item ID 标识）提供多个条目。所有分区条目隐式编号，
 * 第一个条目编号为 1。
 *
 * @author in-somnia
 */
public class FDItemInformationBox extends FullBox {

	public FDItemInformationBox() {
		super("FD Item Information Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		final int entryCount = (int) in.readBytes(2);
		readChildren(in, entryCount); //分区条目

		readChildren(in); //FDSessionGroupBox 和 GroupIDToNameBox
	}
}
