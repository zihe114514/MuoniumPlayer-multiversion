package repackage.net.sourceforge.jaad.mp4.od;

import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import java.io.IOException;

/**
 * <code>ObjectDescriptor</code> 由三部分组成：
 *
 * 第一部分通过一个 ID 在其名称作用域内唯一标记 <code>ObjectDescriptor</code>。
 * 场景描述中的媒体对象使用此 ID 引用其对象描述符。
 * 可选 URL String 表示实际对象描述符位于远程位置。
 *
 * 第二部分为一组可选描述符，支持以向后兼容方式纳入未来扩展并传输私有数据。
 *
 * 第三部分为一个 <code>ESDescriptor</code> 列表，每个为与媒体对象相关的单个原始流
 * 提供参数，并含一组可选的对象内容信息描述符。
 *
 * @author in-somnia
 */
public class ObjectDescriptor extends Descriptor {

	private int objectDescriptorID;
	private boolean urlPresent;
	private String url;

	void decode(MP4InputStream in) throws IOException {
		//10 位 objectDescriptorID，1 位 url 标志，5 位保留
		final int x = (int) in.readBytes(2);
		objectDescriptorID = (x>>6)&0x3FF;
		urlPresent = ((x>>5)&1)==1;

		if(urlPresent) url = in.readString(size-2);

		readChildren(in);
	}

	/**
	 * 该 ID 在其名称作用域内唯一标识此 ObjectDescriptor。
	 * 应在 0 与 1023 之间（不含端点）。禁止使用 0，1023 保留。
	 *
	 * @return 此 ObjectDescriptor 的 ID
	 */
	public int getObjectDescriptorID() {
		return objectDescriptorID;
	}

	/**
	 * 指示是否存在 URL 的标志。若设置，则不存在 profile。
	 *
	 * @return 若存在 URL 则返回 true
	 */
	public boolean isURLPresent() {
		return urlPresent;
	}

	/**
	 * URL String，应指向另一个 InitialObjectDescriptor。
	 * 若不存在 URL（<code>isURLPresent()</code> 返回 false），此方法返回 null。
	 *
	 * @return URL String；若不存在则返回 null
	 */
	public String getURL() {
		return url;
	}
}
