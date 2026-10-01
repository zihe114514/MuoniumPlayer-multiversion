package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * 存于文件中的典型放映每个媒体类型含一个 alternate group：视频一个、音频一个，等等。
 * 这样的文件可含多个视频轨道，但任一时刻只应播放或串流其中一个。
 * 做法是将所有视频轨道分配到同一个 alternate group。
 *（alternate group 的定义见子条款 8.3.2。）
 *
 * alternate group 中的所有轨道都是媒体选择的候选，但在会话期间在某些轨道间切换可能没有意义。
 * 例如可允许在不同比特率、相同帧大小的视频轨道间切换，
 * 但不允许在不同帧大小的轨道间切换。
 * 同样，可能希望在不同视频编解码器或不同音频语言的轨道间
 * 进行选择——而非切换。
 *
 * 选择与切换的区别通过除 alternate group 外再将轨道分配到 switch group 来处理。
 * 一个 alternate group 可含一个或多个 switch group。
 * alternate group 中的所有轨道都是媒体选择候选，
 * 而 switch group 中的轨道还可在会话期间切换。
 * 不同 switch group 代表不同工作点，例如不同帧大小、高低画质等。
 *
 * 对于非可扩展比特流，一个 switch group 中可含多个轨道。
 * 对于非分层可扩展比特流（如传统 AVC 流）同样适用。
 *
 * 通过用属性标记轨道可以刻画它们。每个轨道可用一组属性标记，
 * 用于描述某个 switch group 中的轨道或区分属于不同 switch group 的轨道。
 * @author in-somnia
 */
public class TrackSelectionBox extends FullBox {

	private long switchGroup;
	private final List<Long> attributes;

	public TrackSelectionBox() {
		super("Track Selection Box");
		attributes = new ArrayList<Long>();
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		switchGroup = in.readBytes(4);

		while(getLeft(in)>3) {
			attributes.add(in.readBytes(4));
		}
	}

	/**
	 * switch group 为整数，指定一组轨道或轨道集合。
	 * 若此字段为 0（默认值）或不存在 Track Selection box，
	 * 则没有关于该轨道能否在播放或串流期间切换的信息。
	 * 若非 0，可相互切换的轨道应取相同值。
	 * 属于同一 switch group 的轨道应属于同一 alternate group。
	 * 一个 switch group 可只有一个成员。
	 */
	public long getSwitchGroup() {
		return switchGroup;
	}

	/**
	 * <p>一组属性，应用作同属一个 alternate 或 switch group 中轨道的描述
	 * 或区分准则。每个区分性属性都关联一个指针，
	 * 指向区分该轨道的字段或信息。</p>
	 *
	 * <p>以下属性为描述性：
	 * <table>
	 * <tr><th>名称</th><th>属性</th><th>描述</th></tr>
	 * <tr><td>时间可扩展性</td><td>'tesc'</td><td>该轨道可在时间上缩放。</td></tr>
	 * <tr><td>细粒度 SNR 可扩展性</td><td>'fgsc'</td><td>该轨道可细粒度缩放。</td></tr>
	 * <tr><td>粗粒度 SNR 可扩展性</td><td>'cgsc'</td><td>该轨道可粗粒度缩放。</td></tr>
	 * <tr><td>空间可扩展性</td><td>'spsc'</td><td>该轨道可在空间上缩放。</td></tr>
	 * <tr><td>感兴趣区域可扩展性</td><td>'resc'</td><td>该轨道可按感兴趣区域缩放。</td></tr>
	 * </table></p>
	 *
	 * <p>以下属性为区分性：
	 * <table><tr><th>名称</th><th>属性</th><th>指针</th></tr>
	 * <tr><td>编解码器</td><td>'cdec'</td><td>采样条目（在媒体轨道的采样描述 box 中）</td></tr>
	 * <tr><td>屏幕大小</td><td>'scsz'</td><td>视觉采样条目的宽高字段。</td></tr>
	 * <tr><td>最大包大小</td><td>'mpsz'</td><td>RTP 提示采样条目中的 Maxpacketsize 字段</td></tr>
	 * <tr><td>媒体类型</td><td>'mtyp'</td><td>Handler box 中的 Handlertype（媒体轨道的）</td></tr>
	 * <tr><td>媒体语言</td><td>'mela'</td><td>Media Header box 中的 Language 字段</td></tr>
	 * <tr><td>比特率</td><td>'bitr'</td><td>轨道中采样总大小除以 track header box 中的时长</td></tr>
	 * <tr><td>帧率</td><td>'frar'</td><td>轨道中采样数除以 track header box 中的时长</td></tr>
	 * </table></p>
	 *
	 * <p>描述性属性刻画其所修饰的轨道，而区分性属性用于区分
	 * 属于同一 alternate 或 switch group 的轨道。区分性属性的指针
	 * 指示将该轨道与具有相同属性的其他轨道区分开的信息所在位置。</p>
	 */
	public List<Long> getAttributes() {
		return Collections.unmodifiableList(attributes);
	}
}
