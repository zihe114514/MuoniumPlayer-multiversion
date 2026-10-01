package repackage.net.sourceforge.jaad.mp4.api;

import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.Box;
import repackage.net.sourceforge.jaad.mp4.boxes.BoxTypes;
import repackage.net.sourceforge.jaad.mp4.boxes.impl.SampleDescriptionBox;
import repackage.net.sourceforge.jaad.mp4.boxes.impl.SoundMediaHeaderBox;
import repackage.net.sourceforge.jaad.mp4.boxes.impl.sampleentries.AudioSampleEntry;
import repackage.net.sourceforge.jaad.mp4.boxes.impl.sampleentries.codec.CodecSpecificBox;
import repackage.net.sourceforge.jaad.mp4.boxes.impl.ESDBox;

public class AudioTrack extends Track {

	public enum AudioCodec implements Codec {

		AAC,
		AC3,
		AMR,
		AMR_WIDE_BAND,
		EVRC,
		EXTENDED_AC3,
		QCELP,
		SMV,
		UNKNOWN_AUDIO_CODEC;

		static Codec forType(long type) {
			final Codec ac;
			if(type==BoxTypes.MP4A_SAMPLE_ENTRY) ac = AAC;
			else if(type==BoxTypes.AC3_SAMPLE_ENTRY) ac = AC3;
			else if(type==BoxTypes.AMR_SAMPLE_ENTRY) ac = AMR;
			else if(type==BoxTypes.AMR_WB_SAMPLE_ENTRY) ac = AMR_WIDE_BAND;
			else if(type==BoxTypes.EVRC_SAMPLE_ENTRY) ac = EVRC;
			else if(type==BoxTypes.EAC3_SAMPLE_ENTRY) ac = EXTENDED_AC3;
			else if(type==BoxTypes.QCELP_SAMPLE_ENTRY) ac = QCELP;
			else if(type==BoxTypes.SMV_SAMPLE_ENTRY) ac = SMV;
			else ac = UNKNOWN_AUDIO_CODEC;
			return ac;
		}
	}
	private final SoundMediaHeaderBox smhd;
	private final AudioSampleEntry sampleEntry;
	private Codec codec;

	public AudioTrack(Box trak, MP4InputStream in) {
		super(trak, in);

		final Box mdia = trak.getChild(BoxTypes.MEDIA_BOX);
		final Box minf = mdia.getChild(BoxTypes.MEDIA_INFORMATION_BOX);
		smhd = (SoundMediaHeaderBox) minf.getChild(BoxTypes.SOUND_MEDIA_HEADER_BOX);

		final Box stbl = minf.getChild(BoxTypes.SAMPLE_TABLE_BOX);

		//采样描述：'mp4a' 和 'enca' 有一个 ESDBox，其余均有 CodecSpecificBox
		final SampleDescriptionBox stsd = (SampleDescriptionBox) stbl.getChild(BoxTypes.SAMPLE_DESCRIPTION_BOX);
		if(stsd.getChildren().get(0) instanceof AudioSampleEntry) {
			sampleEntry = (AudioSampleEntry) stsd.getChildren().get(0);
			final long type = sampleEntry.getType();
			if(sampleEntry.hasChild(BoxTypes.ESD_BOX)) findDecoderSpecificInfo((ESDBox) sampleEntry.getChild(BoxTypes.ESD_BOX));
			else decoderInfo = DecoderInfo.parse((CodecSpecificBox) sampleEntry.getChildren().get(0));

			if(type==BoxTypes.ENCRYPTED_AUDIO_SAMPLE_ENTRY||type==BoxTypes.DRMS_SAMPLE_ENTRY) {
				findDecoderSpecificInfo((ESDBox) sampleEntry.getChild(BoxTypes.ESD_BOX));
				protection = Protection.parse(sampleEntry.getChild(BoxTypes.PROTECTION_SCHEME_INFORMATION_BOX));
				codec = protection.getOriginalFormat();
			}
			else codec = AudioCodec.forType(sampleEntry.getType());
		}
		else {
			sampleEntry = null;
			codec = AudioCodec.UNKNOWN_AUDIO_CODEC;
		}
	}

	@Override
	public Type getType() {
		return Type.AUDIO;
	}

	@Override
	public Codec getCodec() {
		return codec;
	}

	/**
	 * 平衡度是一个浮点数，用于将单声道音轨置于
	 * 立体声空间中：0 为居中（正常值），最左为 -1.0，最右
	 * 为 1.0。
	 *
	 * @return 该音轨的立体声平衡
	 */
	public double getBalance() {
		return smhd.getBalance();
	}

	/**
	 * 返回此音轨的声道数。
	 * @return 声道数
	 */
	public int getChannelCount() {
		return sampleEntry.getChannelCount();
	}

	/**
	 * 返回此音轨的采样率。
	 * @return 采样率
	 */
	public int getSampleRate() {
		return sampleEntry.getSampleRate();
	}

	/**
	 * 返回此音轨的采样大小（位）。
	 * @return 采样大小
	 */
	public int getSampleSize() {
		return sampleEntry.getSampleSize();
	}

	public double getVolume() {
		return tkhd.getVolume();
	}
}
