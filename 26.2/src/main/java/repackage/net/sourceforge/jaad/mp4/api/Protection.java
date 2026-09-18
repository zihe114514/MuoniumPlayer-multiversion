package repackage.net.sourceforge.jaad.mp4.api;

import repackage.net.sourceforge.jaad.mp4.api.Track.Codec;
import repackage.net.sourceforge.jaad.mp4.api.drm.ITunesProtection;
import repackage.net.sourceforge.jaad.mp4.boxes.Box;
import repackage.net.sourceforge.jaad.mp4.boxes.BoxTypes;
import repackage.net.sourceforge.jaad.mp4.boxes.impl.OriginalFormatBox;
import repackage.net.sourceforge.jaad.mp4.boxes.impl.SchemeTypeBox;

/**
 * 此类包含某个 DRM 系统的信息。
 */
public abstract class Protection {

	public static enum Scheme {

		ITUNES_FAIR_PLAY(1769239918),
		UNKNOWN(-1);
		private long type;

		private Scheme(long type) {
			this.type = type;
		}
	}

	static Protection parse(Box sinf) {
		Protection p = null;
		if(sinf.hasChild(BoxTypes.SCHEME_TYPE_BOX)) {
			final SchemeTypeBox schm = (SchemeTypeBox) sinf.getChild(BoxTypes.SCHEME_TYPE_BOX);
			final long l = schm.getSchemeType();
			if(l==Scheme.ITUNES_FAIR_PLAY.type) p = new ITunesProtection(sinf);
		}

		if(p==null) p = new UnknownProtection(sinf);
		return p;
	}
	private final Codec originalFormat;

	protected Protection(Box sinf) {
		//原始格式
		final long type = ((OriginalFormatBox) sinf.getChild(BoxTypes.ORIGINAL_FORMAT_BOX)).getOriginalFormat();
		Codec c;
		//TODO: 目前它检测音频和视频编解码器，是否有其他方式？
		if(!(c = AudioTrack.AudioCodec.forType(type)).equals(AudioTrack.AudioCodec.UNKNOWN_AUDIO_CODEC)) originalFormat = c;
		else if(!(c = VideoTrack.VideoCodec.forType(type)).equals(VideoTrack.VideoCodec.UNKNOWN_VIDEO_CODEC)) originalFormat = c;
		else originalFormat = null;
	}

	Codec getOriginalFormat() {
		return originalFormat;
	}

	public abstract Scheme getScheme();

	//未知保护方案的默认实现
	private static class UnknownProtection extends Protection {

		UnknownProtection(Box sinf) {
			super(sinf);
		}

		@Override
		public Scheme getScheme() {
			return Scheme.UNKNOWN;
		}
	}
}
