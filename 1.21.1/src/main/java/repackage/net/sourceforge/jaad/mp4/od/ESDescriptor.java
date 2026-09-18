package repackage.net.sourceforge.jaad.mp4.od;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;

/**
 * ESDescriptor 传达与某个特定原始流相关的全部信息，含三大部分：
 *
 * 第一部分由 ES_ID（其名称作用域内对原始流的唯一引用）、
 * 将本 Descriptor 内多个原始流分组的机制、以及一个可选 URL String 组成。
 *
 * 第二部分为一组可选扩展描述符，支持以向后兼容方式纳入未来扩展并传输私有数据。
 *
 * 第三部分由 DecoderConfigDescriptor、SLConfigDescriptor、
 * IPIDescriptor 和 QoSDescriptor 组成，传达原始流的参数和需求。
 *
 * @author in-somnia
 */
public class ESDescriptor extends Descriptor {

	private int esID, streamPriority, dependingOnES_ID;
	private boolean streamDependency, urlPresent, ocrPresent;
	private String url;

	void decode(MP4InputStream in) throws IOException {
		esID = (int) in.readBytes(2);

		//1 位流依赖标志，1 位 url 标志，1 位保留，5 位流优先级
		final int flags = in.read();
		streamDependency = ((flags>>7)&1)==1;
		urlPresent = ((flags>>6)&1)==1;
		streamPriority = flags&31;

		if(streamDependency) dependingOnES_ID = (int) in.readBytes(2);
		else dependingOnES_ID = -1;

		if(urlPresent) {
			final int len = in.read();
			url = in.readString(len);
		}

		readChildren(in);
	}

	/**
	 * ES_ID 在其名称作用域内为每个原始流提供唯一标签。
	 * 值应在 0 与 65535 之间（不含端点）。0 和 65535 保留。
	 *
	 * @return 原始流的 ID
	 */
	public int getES_ID() {
		return esID;
	}

	/**
	 * 指示是否存在此流所依赖的另一流的 ID。
	 *
	 * @return 若 dependingOnES_ID 存在则返回 true
	 */
	public boolean hasStreamDependency() {
		return streamDependency;
	}

	/**
	 * <code>dependingOnES_ID</code> 是此原始流所依赖的另一原始流的 ES_ID。
	 * 具有该 <code>dependingOnES_ID</code> 的流也应关联到此 Descriptor。
	 * 若不存在值（<code>hasStreamDependency()</code> 返回 false），此方法返回 -1。
	 * 
	 * @return dependingOnES_ID 值；若不存在则返回 -1
	 */
	public int getDependingOnES_ID() {
		return dependingOnES_ID;
	}

	/**
	 * 指示是否存在 URL 的标志。
	 *
	 * @return 若存在 URL 则返回 true
	 */
	public boolean isURLPresent() {
		return urlPresent;
	}

	/**
	 * URL String，按名称指向 SL 分组流的位置。
	 * 从该 URL 取回的 SL 分组流的参数在此 ESDescriptor 中完整规定。
	 * 若不存在 URL（<code>isURLPresent()</code> 返回 false），此方法返回 null。
	 *
	 * @return URL String；若不存在则返回 null
	 */
	public String getURL() {
		return url;
	}

	/**
	 * 流优先级指示此原始流优先级的相对度量。
	 * 优先级高的原始流比优先级低的更重要。绝对值未作规范性定义。
	 *
	 * @return 流优先级
	 */
	public int getStreamPriority() {
		return streamPriority;
	}
}
