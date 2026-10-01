package repackage.net.sourceforge.jaad.mp4.od;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;

/**
 * <code>InitialObjectDescriptor</code> 是 <code>ObjectDescriptor</code> 的变体，
 * 用于获取对内容的初始访问。
 *
 * @author in-somnia
 */
public class InitialObjectDescriptor extends Descriptor {

	private int objectDescriptorID;
	private boolean urlPresent, includeInlineProfiles;
	private String url;
	private int odProfile, sceneProfile, audioProfile, visualProfile, graphicsProfile;

	@Override
	void decode(MP4InputStream in) throws IOException {
		//10 位 objectDescriptorID，1 位 url 标志，1 位
		//includeInlineProfiles 标志，4 位保留
		final int x = (int) in.readBytes(2);
		objectDescriptorID = (x>>6)&0x3FF;
		urlPresent = ((x>>5)&1)==1;
		includeInlineProfiles = ((x>>4)&1)==1;

		if(urlPresent) url = in.readString(size-2);
		else {
			odProfile = in.read();
			sceneProfile = in.read();
			audioProfile = in.read();
			visualProfile = in.read();
			graphicsProfile = in.read();
		}

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
	 * 若设置此标志，表示后续 profile 指示考虑了处理任何可能内联的内容所需资源。
	 *
	 * @return 若此 ObjectDescriptor 含内联 profile 则返回 true
	 */
	public boolean includesInlineProfiles() {
		return includeInlineProfiles;
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

	/**
	 * 指示是否存在 profile 的标志。若设置，则不存在 URL。
	 *
	 * @return 若存在 profile 则返回 true
	 */
	public boolean areProfilesPresent() {
		return !urlPresent;
	}

	//TODO: javadoc
	public int getODProfile() {
		return odProfile;
	}

	/**
	 * 处理与此 InitialObjectDescriptor 关联内容所需场景描述 profile 的指示。<br />
	 * 取值应为以下之一：
	 * 0x00: 保留给 ISO 使用
	 * 0x01: ISO 14496-1 XXXX profile
	 * 0x02-0x7F: 保留给 ISO 使用
	 * 0x80-0xFD: 用户私有
	 * 0xFE: 未指定场景描述 profile
	 * 0xFF: 不需要场景描述能力
	 *
	 * @return 场景 profile
	 */
	public int getSceneProfile() {
		return sceneProfile;
	}

	/**
	 * 处理与此 InitialObjectDescriptor 关联内容所需音频 profile 的指示。<br />
	 * 取值应为以下之一：
	 * 0x00: 保留给 ISO 使用
	 * 0x01: ISO 14496-3 XXXX profile
	 * 0x02-0x7F: 保留给 ISO 使用
	 * 0x80-0xFD: 用户私有
	 * 0xFE: 未指定音频 profile
	 * 0xFF: 不需要音频能力
	 *
	 * @return 音频 profile
	 */
	public int getAudioProfile() {
		return audioProfile;
	}

	/**
	 * 处理与此 InitialObjectDescriptor 关联内容所需视觉 profile 的指示。<br />
	 * 取值应为以下之一：
	 * 0x00: 保留给 ISO 使用
	 * 0x01: ISO 14496-2 XXXX profile
	 * 0x02-0x7F: 保留给 ISO 使用
	 * 0x80-0xFD: 用户私有
	 * 0xFE: 未指定视觉 profile
	 * 0xFF: 不需要视觉能力
	 *
	 * @return 视觉 profile
	 */
	public int getVisualProfile() {
		return visualProfile;
	}

	/**
	 * 处理与此 InitialObjectDescriptor 关联内容所需图形 profile 的指示。<br />
	 * 取值应为以下之一：
	 * 0x00: 保留给 ISO 使用
	 * 0x01: ISO 14496-1 XXXX profile
	 * 0x02-0x7F: 保留给 ISO 使用
	 * 0x80-0xFD: 用户私有
	 * 0xFE: 未指定图形 profile
	 * 0xFF: 不需要图形能力
	 *
	 * @return 图形 profile
	 */
	public int getGraphicsProfile() {
		return graphicsProfile;
	}
}
