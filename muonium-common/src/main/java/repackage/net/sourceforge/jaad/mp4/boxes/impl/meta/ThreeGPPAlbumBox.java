package repackage.net.sourceforge.jaad.mp4.boxes.impl.meta;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;

public class ThreeGPPAlbumBox extends ThreeGPPMetadataBox {

	private int trackNumber;

	public ThreeGPPAlbumBox() {
		super("3GPP Album Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		trackNumber = (getLeft(in)>0) ? in.read() : -1;
	}

	/**
	 * 本专辑中该媒体的轨道号（序号）。这是可选字段。
	 * 若字段不存在，返回 -1。
	 * 
	 * @return 轨道号
	 */
	public int getTrackNumber() {
		return trackNumber;
	}
}
