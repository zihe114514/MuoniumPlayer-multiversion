package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.boxes.Utils;

/**
 * movie header box 定义与媒体无关、且作为整体与整个放映相关的
 * 总体信息。
 * @author in-somnia
 */
public class MovieHeaderBox extends FullBox {

	private long creationTime, modificationTime, timeScale, duration;
	private double rate, volume;
	private double[] matrix;
	private long nextTrackID;

	public MovieHeaderBox() {
		super("Movie Header Box");
		matrix = new double[9];
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);
		final int len = (version==1) ? 8 : 4;
		creationTime = in.readBytes(len);
		modificationTime = in.readBytes(len);
		timeScale = in.readBytes(4);
		duration = Utils.detectUndetermined(in.readBytes(len));

		rate = in.readFixedPoint(16, 16);
		volume = in.readFixedPoint(8, 8);

		in.skipBytes(10); //保留

		for(int i = 0; i<9; i++) {
			if(i<6) matrix[i] = in.readFixedPoint(16, 16);
			else matrix[i] = in.readFixedPoint(2, 30);
		}

		in.skipBytes(24); //保留

		nextTrackID = in.readBytes(4);
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
	 * 时间刻度为整数，指定整个放映的时间刻度；
	 * 即一秒内经过的时间单位数。
	 * 例如，以六十分之一秒计量时间的坐标系，
	 * 其时标为 60。
	 * @return 时间刻度
	 */
	public long getTimeScale() {
		return timeScale;
	}

	/**
	 * 时长为整数，声明放映的长度（以指示的时间刻度为单位）。
	 * 此属性派生自放映的各轨道：该字段值对应
	 * 放映中最长轨道的时长。若无法确定时长，
	 * 则 duration 设为 -1。
	 * @return 最长轨道的时长
	 */
	public long getDuration() {
		return duration;
	}

	/**
	 * 速率为浮点数，指示放映的首选播放速率；
	 * 1.0 为正常正向播放
	 * @return 播放速率
	 */
	public double getRate() {
		return rate;
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
	 * 提供视频的变换矩阵：
	 * [A,B,U,C,D,V,X,Y,W]
	 * A：宽度缩放
	 * B：宽度旋转
	 * U：宽度角度
	 * C：高度旋转
	 * D：高度缩放
	 * V：高度角度
	 * X：距左边的位置
	 * Y：距顶部的位置
	 * W：除数缩放（限定为 1.0）
	 *
	 * 缩放的正常值为 1.0，旋转为 0.0。
	 * 角度限定为 0.0。
	 *
	 * @return 视频的变换矩阵
	 */
	public double[] getTransformationMatrix() {
		return matrix;
	}

	/**
	 * next-track-ID 为非零整数，指示要添加到此放映的
	 * 下一个轨道的轨道 ID。零不是合法的轨道 ID。
	 * 该值应大于当前在用的最大轨道 ID。
	 * 若该值等于全 1（32 位）且要添加新的
	 * 媒体轨道，则必须在文件中搜索一个未使用的轨道标识符。
	 * @return 下一个轨道的 ID
	 */
	public long getNextTrackID() {
		return nextTrackID;
	}
}
