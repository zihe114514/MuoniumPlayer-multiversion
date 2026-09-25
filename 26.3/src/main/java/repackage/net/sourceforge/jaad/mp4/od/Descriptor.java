package repackage.net.sourceforge.jaad.mp4.od;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;

/**
 * 所有描述符的抽象基类与工厂（在 ISO 14496-1 中定义为 'ObjectDescriptors'）。
 *
 * @author in-somnia
 */
public abstract class Descriptor {

	public static final int TYPE_OBJECT_DESCRIPTOR = 1;
	public static final int TYPE_INITIAL_OBJECT_DESCRIPTOR = 2;
	public static final int TYPE_ES_DESCRIPTOR = 3;
	public static final int TYPE_DECODER_CONFIG_DESCRIPTOR = 4;
	public static final int TYPE_DECODER_SPECIFIC_INFO = 5;
	public static final int TYPE_SL_CONFIG_DESCRIPTOR = 6;
	public static final int TYPE_ES_ID_INC = 14;
	public static final int TYPE_MP4_INITIAL_OBJECT_DESCRIPTOR = 16;

	public static Descriptor createDescriptor(MP4InputStream in) throws IOException {
		//读取 tag 和 size
		final int type = in.read();
		int read = 1;
		int size = 0;
		int b = 0;
		do {
			b = in.read();
			size <<= 7;
			size |= b&0x7f;
			read++;
		}
		while((b&0x80)==0x80);

		//创建描述符
		final Descriptor desc = forTag(type);
		desc.type = type;
		desc.size = size;
		desc.start = in.getOffset();

		//解码
		desc.decode(in);
		//跳过剩余字节
		final long remaining = size-(in.getOffset()-desc.start);
		if(remaining>0) {
			Logger.getLogger("MP4 Boxes").log(Level.INFO, "Descriptor: bytes left: {0}, offset: {1}", new Long[]{remaining, in.getOffset()});
			in.skipBytes(remaining);
		}
		desc.size += read; //含 type 和 size 字段

		return desc;
	}

	private static Descriptor forTag(int tag) {
		Descriptor desc;
		switch(tag) {
			case TYPE_OBJECT_DESCRIPTOR:
				desc = new ObjectDescriptor();
				break;
			case TYPE_INITIAL_OBJECT_DESCRIPTOR:
			case TYPE_MP4_INITIAL_OBJECT_DESCRIPTOR:
				desc = new InitialObjectDescriptor();
				break;
			case TYPE_ES_DESCRIPTOR:
				desc = new ESDescriptor();
				break;
			case TYPE_DECODER_CONFIG_DESCRIPTOR:
				desc = new DecoderConfigDescriptor();
				break;
			case TYPE_DECODER_SPECIFIC_INFO:
				desc = new DecoderSpecificInfo();
				break;
			case TYPE_SL_CONFIG_DESCRIPTOR:
			//desc = new SLConfigDescriptor();
			//break;
			default:
				Logger.getLogger("MP4 Boxes").log(Level.INFO, "Unknown descriptor type: {0}", tag);
				desc = new UnknownDescriptor();
		}
		return desc;
	}
	protected int type, size;
	protected long start;
	private List<Descriptor> children;

	protected Descriptor() {
		children = new ArrayList<Descriptor>();
	}

	abstract void decode(MP4InputStream in) throws IOException;

	//子项
	protected void readChildren(MP4InputStream in) throws IOException {
		Descriptor desc;
		while((size-(in.getOffset()-start))>0) {
			desc = createDescriptor(in);
			children.add(desc);
		}
	}

	public List<Descriptor> getChildren() {
		return Collections.unmodifiableList(children);
	}

	//getter
	public int getType() {
		return type;
	}

	public int getSize() {
		return size;
	}
}
