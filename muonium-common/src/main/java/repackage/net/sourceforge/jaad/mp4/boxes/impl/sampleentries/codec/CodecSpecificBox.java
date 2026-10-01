package repackage.net.sourceforge.jaad.mp4.boxes.impl.sampleentries.codec;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.BoxImpl;

/**
 * <code>CodecSpecificBox</code> 可在采样条目中代替 <code>ESDBox</code> 使用。
 * 它含若干 <code>DecoderSpecificInfo</code>。
 *
 * @author in-somnia
 */
public abstract class CodecSpecificBox extends BoxImpl {

	private long vendor;
	private int decoderVersion;

	public CodecSpecificBox(String name) {
		super(name);
	}

	protected void decodeCommon(MP4InputStream in) throws IOException {
		vendor = in.readBytes(4);
		decoderVersion = in.read();
	}

	public long getVendor() {
		return vendor;
	}

	public int getDecoderVersion() {
		return decoderVersion;
	}
}
