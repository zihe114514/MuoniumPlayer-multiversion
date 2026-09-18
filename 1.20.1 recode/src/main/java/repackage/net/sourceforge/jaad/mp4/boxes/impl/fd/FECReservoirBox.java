package repackage.net.sourceforge.jaad.mp4.boxes.impl.fd;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * FEC reservoir box 将文件分区 box 中标识的源文件与
 * 作为附加条目存储的 FEC 储备库关联。它包含一个列表，
 * 从与源文件第一个源块关联的第一个 FEC 储备库开始，
 * 顺序遍历源文件的各源块。
 *
 * @author in-somnia
 */
public class FECReservoirBox extends FullBox {

	private int[] itemIDs;
	private long[] symbolCounts;

	public FECReservoirBox() {
		super("FEC Reservoir Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		final int entryCount = (int) in.readBytes(2);
		itemIDs = new int[entryCount];
		symbolCounts = new long[entryCount];
		for(int i = 0; i<entryCount; i++) {
			itemIDs[i] = (int) in.readBytes(2);
			symbolCounts[i] = in.readBytes(4);
		}
	}

	/**
	 * item ID 指示与某个源块关联的 FEC 储备库的位置。
	 *
	 * @return 所有 item ID
	 */
	public int[] getItemIDs() {
		return itemIDs;
	}

	/**
	 * symbol count 指示 FEC 储备库中所含修复符号的数量。
	 *
	 * @return 所有 symbol count
	 */
	public long[] getSymbolCounts() {
		return symbolCounts;
	}
}
