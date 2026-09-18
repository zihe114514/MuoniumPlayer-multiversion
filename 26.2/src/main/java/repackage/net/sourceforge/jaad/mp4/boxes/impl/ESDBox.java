package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;
import repackage.net.sourceforge.jaad.mp4.od.ESDescriptor;
import repackage.net.sourceforge.jaad.mp4.od.ObjectDescriptor;

/**
 * entry sample descriptor (ESD) box 是条目描述符的容器。
 * 若使用，它位于某个采样条目内。除 <code>ESDBox</code> 外，
 * 也可能出现 <code>CodecSpecificBox</code>。
 * 
 * @author in-somnia
 */
public class ESDBox extends FullBox {

	private ESDescriptor esd;

	public ESDBox() {
		super("ESD Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);
		
		esd = (ESDescriptor) ObjectDescriptor.createDescriptor(in);
	}

	public ESDescriptor getEntryDescriptor() {
		return esd;
	}
}
