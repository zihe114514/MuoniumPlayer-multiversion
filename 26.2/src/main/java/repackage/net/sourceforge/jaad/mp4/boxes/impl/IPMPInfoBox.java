package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;
import repackage.net.sourceforge.jaad.mp4.od.Descriptor;
import repackage.net.sourceforge.jaad.mp4.od.ObjectDescriptor;

/**
 * IPMPInfoBox 包含 IPMP 描述符，记录应用于流的保护。
 *
 * IPMP Descriptor 定义于 ISO/IEC 14496-1。它是
 * MPEG-4 对象描述符（OD）的一部分，描述如何访问和解码
 * 一个对象。在 ISO Base Media File Format 中，IPMP Descriptor
 * 可直接携带于 IPMPInfoBox 中，无需 OD 流。
 *
 * 此 IPMPInfoBox 中存在 IPMP Descriptor，表明相关媒体流受到
 * IPMP Descriptor 中所述 IPMP 工具的保护。
 *
 * 每个 IPMP Descriptor 都有一个 IPMP-toolID，标识所需的保护用 IPMP
 * 工具。使用独立的注册机构（RA），任何一方都可注册自己的
 * IPMP 工具并无冲突地标识它。
 *
 * 该 IPMP Descriptor 携带一个或多个 IPMP 工具实例的 IPMP 信息，
 * 包括但不限于 IPMP 权利数据、IPMP 密钥数据、
 * 工具配置数据等。
 *
 * 若此媒体流受到多个 IPMP 工具保护，此 IPMPInfoBox 中可携带
 * 多个 IPMP Descriptor。
 *
 * @author in-somnia
 */
public class IPMPInfoBox extends FullBox {

	private List</*IPMP*/Descriptor> ipmpDescriptors;

	public IPMPInfoBox() {
		super("IPMP Info Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		ipmpDescriptors = new ArrayList</*IPMP*/Descriptor>();
		/*IPMP*/Descriptor desc;
		while(getLeft(in)>0) {
			desc = (/*IPMP*/Descriptor) ObjectDescriptor.createDescriptor(in);
			ipmpDescriptors.add(desc);
		}
	}

	/**
	 * 所含 IPMP 描述符列表。
	 *
	 * @return IPMP 描述符
	 */
	public List</*IPMP*/Descriptor> getIPMPDescriptors() {
		return Collections.unmodifiableList(ipmpDescriptors);
	}
}
