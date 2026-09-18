package repackage.net.sourceforge.jaad.mp4.boxes.impl;

import java.io.IOException;
import repackage.net.sourceforge.jaad.mp4.MP4InputStream;
import repackage.net.sourceforge.jaad.mp4.boxes.FullBox;

public class ItemInformationEntry extends FullBox {

	private int itemID, itemProtectionIndex;
	private String itemName, contentType, contentEncoding;
	private long extensionType;
	private Extension extension;

	public ItemInformationEntry() {
		super("Item Information Entry");
	}

	@Override
	public void decode(MP4InputStream in) throws IOException {
		super.decode(in);

		if((version==0)||(version==1)) {
			itemID = (int) in.readBytes(2);
			itemProtectionIndex = (int) in.readBytes(2);
			itemName = in.readUTFString((int) getLeft(in), MP4InputStream.UTF8);
			contentType = in.readUTFString((int) getLeft(in), MP4InputStream.UTF8);
			contentEncoding = in.readUTFString((int) getLeft(in), MP4InputStream.UTF8); //可选
		}
		if(version==1&&getLeft(in)>0) {
			//可选
			extensionType = in.readBytes(4);
			if(getLeft(in)>0) {
				extension = Extension.forType((int) extensionType);
				if(extension!=null) extension.decode(in);
			}
		}
	}

	/**
	 * item ID 对主资源为 0（例如 'xml ' box 中所含的 XML），
	 * 否则为定义了以下信息的条目 ID。
	 *
	  * @return 项目 ID
	 */
	public int getItemID() {
		return itemID;
	}

	/**
	 * 条目保护索引对未保护条目为 0，否则为 item protection box 中
	 * 的从 1 开始的索引，用于定义应用于该条目的保护
	 *（item protection box 中第一个 box 的索引为
	 * 1）。
	 *
	 * @return 条目保护索引
	 */
	public int getItemProtectionIndex() {
		return itemProtectionIndex;
	}

	/**
	 * 条目名称为一个 String，包含条目的符号名称
	 *（文件交付传输中即源文件）。
	 *
	 * @return 条目名称
	 */
	public String getItemName() {
		return itemName;
	}

	/**
	 * 内容类型为一个 String，包含条目的 MIME 类型。若该条目
	 * 经过内容编码（见下），则内容类型指内容解码
	 * 之后的条目。
	 * 
	 * @return 内容类型
	 */
	public String getContentType() {
		return contentType;
	}

	/**
	 * 内容编码为一个可选 String，用于表明该
	 * 二进制文件已编码，在解释前需要先解码。
	 * 取值与 HTTP/1.1 中 Content-Encoding 的定义相同。可能的
	 * 取值如 "gzip"、"compress" 和 "deflate"。空字符串表示
	 * 无内容编码。注意，条目是在应用内容编码
	 * 之后存储的。
	 *
	 * @return 内容编码
	 */
	public String getContentEncoding() {
		return contentEncoding;
	}

	/**
	 * 扩展类型为一个可打印的四字代码，标识
	 * 相对于版本 0 的 item information 条目，
	 * 版本 1 的扩展字段。
	 * 
	 * @return 扩展类型
	 */
	public long getExtensionType() {
		return extensionType;
	}

	/**
	 * 返回扩展。
	 */
	public Extension getExtension() {
		return extension;
	}

	public abstract static class Extension {

		private static final int TYPE_FDEL = 1717855596; //fdel

		static Extension forType(int type) {
			final Extension ext;
			switch(type) {
				case Extension.TYPE_FDEL:
					ext = new FDExtension();
					break;
				default:
					ext = null;
			}
			return ext;
		}

		abstract void decode(MP4InputStream in) throws IOException;
	}

	public static class FDExtension extends Extension {

		private String contentLocation, contentMD5;
		private long contentLength, transferLength;
		private long[] groupID;

		@Override
		void decode(MP4InputStream in) throws IOException {
			contentLocation = in.readUTFString(100, MP4InputStream.UTF8);
			contentMD5 = in.readUTFString(100, MP4InputStream.UTF8);

			contentLength = in.readBytes(8);
			transferLength = in.readBytes(8);

			final int entryCount = in.read();
			groupID = new long[entryCount];
			for(int i = 0; i<entryCount; i++) {
				groupID[i] = in.readBytes(4);
			}
		}

		/**
		 * 内容位置为一个 String，包含 HTTP/1.1（RFC 2616）
		 * 所定义的文件 URI。
		 *
		 * @return 内容位置
		 */
		public String getContentLocation() {
			return contentLocation;
		}

		/**
		 * 内容 MD5 为一个字符串，包含该文件的 MD5 摘要。见
		 * HTTP/1.1（RFC 2616）和 RFC 1864。
		 *
		 * @return 内容 MD5
		 */
		public String getContentMD5() {
			return contentMD5;
		}

		/**
		 *（未编码）文件的总长度（字节）。
		 *
		 * @return 内容长度
		 */
		public long getContentLength() {
			return contentLength;
		}

		/**
		 * 传输长度为（编码后）文件的总长度（字节）。
		 * 注意，若未应用内容编码（见上），
		 * 传输长度等于内容长度。
		 *
		 * @return 传输长度
		 */
		public long getTransferLength() {
			return transferLength;
		}

		/**
		 * 组 ID 指示该文件条目（源文件）所属的
		 * 文件组。文件组的更多细节见 3GPP TS 26.346。
		 *
		 * @return 组 ID
		 */
		public long[] getGroupID() {
			return groupID;
		}
	}
}
