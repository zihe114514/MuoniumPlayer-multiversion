package repackage.net.sourceforge.jaad.aac;

/**
 * AAC 的所有可能声道配置。
 * @author in-somnia
 */
public enum ChannelConfiguration {

	CHANNEL_CONFIG_UNSUPPORTED(-1, "invalid"),
	CHANNEL_CONFIG_NONE(0, "No channel"),
	CHANNEL_CONFIG_MONO(1, "Mono"),
	CHANNEL_CONFIG_STEREO(2, "Stereo"),
	CHANNEL_CONFIG_STEREO_PLUS_CENTER(3, "Stereo+Center"),
	CHANNEL_CONFIG_STEREO_PLUS_CENTER_PLUS_REAR_MONO(4, "Stereo+Center+Rear"),
	CHANNEL_CONFIG_FIVE(5, "Five channels"),
	CHANNEL_CONFIG_FIVE_PLUS_ONE(6, "Five channels+LF"),
	CHANNEL_CONFIG_SEVEN_PLUS_ONE(8, "Seven channels+LF");

	public static ChannelConfiguration forInt(int i) {
		ChannelConfiguration c;
		switch(i) {
			case 0:
				c = CHANNEL_CONFIG_NONE;
				break;
			case 1:
				c = CHANNEL_CONFIG_MONO;
				break;
			case 2:
				c = CHANNEL_CONFIG_STEREO;
				break;
			case 3:
				c = CHANNEL_CONFIG_STEREO_PLUS_CENTER;
				break;
			case 4:
				c = CHANNEL_CONFIG_STEREO_PLUS_CENTER_PLUS_REAR_MONO;
				break;
			case 5:
				c = CHANNEL_CONFIG_FIVE;
				break;
			case 6:
				c = CHANNEL_CONFIG_FIVE_PLUS_ONE;
				break;
			case 7:
			case 8:
				c = CHANNEL_CONFIG_SEVEN_PLUS_ONE;
				break;
			default:
				c = CHANNEL_CONFIG_UNSUPPORTED;
				break;
		}
		return c;
	}
	private final int chCount;
	private final String descr;

	private ChannelConfiguration(int chCount, String descr) {
		this.chCount = chCount;
		this.descr = descr;
	}

	/**
	 * 返回此配置中的声道数量。
	 */
	public int getChannelCount() {
		return chCount;
	}

	/**
	 * 返回此配置的简短描述。
	 * @return 该声道配置的描述
	 */
	public String getDescription() {
		return descr;
	}

	/**
	 * 返回此声道配置的字符串表示。
	 * 该方法与 <code>getDescription()</code> 相同。
	 * @return 该声道配置的描述
	 */
	@Override
	public String toString() {
		return descr;
	}
}
