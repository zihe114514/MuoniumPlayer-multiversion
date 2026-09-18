package repackage.net.sourceforge.jaad.mp4.boxes.impl.meta;

import java.io.IOException;
import java.nio.charset.Charset;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.BoxTypes;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

/**
 * 此 box 包含某个元数据标签的数据。它紧接在一个
 * iTunes 元数据 box（例如 '@nam'）或自定义元标签 box（'----'）之下。
 * 自定义元标签 box 还包含一个声明该标签名称的 'name' box。
 *
 * @author in-somnia
 */
/*TODO: 此处是否使用泛型？-> 每个 DataType 应返回与其类对应的 <T>
	（String/Integer/...）*/
public class ITunesMetadataBox extends FullBox {

	private static final String[] TIMESTAMPS = {"yyyy", "yyyy-MM", "yyyy-MM-dd"};

	public enum DataType {

		IMPLICIT(/*Object.class*/),
		UTF8(/*String.class*/),
		UTF16(/*String.class*/),
		HTML(/*String.class*/),
		XML(/*String.class*/),
		UUID(/*Long.class*/),
		ISRC(/*String.class*/),
		MI3P(/*String.class*/),
		GIF(/*byte[].class*/),
		JPEG(/*byte[].class*/),
		PNG(/*byte[].class*/),
		URL(/*String.class*/),
		DURATION(/*Long.class*/),
		DATETIME(/*Long.class*/),
		GENRE(/*Integer.class*/),
		INTEGER(/*Long.class*/),
		RIAA(/*Integer.class*/),
		UPC(/*String.class*/),
		BMP(/*byte[].class*/),
		UNDEFINED(/*byte[].class*/);
		private static final DataType[] TYPES = {
			IMPLICIT, UTF8, UTF16, null, null, null, HTML, XML, UUID, ISRC, MI3P, null,
			GIF, JPEG, PNG, URL, DURATION, DATETIME, GENRE, null, null, INTEGER,
			null, null, RIAA, UPC, null, BMP
		};

		private DataType() {
		}

		private static DataType forInt(int i) {
			DataType type = null;
			if(i>=0&&i<TYPES.length) type = TYPES[i];

			if(type==null) type = UNDEFINED;
			return type;
		}
	}
	private DataType dataType;
	private byte[] data;

	public ITunesMetadataBox() {
		super("iTunes Metadata Box");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		dataType = DataType.forInt(flags);

		in.skipBytes(4); //填充？

		data = new byte[(int) getLeft(in)];
		in.readBytes(data);
	}

	public DataType getDataType() {
		return dataType;
	}

	/**
	 * 返回一个不可修改的数组，含原始内容，可呈不同格式。
	 * 
	 * @return 原始元数据
	 */
	public byte[] getData() {
		return Arrays.copyOf(data, data.length);
	}

	/**
	 * 以文本字符串返回内容。
	 * @return 文本形式的元数据
	 */
	public String getText() {
		//前四个字节为填充（零）
		return new String(data, 0, data.length, Charset.forName("UTF-8"));
	}

	/**
	 * 以无符号 8 位整数返回内容。
	 * @return 整数形式的元数据
	 */
	public long getNumber() {
		//前四个字节为填充（零）
		long l = 0;
		for(int i = 0; i<data.length; i++) {
			l <<= 8;
			l |= (data[i]&0xFF);
		}
		return l;
	}

	public int getInteger() {
		return (int) getNumber();
	}

	/**
	 * 以布尔（标志）值返回内容。
	 * @return 布尔形式的元数据
	 */
	public boolean getBoolean() {
		return getNumber()!=0;
	}

	public Date getDate() {
		//时间戳长度：4,7,9
		final int i = (int) Math.floor(data.length/3)-1;
		final Date date;
		if(i>=0&&i<TIMESTAMPS.length) {
			final SimpleDateFormat sdf = new SimpleDateFormat(TIMESTAMPS[i]);
			date = sdf.parse(new String(data), new ParsePosition(0));
		}
		else date = null;
		return date;
	}
}
