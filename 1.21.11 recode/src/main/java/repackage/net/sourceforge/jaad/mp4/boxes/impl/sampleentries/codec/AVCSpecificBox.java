package repackage.net.sourceforge.jaad.mp4.boxes.impl.sampleentries.codec;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;

//在 ISO 14496-15 中定义为 'AVC Configuration Record'
public class AVCSpecificBox extends CodecSpecificBox {

	private int configurationVersion, profile, level, lengthSize;
	private byte profileCompatibility;
	private byte[][] sequenceParameterSetNALUnit, pictureParameterSetNALUnit;

	public AVCSpecificBox() {
		super("AVC Specific Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		configurationVersion = in.read();
		profile = in.read();
		profileCompatibility = (byte) in.read();
		level = in.read();
		//6 位保留，2 位 'length size minus one'
		lengthSize = (in.read()&3)+1;

		int len;
		//3 位保留，5 位 sequence parameter set 数量
		final int sequenceParameterSets = in.read()&31;

		sequenceParameterSetNALUnit = new byte[sequenceParameterSets][];
		for(int i = 0; i<sequenceParameterSets; i++) {
			len = (int) in.readBytes(2);
			sequenceParameterSetNALUnit[i] = new byte[len];
			in.readBytes(sequenceParameterSetNALUnit[i]);
		}

		final int pictureParameterSets = in.read();

		pictureParameterSetNALUnit = new byte[pictureParameterSets][];
		for(int i = 0; i<pictureParameterSets; i++) {
			len = (int) in.readBytes(2);
			pictureParameterSetNALUnit[i] = new byte[len];
			in.readBytes(pictureParameterSetNALUnit[i]);
		}
	}

	public int getConfigurationVersion() {
		return configurationVersion;
	}

	/**
	 * ISO/IEC 14496-10 中定义的 AVC profile 代码。
	 *
	 * @return AVC profile
	 */
	public int getProfile() {
		return profile;
	}

	/**
	 * profileCompatibility 是一个字节，其定义与 sequence parameter set（SPS）
	 * 中 profileIDC 与 levelIDC 之间出现的字节完全相同，如 ISO/IEC 14496-10 所定义。
	 *
	 * @return profile 兼容字节
	 */
	public byte getProfileCompatibility() {
		return profileCompatibility;
	}

	public int getLevel() {
		return level;
	}

	/**
	 * 关联流的 AVC 视频采样或 AVC 参数集采样中 NALUnitLength 字段的字节长度。
	 * 此字段值为 1、2 或 4 字节。
	 *
	 * @return NALUnitLength 的字节长度
	 */
	public int getLengthSize() {
		return lengthSize;
	}

	/**
	 * ISO/IEC 14496-10 规定的 SPS NAL 单元。SPS 应按参数集标识符升序出现，允许有间隔。
	 *
	 * @return 所有 SPS NAL 单元
	 */
	public byte[][] getSequenceParameterSetNALUnits() {
		return sequenceParameterSetNALUnit;
	}

	/**
	 * ISO/IEC 14496-10 规定的 PPS NAL 单元。PPS 应按参数集标识符升序出现，允许有间隔。
	 *
	 * @return 所有 PPS NAL 单元
	 */
	public byte[][] getPictureParameterSetNALUnits() {
		return pictureParameterSetNALUnit;
	}
}
