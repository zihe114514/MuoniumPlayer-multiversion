package repackage.net.sourceforge.jaad.mp4.boxes.impl.oma;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

public class OMAAccessUnitFormatBox extends FullBox {

	private boolean selectiveEncrypted;
	private int keyIndicatorLength, initialVectorLength;

	public OMAAccessUnitFormatBox() {
		super("OMA DRM Access Unit Format Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		//1 位选择性加密，7 位保留
		selectiveEncrypted = ((in.read()>>7)&1)==1;
		keyIndicatorLength = in.read(); //始终为零？
		initialVectorLength = in.read();
	}

	public boolean isSelectiveEncrypted() {
		return selectiveEncrypted;
	}

	public int getKeyIndicatorLength() {
		return keyIndicatorLength;
	}

	public int getInitialVectorLength() {
		return initialVectorLength;
	}
}
