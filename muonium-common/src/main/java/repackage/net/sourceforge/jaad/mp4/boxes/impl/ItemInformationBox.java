package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * item information box 提供关于所选条目的额外信息，
 * 包括符号化的（'file'）名称。它可选用，但若出现，
 * 必须加以解释，因为条目保护或内容编码可能已
 * 改变了条目中数据的格式。若某条目同时指示内容编码和
 * 保护，读取者应先解除该条目的保护，
 * 再解码其内容编码。若需要更多控制，
 * 可使用 IPMP 序列码。
 *
 * 此 box 包含一个条目数组，每个条目格式为一个 box。
 * 该数组按条目记录中递增的 item ID 排序。
 *
 * 定义了两个版本的 item info 条目。版本 1 在版本 0 基础上
 * 增加了由扩展类型指定的信息。例如，对于由文件分区 box（'fpar'）
 * 引用的条目，应与扩展类型 'fdel' 一起使用；'fpar' 为源
 * 文件分区定义，适用于文件交付传输。
 *
 * 若不需要扩展，该 box 可在扩展类型字段和扩展之前终止；
 * 此外若不需要内容编码，该字段也可缺省，
 * 而 box 在其之前终止。
 *
 * @author in-somnia
 */
public class ItemInformationBox extends FullBox {

	public ItemInformationBox() {
		super("Item Information Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		final int protectionCount = (int) in.readBytes(2);

		readChildren(in, protectionCount);
	}
}
