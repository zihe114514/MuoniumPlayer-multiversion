package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * Media Box 内的此 box 声明轨道中媒体数据的呈现方式，
 * 从而声明轨道中媒体的性质。例如，视频轨道由视频处理器处理。
 *
 * 当存在于 Meta Box 内时，此 box 声明 'meta' box 内容的结构或格式。
 *
 * 对任何类型的元数据流都有通用处理器；具体格式
 * 由采样条目标识，例如视频或音频那样。
 * 若为文本，则提供 MIME 格式以记录其格式；
 * 若为 XML，则每个采样都是一个完整的 XML 文档，并且还提供
 * XML 的命名空间。
 * @author in-somnia
 */
public class HandlerBox extends FullBox {

	//ISO BMFF 类型
	public static final int TYPE_VIDEO = 1986618469; //vide
	public static final int TYPE_SOUND = 1936684398; //soun
	public static final int TYPE_HINT = 1751740020; //hint
	public static final int TYPE_META = 1835365473; //meta
	public static final int TYPE_NULL = 1853189228; //null
	//MP4 类型
	public static final int TYPE_ODSM = 1868854125; //odsm
	public static final int TYPE_CRSM = 1668445037; //crsm
	public static final int TYPE_SDSM = 1935962989; //sdsm
	public static final int TYPE_M7SM = 1832350573; //m7sm
	public static final int TYPE_OCSM = 1868788589; //ocsm
	public static final int TYPE_IPSM = 1768977261; //ipsm
	public static final int TYPE_MJSM = 1835692909; //mjsm
	private long handlerType;
	private String handlerName;

	public HandlerBox() {
		super("Handler Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		in.skipBytes(4); //预定义：0

		handlerType = in.readBytes(4);

		in.readBytes(4); //保留
		in.readBytes(4); //保留
		in.readBytes(4); //保留

		handlerName = in.readUTFString((int) getLeft(in), MP4InputStream.UTF8);
	}

	/**
	 * 当存在于媒体 box 中时，处理器类型为整数，包含
	 * 以下值之一：
	 * <ul>
	 * <li>'vide'：视频轨道</li>
	 * <li>'soun'：音频轨道</li>
	 * <li>'hint'：提示轨道</li>
	 * <li>'meta'：定时元数据轨道</li>
	 * </ul>
	 *
	 * 当存在于 meta box 中时，它包含一个适当的值，用以指示
	 * meta box 内容的格式。值 'null' 可用于主 meta box，
	 * 表示它仅用于容纳资源。
	 *
	 * @return 处理器类型
	 */
	public long getHandlerType() {
		return handlerType;
	}

	/**
	 * 该名称给出轨道类型的人类可读名称（用于调试
	 * 和检查目的）。
	 * 
	 * @return 处理器类型的名称
	 */
	public String getHandlerName() {
		return handlerName;
	}
}
