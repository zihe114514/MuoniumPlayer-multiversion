package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;
import repackage.net.sourceforge.jaad.mp4.od.Descriptor;

/**
 * IPMP Control Box 可包含 IPMP 描述符，文件中的任意流都可引用它们。
 *
 * IPMP ToolListDescriptor 定义于 ISO/IEC 14496-1，它传达
 * 访问 ISO Base Media File 或 meta-box 中媒体流所需的
 * IPMP 工具列表，并可包含备用 IPMP 工具列表或
 * 访问内容所需工具的参数化描述。
 * 
 * 此 IPMPControlBox 中存在 IPMP Descriptor，表明文件
 * 或 meta-box 内的媒体流受到 IPMP Descriptor 中所述 IPMP 工具的保护。
 * 若有多个 IPMP 工具提供全局治理，此处可携带多个 IPMP Descriptor。
 *
 * @author in-somnia
 */
public class IPMPControlBox extends FullBox {

	private /*IPMPToolList*/Descriptor toolList;
	private /*IPMP*/Descriptor[] ipmpDescriptors;

	public IPMPControlBox() {
		super("IPMP Control Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		toolList = /*(IPMPToolListDescriptor)*/ Descriptor.createDescriptor(in);

		final int count = in.read();

		ipmpDescriptors = new Descriptor[count];
		for(int i = 0; i<count; i++) {
			ipmpDescriptors[i] = /*(IPMPDescriptor)*/ Descriptor.createDescriptor(in);
		}
	}

	/**
	 * toollist 是 ISO/IEC 14496-1 中定义的 IPMP ToolListDescriptor。
	 *
	 * @return toollist
	 */
	public Descriptor getToolList() {
		return toolList;
	}

	/**
	 * 所含 IPMP 描述符的列表。
	 *
	 * @return IPMP 描述符
	 */
	public Descriptor[] getIPMPDescriptors() {
		return ipmpDescriptors;
	}
}
