package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;
import repackage.net.sourceforge.jaad.mp4.boxes.Utils;

/**
 * 此 box 指定单个轨道的特性。一个轨道中恰好含一个 Track Header Box。
 * 在没有编辑列表时，轨道的放映从整个放映的开头开始。
 * 空编辑用于偏移轨道的起始时间。
 * 若一个放映中所有轨道既未设置 trackInMovie 也未设置 trackInPreview，
 * 则所有轨道应被视为两个标志在所有轨道上都已设置。
 * 提示轨道不应设置 track header 标志，使其在本地播放和预览时被忽略。
 * track header 中的宽高以假想的“正方形”（均匀）网格度量。
 * 在由排版或合成系统引起的任何变换或放置之前，轨道视频数据（逻辑上）
 * 被归一化到这些尺寸。轨道（及电影）矩阵若使用，也在该均匀缩放空间中操作。
 * @author in-somnia
 */
public class TrackHeaderBox extends FullBox {

	private boolean enabled, inMovie, inPreview;
	private long creationTime, modificationTime, duration;
	private int trackID, layer, alternateGroup;
	private double volume, width, height;
	private double[] matrix;

	public TrackHeaderBox() {
		super("Track Header Box");
		matrix = new double[9];
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		enabled = (flags&1)==1;
		inMovie = (flags&2)==2;
		inPreview = (flags&4)==4;

		final int len = (version==1) ? 8 : 4;
		creationTime = in.readBytes(len);
		modificationTime = in.readBytes(len);
		trackID = (int) in.readBytes(4);
		in.skipBytes(4); //保留
		duration = Utils.detectUndetermined(in.readBytes(len));

		in.skipBytes(8); //保留

		layer = (int) in.readBytes(2);
		alternateGroup = (int) in.readBytes(2);
		volume = in.readFixedPoint(8, 8);

		in.skipBytes(2); //保留

		for(int i = 0; i<9; i++) {
			if(i<6) matrix[i] = in.readFixedPoint(16, 16);
			else matrix[i] = in.readFixedPoint(2, 30);
		}

		width = in.readFixedPoint(16, 16);
		height = in.readFixedPoint(16, 16);
	}

	/**
	 * 指示轨道已启用的标志。已禁用的轨道被视为不存在。
	 * @return 如果轨道已启用则返回 true
	 */
	public boolean isTrackEnabled() {
		return enabled;
	}

	/**
	 * 指示轨道在放映中被使用的标志。
	 * @return 如果轨道被使用则返回 true
	 */
	public boolean isTrackInMovie() {
		return inMovie;
	}

	/**
	 * 指示预览放映时使用该轨道的标志。
	 * @return 如果轨道在预览中被使用则返回 true
	 */
	public boolean isTrackInPreview() {
		return inPreview;
	}

	/**
	 * 创建时间为整数，以自 1904 年 1 月 1 日午夜（UTC）起的秒数
	 * 声明放映的创建时间。
	 * @return 创建时间
	 */
	public long getCreationTime() {
		return creationTime;
	}

	/**
	 * 修改时间为整数，以自 1904 年 1 月 1 日午夜（UTC）起的秒数
	 * 声明放映的最近修改时间。
	 */
	public long getModificationTime() {
		return modificationTime;
	}

	/**
	 * track ID 为整数，在整个放映生命周期内唯一标识此轨道。
	 * 轨道 ID 永不重用，且不能为零。
	 * @return 该轨道的 ID
	 */
	public int getTrackID() {
		return trackID;
	}

	/**
	 * 时长为整数，指示此轨道的时长（以 Movie Header Box 中
	 * 指示的时间刻度为单位）。该字段值等于该轨道所有编辑时长之和。
	 * 若没有编辑列表，则时长为各采样时长之和，
	 * 换算为 Movie Header Box 中的时间刻度。若无法确定此轨道的时长，
	 * 则此值为 -1。
	 * @return 此轨道的时长
	 */
	public long getDuration() {
		return duration;
	}

	/**
	 * layer 指定视频轨道从前到后的顺序；编号越小越靠近观看者。
	 * 0 为正常值，-1 在轨道 0 之前，依此类推。
	 * @return layer
	 */
	public int getLayer() {
		return layer;
	}

	/**
	 * alternate group 为整数，指定一组轨道或轨道集合。
	 * 若此字段为 0，则没有关于与其他轨道可能关系的信息。
	 * 若非 0，互为备用数据的轨道应取相同值，
	 * 属于不同此类组的轨道应取不同值。alternate group 内任一时刻
	 * 只应播放或串流一个轨道，且该轨道应能通过比特率、编解码器、
	 * 语言、包大小等属性与组内其他轨道区分。一个组可以只有一个成员。
	  * @return 备用分组
	 */
	public int getAlternateGroup() {
		return alternateGroup;
	}

	/**
	 * 音量为浮点数，指示首选播放音量：
	 * 0.0 为静音，1.0 为正常音量。
	 * @return 音量
	 */
	public double getVolume() {
		return volume;
	}

	/**
	 * 宽度以浮点数指定轨道的视觉放映宽度。
	 * 它不必等于图像的像素宽度（记录在采样描述中）；
	 * 在由矩阵表示的轨道整体变换之前，序列中所有图像被缩放到此宽度。
	 * 图像的像素尺寸为默认值。
	 * @return 图像宽度
	 */
	public double getWidth() {
		return width;
	}

	/**
	 * 高度以浮点数指定轨道的视觉放映高度。
	 * 它不必等于图像的像素高度（记录在采样描述中）；
	 * 在由矩阵表示的轨道整体变换之前，序列中所有图像被缩放到此高度。
	 * 图像的像素尺寸为默认值。
	 * @return 图像高度
	 */
	public double getHeight() {
		return height;
	}

	public double[] getMatrix() {
		return matrix;
	}
}
