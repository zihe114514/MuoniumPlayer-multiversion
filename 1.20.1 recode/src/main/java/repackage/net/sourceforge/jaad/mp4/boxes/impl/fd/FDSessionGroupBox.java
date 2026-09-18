package repackage.net.sourceforge.jaad.mp4.boxes.impl.fd;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * FD session group box 为可选，但对含多个 FD 提示轨道的文件为必需。
 * 它包含会话列表以及每个会话所属的所有文件组和提示轨道。
 * 对某个 FD 会话，FD 会话在 FD session group box 中列出的所有
 * FD 提示轨道（通道）上同时发送。
 *
 * 任一时刻只应处理一个会话组。会话组中列出的第一个提示轨道指定基础通道。
 * 若服务器对会话组无偏好，默认应选第一个会话组。
 * 含提示轨道所引用文件的所有文件组的组 ID 应列入文件组列表。
 * 文件组 ID 又可转换为文件组名（使用 group ID to name box），
 * 供服务器包含在 FDT 中。
 *
 * @author in-somnia
 */
public class FDSessionGroupBox extends FullBox {

	private long[][] groupIDs, hintTrackIDs;

	public FDSessionGroupBox() {
		super("FD Session Group Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		final int sessionGroups = (int) in.readBytes(2);
		groupIDs = new long[sessionGroups][];
		hintTrackIDs = new long[sessionGroups][];

		int j, entryCount, channelsInSessionGroup;
		for(int i = 0; i<sessionGroups; i++) {
			entryCount = in.read();
			groupIDs[i] = new long[entryCount];
			for(j = 0; j<entryCount; j++) {
				groupIDs[i][j] = in.readBytes(4);
			}

			channelsInSessionGroup = (int) in.readBytes(2);
			hintTrackIDs[i] = new long[channelsInSessionGroup];
			for(j = 0; j<channelsInSessionGroup; j++) {
				hintTrackIDs[i][j] = in.readBytes(4);
			}
		}
	}

	/**
	 * 组 ID 指示该会话组所遵从的文件组。
	 *
	 * @return 所有会话组的所有组 ID
	 */
	public long[][] getGroupIDs() {
		return groupIDs;
	}

	/**
	 * 提示轨道 ID 指定属于某会话组的 FD 提示轨道的轨道 ID。
	 * 注意一个 FD 提示轨道对应一个 LCT 通道。
	 *
	 * @return 所有会话组的所有提示轨道 ID
	 */
	public long[][] getHintTrackIDs() {
		return hintTrackIDs;
	}
}
