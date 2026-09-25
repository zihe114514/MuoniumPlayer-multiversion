package repackage.net.sourceforge.jaad.mp4.api;

import repackage.net.sourceforge.jaad.mp4.api.codec.*;
import repackage.net.sourceforge.jaad.mp4.boxes.BoxTypes;
import repackage.net.sourceforge.jaad.mp4.boxes.impl.sampleentries.codec.*;

/**
 * <code>DecoderInfo</code> 对象包含初始化解码器所需的
 * 必要数据。一个轨道要么包含一个 <code>DecoderInfo</code>，要么包含一个
 * 名为 'DecoderSpecificInfo' 的字节数组，后者例如用于 AAC。
 * 
 * 从轨道获取的 <code>DecoderInfo</code> 对象是本类的子类，
 * 具体取决于 <code>Codec</code>。
 * 
 * <code>
 * AudioTrack track = (AudioTrack) movie.getTrack(AudioCodec.AC3);
 * AC3DecoderInfo info = (AC3DecoderInfo) track.getDecoderInfo();
 * </code>
 * 
 * @author in-somnia
 */
public abstract class DecoderInfo {

	static DecoderInfo parse(CodecSpecificBox css) {
		final long l = css.getType();

		final DecoderInfo info;
		if(l==BoxTypes.H263_SPECIFIC_BOX) info = new H263DecoderInfo(css);
		else if(l==BoxTypes.AMR_SPECIFIC_BOX) info = new AMRDecoderInfo(css);
		else if(l==BoxTypes.EVRC_SPECIFIC_BOX) info = new EVRCDecoderInfo(css);
		else if(l==BoxTypes.QCELP_SPECIFIC_BOX) info = new QCELPDecoderInfo(css);
		else if(l==BoxTypes.SMV_SPECIFIC_BOX) info = new SMVDecoderInfo(css);
		else if(l==BoxTypes.AVC_SPECIFIC_BOX) info = new AVCDecoderInfo(css);
		else if(l==BoxTypes.AC3_SPECIFIC_BOX) info = new AC3DecoderInfo(css);
		else if(l==BoxTypes.EAC3_SPECIFIC_BOX) info = new EAC3DecoderInfo(css);
		else info = new UnknownDecoderInfo();
		return info;
	}

	private static class UnknownDecoderInfo extends DecoderInfo {
	}
}
