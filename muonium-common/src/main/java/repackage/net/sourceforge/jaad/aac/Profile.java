package repackage.net.sourceforge.jaad.aac;

/**
 * 各种 AAC profile。
 * <code>Decoder.canDecode</code> 函数指明解码器能否
 * 处理给定格式。
 * 更确切地说，ISO 标准称之为“object types”（对象类型）。
 * @author in-somnia
 */
public enum Profile {

	UNKNOWN(-1, "unknown", false),
	AAC_MAIN(1, "AAC Main Profile", true),
	AAC_LC(2, "AAC Low Complexity", true),
	AAC_SSR(3, "AAC Scalable Sample Rate", false),
	AAC_LTP(4, "AAC Long Term Prediction", false),
	AAC_SBR(5, "AAC SBR", true),
	AAC_SCALABLE(6, "Scalable AAC", false),
	TWIN_VQ(7, "TwinVQ", false),
	AAC_LD(11, "AAC Low Delay", false),
	ER_AAC_LC(17, "Error Resilient AAC Low Complexity", true),
	ER_AAC_SSR(18, "Error Resilient AAC SSR", false),
	ER_AAC_LTP(19, "Error Resilient AAC Long Term Prediction", false),
	ER_AAC_SCALABLE(20, "Error Resilient Scalable AAC", false),
	ER_TWIN_VQ(21, "Error Resilient TwinVQ", false),
	ER_BSAC(22, "Error Resilient BSAC", false),
	ER_AAC_LD(23, "Error Resilient AAC Low Delay", false);
	private static final Profile[] ALL = {
		AAC_MAIN, AAC_LC, AAC_SSR, AAC_LTP, AAC_SBR, AAC_SCALABLE, TWIN_VQ,
		null, null, null, AAC_LD, null, null, null, null, null, ER_AAC_LC, ER_AAC_SSR,
		ER_AAC_LTP, ER_AAC_SCALABLE, ER_TWIN_VQ, ER_BSAC, ER_AAC_LD
	};

	/**
	 * 返回给定索引对应的 profile 实例。如果索引不在
	 * 1 到 23（含）之间，则返回 UNKNOWN。
	 * @return 具有给定索引的 profile
	 */
	public static Profile forInt(int i) {
		Profile p;
		if(i<=0||i>ALL.length) p = UNKNOWN;
		else p = ALL[i-1];
		return p;
	}
	private final int num;
	private final String descr;
	private final boolean supported;

	private Profile(int num, String descr, boolean supported) {
		this.num = num;
		this.descr = descr;
		this.supported = supported;
	}

	/**
	 * 返回此 profile 在 1 到 23 之间的索引；如果这是
	 * <code>UNKNOWN</code> 实例则返回 -1。
	 * @return 该 profile 的索引
	 */
	public int getIndex() {
		return num;
	}

	/**
	 * 返回此 profile 的简短描述。
	 * @return 该 profile 的描述
	 */
	public String getDescription() {
		return descr;
	}

	/**
	 * 返回此 profile 的字符串表示。该方法与
	 * <code>getDescription()</code> 相同。
	 * @return 该 profile 的描述
	 */
	@Override
	public String toString() {
		return descr;
	}

	/**
	 * 返回一个布尔值，表明此 profile 是否可由
	 * <code>Decoder</code> 解码。
	 * @see Decoder#canDecode(repackage.net.sourceforge.jaad.aac.Profile) 
	 * @return 如果该 profile 受支持则返回 true
	 */
	public boolean isDecodingSupported() {
		return supported;
	}

	/**
	 * 返回一个布尔值，表明此 profile 是否包含错误弹性
	 * 工具。也就是说，如果其索引大于 16，因为第一个错误弹性
	 * profile 是 ER_AAC_LC (17)。
	 * 此方法主要供内部使用。
	 * @return 如果该 profile 使用错误弹性则返回 true
	 */
	public boolean isErrorResilientProfile() {
		return num>16;
	}
}
