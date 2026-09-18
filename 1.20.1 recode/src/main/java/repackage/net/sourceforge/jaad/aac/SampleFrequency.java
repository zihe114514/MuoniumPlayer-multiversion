package repackage.net.sourceforge.jaad.aac;

/**
 * 一个枚举，表示 AAC 数据可能具有的所有采样频率。
 * 
 * @author in-somnia
 */
public enum SampleFrequency {

	SAMPLE_FREQUENCY_96000(0, 96000, new int[]{33, 512}, new int[]{31, 9}),
	SAMPLE_FREQUENCY_88200(1, 88200, new int[]{33, 512}, new int[]{31, 9}),
	SAMPLE_FREQUENCY_64000(2, 64000, new int[]{38, 664}, new int[]{34, 10}),
	SAMPLE_FREQUENCY_48000(3, 48000, new int[]{40, 672}, new int[]{40, 14}),
	SAMPLE_FREQUENCY_44100(4, 44100, new int[]{40, 672}, new int[]{42, 14}),
	SAMPLE_FREQUENCY_32000(5, 32000, new int[]{40, 672}, new int[]{51, 14}),
	SAMPLE_FREQUENCY_24000(6, 24000, new int[]{41, 652}, new int[]{46, 14}),
	SAMPLE_FREQUENCY_22050(7, 22050, new int[]{41, 652}, new int[]{46, 14}),
	SAMPLE_FREQUENCY_16000(8, 16000, new int[]{37, 664}, new int[]{42, 14}),
	SAMPLE_FREQUENCY_12000(9, 12000, new int[]{37, 664}, new int[]{42, 14}),
	SAMPLE_FREQUENCY_11025(10, 11025, new int[]{37, 664}, new int[]{42, 14}),
	SAMPLE_FREQUENCY_8000(11, 8000, new int[]{34, 664}, new int[]{39, 14}),
	SAMPLE_FREQUENCY_NONE(-1, 0, new int[]{0, 0}, new int[]{0, 0});

	/**
	 * 返回给定索引对应的采样频率实例。如果索引
	 * 不在 0 到 11（含）之间，则返回 SAMPLE_FREQUENCY_NONE。
	 * @return 具有给定索引的采样频率
	 */
	public static SampleFrequency forInt(int i) {
		final SampleFrequency freq;
		if(i>=0&&i<12) freq = values()[i];
		else freq = SAMPLE_FREQUENCY_NONE;
		return freq;
	}

	public static SampleFrequency forFrequency(int i) {
		final SampleFrequency[] all = values();

		SampleFrequency freq = null;
		for(int j = 0; freq==null&&j<12; j++) {
			if(i==all[j].frequency) freq = all[j];
		}

		if(freq==null) freq = SAMPLE_FREQUENCY_NONE;
		return freq;
	}
	private final int index, frequency;
	private final int[] prediction, maxTNS_SFB;

	private SampleFrequency(int index, int freqency, int[] prediction, int[] maxTNS_SFB) {
		this.index = index;
		this.frequency = freqency;
		this.prediction = prediction;
		this.maxTNS_SFB = maxTNS_SFB;
	}

	/**
	 * 返回此采样频率在 0 (96000) 到 11 (8000) 之间的
	 * 索引；如果是 SAMPLE_FREQUENCY_NONE 则返回 -1。
	 * @return 该采样频率的索引
	 */
	public int getIndex() {
		return index;
	}

	/**
	 * 以整数值返回采样频率。取值可能在
	 * 96000 到 8000 之间；如果是 SAMPLE_FREQUENCY_NONE 则为 0。
	 * @return 采样频率
	 */
	public int getFrequency() {
		return frequency;
	}

	/**
	 * 返回此采样频率下 ICPrediction 允许的最高预测 SFB。
	 * 此方法主要供内部使用。
	 * @return 最高预测 SFB
	 */
	public int getMaximalPredictionSFB() {
		return prediction[0];
	}

	/**
	 * 返回此采样频率下 ICPrediction 允许的预测器数量。
	 * 此方法主要供内部使用。
	 * @return ICPredictor 的数量
	 */
	public int getPredictorCount() {
		return prediction[1];
	}

	/**
	 * 返回此采样频率下 TNS 允许的最高比例因子频带（SFB）。
	 * 此方法主要供内部使用。
	 * @return TNS 的最高 SFB
	 */
	public int getMaximalTNS_SFB(boolean shortWindow) {
		return maxTNS_SFB[shortWindow ? 1 : 0];
	}

	/**
	 * 返回此采样频率的字符串表示。
	 * 该方法与 <code>getDescription()</code> 相同。
	 * @return 该采样频率的描述
	 */
	@Override
	public String toString() {
		return Integer.toString(frequency);
	}
}
