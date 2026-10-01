package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * 数据引用对象包含一张数据引用表（通常为 URL），
 * 声明放映中所用媒体数据的位置。
 * 采样描述中的数据引用索引将此表中的条目
 * 关联到轨道中的采样。一个轨道可以以此方式分散在多个数据源上。
 * 数据条目要么是 DataEntryUrnBox，要么是 DataEntryUrlBox。
 * 
 * @author in-somnia
 */
public class DataReferenceBox extends FullBox {

	public DataReferenceBox() {
		super("Data Reference Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		final int entryCount = (int) in.readBytes(4);

		readChildren(in, entryCount); //DataEntryUrlBox, DataEntryUrnBox
	}
}
