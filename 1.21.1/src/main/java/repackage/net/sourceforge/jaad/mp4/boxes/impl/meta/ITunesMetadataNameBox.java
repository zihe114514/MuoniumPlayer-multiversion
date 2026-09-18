package repackage.net.sourceforge.jaad.mp4.boxes.impl.meta;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * 此 box 用于自定义元数据标签（位于 box 类型 '----' 内）。
 * 它包含自定义标签的名称，其数据存于 'data' box。
 *
 * @author in-somnia
 */
public class ITunesMetadataNameBox extends FullBox {

	private String metaName;

	public ITunesMetadataNameBox() {
		super("iTunes Metadata Name Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		metaName = in.readString((int) getLeft(in));
	}

	public String getMetaName() {
		return metaName;
	}
}
